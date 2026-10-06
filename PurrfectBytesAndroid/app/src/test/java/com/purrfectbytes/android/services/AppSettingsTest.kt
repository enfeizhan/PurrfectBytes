package com.purrfectbytes.android.services

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppSettingsTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    /** The settings as they are after the app was started again. */
    private fun settings() = AppSettings(context)

    private fun steps(vararg steps: Pair<Int, Boolean>) = steps.map { SequenceStep(it.first, it.second) }

    @Test
    fun `a voice is remembered for its language and its speaker`() {
        settings().rememberVoice("ja", "ja-JP-KeitaNeural")
        settings().rememberVoice("ja", "ja-JP-NanamiNeural", secondSpeaker = true)
        settings().rememberVoice("ko", "ko-KR-InJoonNeural")

        assertEquals("ja-JP-KeitaNeural", settings().voiceFor("ja"))
        assertEquals("ja-JP-NanamiNeural", settings().voiceFor("ja", secondSpeaker = true))
        assertEquals("ko-KR-InJoonNeural", settings().voiceFor("ko"))
        assertNull(settings().voiceFor("ko", secondSpeaker = true))
        assertNull(settings().voiceFor("en"))
    }

    @Test
    fun `the voice of the language can be chosen again`() {
        settings().rememberVoice("ja", "ja-JP-KeitaNeural")

        settings().rememberVoice("ja", null)

        assertNull(settings().voiceFor("ja"))
    }

    @Test
    fun `a name that is no voice is not given out`() {
        settings().rememberVoice("ja", "x'><break time='9s'/>")

        assertNull(settings().voiceFor("ja"))
    }

    @Test
    fun `a sequence is three normal, four slow, three normal until it is changed`() {
        assertEquals(SpeedSequence.DEFAULT, settings().speedSequence)

        settings().speedSequence = steps(2 to true, 5 to false)

        assertEquals(steps(2 to true, 5 to false), settings().speedSequence)
    }

    @Test
    fun `a sequence that cannot be played is not remembered`() {
        settings().speedSequence = steps(2 to true)

        settings().speedSequence = emptyList()
        settings().speedSequence = steps(60 to false, 60 to true)
        settings().speedSequence = steps(0 to false)

        assertEquals(steps(2 to true), settings().speedSequence)
    }

    @Test
    fun `the source of the texts is remembered until another or none is chosen`() {
        assertNull(settings().textSource)

        settings().textSource = "3f9a1c2e"
        assertEquals("3f9a1c2e", settings().textSource)

        settings().textSource = null
        assertNull(settings().textSource)
    }

    @Test
    fun `uploads are private until another choice is made`() {
        assertEquals("Private", settings().uploadPrivacy)

        settings().uploadPrivacy = "Unlisted"

        assertEquals("Unlisted", settings().uploadPrivacy)
    }
}
