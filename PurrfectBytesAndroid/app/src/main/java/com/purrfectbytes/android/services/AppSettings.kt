package com.purrfectbytes.android.services

import android.content.Context
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Choices that are remembered between runs of the app. */
@Singleton
class AppSettings @Inject constructor(@ApplicationContext context: Context) {

    companion object {
        val PRIVACY_OPTIONS = listOf("Public", "Unlisted", "Private")

        /** Until the user chooses otherwise, uploads can only be seen by their owner. */
        const val DEFAULT_PRIVACY = "Private"

        private const val KEY_PRIVACY = "upload_privacy"
        private const val KEY_VOICE = "voice_"
        private const val KEY_SECOND_VOICE = "second_voice_"
        private const val KEY_SEQUENCE = "speed_sequence"
        private const val KEY_SOURCE = "text_source"
    }

    private val prefs = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)

    /** The privacy chosen for the last upload. */
    var uploadPrivacy: String
        get() = prefs.getString(KEY_PRIVACY, null)?.takeIf { it in PRIVACY_OPTIONS } ?: DEFAULT_PRIVACY
        set(value) = prefs.edit { putString(KEY_PRIVACY, value) }

    /**
     * The voice chosen for [languageCode], or for the second speaker of a conversation in
     * that language. Null is the voice that goes with the language.
     */
    fun voiceFor(languageCode: String, secondSpeaker: Boolean = false): String? =
        prefs.getString(voiceKey(languageCode, secondSpeaker), null)
            ?.takeIf { EdgeTtsProtocol.isVoiceName(it) }

    fun rememberVoice(languageCode: String, voice: String?, secondSpeaker: Boolean = false) {
        prefs.edit {
            if (voice == null) {
                remove(voiceKey(languageCode, secondSpeaker))
            } else {
                putString(voiceKey(languageCode, secondSpeaker), voice)
            }
        }
    }

    private fun voiceKey(languageCode: String, secondSpeaker: Boolean) =
        (if (secondSpeaker) KEY_SECOND_VOICE else KEY_VOICE) + languageCode

    /** The steps of the speed sequence as they were last set. */
    var speedSequence: List<SequenceStep>
        get() = SpeedSequence.parse(prefs.getString(KEY_SEQUENCE, null)) ?: SpeedSequence.DEFAULT
        set(value) {
            // Steps that cannot be played yet, as while they are being edited, are not kept
            if (SpeedSequence.problemWith(value) == null) {
                prefs.edit { putString(KEY_SEQUENCE, SpeedSequence.format(value)) }
            }
        }

    /** The saved source the texts are taken from, by its id; null when none is chosen. */
    var textSource: String?
        get() = prefs.getString(KEY_SOURCE, null)
        set(value) = prefs.edit {
            if (value == null) remove(KEY_SOURCE) else putString(KEY_SOURCE, value)
        }
}
