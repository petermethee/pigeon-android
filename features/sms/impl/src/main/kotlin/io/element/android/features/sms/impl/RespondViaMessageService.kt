/*
 * Copyright 2026 Pigeon contributors.
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.element.android.features.sms.impl

import android.app.Service
import android.content.Intent
import android.os.IBinder
import timber.log.Timber

/**
 * Réponse rapide par SMS quand on refuse un appel ("Je te rappelle", etc.) : envoie le SMS choisi.
 */
class RespondViaMessageService : Service() {
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val address = intent?.data?.smsAddress()
        val body = intent?.getStringExtra(Intent.EXTRA_TEXT)
        if (address != null && !body.isNullOrBlank()) {
            SmsSender.send(this, address, body)
        } else {
            Timber.w("Réponse rapide : numéro ou texte manquant")
        }
        stopSelf(startId)
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
