package com.purrfectbytes.android.services

import com.google.android.gms.tasks.Tasks
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.languageid.LanguageIdentificationOptions
import com.google.mlkit.nl.languageid.LanguageIdentifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** Works out which language a text is written in, on the phone. */
@Singleton
open class LanguageDetector @Inject constructor() {

    companion object {
        /** What ML Kit answers when it cannot tell. */
        const val UNDETERMINED = "und"
    }

    private var identifier: LanguageIdentifier? = null

    @Synchronized
    private fun identifier(): LanguageIdentifier =
        identifier ?: LanguageIdentification.getClient(
            LanguageIdentificationOptions.Builder()
                .setConfidenceThreshold(0.3f)
                .build()
        ).also { identifier = it }

    /** The language of [text] as a code such as "ja" or "zh-Latn", or null when unclear. */
    open suspend fun detect(text: String): String? = withContext(Dispatchers.IO) {
        Tasks.await(identifier().identifyLanguage(text)).takeIf { it != UNDETERMINED }
    }

    /** Frees the model. It is loaded again when next needed. */
    @Synchronized
    open fun close() {
        identifier?.close()
        identifier = null
    }
}
