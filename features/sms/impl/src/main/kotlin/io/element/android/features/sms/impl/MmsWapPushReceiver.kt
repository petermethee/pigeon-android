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
import kotlin.concurrent.thread

/**
 * Reçoit les avis d'arrivée de MMS quand Pigeon est l'app SMS par défaut.
 *
 * L'avis ne contient pas le MMS, seulement l'adresse où le télécharger chez l'opérateur :
 * on enregistre l'avis, on lance le téléchargement, puis [MmsDownloadedReceiver] enregistre le MMS.
 */
class MmsWapPushReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.WAP_PUSH_DELIVER_ACTION) return
        val data = intent.getByteArrayExtra("data") ?: return
        val subscriptionId = subscriptionId(intent)
        // Accès à la base hors du fil principal.
        val pending = goAsync()
        thread(name = "pigeon-mms-notification") {
            try {
                handle(context, data, subscriptionId)
            } finally {
                pending.finish()
            }
        }
    }

    private fun handle(context: Context, data: ByteArray, subscriptionId: Int?) {
        val notification = MmsPdu.parse(data)
        val location = notification?.contentLocation
        if (notification?.messageType != MmsPdu.TYPE_NOTIFICATION_IND || location == null) {
            Timber.w("Avis de MMS illisible ou d'un autre type : ignoré")
            return
        }
        when (val known = MmsStore.find(context, location)) {
            KnownMms.Received -> {
                // L'opérateur renvoie l'avis d'un MMS déjà reçu : on lui confirme la réception pour qu'il arrête.
                Timber.i("Avis de MMS déjà reçu : confirmation renvoyée")
                notification.transactionId?.let { MmsDownloader.sendNotifyResponse(context, it, subscriptionId) }
            }
            is KnownMms.Pending -> {
                if (known.mms.failed) {
                    MmsDownloader.download(context, known.mms)
                } else {
                    Timber.i("Avis de MMS déjà en cours de téléchargement : ignoré")
                }
            }
            null -> {
                val pending = MmsStore.savePending(context, notification, location, subscriptionId)
                    // Avis non enregistré (expéditeur masqué…) : téléchargement quand même.
                    ?: PendingMms(id = -1, threadId = -1, location, notification.transactionId, subscriptionId, failed = false)
                MmsDownloader.download(context, pending)
            }
        }
    }
}

/** Carte SIM ayant reçu le SMS ou le MMS (utile avec deux SIM). */
internal fun subscriptionId(intent: Intent): Int? {
    // "android.telephony.extra.SUBSCRIPTION_INDEX" depuis Android 11, "subscription" avant.
    val id = intent.getIntExtra("android.telephony.extra.SUBSCRIPTION_INDEX", intent.getIntExtra("subscription", -1))
    return id.takeIf { it >= 0 }
}
