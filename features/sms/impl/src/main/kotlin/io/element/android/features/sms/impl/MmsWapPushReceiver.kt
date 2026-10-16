/*
 * Copyright 2026 Pigeon contributors.
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.element.android.features.sms.impl

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import timber.log.Timber

/**
 * Reçoit les avis d'arrivée de MMS quand Pigeon est l'app SMS par défaut.
 *
 * Pas encore géré (étape MMS) : le MMS n'est pas téléchargé.
 */
class MmsWapPushReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Timber.w("MMS reçu : pas encore pris en charge par Pigeon")
    }
}
