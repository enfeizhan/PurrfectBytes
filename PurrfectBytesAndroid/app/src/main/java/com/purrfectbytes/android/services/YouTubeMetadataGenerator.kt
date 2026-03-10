package com.purrfectbytes.android.services

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.delay

@Singleton
class YouTubeMetadataGenerator @Inject constructor() {

    // Dummy version until Gemini is restored
    suspend fun generateMetadata(text: String): Pair<String, String> {
        delay(1000)
        return Pair("Generated Title", "Generated Description from: $text")
    }
}
