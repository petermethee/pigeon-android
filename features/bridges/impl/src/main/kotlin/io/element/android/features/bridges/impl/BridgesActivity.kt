/*
 * Copyright 2026 Pigeon contributors.
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.element.android.features.bridges.impl

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import io.element.android.compound.theme.ElementTheme
import io.element.android.libraries.architecture.bindings

/** Comptes connectés : WhatsApp et Messenger reliés au serveur, ouvert depuis les paramètres. */
class BridgesActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val accounts = ConnectedAccounts.get(bindings<BridgesBindings>().sessionStore())
        setContent {
            ElementTheme {
                BridgesApp(accounts = accounts, onClose = ::finish)
            }
        }
    }

    companion object {
        fun intent(context: Context): Intent = Intent(context, BridgesActivity::class.java)
    }
}
