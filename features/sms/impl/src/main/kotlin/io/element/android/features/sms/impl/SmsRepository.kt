/*
 * Copyright 2026 Pigeon contributors.
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.element.android.features.sms.impl

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.database.Cursor
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import android.provider.Telephony
import androidx.core.content.ContextCompat
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap

/** Une conversation : un fil avec un correspondant, ou plusieurs pour un groupe (MMS). */
data class SmsConversation(
    val threadId: Long,
    val addresses: List<String>,
    val displayName: String,
    val snippet: String,
    /** Le dernier message est un message envoyé (affiché "Vous : …"). */
    val lastIsOutgoing: Boolean,
    val date: Long,
    val unreadCount: Int,
)

/** Une conversation ouverte : son fil, ses correspondants et son titre. */
data class SmsThreadInfo(val threadId: Long, val addresses: List<String>, val title: String) {
    val isGroup: Boolean get() = addresses.size > 1
}

/** État d'un message. */
enum class SmsStatus { RECEIVED, SENDING, SENT, FAILED }

/** Un SMS ou un MMS dans une conversation. */
data class SmsItem(
    /** Unique dans la conversation ("sms-12", "mms-5") : SMS et MMS ont chacun leur numérotation. */
    val key: String,
    val body: String,
    val date: Long,
    val status: SmsStatus,
    /** Nom de l'expéditeur, pour un message reçu dans un groupe. */
    val sender: String? = null,
    val attachments: List<MmsAttachment> = emptyList(),
    /** MMS pas encore téléchargé : son avis en attente. */
    val pendingMms: PendingMmsState? = null,
) {
    val isIncoming: Boolean get() = status == SmsStatus.RECEIVED
}

/** MMS annoncé mais pas encore téléchargé ([downloading]), ou dont le téléchargement a échoué. */
data class PendingMmsState(val id: Long, val downloading: Boolean)

/** Pièce jointe d'un MMS (photo, vidéo…), lisible à l'adresse [uri]. */
data class MmsAttachment(val uri: Uri, val contentType: String) {
    val isImage: Boolean get() = contentType.startsWith("image/")
}

/**
 * Lecture des SMS et MMS dans la base du téléphone (le "Telephony provider").
 *
 * Toutes ces fonctions accèdent au disque : à appeler hors du fil principal.
 */
object SmsRepository {
    private val SMS_PROJECTION = arrayOf(
        Telephony.Sms._ID,
        Telephony.Sms.THREAD_ID,
        Telephony.Sms.ADDRESS,
        Telephony.Sms.BODY,
        Telephony.Sms.DATE,
        Telephony.Sms.TYPE,
        Telephony.Sms.READ,
    )
    private val SMS_LIGHT_PROJECTION = arrayOf(
        Telephony.Sms._ID,
        Telephony.Sms.THREAD_ID,
        Telephony.Sms.DATE,
        Telephony.Sms.TYPE,
        Telephony.Sms.READ,
    )
    private val MMS_PROJECTION = arrayOf(
        Telephony.Mms._ID,
        Telephony.Mms.THREAD_ID,
        Telephony.Mms.DATE,
        Telephony.Mms.MESSAGE_BOX,
        Telephony.Mms.READ,
        Telephony.Mms.SUBJECT,
        Telephony.Mms.MESSAGE_TYPE,
        Telephony.Mms.STATUS,
    )

    // Les brouillons ne sont pas affichés.
    private const val MMS_SELECTION = "${Telephony.Mms.MESSAGE_BOX} != ${Telephony.Mms.MESSAGE_BOX_DRAFTS}"

    private val THREADS_URI: Uri = Uri.parse("content://mms-sms/conversations?simple=true")
    private val CANONICAL_ADDRESSES_URI: Uri = Uri.parse("content://mms-sms/canonical-addresses")
    private val MMS_PARTS_URI: Uri = Uri.parse("content://mms/part")

    /** Toutes les conversations, la plus récente en premier. */
    fun loadConversations(context: Context): List<SmsConversation> {
        // 1. Parcours léger de tous les SMS (sans leur texte) : le dernier SMS de chaque fil et le nombre de non-lus.
        val latestSms = HashMap<Long, SmsRow>()
        val unread = HashMap<Long, Int>()
        query(context, Telephony.Sms.CONTENT_URI, SMS_LIGHT_PROJECTION, null, null, "${Telephony.Sms.DATE} DESC") { c ->
            val row = SmsRow(id = c.getLong(0), threadId = c.getLong(1), address = "", body = "", date = c.getLong(2), type = c.getInt(3), read = c.getInt(4) == 1)
            if (row.isIncoming && !row.read) unread[row.threadId] = (unread[row.threadId] ?: 0) + 1
            if (row.threadId !in latestSms) latestSms[row.threadId] = row
        }
        // 2. Texte et numéro de ces seuls derniers SMS.
        val bodies = HashMap<Long, Pair<String, String>>()
        latestSms.values.map { it.id }.chunked(500).forEach { ids ->
            val selection = "${Telephony.Sms._ID} IN (${ids.joinToString(",")})"
            query(context, Telephony.Sms.CONTENT_URI, arrayOf(Telephony.Sms._ID, Telephony.Sms.BODY, Telephony.Sms.ADDRESS), selection, null, null) { c ->
                bodies[c.getLong(0)] = c.getString(1).orEmpty() to c.getString(2).orEmpty()
            }
        }
        val latest = HashMap<Long, SmsConversation>()
        for ((threadId, row) in latestSms) {
            val (body, address) = bodies[row.id] ?: ("" to "")
            latest[threadId] = SmsConversation(
                threadId = threadId,
                addresses = listOf(address),
                displayName = address,
                snippet = body,
                lastIsOutgoing = !row.isIncoming,
                date = row.date,
                unreadCount = 0,
            )
        }
        val latestMms = HashMap<Long, MmsRow>()
        queryMms(context, MMS_SELECTION, null, "${Telephony.Mms.DATE} DESC") { row ->
            if (row.isIncoming && !row.read && !row.isPending) unread[row.threadId] = (unread[row.threadId] ?: 0) + 1
            if (row.threadId !in latestMms) latestMms[row.threadId] = row
        }
        // Un MMS plus récent que le dernier SMS devient le dernier message du fil.
        for ((threadId, mms) in latestMms) {
            val sms = latest[threadId]
            if (sms == null || mms.date > sms.date) {
                latest[threadId] = SmsConversation(
                    threadId = threadId,
                    addresses = sms?.addresses.orEmpty(),
                    displayName = "",
                    snippet = mmsSnippet(context, mms),
                    lastIsOutgoing = !mms.isIncoming,
                    date = mms.date,
                    unreadCount = 0,
                )
            }
        }

        val members = threadMembers(context, threadId = null)
        val names = ContactNames(context)
        return latest.values
            .map { conversation ->
                val addresses = members[conversation.threadId]?.takeIf { it.isNotEmpty() } ?: conversation.addresses
                conversation.copy(
                    addresses = addresses,
                    displayName = names.title(addresses),
                    unreadCount = unread[conversation.threadId] ?: 0,
                )
            }
            .sortedByDescending { it.date }
    }

    /** Les SMS et MMS d'une conversation, le plus ancien en premier. */
    fun loadMessages(context: Context, threadId: Long, isGroup: Boolean): List<SmsItem> {
        val items = ArrayList<SmsItem>()
        querySms(context, "${Telephony.Sms.THREAD_ID} = ?", arrayOf(threadId.toString()), "${Telephony.Sms.DATE} ASC") { row ->
            items += SmsItem(key = "sms-${row.id}", body = row.body, date = row.date, status = row.status)
        }
        val names = ContactNames(context)
        val mmsRows = ArrayList<MmsRow>()
        queryMms(context, "${Telephony.Mms.THREAD_ID} = ? AND $MMS_SELECTION", arrayOf(threadId.toString()), "${Telephony.Mms.DATE} ASC") { mmsRows += it }
        // Pièces de tous les MMS de la conversation en une seule lecture.
        val partsByMms = loadParts(context, mmsRows.filterNot { it.isPending }.map { it.id })
        for (row in mmsRows) {
            val parts = partsByMms[row.id] ?: MmsParts(emptyList(), emptyList())
            items += SmsItem(
                key = "mms-${row.id}",
                body = parts.texts.joinToString("\n").ifBlank { row.subject.orEmpty() },
                date = row.date,
                status = row.status,
                sender = if (isGroup && row.isIncoming) mmsSender(context, row.id)?.let(names::lookup) else null,
                attachments = parts.attachments,
                pendingMms = if (row.isPending) PendingMmsState(row.id, downloading = row.downloadState == MmsStore.STATE_DOWNLOADING && MmsDownloader.isDownloading(row.id)) else null,
            )
        }
        return items.sortedBy { it.date }
    }

    /** Conversation d'un fil existant (notification d'un MMS de groupe…). */
    fun threadInfo(context: Context, threadId: Long): SmsThreadInfo {
        val addresses = threadMembers(context, threadId)[threadId].orEmpty()
        return SmsThreadInfo(threadId, addresses, ContactNames(context).title(addresses))
    }

    /** Conversation avec un numéro (créée si elle n'existe pas). */
    fun threadInfo(context: Context, address: String): SmsThreadInfo =
        SmsThreadInfo(threadIdFor(context, address), listOf(address), displayName(context, address))

    /** Nom du contact pour un numéro, ou le numéro lui-même. */
    fun displayName(context: Context, address: String): String = ContactNames(context).lookup(address)

    /** Titre d'une conversation : le nom du correspondant, ou ceux des membres du groupe. */
    fun conversationTitle(context: Context, addresses: List<String>): String = ContactNames(context).title(addresses)

    /** Fil de conversation d'un numéro (créé s'il n'existe pas). */
    fun threadIdFor(context: Context, address: String): Long = Telephony.Threads.getOrCreateThreadId(context, address)

    /** Marque comme lus tous les SMS et MMS reçus d'une conversation. */
    fun markThreadRead(context: Context, threadId: Long) {
        val values = ContentValues().apply {
            put(Telephony.Sms.READ, 1)
            put(Telephony.Sms.SEEN, 1)
        }
        val args = arrayOf(threadId.toString())
        try {
            context.contentResolver.update(Telephony.Sms.CONTENT_URI, values, "${Telephony.Sms.THREAD_ID} = ? AND ${Telephony.Sms.READ} = 0", args)
            context.contentResolver.update(Telephony.Mms.CONTENT_URI, values, "${Telephony.Mms.THREAD_ID} = ? AND ${Telephony.Mms.READ} = 0", args)
        } catch (e: Exception) {
            // Seule l'app SMS par défaut peut modifier les messages.
            Timber.w(e, "Impossible de marquer la conversation comme lue")
        }
    }

    // ── SMS ──────────────────────────────────────────────────────────────────

    private class SmsRow(
        val id: Long,
        val threadId: Long,
        val address: String,
        val body: String,
        val date: Long,
        val type: Int,
        val read: Boolean,
    ) {
        val isIncoming: Boolean get() = type == Telephony.Sms.MESSAGE_TYPE_INBOX
        val status: SmsStatus
            get() = when (type) {
                Telephony.Sms.MESSAGE_TYPE_INBOX -> SmsStatus.RECEIVED
                Telephony.Sms.MESSAGE_TYPE_SENT -> SmsStatus.SENT
                Telephony.Sms.MESSAGE_TYPE_FAILED -> SmsStatus.FAILED
                // Boîte d'envoi, file d'attente : pas encore parti.
                else -> SmsStatus.SENDING
            }
    }

    private fun querySms(context: Context, selection: String?, args: Array<String>?, sortOrder: String, onRow: (SmsRow) -> Unit) {
        query(context, Telephony.Sms.CONTENT_URI, SMS_PROJECTION, selection, args, sortOrder) { c ->
            onRow(
                SmsRow(
                    id = c.getLong(0),
                    threadId = c.getLong(1),
                    address = c.getString(2).orEmpty(),
                    body = c.getString(3).orEmpty(),
                    date = c.getLong(4),
                    type = c.getInt(5),
                    read = c.getInt(6) == 1,
                )
            )
        }
    }

    // ── MMS ──────────────────────────────────────────────────────────────────

    private class MmsRow(
        val id: Long,
        val threadId: Long,
        /** En millisecondes (la base MMS compte en secondes). */
        val date: Long,
        val box: Int,
        val read: Boolean,
        val subject: String?,
        val type: Int,
        /** État de téléchargement d'un avis en attente. */
        val downloadState: Int,
    ) {
        val isIncoming: Boolean get() = box == Telephony.Mms.MESSAGE_BOX_INBOX
        val isPending: Boolean get() = type == MmsPdu.TYPE_NOTIFICATION_IND
        val status: SmsStatus
            get() = when (box) {
                Telephony.Mms.MESSAGE_BOX_INBOX -> SmsStatus.RECEIVED
                Telephony.Mms.MESSAGE_BOX_SENT -> SmsStatus.SENT
                Telephony.Mms.MESSAGE_BOX_FAILED -> SmsStatus.FAILED
                else -> SmsStatus.SENDING
            }
    }

    private fun queryMms(context: Context, selection: String, args: Array<String>?, sortOrder: String, onRow: (MmsRow) -> Unit) {
        query(context, Telephony.Mms.CONTENT_URI, MMS_PROJECTION, selection, args, sortOrder) { c ->
            onRow(
                MmsRow(
                    id = c.getLong(0),
                    threadId = c.getLong(1),
                    date = c.getLong(2) * 1000,
                    box = c.getInt(3),
                    read = c.getInt(4) == 1,
                    subject = c.getString(5),
                    type = c.getInt(6),
                    downloadState = c.getInt(7),
                )
            )
        }
    }

    private class MmsParts(val texts: List<String>, val attachments: List<MmsAttachment>)

    /** Texte et pièces jointes de plusieurs MMS, par MMS (la présentation SMIL est ignorée). */
    private fun loadParts(context: Context, mmsIds: List<Long>): Map<Long, MmsParts> {
        val texts = HashMap<Long, MutableList<String>>()
        val attachments = HashMap<Long, MutableList<MmsAttachment>>()
        val projection = arrayOf(Telephony.Mms.Part._ID, Telephony.Mms.Part.MSG_ID, Telephony.Mms.Part.CONTENT_TYPE, Telephony.Mms.Part.TEXT)
        mmsIds.chunked(500).forEach { ids ->
            val selection = "${Telephony.Mms.Part.MSG_ID} IN (${ids.joinToString(",")})"
            query(context, MMS_PARTS_URI, projection, selection, null, "${Telephony.Mms.Part.SEQ} ASC") { c ->
                val mmsId = c.getLong(1)
                when (val type = c.getString(2).orEmpty().lowercase()) {
                    "text/plain" -> c.getString(3)?.takeIf { it.isNotBlank() }?.let { texts.getOrPut(mmsId) { ArrayList() } += it }
                    // Présentation, et version HTML d'un texte déjà présent : non affichées.
                    "application/smil", "text/html" -> Unit
                    else -> attachments.getOrPut(mmsId) { ArrayList() } += MmsAttachment(Uri.parse("content://mms/part/${c.getLong(0)}"), type)
                }
            }
        }
        return mmsIds.associateWith { MmsParts(texts[it].orEmpty(), attachments[it].orEmpty()) }
    }

    private fun mmsSnippet(context: Context, row: MmsRow): String {
        if (row.isPending) return context.getString(R.string.screen_sms_preview_pending_mms)
        val parts = loadParts(context, listOf(row.id))[row.id] ?: MmsParts(emptyList(), emptyList())
        return mmsPreview(context, parts.texts, parts.attachments.map { it.contentType }, row.subject)
    }

    /** Expéditeur d'un MMS reçu. */
    private fun mmsSender(context: Context, mmsId: Long): String? {
        var sender: String? = null
        val uri = Uri.parse("content://mms/$mmsId/addr")
        query(context, uri, arrayOf(Telephony.Mms.Addr.ADDRESS), "${Telephony.Mms.Addr.TYPE} = 137", null, null) { c ->
            if (sender == null) sender = c.getString(0)
        }
        return sender
    }

    // ── Membres des conversations ────────────────────────────────────────────

    /** Numéros des membres de chaque fil (ou du seul fil [threadId]). */
    private fun threadMembers(context: Context, threadId: Long?): Map<Long, List<String>> {
        val recipientIds = HashMap<Long, List<Long>>()
        query(
            context,
            THREADS_URI,
            arrayOf(Telephony.Threads._ID, Telephony.Threads.RECIPIENT_IDS),
            threadId?.let { "${Telephony.Threads._ID} = ?" },
            threadId?.let { arrayOf(it.toString()) },
            null,
        ) { c ->
            recipientIds[c.getLong(0)] = c.getString(1).orEmpty().split(' ').mapNotNull { it.trim().toLongOrNull() }
        }
        val addresses = HashMap<Long, String>()
        query(context, CANONICAL_ADDRESSES_URI, arrayOf("_id", "address"), null, null, null) { c ->
            addresses[c.getLong(0)] = c.getString(1).orEmpty()
        }
        return recipientIds.mapValues { (_, ids) -> ids.mapNotNull { id -> addresses[id] ?: canonicalAddress(context, id) } }
    }

    /** Numéro d'un membre, si la liste complète n'a pas pu être lue. */
    private fun canonicalAddress(context: Context, id: Long): String? {
        var address: String? = null
        query(context, Uri.parse("content://mms-sms/canonical-address/$id"), null, null, null, null) { c ->
            address = c.getString(c.getColumnIndexOrThrow("address"))
        }
        return address
    }

    private fun query(
        context: Context,
        uri: Uri,
        projection: Array<String>?,
        selection: String?,
        args: Array<String>?,
        sortOrder: String?,
        onRow: (Cursor) -> Unit,
    ) {
        try {
            context.contentResolver.query(uri, projection, selection, args, sortOrder)?.use { c ->
                while (c.moveToNext()) onRow(c)
            }
        } catch (e: Exception) {
            // Permission de lecture des SMS absente (Pigeon n'est pas l'app SMS par défaut), ou base différente selon le fabricant.
            Timber.w(e, "Lecture impossible : %s", uri)
        }
    }

    /** Recherche des noms dans les contacts. */
    private class ContactNames(private val context: Context) {
        private val allowed = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

        fun lookup(address: String): String {
            if (!allowed || address.isBlank()) return address
            return ContactNameCache.get(context, address)
        }

        /** "Alice" pour une conversation, "Alice, Bob, Carole" pour un groupe. */
        fun title(addresses: List<String>): String = addresses.joinToString(", ") { lookup(it) }
    }
}

/**
 * Noms des contacts déjà cherchés, gardés tant que l'app tourne : chaque recherche interroge les contacts du téléphone,
 * ce qui est lent quand il faut le refaire pour toute la liste à chaque affichage.
 * Le cache est vidé dès qu'un contact change.
 */
private object ContactNameCache {
    private val names = ConcurrentHashMap<String, String>()

    @Volatile
    private var watching = false

    fun get(context: Context, address: String): String {
        watchContacts(context)
        return names.getOrPut(address) { find(context, address) ?: address }
    }

    private fun find(context: Context, address: String): String? {
        val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(address))
        return try {
            context.contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        } catch (e: Exception) {
            Timber.w(e, "Recherche de contact impossible")
            null
        }
    }

    @Synchronized
    private fun watchContacts(context: Context) {
        if (watching) return
        try {
            val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean) {
                    names.clear()
                }
            }
            context.applicationContext.contentResolver.registerContentObserver(ContactsContract.Contacts.CONTENT_URI, true, observer)
            watching = true
        } catch (e: Exception) {
            Timber.w(e, "Suivi des modifications des contacts impossible")
        }
    }
}
