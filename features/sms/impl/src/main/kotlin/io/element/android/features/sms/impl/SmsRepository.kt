/*
 * Copyright 2026 Pigeon contributors.
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.element.android.features.sms.impl

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import android.provider.Telephony
import androidx.core.content.ContextCompat
import timber.log.Timber

/** Une conversation SMS : un fil avec un correspondant. */
data class SmsConversation(
    val threadId: Long,
    val address: String,
    val displayName: String,
    val snippet: String,
    /** Le dernier SMS est un SMS envoyé (affiché "Vous : …"). */
    val lastIsOutgoing: Boolean,
    val date: Long,
    val unreadCount: Int,
)

/** État d'un SMS. */
enum class SmsStatus { RECEIVED, SENDING, SENT, FAILED }

/** Un SMS dans une conversation. */
data class SmsItem(
    val id: Long,
    val body: String,
    val date: Long,
    val status: SmsStatus,
) {
    val isIncoming: Boolean get() = status == SmsStatus.RECEIVED
}

/**
 * Lecture des SMS dans la base SMS du téléphone (le "Telephony provider").
 *
 * Toutes ces fonctions accèdent au disque : à appeler hors du fil principal.
 */
object SmsRepository {
    private val PROJECTION = arrayOf(
        Telephony.Sms._ID,
        Telephony.Sms.THREAD_ID,
        Telephony.Sms.ADDRESS,
        Telephony.Sms.BODY,
        Telephony.Sms.DATE,
        Telephony.Sms.TYPE,
        Telephony.Sms.READ,
    )

    /** Toutes les conversations, la plus récente en premier. */
    fun loadConversations(context: Context): List<SmsConversation> {
        // Pour chaque fil : le dernier SMS (le premier rencontré, tri par date décroissante) et le nombre de non-lus.
        val latest = LinkedHashMap<Long, SmsConversation>()
        val unread = HashMap<Long, Int>()
        query(context, selection = null, args = null, sortOrder = "${Telephony.Sms.DATE} DESC") { row ->
            if (row.isIncoming && !row.read) unread[row.threadId] = (unread[row.threadId] ?: 0) + 1
            if (row.threadId !in latest) {
                latest[row.threadId] = SmsConversation(
                    threadId = row.threadId,
                    address = row.address,
                    displayName = row.address,
                    snippet = row.body,
                    lastIsOutgoing = !row.isIncoming,
                    date = row.date,
                    unreadCount = 0,
                )
            }
        }
        val names = ContactNames(context)
        return latest.values.map { it.copy(displayName = names.lookup(it.address), unreadCount = unread[it.threadId] ?: 0) }
    }

    /** Les SMS d'une conversation, le plus ancien en premier. */
    fun loadMessages(context: Context, threadId: Long): List<SmsItem> {
        val items = ArrayList<SmsItem>()
        query(context, "${Telephony.Sms.THREAD_ID} = ?", arrayOf(threadId.toString()), "${Telephony.Sms.DATE} ASC") { row ->
            items += SmsItem(id = row.id, body = row.body, date = row.date, status = row.status)
        }
        return items
    }

    /** Nom du contact pour un numéro, ou le numéro lui-même. */
    fun displayName(context: Context, address: String): String = ContactNames(context).lookup(address)

    /** Fil de conversation d'un numéro (créé s'il n'existe pas). */
    fun threadIdFor(context: Context, address: String): Long = Telephony.Threads.getOrCreateThreadId(context, address)

    /** Marque comme lus tous les SMS reçus d'une conversation. */
    fun markThreadRead(context: Context, threadId: Long) {
        val values = ContentValues().apply {
            put(Telephony.Sms.READ, 1)
            put(Telephony.Sms.SEEN, 1)
        }
        try {
            context.contentResolver.update(
                Telephony.Sms.CONTENT_URI,
                values,
                "${Telephony.Sms.THREAD_ID} = ? AND ${Telephony.Sms.READ} = 0",
                arrayOf(threadId.toString()),
            )
        } catch (e: Exception) {
            // Seule l'app SMS par défaut peut modifier les SMS.
            Timber.w(e, "Impossible de marquer la conversation comme lue")
        }
    }

    private class Row(
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

    private fun query(context: Context, selection: String?, args: Array<String>?, sortOrder: String, onRow: (Row) -> Unit) {
        try {
            context.contentResolver.query(Telephony.Sms.CONTENT_URI, PROJECTION, selection, args, sortOrder)?.use { c ->
                val iId = c.getColumnIndexOrThrow(Telephony.Sms._ID)
                val iThread = c.getColumnIndexOrThrow(Telephony.Sms.THREAD_ID)
                val iAddress = c.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
                val iBody = c.getColumnIndexOrThrow(Telephony.Sms.BODY)
                val iDate = c.getColumnIndexOrThrow(Telephony.Sms.DATE)
                val iType = c.getColumnIndexOrThrow(Telephony.Sms.TYPE)
                val iRead = c.getColumnIndexOrThrow(Telephony.Sms.READ)
                while (c.moveToNext()) {
                    onRow(
                        Row(
                            id = c.getLong(iId),
                            threadId = c.getLong(iThread),
                            address = c.getString(iAddress).orEmpty(),
                            body = c.getString(iBody).orEmpty(),
                            date = c.getLong(iDate),
                            type = c.getInt(iType),
                            read = c.getInt(iRead) == 1,
                        )
                    )
                }
            }
        } catch (e: SecurityException) {
            // Permission de lecture des SMS absente (Pigeon n'est pas l'app SMS par défaut).
            Timber.w(e, "Lecture des SMS refusée")
        }
    }

    /** Recherche des noms dans les contacts, avec un cache (un même numéro revient souvent). */
    private class ContactNames(private val context: Context) {
        private val cache = HashMap<String, String>()
        private val allowed = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

        fun lookup(address: String): String {
            if (!allowed || address.isBlank()) return address
            return cache.getOrPut(address) {
                val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(address))
                try {
                    context.contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)?.use { c ->
                        if (c.moveToFirst()) c.getString(0) else null
                    }
                } catch (e: Exception) {
                    Timber.w(e, "Recherche de contact impossible")
                    null
                } ?: address
            }
        }
    }
}
