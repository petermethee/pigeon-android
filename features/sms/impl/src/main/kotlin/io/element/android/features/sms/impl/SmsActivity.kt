/*
 * Copyright 2026 Pigeon contributors.
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.element.android.features.sms.impl

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.element.android.compound.theme.ElementTheme

/**
 * Conversation à ouvrir à l'arrivée sur l'écran (par son fil ou par un numéro), avec un brouillon éventuel.
 *
 * [requestId] distingue deux demandes identiques (la même notification touchée deux fois) : chacune ouvre la conversation.
 */
internal data class SmsTarget(val threadId: Long?, val address: String?, val draft: String?, val requestId: Long = SystemClock.elapsedRealtimeNanos())

/**
 * Écran des SMS : liste des conversations, puis fil d'une conversation.
 *
 * Ouvert par l'icône "Pigeon SMS", en touchant une notification de SMS, ou par "Envoyer un SMS" depuis une autre app.
 */
class SmsActivity : ComponentActivity() {
    /** Conversation à ouvrir (notification, "Envoyer un SMS" depuis une autre app), ou null pour la liste. */
    private var target by mutableStateOf<SmsTarget?>(null)

    /** Écran au premier plan. */
    private var resumed by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // Écran recréé (rotation, thème…) : la conversation ouverte est restaurée, la demande d'origine est déjà traitée.
        target = if (savedInstanceState == null) intent.toTarget() else null
        setContent {
            ElementTheme {
                SmsApp(target = target, resumed = resumed)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        resumed = true
    }

    override fun onPause() {
        resumed = false
        super.onPause()
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
        val draft = getStringExtra(EXTRA_DRAFT)
        if (threadId == null && address == null && draft == null) return null
        return SmsTarget(threadId = threadId, address = address, draft = draft)
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
