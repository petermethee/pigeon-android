/*
 * Copyright 2026 Pigeon contributors.
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.element.android.features.bridges.impl

import androidx.annotation.StringRes
import io.element.android.libraries.sessionstorage.api.SessionStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Un réseau relié au serveur par un bridge.
 *
 * @param path chemin de son API de connexion sur le serveur (voir server/init/nginx.conf du dépôt Pigeon)
 * @param flowId façon de se connecter : code d'appairage pour WhatsApp (on ne peut pas scanner un QR code
 *   affiché sur son propre téléphone), page de connexion messenger.com pour Messenger
 */
internal enum class BridgeNetwork(val path: String, val flowId: String, @StringRes val label: Int) {
    WHATSAPP("whatsapp", "phone", R.string.screen_bridges_whatsapp),
    MESSENGER("meta", "messenger", R.string.screen_bridges_messenger),
}

// ── Réponses de l'API (bridgev2 de mautrix, "provisioning API" v3) ───────────────

/** Comptes connectés au bridge. */
@Serializable
internal data class Whoami(val logins: List<WhoamiLogin> = emptyList())

@Serializable
internal data class WhoamiLogin(
    val id: String,
    val name: String = "",
    val state: BridgeState? = null,
    val profile: RemoteProfile? = null,
)

/** État de la connexion d'un compte : "CONNECTED", "CONNECTING", "BAD_CREDENTIALS", "LOGGED_OUT"… */
@Serializable
internal data class BridgeState(
    @SerialName("state_event") val stateEvent: String = "",
    val error: String? = null,
    val message: String? = null,
)

@Serializable
internal data class RemoteProfile(val phone: String? = null, val name: String? = null, val username: String? = null, val email: String? = null)

/** Étape d'une connexion : saisie, code à afficher, page de connexion, ou fin. */
@Serializable
internal data class LoginStep(
    @SerialName("login_id") val loginId: String = "",
    val type: String,
    @SerialName("step_id") val stepId: String,
    @SerialName("txn_id") val txnId: String = "",
    val instructions: String = "",
    @SerialName("display_and_wait") val displayAndWait: DisplayAndWait? = null,
    val cookies: CookiesParams? = null,
    @SerialName("user_input") val userInput: UserInput? = null,
) {
    companion object {
        const val TYPE_USER_INPUT = "user_input"
        const val TYPE_DISPLAY_AND_WAIT = "display_and_wait"
        const val TYPE_COOKIES = "cookies"
        const val TYPE_COMPLETE = "complete"
    }
}

/** Code ("code"), QR code ("qr") ou émojis ("emoji") à afficher, puis attente d'une action sur le réseau. */
@Serializable
internal data class DisplayAndWait(val type: String, val data: String = "")

/** Page de connexion à ouvrir, et cookies à transmettre au bridge une fois connecté. */
@Serializable
internal data class CookiesParams(
    val url: String,
    @SerialName("user_agent") val userAgent: String? = null,
    val fields: List<CookieField> = emptyList(),
    @SerialName("wait_for_url_pattern") val waitForUrlPattern: String? = null,
)

@Serializable
internal data class CookieField(val id: String, val required: Boolean = false, val sources: List<CookieSource> = emptyList())

/** Où trouver la valeur : un cookie ("cookie") du domaine [cookieDomain] ; les autres sources ne sont pas gérées. */
@Serializable
internal data class CookieSource(val type: String, val name: String = "", @SerialName("cookie_domain") val cookieDomain: String? = null)

@Serializable
internal data class UserInput(val fields: List<InputField> = emptyList())

@Serializable
internal data class InputField(
    val type: String,
    val id: String,
    val name: String = "",
    val description: String = "",
    @SerialName("default_value") val defaultValue: String = "",
)

@Serializable
private data class ApiError(val errcode: String = "", val error: String = "")

/** Erreur renvoyée par le serveur ([status] HTTP), ou serveur injoignable ([status] = 0). */
internal class ProvisioningException(val status: Int, override val message: String) : Exception(message)

/**
 * Client de l'API de connexion des bridges, au nom de l'utilisateur connecté à Pigeon.
 *
 * Le bridge vérifie le jeton Matrix de la session ouverte : il est relu à chaque demande, car Element
 * peut le renouveler.
 */
internal class ProvisioningClient(private val sessionStore: SessionStore) {
    private val json = Json { ignoreUnknownKeys = true }

    // Certaines étapes attendent une action de l'utilisateur sur le réseau (code saisi dans WhatsApp) :
    // le serveur garde alors la demande ouverte, et on la renvoie si elle est coupée (voir [waitForNext]).
    private val http = OkHttpClient.Builder()
        .readTimeout(90, TimeUnit.SECONDS)
        .build()

    suspend fun whoami(network: BridgeNetwork): Whoami = json.decodeFromString(call(network, listOf("whoami"), body = null))

    suspend fun start(network: BridgeNetwork): LoginStep =
        json.decodeFromString(call(network, listOf("login", "start", network.flowId), body = "{}"))

    /** Envoie les valeurs de l'étape [step] (saisie, cookies), ou attend sa fin pour un code affiché. */
    suspend fun submit(network: BridgeNetwork, step: LoginStep, values: Map<String, String> = emptyMap()): LoginStep {
        val body = json.encodeToString(values)
        val path = listOf("login", "step", step.loginId, step.stepId, step.type)
        val query = mapOf("txn_id" to step.txnId).filterValues { it.isNotEmpty() }
        return json.decodeFromString<LoginStep>(call(network, path, body, query)).let { if (it.loginId.isEmpty()) it.copy(loginId = step.loginId) else it }
    }

    /**
     * Attend l'action de l'utilisateur sur le réseau pour une étape "afficher et attendre" (code saisi dans WhatsApp).
     * Une demande coupée par le reverse proxy du NAS (au bout d'une minute) est simplement renvoyée.
     */
    suspend fun waitForNext(network: BridgeNetwork, step: LoginStep): LoginStep {
        while (true) {
            try {
                return submit(network, step)
            } catch (e: ProvisioningException) {
                // 0 : délai dépassé ; 502 et 504 : demande coupée par un proxy.
                if (e.status != 0 && e.status != 502 && e.status != 504) throw e
            }
        }
    }

    suspend fun cancel(network: BridgeNetwork, loginId: String) {
        call(network, listOf("login", "cancel", loginId), body = "{}")
    }

    suspend fun logout(network: BridgeNetwork, loginId: String) {
        call(network, listOf("logout", loginId), body = "{}")
    }

    private suspend fun call(network: BridgeNetwork, path: List<String>, body: String?, query: Map<String, String> = emptyMap()): String =
        withContext(Dispatchers.IO) {
            val session = sessionStore.getLatestSession()?.takeIf { it.isTokenValid }
                ?: throw ProvisioningException(401, "not logged in")
            val url = provisionUrl(session.homeserverUrl, network, path, query + ("user_id" to session.userId))
            val request = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer ${session.accessToken}")
                .apply { if (body != null) post(body.toRequestBody(JSON_TYPE)) }
                .build()
            val response = try {
                http.newCall(request).execute()
            } catch (e: java.io.IOException) {
                throw ProvisioningException(0, e.message ?: e.javaClass.simpleName)
            }
            response.use {
                val text = it.body.string()
                if (!it.isSuccessful) {
                    val error = runCatching { json.decodeFromString<ApiError>(text) }.getOrNull()
                    throw ProvisioningException(it.code, error?.error?.ifEmpty { null } ?: "HTTP ${it.code}")
                }
                text
            }
        }

    private fun provisionUrl(homeserverUrl: String, network: BridgeNetwork, path: List<String>, query: Map<String, String>): HttpUrl =
        homeserverUrl.toHttpUrl().newBuilder()
            .addPathSegments("_matrix/provision/${network.path}/v3")
            .apply {
                path.forEach { addPathSegment(it) }
                query.forEach { (key, value) -> addQueryParameter(key, value) }
            }
            .build()

    private companion object {
        val JSON_TYPE = "application/json".toMediaType()
    }
}
