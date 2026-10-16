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

/** Avis de MMS enregistré en attendant son téléchargement (ou après un échec, pour réessayer). */
class PendingMms(val id: Long, val threadId: Long, val location: String, val transactionId: String?, val subscriptionId: Int?, val failed: Boolean)

/** Ce que la base contient déjà pour une adresse de téléchargement. */
sealed interface KnownMms {
    data object Received : KnownMms
    class Pending(val mms: PendingMms) : KnownMms
}

/**
 * Enregistrement des MMS reçus dans la base SMS/MMS du téléphone (le "Telephony provider").
 *
 * Comme pour les SMS, c'est à l'app par défaut de le faire. Un MMS y est rangé en trois tables :
 * le message, ses pièces (texte, photos…) et ses adresses (expéditeur, destinataires).
 *
 * L'avis d'arrivée est d'abord enregistré seul ("en attente", comme le fait Android), puis remplacé
 * par le MMS une fois téléchargé : un échec de téléchargement ne fait donc pas perdre le MMS.
 */
object MmsStore {
    // Codes de la base MMS (OMA MMS) : type d'adresse et jeu de caractères UTF-8.
    private const val ADDRESS_FROM = 137
    private const val ADDRESS_TO = 151
    private const val ADDRESS_CC = 130
    private const val CHARSET_UTF8 = 106

    // État de téléchargement d'un avis en attente (colonne "st", mêmes valeurs qu'Android).
    const val STATE_DOWNLOADING = 0x81
    private const val STATE_FAILED = 0x82

    private val PENDING_PROJECTION = arrayOf(
        Telephony.Mms._ID,
        Telephony.Mms.THREAD_ID,
        Telephony.Mms.CONTENT_LOCATION,
        Telephony.Mms.TRANSACTION_ID,
        Telephony.Mms.SUBSCRIPTION_ID,
        Telephony.Mms.STATUS,
        Telephony.Mms.MESSAGE_TYPE,
    )

    /** Ce que la base contient déjà pour cette adresse de téléchargement (avis reçu deux fois…). */
    fun find(context: Context, location: String): KnownMms? {
        var known: KnownMms? = null
        queryPending(context, "${Telephony.Mms.CONTENT_LOCATION} = ?", arrayOf(location)) { pending, type ->
            if (type == MmsPdu.TYPE_RETRIEVE_CONF) {
                known = KnownMms.Received
            } else if (known == null) {
                known = KnownMms.Pending(pending)
            }
        }
        return known
    }

    /** Avis en attente n° [id], pour relancer son téléchargement. */
    fun pending(context: Context, id: Long): PendingMms? {
        var found: PendingMms? = null
        queryPending(context, "${Telephony.Mms._ID} = ? AND ${Telephony.Mms.MESSAGE_TYPE} = ${MmsPdu.TYPE_NOTIFICATION_IND}", arrayOf(id.toString())) { pending, _ ->
            found = pending
        }
        return found
    }

    /** Enregistre l'avis, rangé dans la conversation avec l'expéditeur, le temps du téléchargement. */
    fun savePending(context: Context, notification: MmsPdu, location: String, subscriptionId: Int?): PendingMms? {
        // Expéditeur masqué : pas de conversation où ranger l'avis, le MMS est seulement téléchargé.
        val from = notification.from?.takeIf { it.isNotBlank() } ?: return null
        return try {
            val threadId = Telephony.Threads.getOrCreateThreadId(context, from)
            val values = ContentValues().apply {
                put(Telephony.Mms.THREAD_ID, threadId)
                put(Telephony.Mms.DATE, System.currentTimeMillis() / 1000)
                put(Telephony.Mms.MESSAGE_BOX, Telephony.Mms.MESSAGE_BOX_INBOX)
                // Pas compté comme non lu : c'est le MMS téléchargé qui le sera.
                put(Telephony.Mms.READ, 1)
                put(Telephony.Mms.SEEN, 1)
                put(Telephony.Mms.MESSAGE_TYPE, MmsPdu.TYPE_NOTIFICATION_IND)
                put(Telephony.Mms.MMS_VERSION, notification.version ?: MmsPdu.MMS_VERSION_1_2)
                put(Telephony.Mms.TRANSACTION_ID, notification.transactionId)
                put(Telephony.Mms.CONTENT_LOCATION, location)
                put(Telephony.Mms.STATUS, STATE_DOWNLOADING)
                notification.subject?.let {
                    put(Telephony.Mms.SUBJECT, it)
                    put(Telephony.Mms.SUBJECT_CHARSET, CHARSET_UTF8)
                }
                subscriptionId?.let { put(Telephony.Mms.SUBSCRIPTION_ID, it) }
            }
            val uri = context.contentResolver.insert(Telephony.Mms.Inbox.CONTENT_URI, values) ?: return null
            val id = ContentUris.parseId(uri)
            saveAddress(context, id, from, ADDRESS_FROM)
            PendingMms(id, threadId, location, notification.transactionId, subscriptionId, failed = false)
        } catch (e: Exception) {
            Timber.e(e, "Impossible d'enregistrer l'avis de MMS")
            null
        }
    }

    /** Téléchargement en cours ou échoué, affiché dans la conversation. */
    fun setPendingState(context: Context, id: Long, downloading: Boolean) {
        val values = ContentValues().apply { put(Telephony.Mms.STATUS, if (downloading) STATE_DOWNLOADING else STATE_FAILED) }
        try {
            context.contentResolver.update(ContentUris.withAppendedId(Telephony.Mms.CONTENT_URI, id), values, null, null)
        } catch (e: Exception) {
            Timber.e(e, "Impossible de mettre à jour l'avis de MMS")
        }
    }

    private fun queryPending(context: Context, selection: String, args: Array<String>, onRow: (PendingMms, Int) -> Unit) {
        try {
            context.contentResolver.query(Telephony.Mms.CONTENT_URI, PENDING_PROJECTION, selection, args, null)?.use { c ->
                while (c.moveToNext()) {
                    val pending = PendingMms(
                        id = c.getLong(0),
                        threadId = c.getLong(1),
                        location = c.getString(2).orEmpty(),
                        transactionId = c.getString(3),
                        subscriptionId = if (c.isNull(4)) null else c.getInt(4).takeIf { it >= 0 },
                        failed = c.getInt(5) != STATE_DOWNLOADING,
                    )
                    onRow(pending, c.getInt(6))
                }
            }
        } catch (e: Exception) {
            Timber.w(e, "Lecture des MMS en attente impossible")
        }
    }

    /**
     * Enregistre le MMS téléchargé, puis retire son avis en attente [pendingId].
     *
     * [location] est l'adresse de téléchargement donnée par l'avis (le MMS téléchargé ne la contient pas) :
     * elle permet de reconnaître un avis reçu deux fois.
     */
    fun saveIncoming(context: Context, pdu: MmsPdu, size: Int, subscriptionId: Int?, location: String?, pendingId: Long?): SavedMms? {
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
            put(Telephony.Mms.CONTENT_LOCATION, location)
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
        if (from.isNotEmpty()) saveAddress(context, messageId, from, ADDRESS_FROM)
        pdu.to.forEach { saveAddress(context, messageId, it, ADDRESS_TO) }
        pdu.cc.forEach { saveAddress(context, messageId, it, ADDRESS_CC) }

        // L'avis en attente est remplacé par le MMS.
        pendingId?.let { id ->
            try {
                resolver.delete(ContentUris.withAppendedId(Telephony.Mms.CONTENT_URI, id), null, null)
            } catch (e: Exception) {
                Timber.w(e, "Impossible de retirer l'avis de MMS")
            }
        }

        // Ne jamais journaliser le contenu ni les numéros (données personnelles).
        Timber.i("MMS reçu enregistré (%d pièce(s)) : %s", pdu.parts.size, messageUri)

        val sender = SmsRepository.displayName(context, from)
        val preview = mmsPreview(context, pdu.parts.filter { it.contentType == "text/plain" }.map { it.text() }, pdu.parts.map { it.contentType }, pdu.subject)
        return if (recipients.size > 1) {
            SavedMms(threadId, SmsRepository.conversationTitle(context, recipients), context.getString(R.string.screen_sms_group_preview, sender, preview))
        } else {
            SavedMms(threadId, sender, preview)
        }
    }

    private fun saveAddress(context: Context, messageId: Long, address: String, type: Int) {
        val values = ContentValues().apply {
            put(Telephony.Mms.Addr.MSG_ID, messageId)
            put(Telephony.Mms.Addr.ADDRESS, address)
            put(Telephony.Mms.Addr.TYPE, type)
            put(Telephony.Mms.Addr.CHARSET, CHARSET_UTF8)
        }
        try {
            context.contentResolver.insert(Uri.parse("content://mms/$messageId/addr"), values)
        } catch (e: Exception) {
            Timber.e(e, "Impossible d'enregistrer une adresse du MMS")
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
internal fun mmsPreview(context: Context, texts: List<String>, contentTypes: List<String>, subject: String?): String {
    val text = texts.joinToString("\n").trim()
    return when {
        text.isNotEmpty() -> text
        contentTypes.any { it.startsWith("image/") } -> context.getString(R.string.screen_sms_preview_photo)
        contentTypes.any { it.startsWith("video/") } -> context.getString(R.string.screen_sms_preview_video)
        contentTypes.any { it.startsWith("audio/") } -> context.getString(R.string.screen_sms_preview_audio)
        !subject.isNullOrBlank() -> subject
        else -> context.getString(R.string.screen_sms_preview_attachment)
    }
}

/** Même numéro, quelle que soit l'écriture ("06…" ou "+336…"). */
@Suppress("DEPRECATION")
private fun sameNumber(a: String, b: String): Boolean = PhoneNumberUtils.compare(a, b)

/**
 * Numéros de téléphone de l'utilisateur (ses cartes SIM), pour se retirer des membres d'un MMS de groupe.
 *
 * Le numéro est rarement lisible sur la carte SIM en France : il est appris à la réception d'un MMS individuel,
 * ou saisi par l'utilisateur à la première ouverture de l'écran des SMS.
 */
internal object SelfNumbers {
    private const val PREFS = "pigeon_sms"
    private const val KEY = "self_numbers"
    private const val KEY_ASKED = "self_number_asked"

    /** Au moins un numéro connu. */
    fun isKnown(context: Context): Boolean = get(context, subscriptionId = null).isNotEmpty()

    /** L'utilisateur a déjà répondu (ou refusé) à la demande de son numéro. */
    fun wasAsked(context: Context): Boolean = prefs(context).getBoolean(KEY_ASKED, false)

    fun setAsked(context: Context) = prefs(context).edit().putBoolean(KEY_ASKED, true).apply()

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
