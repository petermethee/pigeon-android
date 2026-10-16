/*
 * Copyright 2026 Pigeon contributors.
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.element.android.features.sms.impl

import android.annotation.SuppressLint
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.Telephony
import android.telephony.PhoneNumberUtils
import android.telephony.TelephonyManager
import timber.log.Timber

/** MMS reçu et enregistré : de quoi afficher sa notification. */
class SavedMms(val threadId: Long, val title: String, val preview: String)

/**
 * Enregistrement des MMS reçus dans la base SMS/MMS du téléphone (le "Telephony provider").
 *
 * Comme pour les SMS, c'est à l'app par défaut de le faire. Un MMS y est rangé en trois tables :
 * le message, ses pièces (texte, photos…) et ses adresses (expéditeur, destinataires).
 */
object MmsStore {
    // Codes de la base MMS (OMA MMS) : type d'adresse et jeu de caractères UTF-8.
    private const val ADDRESS_FROM = 137
    private const val ADDRESS_TO = 151
    private const val ADDRESS_CC = 130
    private const val CHARSET_UTF8 = 106

    /** Un MMS de cette adresse de téléchargement est déjà enregistré (avis reçu deux fois). */
    fun isAlreadyDownloaded(context: Context, location: String): Boolean = try {
        context.contentResolver.query(
            Telephony.Mms.CONTENT_URI,
            arrayOf(Telephony.Mms._ID),
            "${Telephony.Mms.CONTENT_LOCATION} = ? AND ${Telephony.Mms.MESSAGE_TYPE} = ?",
            arrayOf(location, MmsPdu.TYPE_RETRIEVE_CONF.toString()),
            null,
        )?.use { it.count > 0 } ?: false
    } catch (e: Exception) {
        Timber.w(e, "Vérification des MMS déjà reçus impossible")
        false
    }

    fun saveIncoming(context: Context, pdu: MmsPdu, size: Int, subscriptionId: Int?): SavedMms? {
        val from = pdu.from.orEmpty()
        val recipients = conversationMembers(context, from, pdu.to + pdu.cc, subscriptionId)
        val threadId = try {
            Telephony.Threads.getOrCreateThreadId(context, recipients.toSet())
        } catch (e: Exception) {
            Timber.e(e, "Conversation du MMS introuvable")
            return null
        }

        val values = ContentValues().apply {
            put(Telephony.Mms.THREAD_ID, threadId)
            // Dates en secondes dans la base MMS (et non en millisecondes comme pour les SMS).
            put(Telephony.Mms.DATE, System.currentTimeMillis() / 1000)
            pdu.date?.let { put(Telephony.Mms.DATE_SENT, it) }
            put(Telephony.Mms.MESSAGE_BOX, Telephony.Mms.MESSAGE_BOX_INBOX)
            put(Telephony.Mms.READ, 0)
            put(Telephony.Mms.SEEN, 0)
            put(Telephony.Mms.MESSAGE_TYPE, MmsPdu.TYPE_RETRIEVE_CONF)
            put(Telephony.Mms.MMS_VERSION, pdu.version ?: MmsPdu.MMS_VERSION_1_2)
            put(Telephony.Mms.MESSAGE_ID, pdu.messageId)
            put(Telephony.Mms.TRANSACTION_ID, pdu.transactionId)
            put(Telephony.Mms.CONTENT_LOCATION, pdu.contentLocation)
            put(Telephony.Mms.CONTENT_TYPE, pdu.contentType)
            put(Telephony.Mms.MESSAGE_SIZE, size)
            pdu.subject?.let {
                put(Telephony.Mms.SUBJECT, it)
                put(Telephony.Mms.SUBJECT_CHARSET, CHARSET_UTF8)
            }
            put(Telephony.Mms.TEXT_ONLY, if (pdu.parts.all { it.isText }) 1 else 0)
            subscriptionId?.let { put(Telephony.Mms.SUBSCRIPTION_ID, it) }
        }

        val resolver = context.contentResolver
        val messageUri = try {
            resolver.insert(Telephony.Mms.Inbox.CONTENT_URI, values)
        } catch (e: Exception) {
            Timber.e(e, "Impossible d'enregistrer le MMS reçu")
            null
        } ?: return null
        val messageId = ContentUris.parseId(messageUri)

        // Pièces : le texte est rangé dans la base, les fichiers (photos…) dans un fichier géré par Android.
        val partsUri = Uri.parse("content://mms/$messageId/part")
        for (part in pdu.parts) {
            val partValues = ContentValues().apply {
                put(Telephony.Mms.Part.CONTENT_TYPE, part.contentType)
                put(Telephony.Mms.Part.NAME, part.name)
                put(Telephony.Mms.Part.FILENAME, part.fileName)
                put(Telephony.Mms.Part.CONTENT_ID, part.contentId)
                put(Telephony.Mms.Part.CONTENT_LOCATION, part.contentLocation)
                if (part.isText) {
                    put(Telephony.Mms.Part.CHARSET, CHARSET_UTF8)
                    put(Telephony.Mms.Part.TEXT, part.text())
                }
            }
            try {
                val partUri = resolver.insert(partsUri, partValues) ?: continue
                if (!part.isText) resolver.openOutputStream(partUri)?.use { it.write(part.data) }
            } catch (e: Exception) {
                Timber.e(e, "Impossible d'enregistrer une pièce du MMS (%s)", part.contentType)
            }
        }

        // Adresses.
        val addressesUri = Uri.parse("content://mms/$messageId/addr")
        fun saveAddress(address: String, type: Int) {
            val addressValues = ContentValues().apply {
                put(Telephony.Mms.Addr.MSG_ID, messageId)
                put(Telephony.Mms.Addr.ADDRESS, address)
                put(Telephony.Mms.Addr.TYPE, type)
                put(Telephony.Mms.Addr.CHARSET, CHARSET_UTF8)
            }
            try {
                resolver.insert(addressesUri, addressValues)
            } catch (e: Exception) {
                Timber.e(e, "Impossible d'enregistrer une adresse du MMS")
            }
        }
        if (from.isNotEmpty()) saveAddress(from, ADDRESS_FROM)
        pdu.to.forEach { saveAddress(it, ADDRESS_TO) }
        pdu.cc.forEach { saveAddress(it, ADDRESS_CC) }

        // Ne jamais journaliser le contenu ni les numéros (données personnelles).
        Timber.i("MMS reçu enregistré (%d pièce(s)) : %s", pdu.parts.size, messageUri)

        val sender = SmsRepository.displayName(context, from)
        val preview = mmsPreview(pdu.parts.filter { it.isText && it.contentType == "text/plain" }.map { it.text() }, pdu.parts.map { it.contentType }, pdu.subject)
        return if (recipients.size > 1) {
            SavedMms(threadId, SmsRepository.conversationTitle(context, recipients), "$sender : $preview")
        } else {
            SavedMms(threadId, sender, preview)
        }
    }

    /**
     * Membres de la conversation : l'expéditeur et les autres destinataires, sans soi-même.
     *
     * Pour un MMS à une seule personne, le seul destinataire est soi : on en profite pour retenir son numéro,
     * qui permet ensuite de se retirer des MMS de groupe.
     */
    private fun conversationMembers(context: Context, from: String, recipients: List<String>, subscriptionId: Int?): List<String> {
        val members = if (recipients.size <= 1) {
            recipients.firstOrNull()?.let { SelfNumbers.remember(context, it) }
            listOf(from)
        } else {
            val self = SelfNumbers.get(context, subscriptionId)
            listOf(from) + recipients.filterNot { address -> self.any { sameNumber(address, it) } }
        }
        return members
            .filter { it.isNotBlank() }
            .fold(emptyList<String>()) { acc, address -> if (acc.any { sameNumber(it, address) }) acc else acc + address }
            .ifEmpty { listOf(from) }
    }
}

/** Résumé d'un MMS : son texte, sinon le type de pièce jointe. */
internal fun mmsPreview(texts: List<String>, contentTypes: List<String>, subject: String?): String {
    val text = texts.joinToString("\n").trim()
    return when {
        text.isNotEmpty() -> text
        contentTypes.any { it.startsWith("image/") } -> "📷 Photo"
        contentTypes.any { it.startsWith("video/") } -> "🎬 Vidéo"
        contentTypes.any { it.startsWith("audio/") } -> "🎤 Audio"
        !subject.isNullOrBlank() -> subject
        else -> "📎 Pièce jointe"
    }
}

/** Même numéro, quelle que soit l'écriture ("06…" ou "+336…"). */
@Suppress("DEPRECATION")
private fun sameNumber(a: String, b: String): Boolean = PhoneNumberUtils.compare(a, b)

/** Numéros de téléphone de l'utilisateur (ses cartes SIM). */
private object SelfNumbers {
    private const val PREFS = "pigeon_sms"
    private const val KEY = "self_numbers"

    fun get(context: Context, subscriptionId: Int?): Set<String> {
        val known = prefs(context).getStringSet(KEY, emptySet()).orEmpty()
        return known + listOfNotNull(simNumber(context, subscriptionId))
    }

    fun remember(context: Context, number: String) {
        if (number.isBlank()) return
        val known = prefs(context).getStringSet(KEY, emptySet()).orEmpty()
        if (known.none { sameNumber(it, number) }) {
            prefs(context).edit().putStringSet(KEY, known + number).apply()
        }
    }

    /** Numéro enregistré sur la carte SIM : souvent absent en France, d'où [remember]. */
    @SuppressLint("HardwareIds", "MissingPermission")
    @Suppress("DEPRECATION")
    private fun simNumber(context: Context, subscriptionId: Int?): String? = try {
        val telephony = context.getSystemService(TelephonyManager::class.java)
        val forSim = if (subscriptionId != null) telephony.createForSubscriptionId(subscriptionId) else telephony
        forSim.line1Number?.takeIf { it.isNotBlank() }
    } catch (e: Exception) {
        // Permission absente.
        null
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
