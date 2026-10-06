package com.purrfectbytes.android.services

import com.google.gson.JsonParser
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** A word of the spoken text and when it is heard, in seconds from the start of the audio. */
data class WordBoundary(val word: String, val start: Double, val end: Double)

/** A voice of the service. [id] is what the service calls it: "ja-JP-NanamiNeural". */
data class EdgeVoice(val id: String, val gender: String, val locale: String) {

    /** "Nanami", "Hyunsu Multilingual" */
    val name: String
        get() = id.removePrefix("$locale-").removeSuffix("Neural").replace("Multilingual", " Multilingual").trim()

    /** "Nanami - Female (ja-JP)" */
    val label: String
        get() = "$name - $gender ($locale)"

    /** The language of the voice the way the service writes it: "ja", "nb", "fil". */
    val language: String
        get() = locale.substringBefore("-").lowercase()
}

/**
 * The message format of Microsoft Edge's read-aloud service.
 *
 * Ported from the Python edge-tts library (version 7.2.7) that the web app uses, so both
 * apps talk to the service the same way. Nothing here touches the network or Android.
 */
internal object EdgeTtsProtocol {

    const val TRUSTED_CLIENT_TOKEN = "6A5AA1D4EAFF4E9FB37E23D68491D6F4"

    /**
     * The Edge version the service is told it is talking to. Microsoft refuses versions
     * that get too old: when every request starts failing with HTTP 403, copy
     * CHROMIUM_FULL_VERSION from the current edge-tts release (edge_tts/constants.py).
     */
    const val CHROMIUM_FULL_VERSION = "143.0.3650.75"
    val CHROMIUM_MAJOR_VERSION = CHROMIUM_FULL_VERSION.substringBefore(".")

    const val SERVICE_URL =
        "wss://speech.platform.bing.com/consumer/speech/synthesize/readaloud/edge/v1" +
            "?TrustedClientToken=$TRUSTED_CLIENT_TOKEN"

    const val VOICES_URL =
        "https://speech.platform.bing.com/consumer/speech/synthesize/readaloud/voices/list" +
            "?trustedclienttoken=$TRUSTED_CLIENT_TOKEN"

    private val BROWSER_HEADERS: Map<String, String> = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36" +
            " (KHTML, like Gecko) Chrome/$CHROMIUM_MAJOR_VERSION.0.0.0 Safari/537.36" +
            " Edg/$CHROMIUM_MAJOR_VERSION.0.0.0",
        "Accept-Language" to "en-US,en;q=0.9"
    )

    val HEADERS: Map<String, String> = mapOf(
        "Pragma" to "no-cache",
        "Cache-Control" to "no-cache",
        "Origin" to "chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold"
    ) + BROWSER_HEADERS

    /** Sent with the request for the list of voices. */
    val VOICE_HEADERS: Map<String, String> = mapOf(
        "Authority" to "speech.platform.bing.com",
        "Sec-CH-UA" to "\" Not;A Brand\";v=\"99\", \"Microsoft Edge\";v=\"$CHROMIUM_MAJOR_VERSION\"," +
            " \"Chromium\";v=\"$CHROMIUM_MAJOR_VERSION\"",
        "Sec-CH-UA-Mobile" to "?0",
        "Accept" to "*/*",
        "Sec-Fetch-Site" to "none",
        "Sec-Fetch-Mode" to "cors",
        "Sec-Fetch-Dest" to "empty"
    ) + BROWSER_HEADERS

    /** Letters, digits and hyphens: a name that can stand in a request as it is. */
    private val VOICE_NAME = Regex("[A-Za-z0-9-]+")

    fun isVoiceName(name: String): Boolean = VOICE_NAME.matches(name)

    /** The service takes at most this many bytes of text in one request. */
    const val MAX_TEXT_BYTES = 4096

    /** Silence the service adds after each request, in 100-nanosecond ticks. */
    const val PADDING_TICKS = 8_750_000L

    const val TICKS_PER_SECOND = 10_000_000.0

    private const val WINDOWS_EPOCH_SECONDS = 11_644_473_600L

    fun connectionUrl(baseUrl: String, connectionId: String, unixSeconds: Double): String =
        "$baseUrl&ConnectionId=$connectionId" +
            "&Sec-MS-GEC=${secMsGec(unixSeconds)}" +
            "&Sec-MS-GEC-Version=1-$CHROMIUM_FULL_VERSION"

    fun voicesUrl(baseUrl: String, unixSeconds: Double): String =
        "$baseUrl&Sec-MS-GEC=${secMsGec(unixSeconds)}" +
            "&Sec-MS-GEC-Version=1-$CHROMIUM_FULL_VERSION"

    /**
     * The access token the service expects: the current time rounded down to five
     * minutes, as a Windows file time, hashed together with the client token.
     */
    fun secMsGec(unixSeconds: Double): String {
        var ticks = unixSeconds + WINDOWS_EPOCH_SECONDS
        ticks -= ticks % 300
        ticks *= 1e9 / 100
        val toHash = String.format(Locale.US, "%.0f", ticks) + TRUSTED_CLIENT_TOKEN
        return MessageDigest.getInstance("SHA-256")
            .digest(toHash.toByteArray(Charsets.US_ASCII))
            .joinToString("") { "%02X".format(it) }
    }

    /** The date the way JavaScript prints it, which is what Edge sends. */
    fun timestamp(now: Date = Date()): String {
        val format = SimpleDateFormat(
            "EEE MMM dd yyyy HH:mm:ss 'GMT+0000 (Coordinated Universal Time)'",
            Locale.US
        )
        format.timeZone = TimeZone.getTimeZone("UTC")
        return format.format(now)
    }

    /** Parses the Date header of an HTTP response into seconds since 1970, or null. */
    fun parseHttpDate(value: String?): Double? {
        if (value == null) return null
        return try {
            val format = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US)
            format.timeZone = TimeZone.getTimeZone("UTC")
            format.parse(value)?.let { it.time / 1000.0 }
        } catch (e: java.text.ParseException) {
            null
        }
    }

    /** Control characters make the service reject the whole request; they become spaces. */
    fun removeIncompatibleCharacters(text: String): String = buildString(text.length) {
        for (char in text) {
            val code = char.code
            append(if (code in 0..8 || code in 11..12 || code in 14..31) ' ' else char)
        }
    }

    /** The text travels inside an XML document, so the characters XML reserves are escaped. */
    fun escapeXml(text: String): String =
        text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    fun unescapeXml(text: String): String =
        text.replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")

    /** Cleans and escapes [text], then cuts it into pieces the service accepts. */
    fun prepareText(text: String): List<String> =
        splitByByteLength(escapeXml(removeIncompatibleCharacters(text)), MAX_TEXT_BYTES)

    /**
     * Cuts already escaped text into pieces of at most [byteLimit] UTF-8 bytes. Pieces end
     * at a line break or a space where possible, and never inside a character or inside
     * an escaped entity such as `&amp;`.
     */
    fun splitByByteLength(text: String, byteLimit: Int): List<String> {
        require(byteLimit > 0) { "byteLimit must be greater than 0" }

        val pieces = mutableListOf<String>()
        var rest = text
        while (utf8Length(rest) > byteLimit) {
            val fits = longestPrefixWithin(rest, byteLimit)
            var splitAt = rest.lastIndexOf('\n', fits - 1)
            if (splitAt < 0) splitAt = rest.lastIndexOf(' ', fits - 1)
            if (splitAt < 0) splitAt = fits
            splitAt = moveOutOfXmlEntity(rest, splitAt)

            val piece = rest.substring(0, splitAt).trim()
            if (piece.isNotEmpty()) pieces += piece

            // Always move forward, even when nothing could be cut off
            rest = rest.substring(if (splitAt > 0) splitAt else Character.charCount(rest.codePointAt(0)))
        }
        val last = rest.trim()
        if (last.isNotEmpty()) pieces += last
        return pieces
    }

    private fun utf8Length(text: String): Int = text.toByteArray(Charsets.UTF_8).size

    private fun utf8Length(codePoint: Int): Int = when {
        codePoint < 0x80 -> 1
        codePoint < 0x800 -> 2
        codePoint < 0x10000 -> 3
        else -> 4
    }

    /** Length in chars of the longest run of whole characters that fits in [byteLimit] bytes. */
    private fun longestPrefixWithin(text: String, byteLimit: Int): Int {
        var index = 0
        var bytes = 0
        while (index < text.length) {
            val codePoint = text.codePointAt(index)
            val size = utf8Length(codePoint)
            if (bytes + size > byteLimit) break
            bytes += size
            index += Character.charCount(codePoint)
        }
        return index
    }

    private fun moveOutOfXmlEntity(text: String, proposed: Int): Int {
        var splitAt = proposed
        while (splitAt > 0) {
            val ampersand = text.lastIndexOf('&', splitAt - 1)
            if (ampersand < 0) break
            val semicolon = text.indexOf(';', ampersand)
            if (semicolon in ampersand until splitAt) break // the entity ends before the cut
            splitAt = ampersand
        }
        return splitAt
    }

    fun ssml(voice: String, rate: String, escapedText: String): String =
        "<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='en-US'>" +
            "<voice name='$voice'>" +
            "<prosody pitch='+0Hz' rate='$rate' volume='+0%'>" +
            escapedText +
            "</prosody>" +
            "</voice>" +
            "</speak>"

    /** First message on a connection: asks for MP3 audio and a timestamp for every word. */
    fun speechConfigMessage(timestamp: String): String =
        "X-Timestamp:$timestamp\r\n" +
            "Content-Type:application/json; charset=utf-8\r\n" +
            "Path:speech.config\r\n\r\n" +
            "{\"context\":{\"synthesis\":{\"audio\":{\"metadataoptions\":{" +
            "\"sentenceBoundaryEnabled\":\"false\",\"wordBoundaryEnabled\":\"true\"" +
            "}," +
            "\"outputFormat\":\"audio-24khz-48kbitrate-mono-mp3\"" +
            "}}}}\r\n"

    fun ssmlMessage(requestId: String, timestamp: String, ssml: String): String =
        "X-RequestId:$requestId\r\n" +
            "Content-Type:application/ssml+xml\r\n" +
            "X-Timestamp:${timestamp}Z\r\n" + // The Z is not a mistake, Edge sends it like this
            "Path:ssml\r\n\r\n" +
            ssml

    sealed interface TextMessage {
        /** Words with their position in 100-nanosecond ticks from the start of this request. */
        data class Words(val words: List<TimedWord>) : TextMessage
        object TurnEnd : TextMessage
        object Other : TextMessage
    }

    data class TimedWord(val text: String, val offsetTicks: Long, val durationTicks: Long)

    fun parseTextMessage(message: String): TextMessage {
        val separator = message.indexOf("\r\n\r\n")
        val headerBlock = if (separator >= 0) message.substring(0, separator) else message
        val body = if (separator >= 0) message.substring(separator + 4) else ""

        return when (parseHeaders(headerBlock)["Path"]) {
            "turn.end" -> TextMessage.TurnEnd
            "audio.metadata" -> TextMessage.Words(parseWords(body))
            else -> TextMessage.Other
        }
    }

    private fun parseWords(json: String): List<TimedWord> {
        val entries = try {
            JsonParser.parseString(json).asJsonObject.getAsJsonArray("Metadata")
        } catch (e: RuntimeException) {
            null
        } ?: return emptyList()

        return entries.mapNotNull { entry ->
            try {
                val item = entry.asJsonObject
                if (item.get("Type").asString != "WordBoundary") return@mapNotNull null
                val data = item.getAsJsonObject("Data")
                TimedWord(
                    text = unescapeXml(data.getAsJsonObject("text").get("Text").asString),
                    offsetTicks = data.get("Offset").asLong,
                    durationTicks = data.get("Duration").asLong
                )
            } catch (e: RuntimeException) {
                null
            }
        }
    }

    /**
     * Reads the list of voices the service sends. Entries that cannot be used are left
     * out; throws [EdgeTtsException] when what was sent is no list at all.
     */
    fun parseVoices(json: String): List<EdgeVoice> {
        val entries = try {
            JsonParser.parseString(json).asJsonArray
        } catch (e: RuntimeException) {
            throw EdgeTtsException("Edge TTS sent a list of voices that cannot be read", e)
        }

        return entries.mapNotNull { entry ->
            try {
                val voice = entry.asJsonObject
                val id = voice.get("ShortName").asString
                val locale = voice.get("Locale").asString
                if (!isVoiceName(id) || locale.isBlank()) return@mapNotNull null
                EdgeVoice(
                    id = id,
                    gender = voice.get("Gender")?.takeIf { it.isJsonPrimitive }?.asString ?: "Unknown",
                    locale = locale
                )
            } catch (e: RuntimeException) {
                null
            }
        }
    }

    /**
     * Returns the MP3 bytes carried by a binary message, or null when it carries none
     * (the service ends every stream with an empty audio message).
     *
     * Layout: two bytes giving the length of the headers, the headers, then the audio.
     */
    fun parseAudioMessage(message: ByteArray): ByteArray? {
        if (message.size < 2) throw EdgeTtsException("Edge TTS sent a message that is too short")
        val headerLength = ((message[0].toInt() and 0xFF) shl 8) or (message[1].toInt() and 0xFF)
        val bodyStart = headerLength + 2
        if (bodyStart > message.size) throw EdgeTtsException("Edge TTS sent a message with a broken header")

        val headers = parseHeaders(String(message, 2, headerLength, Charsets.UTF_8))
        if (headers["Path"] != "audio") throw EdgeTtsException("Edge TTS sent data that is not audio")

        val body = message.copyOfRange(bodyStart, message.size)
        return when (headers["Content-Type"]) {
            "audio/mpeg" -> body.takeIf { it.isNotEmpty() }
                ?: throw EdgeTtsException("Edge TTS sent an audio message without audio")
            null -> if (body.isEmpty()) null
                else throw EdgeTtsException("Edge TTS sent audio without saying what kind")
            else -> throw EdgeTtsException("Edge TTS sent audio in an unexpected format")
        }
    }

    private fun parseHeaders(block: String): Map<String, String> =
        block.split("\r\n").mapNotNull { line ->
            val colon = line.indexOf(':')
            if (colon <= 0) null else line.substring(0, colon).trim() to line.substring(colon + 1).trim()
        }.toMap()
}

class EdgeTtsException(message: String, cause: Throwable? = null) : Exception(message, cause)
