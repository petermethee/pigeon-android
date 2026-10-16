/*
 * Copyright 2026 Pigeon contributors.
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.element.android.features.sms.impl

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Telephony
import android.telephony.SmsManager
import timber.log.Timber

/**
 * Envoi des SMS.
 *
 * Comme pour la réception, c'est à l'app SMS par défaut d'enregistrer elle-même les SMS envoyés :
 * le SMS est d'abord enregistré "en cours d'envoi", puis passé à "envoyé" ou "échec" quand Android
 * donne le résultat (voir [SmsSentReceiver]).
 */
object SmsSender {
    /** Envoie [body] à [address]. Renvoie false si l'envoi n'a même pas pu démarrer. */
    fun send(context: Context, address: String, body: String): Boolean {
        if (address.isBlank() || body.isBlank()) return false

        // 1. Enregistrer le SMS dans la boîte d'envoi.
        val values = ContentValues().apply {
            put(Telephony.Sms.ADDRESS, address)
            put(Telephony.Sms.BODY, body)
            put(Telephony.Sms.DATE, System.currentTimeMillis())
            put(Telephony.Sms.READ, 1)
            put(Telephony.Sms.SEEN, 1)
            put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_OUTBOX)
        }
        val uri = try {
            context.contentResolver.insert(Telephony.Sms.Outbox.CONTENT_URI, values)
        } catch (e: Exception) {
            Timber.e(e, "Impossible d'enregistrer le SMS à envoyer")
            null
        } ?: return false

        // 2. L'envoyer. Un SMS long est découpé en plusieurs morceaux, chacun donnant son résultat.
        return try {
            val manager = smsManager(context)
            val parts = manager.divideMessage(body)
            val sentIntents = ArrayList<PendingIntent?>(parts.size)
            parts.indices.forEach { index -> sentIntents += SmsSentReceiver.pendingIntent(context, uri, index, isLast = index == parts.lastIndex) }
            manager.sendMultipartTextMessage(address, null, parts, sentIntents, null)
            Timber.i("SMS en cours d'envoi (%d morceau(x)) : %s", parts.size, uri)
            true
        } catch (e: Exception) {
            // Par exemple : Pigeon n'est pas l'app SMS par défaut, ou pas de carte SIM.
            Timber.e(e, "Envoi du SMS impossible")
            SmsSentReceiver.setType(context, uri, Telephony.Sms.MESSAGE_TYPE_FAILED)
            false
        }
    }

}

/** Envoi et téléchargement par la carte SIM [subscriptionId], ou celle choisie par défaut. */
internal fun smsManager(context: Context, subscriptionId: Int? = null): SmsManager =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val manager = context.getSystemService(SmsManager::class.java)
        if (subscriptionId != null) manager.createForSubscriptionId(subscriptionId) else manager
    } else {
        @Suppress("DEPRECATION")
        if (subscriptionId != null) SmsManager.getSmsManagerForSubscriptionId(subscriptionId) else SmsManager.getDefault()
    }

/**
 * Reçoit le résultat de l'envoi de chaque morceau d'un SMS et met à jour son état.
 *
 * Un morceau en échec fait passer le SMS en "échec", définitivement ; le dernier morceau envoyé le fait passer
 * en "envoyé", sauf si un morceau a déjà échoué (les résultats peuvent arriver dans le désordre).
 */
class SmsSentReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val smsUri = intent.data?.buildUpon()?.fragment(null)?.build() ?: return
        val ok = resultCode == Activity.RESULT_OK
        when {
            !ok -> setType(context, smsUri, Telephony.Sms.MESSAGE_TYPE_FAILED)
            intent.getBooleanExtra(EXTRA_IS_LAST, true) -> setType(context, smsUri, Telephony.Sms.MESSAGE_TYPE_SENT, onlyIfNotFailed = true)
        }
        Timber.i("Résultat d'envoi d'un morceau du SMS %s : %s (code %d)", smsUri, if (ok) "envoyé" else "échec", resultCode)
    }

    companion object {
        private const val EXTRA_IS_LAST = "is_last"

        fun pendingIntent(context: Context, smsUri: Uri, partIndex: Int, isLast: Boolean): PendingIntent {
            // Une adresse par morceau ("…/12#2") : Android garde ainsi un PendingIntent distinct pour chacun.
            val intent = Intent(context, SmsSentReceiver::class.java)
                .setData(smsUri.buildUpon().fragment(partIndex.toString()).build())
                .putExtra(EXTRA_IS_LAST, isLast)
            val requestCode = ContentUris.parseId(smsUri).toInt()
            return PendingIntent.getBroadcast(context, requestCode, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }

        fun setType(context: Context, smsUri: Uri, type: Int, onlyIfNotFailed: Boolean = false) {
            val values = ContentValues().apply { put(Telephony.Sms.TYPE, type) }
            val selection = if (onlyIfNotFailed) "${Telephony.Sms.TYPE} != ${Telephony.Sms.MESSAGE_TYPE_FAILED}" else null
            try {
                context.contentResolver.update(smsUri, values, selection, null)
            } catch (e: Exception) {
                Timber.e(e, "Impossible de mettre à jour l'état du SMS")
            }
        }
    }
}

/** Numéro de téléphone contenu dans une adresse "sms:", "smsto:", "mms:" ou "mmsto:". */
internal fun Uri.smsAddress(): String? =
    schemeSpecificPart
        ?.substringBefore('?')
        ?.removePrefix("//")
        ?.split(',', ';')
        ?.firstOrNull()
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
