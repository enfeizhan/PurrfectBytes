package com.purrfectbytes.android.services

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Date

class EdgeTtsProtocolTest {

    @Test
    fun `the access token is the one edge-tts computes`() {
        // Values from edge_tts.drm.DRM.generate_sec_ms_gec (edge-tts 7.2.7) for fixed moments
        val expected = mapOf(
            0.0 to "7ECB79D14E3AA576D2D79E6D487A1388156D91E614B1BE11C64226A29BC8DD8C",
            1700000000.0 to "42301B335578FEFDAE2637DED1ABD614505D432559EC08032B82048483726AFF",
            1790553600.0 to "4DF49421E22249E38CB1C5943AAECA7027496EEEB736A8EED2E2C76DA8353BF5",
            1790553899.999 to "4DF49421E22249E38CB1C5943AAECA7027496EEEB736A8EED2E2C76DA8353BF5",
            1790553900.0 to "AFFF77F786E91B63C5BC540DB55EF297C5F4C6E5829651312F51E4842D791775",
            4102444800.5 to "19550B79FD170D7E11790C0ED3E998A8BCDE9D014035515B1A7D288198BE4FBC"
        )
        expected.forEach { (unixSeconds, token) ->
            assertEquals("token for $unixSeconds", token, EdgeTtsProtocol.secMsGec(unixSeconds))
        }
    }

    @Test
    fun `text that would break the request is made safe`() {
        // Same results as edge-tts: remove_incompatible_characters and xml escape
        assertEquals("a b c\td\ne f", EdgeTtsProtocol.removeIncompatibleCharacters("a\u000bb\u0000c\td\ne\u001ff"))
        assertEquals("Tom &amp; Jerry &lt;3 &gt; 2 \"q\" 'a'", EdgeTtsProtocol.escapeXml("Tom & Jerry <3 > 2 \"q\" 'a'"))
    }

    @Test
    fun `escaped text reads the same after unescaping`() {
        val text = "a < b && c > d &amp; e"
        assertEquals(text, EdgeTtsProtocol.unescapeXml(EdgeTtsProtocol.escapeXml(text)))
    }

    @Test
    fun `the spoken text cannot end the document it travels in`() {
        val piece = EdgeTtsProtocol.prepareText("</prosody></voice></speak> Tom & Jerry").single()
        val ssml = EdgeTtsProtocol.ssml("en-US-AriaNeural", "+0%", piece)

        assertEquals(1, Regex("</speak>").findAll(ssml).count())
        assertTrue(ssml.contains("&lt;/prosody&gt;&lt;/voice&gt;&lt;/speak&gt; Tom &amp; Jerry"))
    }

    @Test
    fun `long text is cut where edge-tts cuts it`() {
        assertEquals(listOf("hello", "world", "this is", "a test"), EdgeTtsProtocol.splitByByteLength("hello world this is a test", 10))
        assertEquals(listOf("one two", "three four", "five six"), EdgeTtsProtocol.splitByByteLength("one two\nthree four five six", 12))
        assertEquals(listOf("short"), EdgeTtsProtocol.splitByByteLength("short", 100))
        assertEquals(listOf("padded", "text", "here"), EdgeTtsProtocol.splitByByteLength("  padded   text here  ", 9))
    }

    @Test
    fun `text without spaces is cut between characters`() {
        // Every character here takes three bytes
        val pieces = EdgeTtsProtocol.splitByByteLength("こんにちは 世界 です", 10)
        assertEquals(listOf("こんに", "ちは", "世界", "です"), pieces)
    }

    @Test
    fun `an escaped character is never cut in two`() {
        val pieces = EdgeTtsProtocol.splitByByteLength("a &amp; b &lt;c&gt; d &amp; e", 8)
        assertEquals(listOf("a &amp;", "b", "&lt;c", "&gt; d", "&amp; e"), pieces)
    }

    @Test
    fun `no piece is larger than the limit and nothing is lost`() {
        val text = "The quick brown fox — 素早い茶色の狐 — jumps over the lazy dog. ".repeat(200)
        val pieces = EdgeTtsProtocol.prepareText(text)

        assertTrue(pieces.size > 1)
        pieces.forEach { assertTrue(it.toByteArray(Charsets.UTF_8).size <= EdgeTtsProtocol.MAX_TEXT_BYTES) }
        assertEquals(text.filterNot { it.isWhitespace() }, pieces.joinToString("").filterNot { it.isWhitespace() })
    }

    @Test
    fun `a character of four bytes is not cut either`() {
        val pieces = EdgeTtsProtocol.splitByByteLength("😀😀😀", 5)
        assertEquals(listOf("😀", "😀", "😀"), pieces)
    }

    @Test
    fun `the date is written the way a browser writes it`() {
        assertEquals(
            "Mon Sep 28 2026 08:34:00 GMT+0000 (Coordinated Universal Time)",
            EdgeTtsProtocol.timestamp(Date(1790584440000L))
        )
    }

    @Test
    fun `the date of a response is understood`() {
        assertEquals(1790584440.0, EdgeTtsProtocol.parseHttpDate("Mon, 28 Sep 2026 08:34:00 GMT")!!, 0.0)
        assertNull(EdgeTtsProtocol.parseHttpDate("yesterday"))
        assertNull(EdgeTtsProtocol.parseHttpDate(null))
    }

    @Test
    fun `audio is taken out of a binary message`() {
        val audio = byteArrayOf(1, 2, 3, 0, -1)
        assertArrayEquals(audio, EdgeTtsProtocol.parseAudioMessage(audioMessage(audio)))
    }

    @Test
    fun `the empty message that ends a stream carries no audio`() {
        assertNull(EdgeTtsProtocol.parseAudioMessage(audioMessage(ByteArray(0), contentType = null)))
    }

    @Test
    fun `broken binary messages are refused`() {
        assertThrows(EdgeTtsException::class.java) { EdgeTtsProtocol.parseAudioMessage(byteArrayOf(0)) }
        assertThrows(EdgeTtsException::class.java) { EdgeTtsProtocol.parseAudioMessage(byteArrayOf(0, 50, 1, 2)) }
        assertThrows(EdgeTtsException::class.java) {
            EdgeTtsProtocol.parseAudioMessage(audioMessage(byteArrayOf(1), path = "video"))
        }
        assertThrows(EdgeTtsException::class.java) {
            EdgeTtsProtocol.parseAudioMessage(audioMessage(ByteArray(0), contentType = "audio/mpeg"))
        }
    }

    @Test
    fun `words and their times are read from a metadata message`() {
        val message = "X-RequestId:abc\r\nContent-Type:application/json; charset=utf-8\r\nPath:audio.metadata\r\n\r\n" +
            """{"Metadata":[{"Type":"WordBoundary","Data":{"Offset":1000000,"Duration":3500000,"text":{"Text":"Tom &amp; Jerry","Length":11,"BoundaryType":"WordBoundary"}}}]}"""

        val parsed = EdgeTtsProtocol.parseTextMessage(message)

        assertEquals(
            EdgeTtsProtocol.TextMessage.Words(listOf(EdgeTtsProtocol.TimedWord("Tom & Jerry", 1000000, 3500000))),
            parsed
        )
    }

    @Test
    fun `other messages are told apart`() {
        assertEquals(
            EdgeTtsProtocol.TextMessage.TurnEnd,
            EdgeTtsProtocol.parseTextMessage("X-RequestId:abc\r\nPath:turn.end\r\n\r\n{}")
        )
        assertEquals(
            EdgeTtsProtocol.TextMessage.Other,
            EdgeTtsProtocol.parseTextMessage("X-RequestId:abc\r\nPath:turn.start\r\n\r\n{}")
        )
        assertEquals(
            EdgeTtsProtocol.TextMessage.Words(emptyList()),
            EdgeTtsProtocol.parseTextMessage("Path:audio.metadata\r\n\r\nnot json")
        )
    }

    // ------------------------------------------------------------ voices

    @Test
    fun `the list of voices is read as the service sends it`() {
        val voices = EdgeTtsProtocol.parseVoices(voicesOfTheService())

        assertEquals(45, voices.size)
        assertEquals(EdgeVoice("zh-CN-XiaoxiaoNeural", "Female", "zh-CN"), voices.first())
        assertTrue(EdgeVoice("ja-JP-KeitaNeural", "Male", "ja-JP") in voices)
        assertTrue(EdgeVoice("zh-CN-liaoning-XiaobeiNeural", "Female", "zh-CN-liaoning") in voices)
    }

    @Test
    fun `a voice goes by its first name`() {
        val nanami = EdgeVoice("ja-JP-NanamiNeural", "Female", "ja-JP")
        val hyunsu = EdgeVoice("ko-KR-HyunsuMultilingualNeural", "Male", "ko-KR")
        val xiaobei = EdgeVoice("zh-CN-liaoning-XiaobeiNeural", "Female", "zh-CN-liaoning")

        assertEquals("Nanami - Female (ja-JP)", nanami.label)
        assertEquals("Hyunsu Multilingual - Male (ko-KR)", hyunsu.label)
        assertEquals("Xiaobei - Female (zh-CN-liaoning)", xiaobei.label)
        assertEquals(listOf("ja", "ko", "zh"), listOf(nanami, hyunsu, xiaobei).map { it.language })
    }

    @Test
    fun `voices that cannot be used are left out of the list`() {
        val voices = EdgeTtsProtocol.parseVoices(
            """[
              {"ShortName": "ja-JP-NanamiNeural", "Gender": "Female", "Locale": "ja-JP"},
              {"ShortName": "ja-JP-Keita'/><break/>", "Gender": "Male", "Locale": "ja-JP"},
              {"ShortName": "", "Gender": "Male", "Locale": "ja-JP"},
              {"Gender": "Male", "Locale": "ja-JP"},
              {"ShortName": "ja-JP-AoiNeural", "Gender": "Female"},
              {"ShortName": "ja-JP-DaichiNeural", "Locale": "ja-JP"},
              "ja-JP-MayuNeural", 42, null
            ]"""
        )

        assertEquals(
            listOf(
                EdgeVoice("ja-JP-NanamiNeural", "Female", "ja-JP"),
                EdgeVoice("ja-JP-DaichiNeural", "Unknown", "ja-JP")
            ),
            voices
        )
    }

    @Test
    fun `an answer that is no list of voices is refused`() {
        listOf("<html>Bad gateway</html>", """{"error": "no"}""", "").forEach { answer ->
            val error = assertThrows(EdgeTtsException::class.java) { EdgeTtsProtocol.parseVoices(answer) }
            assertEquals("Edge TTS sent a list of voices that cannot be read", error.message)
        }
    }

    @Test
    fun `the voices are asked for with the same token as the speech`() {
        val url = EdgeTtsProtocol.voicesUrl(EdgeTtsProtocol.VOICES_URL, 1790553600.0)

        assertEquals(
            "https://speech.platform.bing.com/consumer/speech/synthesize/readaloud/voices/list" +
                "?trustedclienttoken=6A5AA1D4EAFF4E9FB37E23D68491D6F4" +
                "&Sec-MS-GEC=4DF49421E22249E38CB1C5943AAECA7027496EEEB736A8EED2E2C76DA8353BF5" +
                "&Sec-MS-GEC-Version=1-${EdgeTtsProtocol.CHROMIUM_FULL_VERSION}",
            url
        )
    }

    @Test
    fun `both requests say they come from the same browser`() {
        val version = EdgeTtsProtocol.CHROMIUM_MAJOR_VERSION

        // Values of edge-tts 7.2.7 (constants.py, WSS_HEADERS and VOICE_HEADERS)
        assertEquals(
            mapOf(
                "Pragma" to "no-cache",
                "Cache-Control" to "no-cache",
                "Origin" to "chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold",
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko)" +
                    " Chrome/$version.0.0.0 Safari/537.36 Edg/$version.0.0.0",
                "Accept-Language" to "en-US,en;q=0.9"
            ),
            EdgeTtsProtocol.HEADERS
        )
        assertEquals(
            mapOf(
                "Authority" to "speech.platform.bing.com",
                "Sec-CH-UA" to "\" Not;A Brand\";v=\"99\", \"Microsoft Edge\";v=\"$version\", \"Chromium\";v=\"$version\"",
                "Sec-CH-UA-Mobile" to "?0",
                "Accept" to "*/*",
                "Sec-Fetch-Site" to "none",
                "Sec-Fetch-Mode" to "cors",
                "Sec-Fetch-Dest" to "empty",
                "User-Agent" to EdgeTtsProtocol.HEADERS.getValue("User-Agent"),
                "Accept-Language" to "en-US,en;q=0.9"
            ),
            EdgeTtsProtocol.VOICE_HEADERS
        )
    }

    companion object {
        /** A part of the list the service sent on 28 September 2026, as it was sent. */
        fun voicesOfTheService(): String =
            EdgeTtsProtocolTest::class.java.getResource("/edge_voices_sample.json")!!.readText()

        /** A binary message as the service sends it: header length, headers, audio. */
        fun audioMessage(
            audio: ByteArray,
            contentType: String? = "audio/mpeg",
            path: String = "audio"
        ): ByteArray {
            val headers = buildString {
                append("X-RequestId:abc\r\n")
                if (contentType != null) append("Content-Type:$contentType\r\n")
                append("X-StreamId:def\r\n")
                append("Path:$path\r\n")
            }.toByteArray(Charsets.UTF_8)
            return byteArrayOf((headers.size shr 8).toByte(), headers.size.toByte()) + headers + audio
        }
    }
}
