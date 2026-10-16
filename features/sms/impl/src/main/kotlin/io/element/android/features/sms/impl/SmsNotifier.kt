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
 * Affiche une notification pour chaque SMS ou MMS reçu.
 *
 * Une notification par conversation : un nouveau message dans la même conversation remplace la précédente.
 */
object SmsNotifier {
    private const val CHANNEL_ID = "pigeon_sms"

    // Les numéros de conversation sont positifs : pas de collision.
    private const val MMS_FAILED_ID = -1

    /** Toucher la notification ouvre directement la conversation dans Pigeon. */
    fun notifyIncoming(context: Context, threadId: Long, title: String, text: String) {
        notify(context, notificationId(threadId), title, text, SmsActivity.intent(context, threadId = threadId))
    }

    /** Le MMS n'a pas pu être téléchargé (données mobiles coupées, MMS expiré…). */
    fun notifyMmsDownloadFailed(context: Context) {
        notify(
            context,
            id = MMS_FAILED_ID,
            title = "MMS non reçu",
            text = "Le téléchargement d'un MMS a échoué. Vérifie que les données mobiles et les MMS sont activés.",
            open = SmsActivity.intent(context),
        )
    }

    /** Identifiant de la notification d'une conversation, pour la remplacer ou la retirer. */
    fun notificationId(threadId: Long): Int = threadId.toInt()

    // La permission de notifier est vérifiée par areNotificationsEnabled(), et un refus est rattrapé plus bas.
    @SuppressLint("MissingPermission")
    private fun notify(context: Context, id: Int, title: String, text: String, open: Intent) {
        val manager = NotificationManagerCompat.from(context)
        // Android 13+ : l'utilisateur doit avoir autorisé les notifications de l'app.
        if (!manager.areNotificationsEnabled()) {
            Timber.w("Notifications désactivées : message reçu sans notification")
            return
        }
        createChannel(context, manager)

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_sms)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            // Sur l'écran verrouillé : "SMS de …" sans le contenu.
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setContentIntent(PendingIntent.getActivity(context, id, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            .setAutoCancel(true)
            .build()

        try {
            manager.notify(id, notification)
        } catch (e: SecurityException) {
            Timber.e(e, "Notification refusée par Android")
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
}
