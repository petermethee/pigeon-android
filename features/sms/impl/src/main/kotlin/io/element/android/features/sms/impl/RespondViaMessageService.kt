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
 * Réponse rapide par SMS quand on refuse un appel ("Je te rappelle", etc.).
 *
 * Pas encore réalisé (étape envoi) : la demande est ignorée.
 */
class RespondViaMessageService : Service() {
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Timber.w("Réponse rapide par SMS : pas encore prise en charge par Pigeon")
        stopSelf(startId)
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
