/*
 * Copyright 2026 Pigeon contributors.
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.element.android.features.sms.impl

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import timber.log.Timber

/**
 * Reçoit les avis d'arrivée de MMS quand Pigeon est l'app SMS par défaut.
 *
 * L'avis ne contient pas le MMS, seulement l'adresse où le télécharger chez l'opérateur :
 * on lance le téléchargement, puis [MmsDownloadedReceiver] enregistre le MMS.
 */
class MmsWapPushReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.WAP_PUSH_DELIVER_ACTION) return
        val data = intent.getByteArrayExtra("data") ?: return
        val notification = MmsPdu.parse(data)
        val location = notification?.contentLocation
        if (notification?.messageType != MmsPdu.TYPE_NOTIFICATION_IND || location == null) {
            Timber.w("Avis de MMS illisible ou d'un autre type : ignoré")
            return
        }
        if (MmsStore.isAlreadyDownloaded(context, location)) {
            Timber.i("Avis de MMS déjà traité : ignoré")
            return
        }
        MmsDownloader.download(context, location, subscriptionId(intent))
    }
}

/** Carte SIM ayant reçu le SMS ou le MMS (utile avec deux SIM). */
internal fun subscriptionId(intent: Intent): Int? {
    // "android.telephony.extra.SUBSCRIPTION_INDEX" depuis Android 11, "subscription" avant.
    val id = intent.getIntExtra("android.telephony.extra.SUBSCRIPTION_INDEX", intent.getIntExtra("subscription", -1))
    return id.takeIf { it >= 0 }
}
