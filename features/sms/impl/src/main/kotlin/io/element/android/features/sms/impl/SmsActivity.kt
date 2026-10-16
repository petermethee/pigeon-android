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
 * Ouvert par l'icône "Pigeon SMS", en touchant une notification de SMS, ou par "Envoyer un SMS" depuis une autre app.
 */
/** Conversation à ouvrir à l'arrivée sur l'écran (par son fil ou par un numéro), avec un brouillon éventuel. */
internal data class SmsTarget(val threadId: Long?, val address: String?, val draft: String?)

class SmsActivity : ComponentActivity() {
    /** Conversation à ouvrir (notification, "Envoyer un SMS" depuis une autre app), ou null pour la liste. */
    private var target by mutableStateOf<SmsTarget?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        target = intent.toTarget()
        setContent {
            ElementTheme {
                SmsApp(target = target)
            }
        }
    }

    /** L'écran est déjà ouvert et une notification est touchée : on ouvre sa conversation. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        target = intent.toTarget()
    }

    private fun Intent.toTarget(): SmsTarget? {
        val threadId = getLongExtra(EXTRA_THREAD_ID, -1).takeIf { it >= 0 }
        val address = getStringExtra(EXTRA_ADDRESS)
        if (threadId == null && address == null) return null
        return SmsTarget(threadId = threadId, address = address, draft = getStringExtra(EXTRA_DRAFT))
    }

    companion object {
        private const val EXTRA_ADDRESS = "address"
        private const val EXTRA_DRAFT = "draft"
        private const val EXTRA_THREAD_ID = "thread_id"

        /**
         * Ouvre l'écran des SMS, directement sur la conversation [threadId] ou avec [address] si l'un est fourni,
         * avec [draft] déjà saisi dans la zone de texte.
         */
        fun intent(context: Context, address: String? = null, draft: String? = null, threadId: Long? = null): Intent =
            Intent(context, SmsActivity::class.java)
                .putExtra(EXTRA_ADDRESS, address)
                .putExtra(EXTRA_DRAFT, draft)
                .putExtra(EXTRA_THREAD_ID, threadId ?: -1L)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
    }
}
