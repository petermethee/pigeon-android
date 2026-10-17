/*
 * Copyright 2026 Pigeon contributors.
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.element.android.features.sms.impl

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.telephony.SmsManager
import timber.log.Timber
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import kotlin.concurrent.thread

/** Résultat du lancement de l'envoi d'un MMS. */
enum class MmsSendResult { STARTED, PHOTO_UNREADABLE, FAILED }

/**
 * Envoi des MMS : photo avec ou sans texte, et tout message à un groupe.
 *
 * Comme pour les SMS, le MMS est d'abord enregistré "en cours d'envoi", puis passé à "envoyé" ou "échec"
 * quand Android donne le résultat (voir [MmsSentReceiver]). Android l'envoie par les données mobiles de l'opérateur.
 */
object MmsSender {
    /** Envoie [text] et la photo [image] (l'un des deux peut manquer) à [recipients]. À appeler hors du fil principal. */
    fun send(context: Context, recipients: List<String>, text: String, image: Uri?): MmsSendResult {
        if (recipients.isEmpty() || (text.isBlank() && image == null)) return MmsSendResult.FAILED

        val textBytes = text.toByteArray(Charsets.UTF_8)
        val parts = ArrayList<MmsPart>()
        if (image != null) {
            // Place laissée au texte et aux en-têtes du MMS.
            val maxImageSize = maxMessageSize(context) - textBytes.size - HEADERS_MARGIN
            val jpeg = MmsPhoto.compress(context, image, maxImageSize) ?: return MmsSendResult.PHOTO_UNREADABLE
            parts += MmsPart("image/jpeg", name = null, fileName = "image.jpg", contentId = "<image>", contentLocation = "image.jpg", charset = null, data = jpeg)
        }
        if (text.isNotBlank()) {
            parts += MmsPart("text/plain", name = null, fileName = "text.txt", contentId = "<text>", contentLocation = "text.txt", charset = MmsStore.CHARSET_UTF8, data = textBytes)
        }

        val transactionId = "T" + UUID.randomUUID().toString().replace("-", "").take(12)
        val pdu = MmsPdu.sendRequest(transactionId, recipients, parts)
        val messageUri = MmsStore.saveOutgoing(context, recipients, parts, transactionId, pdu.size, subscriptionId = null)
            ?: return MmsSendResult.FAILED
        return transmit(context, messageUri, pdu)
    }

    /** Renvoie le MMS n° [mmsId] dont l'envoi a échoué (toucher sa bulle). À appeler hors du fil principal. */
    fun resend(context: Context, mmsId: Long): MmsSendResult {
        val outgoing = MmsStore.loadOutgoing(context, mmsId) ?: return MmsSendResult.FAILED
        MmsStore.setSending(context, outgoing.uri)
        return transmit(context, outgoing.uri, MmsPdu.sendRequest(outgoing.transactionId, outgoing.recipients, outgoing.parts))
    }

    /** Confie le MMS [pdu], enregistré à [messageUri], à Android qui l'envoie à l'opérateur. */
    private fun transmit(context: Context, messageUri: Uri, pdu: ByteArray): MmsSendResult {
        val file = MmsDownloader.newTempFile(context)
        return try {
            file.writeBytes(pdu)
            val sent = MmsSentReceiver.pendingIntent(context, messageUri, file.name)
            smsManager(context).sendMultimediaMessage(context, MmsDownloader.shareTempFile(context, file), null, null, sent)
            Timber.i("MMS en cours d'envoi (%d octets) : %s", pdu.size, messageUri)
            MmsSendResult.STARTED
        } catch (e: Exception) {
            // Par exemple : Pigeon n'est pas l'app SMS par défaut, ou pas de carte SIM.
            Timber.e(e, "Envoi du MMS impossible")
            file.delete()
            MmsStore.setSendResult(context, messageUri, sent = false, messageId = null)
            MmsSendResult.FAILED
        }
    }

    /** Taille maximale d'un MMS chez l'opérateur (souvent 300 Ko à 1 Mo). */
    private fun maxMessageSize(context: Context): Int {
        val size = try {
            @Suppress("DEPRECATION")
            smsManager(context).carrierConfigValues.getInt(SmsManager.MMS_CONFIG_MAX_MESSAGE_SIZE, DEFAULT_MAX_MESSAGE_SIZE)
        } catch (e: Exception) {
            DEFAULT_MAX_MESSAGE_SIZE
        }
        return size.takeIf { it > MIN_MAX_MESSAGE_SIZE } ?: DEFAULT_MAX_MESSAGE_SIZE
    }

    private const val DEFAULT_MAX_MESSAGE_SIZE = 300 * 1024
    private const val MIN_MAX_MESSAGE_SIZE = 50 * 1024
    private const val HEADERS_MARGIN = 8 * 1024
}

/** Reçoit le résultat de l'envoi d'un MMS et met à jour son état. */
class MmsSentReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val messageUri = intent.data ?: return
        val fileName = intent.getStringExtra(EXTRA_FILE)
        // Lus avant goAsync(), qui remet le résultat à zéro.
        val code = resultCode
        val response = intent.getByteArrayExtra(SmsManager.EXTRA_MMS_DATA)
        val async = goAsync()
        thread(name = "pigeon-mms-sent") {
            try {
                val ok = code == Activity.RESULT_OK
                // Identifiant donné par l'opérateur (réponse M-Send.conf), utile pour les accusés de réception.
                val messageId = response?.let { MmsPdu.parse(it)?.messageId }
                MmsStore.setSendResult(context, messageUri, sent = ok, messageId = messageId)
                Timber.i("Résultat d'envoi du MMS %s : %s (code %d)", messageUri, if (ok) "envoyé" else "échec", code)
            } finally {
                fileName?.let { File(MmsDownloader.tempDir(context), it).delete() }
                async.finish()
            }
        }
    }

    companion object {
        private const val EXTRA_FILE = "file"

        fun pendingIntent(context: Context, messageUri: Uri, fileName: String): PendingIntent {
            val intent = Intent(context, MmsSentReceiver::class.java)
                .setData(messageUri)
                .putExtra(EXTRA_FILE, fileName)
            val requestCode = ContentUris.parseId(messageUri).toInt()
            // Mutable : Android y ajoute la réponse de l'opérateur.
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
            return PendingIntent.getBroadcast(context, requestCode, intent, flags)
        }
    }
}

/** Préparation des photos à envoyer : réduites et compressées pour tenir dans la taille d'un MMS. */
internal object MmsPhoto {
    /** La photo en JPEG de [maxBytes] octets au plus, ou null si elle est illisible. */
    fun compress(context: Context, uri: Uri, maxBytes: Int): ByteArray? {
        var bitmap = decode(context, uri, MAX_SIDE) ?: return null
        try {
            repeat(MAX_ROUNDS) {
                for (quality in QUALITIES) {
                    val bytes = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, quality, it) }.toByteArray()
                    if (bytes.size <= maxBytes) return bytes
                }
                // Toujours trop lourde : on réduit encore ses dimensions.
                val smaller = Bitmap.createScaledBitmap(bitmap, (bitmap.width * 3 / 4).coerceAtLeast(1), (bitmap.height * 3 / 4).coerceAtLeast(1), true)
                if (smaller !== bitmap) bitmap.recycle()
                bitmap = smaller
            }
            Timber.w("Photo trop lourde pour un MMS, même réduite")
            return null
        } finally {
            bitmap.recycle()
        }
    }

    private fun decode(context: Context, uri: Uri, maxSide: Int): Bitmap? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            // Tient compte de l'orientation de la photo.
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
                val largest = maxOf(info.size.width, info.size.height)
                if (largest > maxSide) {
                    val scale = maxSide.toFloat() / largest
                    decoder.setTargetSize((info.size.width * scale).toInt().coerceAtLeast(1), (info.size.height * scale).toInt().coerceAtLeast(1))
                }
                // Logiciel : la photo doit pouvoir être compressée.
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        } else {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            var sampleSize = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sampleSize * 2) >= maxSide) sampleSize *= 2
            val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
        }
    } catch (e: Exception) {
        Timber.w(e, "Photo à envoyer illisible")
        null
    }

    private const val MAX_SIDE = 1600
    private const val MAX_ROUNDS = 5
    private val QUALITIES = listOf(85, 70, 55)
}
