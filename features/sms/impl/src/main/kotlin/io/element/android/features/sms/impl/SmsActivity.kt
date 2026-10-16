/*
 * Copyright 2026 Pigeon contributors.
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.element.android.features.sms.impl

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.element.android.compound.theme.ElementTheme

/**
 * Écran des SMS : liste des conversations, puis fil d'une conversation.
 *
 * Ouvert par l'icône "Pigeon SMS" ou en touchant une notification de SMS (qui ouvre directement la conversation).
 */
class SmsActivity : ComponentActivity() {
    /** Conversation à ouvrir (fournie par une notification), ou null pour la liste. */
    private var address by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        address = intent.getStringExtra(EXTRA_ADDRESS)
        setContent {
            ElementTheme {
                SmsApp(initialAddress = address)
            }
        }
    }

    /** L'écran est déjà ouvert et une notification est touchée : on ouvre sa conversation. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        address = intent.getStringExtra(EXTRA_ADDRESS)
    }

    companion object {
        private const val EXTRA_ADDRESS = "address"

        /** Ouvre l'écran des SMS, directement sur la conversation avec [address] si elle est fournie. */
        fun intent(context: Context, address: String? = null): Intent =
            Intent(context, SmsActivity::class.java)
                .putExtra(EXTRA_ADDRESS, address)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
    }
}
