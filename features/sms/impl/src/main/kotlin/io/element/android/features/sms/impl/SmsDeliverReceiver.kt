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
import kotlin.concurrent.thread

/**
 * Reçoit les SMS entrants quand Pigeon est l'app SMS par défaut, les enregistre et affiche une notification.
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

        val address = first.displayOriginatingAddress.orEmpty()
        val body = parts.joinToString(separator = "") { it.displayMessageBody.orEmpty() }

        val subscriptionId = subscriptionId(intent)
        // Accès à la base et aux contacts hors du fil principal.
        val pending = goAsync()
        thread(name = "pigeon-sms") {
            try {
                save(context, address, body, first.timestampMillis, first.serviceCenterAddress, subscriptionId, parts.size)
            } finally {
                pending.finish()
            }
        }
    }

    private fun save(context: Context, address: String, body: String, sentAt: Long, serviceCenter: String?, subscriptionId: Int?, partCount: Int) {
        val values = ContentValues().apply {
            put(Telephony.Sms.ADDRESS, address)
            put(Telephony.Sms.BODY, body)
            put(Telephony.Sms.DATE, System.currentTimeMillis())
            put(Telephony.Sms.DATE_SENT, sentAt)
            put(Telephony.Sms.SERVICE_CENTER, serviceCenter)
            put(Telephony.Sms.READ, 0)
            put(Telephony.Sms.SEEN, 0)
            put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_INBOX)
            subscriptionId?.let { put(Telephony.Sms.SUBSCRIPTION_ID, it) }
        }

        try {
            val uri = context.contentResolver.insert(Telephony.Sms.Inbox.CONTENT_URI, values)
            // Ne jamais journaliser le contenu ni le numéro (données personnelles).
            Timber.i("SMS reçu enregistré (%d morceau(x)) : %s", partCount, uri)
        } catch (e: Exception) {
            // La notification est quand même affichée : c'est alors la seule trace du SMS.
            Timber.e(e, "Impossible d'enregistrer le SMS reçu")
        }

        val threadId = try {
            SmsRepository.threadIdFor(context, address)
        } catch (e: Exception) {
            Timber.e(e, "Conversation du SMS introuvable")
            return
        }
        SmsNotifier.notifyIncoming(context, threadId, SmsRepository.displayName(context, address), body)
    }
}
