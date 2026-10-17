/*
 * Copyright 2026 Pigeon contributors.
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.element.android.features.sms.impl

import java.io.ByteArrayOutputStream
import java.nio.charset.Charset

/**
 * Lecture et écriture des messages MMS bruts ("PDU"), au format binaire de l'opérateur (OMA MMS / WAP WSP).
 *
 * Un MMS arrive en deux temps :
 * 1. un avis ([TYPE_NOTIFICATION_IND]) reçu comme un SMS spécial, qui donne l'adresse où télécharger le MMS ;
 * 2. le MMS lui-même ([TYPE_RETRIEVE_CONF]), téléchargé par Android : en-têtes (expéditeur, destinataires…)
 *    puis les pièces (texte, photos…).
 *
 * Seuls les champs utiles à Pigeon sont interprétés ; les autres sont lus puis ignorés.
 */
class MmsPdu(
    val messageType: Int,
    val transactionId: String?,
    val messageId: String?,
    val contentLocation: String?,
    val from: String?,
    val to: List<String>,
    val cc: List<String>,
    val subject: String?,
    /** Date d'envoi, en secondes. */
    val date: Long?,
    val version: Int?,
    val contentType: String?,
    val parts: List<MmsPart>,
) {
    companion object {
        const val TYPE_SEND_REQ = 0x80
        const val TYPE_NOTIFICATION_IND = 0x82
        const val TYPE_NOTIFY_RESP_IND = 0x83
        const val TYPE_RETRIEVE_CONF = 0x84
        const val TYPE_ACKNOWLEDGE_IND = 0x85

        /** Lit un PDU ; renvoie null s'il est illisible. */
        fun parse(bytes: ByteArray): MmsPdu? = try {
            PduReader(bytes).readPdu()
        } catch (e: IndexOutOfBoundsException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        }

        /**
         * Accusé de réception d'un MMS téléchargé (M-Acknowledge.ind), à renvoyer à l'opérateur
         * pour qu'il ne renvoie pas l'avis.
         */
        fun acknowledge(transactionId: String): ByteArray = ByteArrayOutputStream().apply {
            write(0x80 or FIELD_MESSAGE_TYPE)
            write(TYPE_ACKNOWLEDGE_IND)
            write(0x80 or FIELD_TRANSACTION_ID)
            writeText(transactionId)
            write(0x80 or FIELD_VERSION)
            write(0x80 or MMS_VERSION_1_2)
        }.toByteArray()

        /**
         * Réponse à un avis de MMS (M-NotifyResp.ind) : "MMS récupéré". Envoyée quand le MMS téléchargé
         * ne demande pas d'accusé de réception, ou quand l'avis arrive pour un MMS déjà reçu.
         */
        fun notifyResponseRetrieved(transactionId: String): ByteArray = ByteArrayOutputStream().apply {
            write(0x80 or FIELD_MESSAGE_TYPE)
            write(TYPE_NOTIFY_RESP_IND)
            write(0x80 or FIELD_TRANSACTION_ID)
            writeText(transactionId)
            write(0x80 or FIELD_VERSION)
            write(0x80 or MMS_VERSION_1_2)
            write(0x80 or FIELD_STATUS)
            write(STATUS_RETRIEVED)
        }.toByteArray()

        /**
         * MMS à envoyer (M-Send.req) : destinataires [to] et pièces [parts], dans cet ordre.
         *
         * Les pièces sont envoyées en "multipart/related" avec leur présentation SMIL ([smilFor]),
         * comme le font les apps SMS d'Android : certains opérateurs refusent les MMS sans elle.
         * L'expéditeur est laissé à remplir par l'opérateur.
         */
        fun sendRequest(transactionId: String, to: List<String>, parts: List<MmsPart>): ByteArray = ByteArrayOutputStream().apply {
            write(0x80 or FIELD_MESSAGE_TYPE)
            write(TYPE_SEND_REQ)
            write(0x80 or FIELD_TRANSACTION_ID)
            writeText(transactionId)
            write(0x80 or FIELD_VERSION)
            write(0x80 or MMS_VERSION_1_2)
            // Expéditeur : longueur 1, "à compléter par l'opérateur".
            write(0x80 or FIELD_FROM)
            write(1)
            write(FROM_INSERT_ADDRESS)
            to.forEach {
                write(0x80 or FIELD_TO)
                writeText("${phoneNumber(it)}/TYPE=PLMN")
            }
            write(0x80 or FIELD_MESSAGE_CLASS)
            write(MESSAGE_CLASS_PERSONAL)
            write(0x80 or FIELD_DELIVERY_REPORT)
            write(NO)
            write(0x80 or FIELD_READ_REPORT)
            write(NO)
            // Type du corps, toujours en dernier : multipart/related, de présentation SMIL ("start" = son Content-ID).
            write(0x80 or FIELD_CONTENT_TYPE)
            writeWithLength {
                write(0x80 or WELL_KNOWN_MULTIPART_RELATED)
                write(0x80 or PARAM_START)
                writeText(SMIL_CONTENT_ID)
                write(0x80 or PARAM_TYPE)
                writeText(SMIL_TYPE)
            }
            // Corps : nombre de pièces, puis chacune avec ses en-têtes et son contenu.
            val all = listOf(smilFor(parts)) + parts
            writeUintvar(all.size)
            for (part in all) {
                val headers = ByteArrayOutputStream().apply { writePartHeaders(part) }.toByteArray()
                writeUintvar(headers.size)
                writeUintvar(part.data.size)
                write(headers)
                write(part.data)
            }
        }.toByteArray()

        /** Présentation SMIL des pièces : la photo en haut, le texte en dessous. */
        private fun smilFor(parts: List<MmsPart>): MmsPart {
            val image = parts.firstOrNull { it.contentType.startsWith("image/") }
            val text = parts.firstOrNull { it.contentType == "text/plain" }
            val regions = buildString {
                if (image != null) append("""<region id="Image" fit="meet" top="0" left="0" height="80%" width="100%"/>""")
                if (text != null) append("""<region id="Text" top="80%" left="0" height="20%" width="100%"/>""")
            }
            val body = buildString {
                image?.let { append("""<img src="${it.contentLocation}" region="Image"/>""") }
                text?.let { append("""<text src="${it.contentLocation}" region="Text"/>""") }
            }
            val smil = "<smil><head><layout><root-layout/>$regions</layout></head><body><par dur=\"5000ms\">$body</par></body></smil>"
            return MmsPart(SMIL_TYPE, name = null, fileName = null, contentId = SMIL_CONTENT_ID, contentLocation = "smil.xml", charset = null, data = smil.toByteArray())
        }

        /** Type de la pièce (avec son jeu de caractères pour un texte), puis son adresse et son identifiant. */
        private fun ByteArrayOutputStream.writePartHeaders(part: MmsPart) {
            writeWithLength {
                val code = WELL_KNOWN_CODES[part.contentType]
                if (code != null) write(0x80 or code) else writeText(part.contentType)
                part.charset?.let {
                    write(0x80 or PARAM_CHARSET)
                    write(0x80 or it)
                }
                part.contentLocation?.let {
                    write(0x80 or PARAM_NAME)
                    writeText(it)
                }
            }
            part.contentLocation?.let {
                write(0x80 or PART_CONTENT_LOCATION_FIELD)
                writeText(it)
            }
            part.contentId?.let {
                write(0x80 or PART_CONTENT_ID_FIELD)
                // Texte entre guillemets : marqueur 0x22 au début.
                write(0x22)
                write(it.toByteArray(Charsets.US_ASCII))
                write(0)
            }
        }

        /** Numéro sans espaces ni séparateurs ("06 12-34" → "061234"), seule forme acceptée par l'opérateur. */
        private fun phoneNumber(address: String): String = address.filter { it.isDigit() || it == '+' }.ifEmpty { address }

        /** Valeur précédée de sa longueur : sur un octet jusqu'à 30, sinon 31 puis la longueur. */
        private fun ByteArrayOutputStream.writeWithLength(content: ByteArrayOutputStream.() -> Unit) {
            val bytes = ByteArrayOutputStream().apply(content).toByteArray()
            if (bytes.size <= 30) {
                write(bytes.size)
            } else {
                write(31)
                writeUintvar(bytes.size)
            }
            write(bytes)
        }

        /** Nombre écrit par groupes de 7 bits, le bit de poids fort indiquant qu'un groupe suit. */
        private fun ByteArrayOutputStream.writeUintvar(value: Int) {
            var shift = 28
            while (shift > 0 && (value ushr shift) == 0) shift -= 7
            while (shift > 0) {
                write(0x80 or ((value ushr shift) and 0x7F))
                shift -= 7
            }
            write(value and 0x7F)
        }

        // Codes des en-têtes (OMA-TS-MMS_ENC, tableau 25).
        internal const val FIELD_CC = 0x02
        internal const val FIELD_CONTENT_LOCATION = 0x03
        internal const val FIELD_CONTENT_TYPE = 0x04
        internal const val FIELD_DATE = 0x05
        internal const val FIELD_FROM = 0x09
        internal const val FIELD_MESSAGE_ID = 0x0B
        internal const val FIELD_MESSAGE_TYPE = 0x0C
        internal const val FIELD_VERSION = 0x0D
        internal const val FIELD_STATUS = 0x15
        internal const val FIELD_SUBJECT = 0x16
        internal const val FIELD_TO = 0x17
        internal const val FIELD_TRANSACTION_ID = 0x18
        internal const val FIELD_DELIVERY_REPORT = 0x06
        internal const val FIELD_MESSAGE_CLASS = 0x0A
        internal const val FIELD_READ_REPORT = 0x10
        internal const val MMS_VERSION_1_2 = 0x12
        private const val FROM_INSERT_ADDRESS = 0x81
        private const val MESSAGE_CLASS_PERSONAL = 0x80
        private const val NO = 0x81

        // Type du corps et des pièces (WAP WSP, tableaux 38 et 40).
        private const val WELL_KNOWN_MULTIPART_RELATED = 0x33
        private const val PARAM_CHARSET = 0x01
        private const val PARAM_NAME = 0x05
        private const val PARAM_TYPE = 0x09
        private const val PARAM_START = 0x0A
        private const val PART_CONTENT_LOCATION_FIELD = 0x0E
        private const val PART_CONTENT_ID_FIELD = 0x40
        private const val SMIL_TYPE = "application/smil"
        private const val SMIL_CONTENT_ID = "<smil>"
        private val WELL_KNOWN_CODES = mapOf("text/plain" to 0x03, "image/gif" to 0x1D, "image/jpeg" to 0x1E, "image/png" to 0x20)
        private const val STATUS_RETRIEVED = 0x81

        private fun ByteArrayOutputStream.writeText(text: String) {
            val bytes = text.toByteArray(Charsets.US_ASCII)
            // Un texte qui commence par un octet ≥ 0x80 doit être précédé de 0x7F.
            if (bytes.isNotEmpty() && bytes[0].toInt() and 0xFF >= 0x80) write(0x7F)
            write(bytes)
            write(0)
        }
    }
}

/** Une pièce d'un MMS : texte, photo, vidéo, carte de visite, présentation SMIL… */
class MmsPart(
    val contentType: String,
    val name: String?,
    val fileName: String?,
    val contentId: String?,
    val contentLocation: String?,
    /** Jeu de caractères (numéro IANA "MIBenum"), pour les pièces texte. */
    val charset: Int?,
    val data: ByteArray,
) {
    /**
     * Pièce rangée comme texte dans la base (comme le fait Android) ; les autres pièces, y compris
     * les cartes de visite (text/x-vcard), sont rangées comme fichiers.
     */
    val isText: Boolean get() = contentType in TEXT_TYPES

    /** Contenu d'une pièce texte. */
    fun text(): String = String(data, charsetFor(charset)).trimEnd('\u0000')
}

private val TEXT_TYPES = setOf("text/plain", "text/html", "application/smil")

/** Une valeur d'en-tête brute : un nombre, un texte, ou des octets précédés de leur longueur. */
private sealed interface WspValue {
    data class Number(val value: Long) : WspValue
    data class Text(val value: String) : WspValue
    class Bytes(val start: Int, val end: Int) : WspValue
}

private class PduReader(private val b: ByteArray) {
    private var pos = 0

    fun readPdu(): MmsPdu {
        var messageType = -1
        var transactionId: String? = null
        var messageId: String? = null
        var contentLocation: String? = null
        var from: String? = null
        val to = ArrayList<String>()
        val cc = ArrayList<String>()
        var subject: String? = null
        var date: Long? = null
        var version: Int? = null
        var contentType: ContentType? = null

        // En-têtes, jusqu'à Content-Type (toujours le dernier), suivi des pièces.
        while (pos < b.size && contentType == null) {
            val field = u8(pos)
            if (field < 0x80) {
                // En-tête non standard ("X-Nom: valeur") : ignoré.
                readText()
                readValue()
                continue
            }
            pos++
            when (field and 0x7F) {
                MmsPdu.FIELD_CONTENT_TYPE -> contentType = readContentType()
                // Valeur sur un octet (0x80 à 0x88), qu'on garde telle quelle.
                MmsPdu.FIELD_MESSAGE_TYPE -> messageType = readValue().asNumber()?.toInt()?.or(0x80) ?: -1
                MmsPdu.FIELD_TRANSACTION_ID -> transactionId = readValue().asText()
                MmsPdu.FIELD_MESSAGE_ID -> messageId = readValue().asText()
                MmsPdu.FIELD_CONTENT_LOCATION -> contentLocation = readValue().asText()
                MmsPdu.FIELD_FROM -> from = readFrom()
                MmsPdu.FIELD_TO -> readEncodedString()?.let { to += cleanAddress(it) }
                MmsPdu.FIELD_CC -> readEncodedString()?.let { cc += cleanAddress(it) }
                MmsPdu.FIELD_SUBJECT -> subject = readEncodedString()
                MmsPdu.FIELD_DATE -> date = readValue().asNumber()
                MmsPdu.FIELD_VERSION -> version = (readValue() as? WspValue.Number)?.value?.toInt()
                else -> readValue()
            }
        }
        // Les MMS avec plusieurs pièces ("multipart") contiennent un corps ; un avis n'en a pas.
        val parts = if (contentType != null && pos < b.size && contentType.isMultipart) readParts() else emptyList()
        return MmsPdu(
            messageType = messageType,
            transactionId = transactionId,
            messageId = messageId,
            contentLocation = contentLocation,
            from = from?.let(::cleanAddress),
            to = to,
            cc = cc,
            subject = subject?.takeIf { it.isNotBlank() },
            date = date,
            version = version,
            contentType = contentType?.mediaType,
            parts = parts,
        )
    }

    private fun readParts(): List<MmsPart> {
        val count = readUintvar()
        val parts = ArrayList<MmsPart>(count.coerceAtMost(64))
        repeat(count) {
            val headersLength = readUintvar()
            val dataLength = readUintvar()
            val headersEnd = pos + headersLength
            val type = readContentType()
            var contentId: String? = null
            var contentLocation: String? = null
            var fileName = type.fileName
            while (pos < headersEnd) {
                val field = u8(pos)
                if (field >= 0x80) {
                    pos++
                    val value = readValue()
                    when (field and 0x7F) {
                        PART_CONTENT_LOCATION -> contentLocation = value.asText()
                        PART_CONTENT_ID -> contentId = value.asText()
                        PART_CONTENT_DISPOSITION, PART_CONTENT_DISPOSITION_V14 -> fileName = fileName ?: dispositionFileName(value)
                    }
                } else {
                    val name = readText()
                    val value = readValue().asText()
                    when (name.lowercase()) {
                        "content-location" -> contentLocation = value
                        "content-id" -> contentId = value
                    }
                }
            }
            pos = headersEnd
            require(dataLength >= 0 && pos + dataLength <= b.size)
            val data = b.copyOfRange(pos, pos + dataLength)
            pos += dataLength
            parts += MmsPart(
                contentType = type.mediaType.lowercase(),
                name = type.name,
                fileName = fileName,
                contentId = contentId,
                contentLocation = contentLocation,
                charset = type.charset,
                data = data,
            )
        }
        return parts
    }

    // ── Valeurs ──────────────────────────────────────────────────────────────

    /**
     * Lit une valeur d'en-tête. Son premier octet indique sa forme :
     * 0-30 longueur courte, 31 longueur longue, 32-127 texte terminé par 0, ≥ 128 petit nombre.
     */
    private fun readValue(): WspValue {
        val first = u8(pos)
        return when {
            first <= 30 -> {
                pos++
                bytes(first)
            }
            first == 31 -> {
                pos++
                bytes(readUintvar())
            }
            first < 0x80 -> WspValue.Text(readText())
            else -> {
                pos++
                WspValue.Number((first and 0x7F).toLong())
            }
        }
    }

    /** Octets de longueur [length] (un nombre long, un texte avec jeu de caractères…). */
    private fun bytes(length: Int): WspValue {
        require(length >= 0 && pos + length <= b.size)
        val start = pos
        pos += length
        return WspValue.Bytes(start, pos)
    }

    /** Nombre court, ou nombre long (1 à 8 octets, poids fort en premier). */
    private fun WspValue.asNumber(): Long? = when (this) {
        is WspValue.Number -> value
        is WspValue.Text -> null
        is WspValue.Bytes -> if (end - start in 1..8) (start until end).fold(0L) { acc, i -> (acc shl 8) or u8(i).toLong() } else null
    }

    private fun WspValue.asText(): String? = when (this) {
        is WspValue.Text -> value
        is WspValue.Number -> value.toString()
        is WspValue.Bytes -> PduReader(b.copyOfRange(start, end)).readText()
    }

    /** Texte terminé par 0, avec ses éventuels guillemets de début (0x22) ou marqueur 0x7F. */
    fun readText(): String {
        if (u8(pos) == 0x7F || u8(pos) == 0x22) pos++
        val start = pos
        while (pos < b.size && b[pos].toInt() != 0) pos++
        val text = String(b, start, pos - start, Charsets.UTF_8)
        if (pos < b.size) pos++ // le 0 final
        return text
    }

    /** Texte avec son jeu de caractères éventuel (sujet, adresses). */
    private fun readEncodedString(): String? = when (val value = readValue()) {
        is WspValue.Text -> value.value
        is WspValue.Number -> null
        is WspValue.Bytes -> decodeEncodedString(value.start, value.end)
    }

    private fun decodeEncodedString(start: Int, end: Int): String {
        val sub = PduReader(b.copyOfRange(start, end))
        val charset = sub.readInteger()
        val textStart = sub.pos + if (sub.pos < end - start && (sub.u8(sub.pos) == 0x7F)) 1 else 0
        var textEnd = end - start
        while (textEnd > textStart && sub.b[textEnd - 1].toInt() == 0) textEnd--
        return String(sub.b, textStart, textEnd - textStart, charsetFor(charset?.toInt()))
    }

    /** Expéditeur : "adresse présente" (0x80) suivie de l'adresse, ou "à compléter" (0x81). */
    private fun readFrom(): String? {
        val value = readValue() as? WspValue.Bytes ?: return null
        if (value.end <= value.start || u8(value.start) != 0x80) return null
        val sub = PduReader(b.copyOfRange(value.start + 1, value.end))
        return sub.readEncodedString()
    }

    /** Nombre court (≥ 0x80) ou long (longueur puis octets). */
    fun readInteger(): Long? {
        val first = u8(pos)
        return when {
            first >= 0x80 -> {
                pos++
                (first and 0x7F).toLong()
            }
            first <= 30 -> {
                pos++
                var value = 0L
                repeat(first) { value = (value shl 8) or u8(pos++).toLong() }
                value
            }
            else -> null
        }
    }

    private fun readUintvar(): Int {
        var value = 0L
        while (true) {
            val octet = u8(pos++)
            value = (value shl 7) or (octet and 0x7F).toLong()
            require(value <= Int.MAX_VALUE)
            if (octet and 0x80 == 0) return value.toInt()
        }
    }

    fun u8(index: Int): Int = b[index].toInt() and 0xFF

    // ── Content-Type ─────────────────────────────────────────────────────────

    private class ContentType(val mediaType: String, val charset: Int?, val name: String?, val fileName: String?) {
        val isMultipart: Boolean get() = mediaType.startsWith("multipart/") || mediaType.startsWith("application/vnd.wap.multipart.")
    }

    /** Type simple ("image/jpeg", ou son code), ou forme longue : longueur, type, puis paramètres. */
    private fun readContentType(): ContentType {
        val first = u8(pos)
        if (first >= 0x80) {
            pos++
            return ContentType(wellKnownType(first and 0x7F), null, null, null)
        }
        if (first >= 32) return ContentType(readText(), null, null, null)

        val length = if (first == 31) {
            pos++
            readUintvar()
        } else {
            pos++
            first
        }
        val end = pos + length
        require(end <= b.size)
        val mediaType = when (val type = readValue()) {
            is WspValue.Number -> wellKnownType(type.value.toInt())
            is WspValue.Text -> type.value
            // Code de type sur plusieurs octets.
            is WspValue.Bytes -> wellKnownType(type.asNumber()?.toInt() ?: -1)
        }
        var charset: Int? = null
        var name: String? = null
        var fileName: String? = null
        while (pos < end) {
            val param = u8(pos)
            if (param >= 0x80) {
                pos++
                val value = readValue()
                when (param and 0x7F) {
                    PARAM_CHARSET -> charset = value.asNumber()?.toInt()
                    PARAM_NAME, PARAM_NAME_V14 -> name = value.asText()
                    PARAM_FILENAME, PARAM_FILENAME_V14 -> fileName = value.asText()
                }
            } else {
                val paramName = readText()
                val value = readValue()
                when (paramName.lowercase()) {
                    "charset" -> charset = value.asNumber()?.toInt()
                    "name" -> name = value.asText()
                    "filename" -> fileName = value.asText()
                }
            }
        }
        pos = end
        return ContentType(mediaType, charset, name, fileName)
    }

    /** Nom de fichier dans "Content-Disposition" (longueur, type de disposition, paramètres). */
    private fun dispositionFileName(value: WspValue): String? {
        if (value !is WspValue.Bytes) return null
        val sub = PduReader(b.copyOfRange(value.start, value.end))
        sub.pos++ // type : attachment, inline…
        while (sub.pos < sub.b.size) {
            val param = sub.u8(sub.pos)
            if (param >= 0x80) {
                sub.pos++
                val paramValue = sub.readValue()
                if ((param and 0x7F) in setOf(PARAM_FILENAME, PARAM_FILENAME_V14, PARAM_NAME, PARAM_NAME_V14)) {
                    return with(sub) { paramValue.asText() }
                }
            } else {
                sub.readText()
                sub.readValue()
            }
        }
        return null
    }

    private companion object {
        const val PARAM_CHARSET = 0x01
        const val PARAM_NAME = 0x05
        const val PARAM_FILENAME = 0x06
        const val PARAM_NAME_V14 = 0x17
        const val PARAM_FILENAME_V14 = 0x18
        const val PART_CONTENT_LOCATION = 0x0E
        const val PART_CONTENT_DISPOSITION = 0x2E
        const val PART_CONTENT_ID = 0x40
        const val PART_CONTENT_DISPOSITION_V14 = 0x45

        /** Types de contenu codés par un nombre (WAP WSP, annexe A). */
        val WELL_KNOWN_TYPES = mapOf(
            0x00 to "*/*",
            0x01 to "text/*",
            0x02 to "text/html",
            0x03 to "text/plain",
            0x06 to "text/x-vcalendar",
            0x07 to "text/x-vcard",
            0x0B to "multipart/*",
            0x0C to "multipart/mixed",
            0x0F to "multipart/alternative",
            0x10 to "application/*",
            0x1C to "image/*",
            0x1D to "image/gif",
            0x1E to "image/jpeg",
            0x1F to "image/tiff",
            0x20 to "image/png",
            0x21 to "image/vnd.wap.wbmp",
            0x22 to "application/vnd.wap.multipart.*",
            0x23 to "application/vnd.wap.multipart.mixed",
            0x26 to "application/vnd.wap.multipart.alternative",
            0x27 to "application/xml",
            0x28 to "text/xml",
            0x33 to "application/vnd.wap.multipart.related",
            0x3E to "application/vnd.wap.mms-message",
        )

        fun wellKnownType(code: Int): String = WELL_KNOWN_TYPES[code] ?: "application/octet-stream"
    }
}

/** "+33612345678/TYPE=PLMN" → "+33612345678". */
internal fun cleanAddress(address: String): String = address.substringBefore("/TYPE=").trim()

/** Jeu de caractères à partir de son numéro IANA ; UTF-8 par défaut. */
internal fun charsetFor(mibEnum: Int?): Charset = when (mibEnum) {
    3 -> Charsets.US_ASCII
    4 -> Charsets.ISO_8859_1
    1000 -> Charsets.UTF_16BE // ISO-10646-UCS-2
    1013 -> Charsets.UTF_16BE
    1014 -> Charsets.UTF_16LE
    1015 -> Charsets.UTF_16
    else -> Charsets.UTF_8
}
