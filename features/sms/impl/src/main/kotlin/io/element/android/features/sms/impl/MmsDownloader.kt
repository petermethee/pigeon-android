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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Téléchargement des MMS chez l'opérateur.
 *
 * Android télécharge le MMS (via les données mobiles de l'opérateur) dans un fichier temporaire de Pigeon,
 * puis prévient [MmsDownloadedReceiver] qui l'enregistre dans la base SMS/MMS du téléphone.
 */
object MmsDownloader {
    /** Télécharge le MMS annoncé par l'avis [pending] (aussi pour réessayer après un échec). */
    fun download(context: Context, pending: PendingMms) {
        if (pending.id >= 0) {
            // Un seul téléchargement à la fois par avis (toucher la bulle pendant un téléchargement ne le relance pas).
            if (!activeDownloads.add(pending.id)) return
            MmsStore.setPendingState(context, pending.id, downloading = true)
        }
        val file = newTempFile(context)
        try {
            val uri = shareTempFile(context, file)
            val downloaded = MmsDownloadedReceiver.pendingIntent(context, file.name, pending)
            smsManager(context, pending.subscriptionId).downloadMultimediaMessage(context, pending.location, uri, null, downloaded)
            Timber.i("Téléchargement du MMS lancé")
        } catch (e: Exception) {
            Timber.e(e, "Téléchargement du MMS impossible")
            file.delete()
            if (pending.id >= 0) {
                activeDownloads.remove(pending.id)
                MmsStore.setPendingState(context, pending.id, downloading = false)
            }
        }
    }

    /**
     * Avis dont le téléchargement est en cours dans ce processus. Un avis marqué "en cours" dans la base mais absent
     * d'ici a été interrompu (redémarrage du téléphone…) : il est traité comme un échec, qu'on peut relancer.
     */
    private val activeDownloads: MutableSet<Long> = ConcurrentHashMap.newKeySet()

    fun isDownloading(pendingId: Long): Boolean = pendingId in activeDownloads

    internal fun downloadFinished(pendingId: Long) {
        activeDownloads.remove(pendingId)
    }

    /** Relance le téléchargement de l'avis en attente n° [pendingId] (bouton "Réessayer" de la conversation). */
    fun retry(context: Context, pendingId: Long) {
        MmsStore.pending(context, pendingId)?.let { download(context, it) }
    }

    /**
     * Accusé de réception envoyé à l'opérateur une fois le MMS enregistré ; sans lui, certains
     * opérateurs renvoient l'avis plus tard.
     */
    fun acknowledge(context: Context, transactionId: String, subscriptionId: Int?) =
        sendToCarrier(context, MmsPdu.acknowledge(transactionId), subscriptionId)

    /** Réponse "MMS récupéré" à un avis, quand il n'y a pas d'accusé de réception à envoyer. */
    fun sendNotifyResponse(context: Context, notificationTransactionId: String, subscriptionId: Int?) =
        sendToCarrier(context, MmsPdu.notifyResponseRetrieved(notificationTransactionId), subscriptionId)

    private fun sendToCarrier(context: Context, pdu: ByteArray, subscriptionId: Int?) {
        val file = newTempFile(context)
        try {
            file.writeBytes(pdu)
            smsManager(context, subscriptionId).sendMultimediaMessage(context, shareTempFile(context, file), null, null, null)
        } catch (e: Exception) {
            Timber.w(e, "Réponse à l'opérateur pour le MMS impossible")
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
        val pending = PendingMms(
            id = intent.getLongExtra(EXTRA_PENDING_ID, -1),
            threadId = intent.getLongExtra(EXTRA_THREAD_ID, -1),
            location = intent.getStringExtra(EXTRA_LOCATION).orEmpty(),
            transactionId = intent.getStringExtra(EXTRA_TRANSACTION_ID),
            subscriptionId = intent.getIntExtra(EXTRA_SUBSCRIPTION, -1).takeIf { it >= 0 },
            failed = false,
        )
        // Lu avant goAsync(), qui le remet à zéro.
        val code = resultCode
        // Lecture et enregistrement hors du fil principal.
        val async = goAsync()
        thread(name = "pigeon-mms") {
            try {
                MmsDownloader.downloadFinished(pending.id)
                if (code == Activity.RESULT_OK) {
                    save(context, file.readBytes(), pending)
                } else {
                    // Souvent : données mobiles indisponibles, ou MMS expiré chez l'opérateur.
                    Timber.w("Échec du téléchargement du MMS (code %d)", code)
                    failed(context, pending)
                }
            } catch (e: Exception) {
                Timber.e(e, "Enregistrement du MMS impossible")
                failed(context, pending)
            } finally {
                file.delete()
                async.finish()
            }
        }
    }

    private fun save(context: Context, bytes: ByteArray, pending: PendingMms) {
        val pdu = MmsPdu.parse(bytes)
        if (pdu?.messageType != MmsPdu.TYPE_RETRIEVE_CONF) {
            Timber.w("MMS téléchargé illisible")
            failed(context, pending)
            return
        }
        // Déjà enregistré (deux téléchargements du même avis) : on retire seulement l'avis.
        val location = pending.location.ifEmpty { null }
        if (location != null && MmsStore.find(context, location) == KnownMms.Received) {
            Timber.i("MMS déjà enregistré : doublon ignoré")
            if (pending.id >= 0) MmsStore.deletePending(context, pending.id)
            return
        }
        val saved = MmsStore.saveIncoming(
            context = context,
            pdu = pdu,
            size = bytes.size,
            subscriptionId = pending.subscriptionId,
            location = location,
            pendingId = pending.id.takeIf { it >= 0 },
        )
        if (saved == null) {
            failed(context, pending)
            return
        }
        // Accusé de réception si le MMS en demande un, sinon réponse "récupéré" à l'avis.
        val retrieveTransactionId = pdu.transactionId
        val notificationTransactionId = pending.transactionId
        when {
            retrieveTransactionId != null -> MmsDownloader.acknowledge(context, retrieveTransactionId, pending.subscriptionId)
            notificationTransactionId != null -> MmsDownloader.sendNotifyResponse(context, notificationTransactionId, pending.subscriptionId)
        }
        SmsNotifier.notifyIncoming(context, saved.threadId, saved.title, saved.preview)
    }

    /** L'avis reste dans la conversation, avec un bouton pour réessayer. */
    private fun failed(context: Context, pending: PendingMms) {
        if (pending.id >= 0) MmsStore.setPendingState(context, pending.id, downloading = false)
        SmsNotifier.notifyMmsDownloadFailed(context, pending.threadId.takeIf { it >= 0 })
    }

    companion object {
        private const val EXTRA_FILE = "file"
        private const val EXTRA_PENDING_ID = "pending_id"
        private const val EXTRA_THREAD_ID = "thread_id"
        private const val EXTRA_LOCATION = "location"
        private const val EXTRA_TRANSACTION_ID = "transaction_id"
        private const val EXTRA_SUBSCRIPTION = "subscription"

        fun pendingIntent(context: Context, fileName: String, pending: PendingMms): PendingIntent {
            val intent = Intent(context, MmsDownloadedReceiver::class.java)
                .putExtra(EXTRA_FILE, fileName)
                .putExtra(EXTRA_PENDING_ID, pending.id)
                .putExtra(EXTRA_THREAD_ID, pending.threadId)
                .putExtra(EXTRA_LOCATION, pending.location)
                .putExtra(EXTRA_TRANSACTION_ID, pending.transactionId)
                .putExtra(EXTRA_SUBSCRIPTION, pending.subscriptionId ?: -1)
            return PendingIntent.getBroadcast(context, fileName.hashCode(), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
    }
}
