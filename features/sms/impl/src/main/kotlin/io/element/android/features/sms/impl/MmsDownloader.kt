/*
 * Copyright 2026 Pigeon contributors.
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.element.android.features.sms.impl

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import timber.log.Timber
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Téléchargement des MMS chez l'opérateur.
 *
 * Android télécharge le MMS (via les données mobiles de l'opérateur) dans un fichier temporaire de Pigeon,
 * puis prévient [MmsDownloadedReceiver] qui l'enregistre dans la base SMS/MMS du téléphone.
 */
object MmsDownloader {
    fun download(context: Context, location: String, subscriptionId: Int?) {
        val file = newTempFile(context)
        try {
            val uri = shareTempFile(context, file)
            val downloaded = MmsDownloadedReceiver.pendingIntent(context, file.name, subscriptionId)
            smsManager(context, subscriptionId).downloadMultimediaMessage(context, location, uri, null, downloaded)
            Timber.i("Téléchargement du MMS lancé")
        } catch (e: Exception) {
            Timber.e(e, "Téléchargement du MMS impossible")
            file.delete()
        }
    }

    /**
     * Accusé de réception envoyé à l'opérateur une fois le MMS enregistré ; sans lui, certains
     * opérateurs renvoient l'avis plus tard.
     */
    fun acknowledge(context: Context, transactionId: String, subscriptionId: Int?) {
        val file = newTempFile(context)
        try {
            file.writeBytes(MmsPdu.acknowledge(transactionId))
            smsManager(context, subscriptionId).sendMultimediaMessage(context, shareTempFile(context, file), null, null, null)
        } catch (e: Exception) {
            Timber.w(e, "Accusé de réception du MMS impossible")
        }
    }

    internal fun tempDir(context: Context): File = File(context.cacheDir, "mms").apply { mkdirs() }

    private fun newTempFile(context: Context): File {
        val dir = tempDir(context)
        // Ménage : fichiers oubliés (téléchargement sans réponse, accusés de réception déjà envoyés).
        val expired = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(1)
        dir.listFiles()?.filter { it.lastModified() < expired }?.forEach { it.delete() }
        return File(dir, "${UUID.randomUUID()}.pdu").apply { createNewFile() }
    }

    /** Adresse "content://" du fichier, lisible et modifiable par le service MMS d'Android. */
    private fun shareTempFile(context: Context, file: File): Uri {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.mms", file)
        // Android donne normalement lui-même l'accès au service MMS ; on le donne aussi par sécurité.
        try {
            context.grantUriPermission(PHONE_PACKAGE, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        } catch (e: Exception) {
            Timber.w(e, "Accès au fichier MMS non accordé au service téléphone")
        }
        return uri
    }

    private const val PHONE_PACKAGE = "com.android.phone"
}

/** Fichiers temporaires des MMS, partagés avec le service MMS d'Android (voir res/xml/mms_file_paths.xml). */
class MmsFileProvider : FileProvider()

/** Reçoit la fin du téléchargement d'un MMS, puis l'enregistre et affiche une notification. */
class MmsDownloadedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val fileName = intent.getStringExtra(EXTRA_FILE) ?: return
        val file = File(MmsDownloader.tempDir(context), fileName)
        val subscriptionId = intent.getIntExtra(EXTRA_SUBSCRIPTION, -1).takeIf { it >= 0 }
        if (resultCode != Activity.RESULT_OK) {
            // Souvent : données mobiles indisponibles, ou MMS expiré chez l'opérateur.
            Timber.w("Échec du téléchargement du MMS (code %d)", resultCode)
            file.delete()
            SmsNotifier.notifyMmsDownloadFailed(context)
            return
        }
        // Lecture et enregistrement hors du fil principal.
        val pending = goAsync()
        thread(name = "pigeon-mms") {
            try {
                save(context, file.readBytes(), subscriptionId)
            } catch (e: Exception) {
                Timber.e(e, "Enregistrement du MMS impossible")
            } finally {
                file.delete()
                pending.finish()
            }
        }
    }

    private fun save(context: Context, bytes: ByteArray, subscriptionId: Int?) {
        val pdu = MmsPdu.parse(bytes)
        if (pdu?.messageType != MmsPdu.TYPE_RETRIEVE_CONF) {
            Timber.w("MMS téléchargé illisible : ignoré")
            return
        }
        val saved = MmsStore.saveIncoming(context, pdu, bytes.size, subscriptionId) ?: return
        pdu.transactionId?.let { MmsDownloader.acknowledge(context, it, subscriptionId) }
        SmsNotifier.notifyIncoming(context, saved.threadId, saved.title, saved.preview)
    }

    companion object {
        private const val EXTRA_FILE = "file"
        private const val EXTRA_SUBSCRIPTION = "subscription"

        fun pendingIntent(context: Context, fileName: String, subscriptionId: Int?): PendingIntent {
            val intent = Intent(context, MmsDownloadedReceiver::class.java)
                .putExtra(EXTRA_FILE, fileName)
                .putExtra(EXTRA_SUBSCRIPTION, subscriptionId ?: -1)
            return PendingIntent.getBroadcast(context, fileName.hashCode(), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
    }
}
