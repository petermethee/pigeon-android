/*
 * Copyright 2026 Pigeon contributors.
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.element.android.features.bridges.impl

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.element.android.libraries.sessionstorage.api.SessionStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import timber.log.Timber

/** Comptes d'un réseau : chargés ([Loaded]), en cours de chargement, ou serveur injoignable ([Failed]). */
internal sealed interface NetworkAccounts {
    data object Loading : NetworkAccounts
    data class Loaded(val logins: List<WhoamiLogin>) : NetworkAccounts
    data class Failed(val error: ProvisioningException) : NetworkAccounts
}

/** Connexion d'un compte en cours, étape par étape. */
internal sealed interface LoginState {
    val network: BridgeNetwork

    /** Demande en cours (début, envoi d'une saisie…). */
    data class Busy(override val network: BridgeNetwork) : LoginState

    /** Étape à afficher ; [waiting] : on attend une action de l'utilisateur sur le réseau (code à saisir). */
    data class Step(override val network: BridgeNetwork, val step: LoginStep, val waiting: Boolean = false) : LoginState

    data class Done(override val network: BridgeNetwork) : LoginState

    data class Failed(override val network: BridgeNetwork, val message: String) : LoginState
}

/**
 * État de l'écran des comptes connectés, et les demandes au serveur.
 *
 * Gardé tant que l'app tourne : une connexion en cours n'est pas perdue si l'écran est recréé (rotation, passage
 * dans WhatsApp pour saisir le code…).
 */
internal class ConnectedAccounts private constructor(private val client: ProvisioningClient) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var loginJob: Job? = null

    val accounts = mutableStateMapOf<BridgeNetwork, NetworkAccounts>()

    var login by mutableStateOf<LoginState?>(null)
        private set

    fun refresh() {
        BridgeNetwork.entries.forEach { network ->
            if (accounts[network] !is NetworkAccounts.Loaded) accounts[network] = NetworkAccounts.Loading
            scope.launch {
                accounts[network] = try {
                    NetworkAccounts.Loaded(client.whoami(network).logins)
                } catch (e: ProvisioningException) {
                    Timber.w("Comptes %s illisibles (HTTP %d)", network.name, e.status)
                    NetworkAccounts.Failed(e)
                }
            }
        }
    }

    fun startLogin(network: BridgeNetwork) {
        run(network) { client.start(network) }
    }

    /** Envoie la saisie (numéro…) ou les cookies de l'étape affichée. */
    fun submit(step: LoginStep, values: Map<String, String>) {
        val network = login?.network ?: return
        run(network) { client.submit(network, step, values) }
    }

    /** Abandon de la connexion en cours (retour, bouton Annuler). */
    fun cancelLogin() {
        val current = login ?: return
        loginJob?.cancel()
        login = null
        val loginId = (current as? LoginState.Step)?.step?.loginId
        if (loginId != null) {
            scope.launch {
                try {
                    client.cancel(current.network, loginId)
                } catch (e: ProvisioningException) {
                    // Déjà terminée ou expirée : rien à faire.
                    Timber.i("Annulation de la connexion %s : HTTP %d", current.network.name, e.status)
                }
            }
        }
        refresh()
    }

    /** Ferme l'écran de fin ou d'erreur. */
    fun closeLogin() {
        login = null
        refresh()
    }

    fun logout(network: BridgeNetwork, loginId: String) {
        scope.launch {
            try {
                client.logout(network, loginId)
            } catch (e: ProvisioningException) {
                Timber.w("Déconnexion %s impossible (HTTP %d)", network.name, e.status)
            }
            refresh()
        }
    }

    /** Lance la demande [request], puis enchaîne les étapes "afficher et attendre" jusqu'à la suivante à afficher. */
    private fun run(network: BridgeNetwork, request: suspend () -> LoginStep) {
        loginJob?.cancel()
        login = LoginState.Busy(network)
        loginJob = scope.launch {
            try {
                var step = request()
                while (step.type == LoginStep.TYPE_DISPLAY_AND_WAIT) {
                    login = LoginState.Step(network, step, waiting = true)
                    step = client.waitForNext(network, step)
                }
                // Jamais le contenu des étapes (numéro, code, cookies) dans les journaux.
                Timber.i("Connexion %s : étape %s", network.name, step.type)
                login = if (step.type == LoginStep.TYPE_COMPLETE) LoginState.Done(network) else LoginState.Step(network, step)
            } catch (e: ProvisioningException) {
                Timber.w("Connexion %s : échec (HTTP %d)", network.name, e.status)
                login = LoginState.Failed(network, e.message)
            }
        }
    }

    companion object {
        @Volatile
        private var instance: ConnectedAccounts? = null

        fun get(sessionStore: SessionStore): ConnectedAccounts =
            instance ?: synchronized(this) { instance ?: ConnectedAccounts(ProvisioningClient(sessionStore)).also { instance = it } }
    }
}
