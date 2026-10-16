/*
 * Copyright 2026 Pigeon contributors.
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.element.android.features.sms.impl

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import timber.log.Timber

/**
 * Affiche une notification pour chaque SMS reçu.
 *
 * Une notification par correspondant : un nouveau SMS du même numéro remplace la précédente.
 */
object SmsNotifier {
    private const val CHANNEL_ID = "pigeon_sms"

    // La permission de notifier est vérifiée par areNotificationsEnabled(), et un refus est rattrapé plus bas.
    @SuppressLint("MissingPermission")
    fun notifyIncomingSms(context: Context, address: String, body: String) {
        val manager = NotificationManagerCompat.from(context)
        // Android 13+ : l'utilisateur doit avoir autorisé les notifications de l'app.
        if (!manager.areNotificationsEnabled()) {
            Timber.w("Notifications désactivées : SMS reçu sans notification")
            return
        }
        createChannel(context, manager)

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_sms)
            .setContentTitle(address)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            // Sur l'écran verrouillé : "SMS de …" sans le contenu.
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setContentIntent(openAppIntent(context))
            .setAutoCancel(true)
            .build()

        try {
            manager.notify(address.hashCode(), notification)
        } catch (e: SecurityException) {
            Timber.e(e, "Notification SMS refusée par Android")
        }
    }

    private fun createChannel(context: Context, manager: NotificationManagerCompat) {
        val channel = NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_HIGH)
            .setName(context.getString(R.string.sms_notification_channel_name))
            .setDescription(context.getString(R.string.sms_notification_channel_description))
            .build()
        // Sans effet si le canal existe déjà.
        manager.createNotificationChannel(channel)
    }

    /** Toucher la notification ouvre Pigeon (l'écran des SMS viendra à l'étape 3). */
    private fun openAppIntent(context: Context): PendingIntent? {
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return null
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }
}
