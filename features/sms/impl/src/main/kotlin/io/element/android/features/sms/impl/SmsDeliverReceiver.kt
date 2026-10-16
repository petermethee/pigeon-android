/*
 * Copyright 2026 Pigeon contributors.
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.element.android.features.sms.impl

import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import timber.log.Timber

/**
 * Reçoit les SMS entrants quand Pigeon est l'app SMS par défaut.
 *
 * Dans ce cas, Android n'enregistre plus lui-même les SMS reçus : c'est à l'app par défaut
 * de les écrire dans la base SMS du téléphone (le "Telephony provider"), sinon ils sont perdus.
 */
class SmsDeliverReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_DELIVER_ACTION) return

        // Un SMS long arrive découpé en plusieurs morceaux : on les recolle.
        val parts = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        if (parts.isNullOrEmpty()) return
        val first = parts.first()

        val values = ContentValues().apply {
            put(Telephony.Sms.ADDRESS, first.displayOriginatingAddress)
            put(Telephony.Sms.BODY, parts.joinToString(separator = "") { it.displayMessageBody.orEmpty() })
            put(Telephony.Sms.DATE, System.currentTimeMillis())
            put(Telephony.Sms.DATE_SENT, first.timestampMillis)
            put(Telephony.Sms.SERVICE_CENTER, first.serviceCenterAddress)
            put(Telephony.Sms.READ, 0)
            put(Telephony.Sms.SEEN, 0)
            put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_INBOX)
            subscriptionId(intent)?.let { put(Telephony.Sms.SUBSCRIPTION_ID, it) }
        }

        try {
            val uri = context.contentResolver.insert(Telephony.Sms.Inbox.CONTENT_URI, values)
            // Ne jamais journaliser le contenu ni le numéro (données personnelles).
            Timber.i("SMS reçu enregistré (%d morceau(x)) : %s", parts.size, uri)
        } catch (e: Exception) {
            Timber.e(e, "Impossible d'enregistrer le SMS reçu")
        }
    }

    /** Carte SIM ayant reçu le SMS (utile avec deux SIM). */
    private fun subscriptionId(intent: Intent): Int? {
        // "android.telephony.extra.SUBSCRIPTION_INDEX" depuis Android 11, "subscription" avant.
        val id = intent.getIntExtra("android.telephony.extra.SUBSCRIPTION_INDEX", intent.getIntExtra("subscription", -1))
        return id.takeIf { it >= 0 }
    }
}
