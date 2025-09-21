package com.purrfectbytes.android.services

import android.content.Context
import android.graphics.Rect
import android.net.Uri
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.languageid.LanguageIdentificationOptions
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

data class RecognizedTextBlock(
    val text: String,
    val boundingBox: Rect?,
    val lines: List<RecognizedTextLine>,
    val detectedLanguage: String? = null
)

data class RecognizedTextLine(
    val text: String,
    val boundingBox: Rect?,
    val elements: List<RecognizedTextElement>
)

data class RecognizedTextElement(
    val text: String,
    val boundingBox: Rect?
)

enum class RecognitionScript {
    LATIN,
    CHINESE,
    JAPANESE,
    KOREAN,
    DEVANAGARI,
    AUTO
}

@Singleton
class TextRecognitionProcessor @Inject constructor(
    @ApplicationContext private val context: Context
) {
    // Initialize recognizers for different scripts
    private val latinRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val chineseRecognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    private val japaneseRecognizer = TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
    private val koreanRecognizer = TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
    private val devanagariRecognizer = TextRecognition.getClient(DevanagariTextRecognizerOptions.Builder().build())

    private val languageIdentifier = LanguageIdentification.getClient(
        LanguageIdentificationOptions.Builder()
            .setConfidenceThreshold(0.3f)
            .build()
    )

    suspend fun processImageFromUri(
        uri: Uri,
        script: RecognitionScript = RecognitionScript.AUTO
    ): Result<List<RecognizedTextBlock>> {
        return withContext(Dispatchers.IO) {
            try {
                val image = InputImage.fromFilePath(context, uri)

                // Try multiple recognizers if AUTO mode
                val results = if (script == RecognitionScript.AUTO) {
                    tryMultipleRecognizers(image)
                } else {
                    val recognizer = getRecognizerForScript(script)
                    val task = recognizer.process(image)
                    val visionText = Tasks.await(task)
                    extractTextBlocks(visionText)
                }

                // Identify languages in the recognized text
                val resultsWithLanguage = identifyLanguages(results)

                Result.success(resultsWithLanguage)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    private suspend fun tryMultipleRecognizers(image: InputImage): List<RecognizedTextBlock> {
        val allResults = mutableListOf<RecognizedTextBlock>()
        val recognizers = listOf(
            latinRecognizer to "latin",
            chineseRecognizer to "chinese",
            japaneseRecognizer to "japanese",
            koreanRecognizer to "korean",
            devanagariRecognizer to "devanagari"
        )

        // Try all recognizers and collect results
        val recognitionResults = mutableListOf<Pair<List<RecognizedTextBlock>, String>>()

        recognizers.forEach { (recognizer, scriptName) ->
            try {
                val text = Tasks.await(recognizer.process(image))
                if (text.text.isNotBlank()) {
                    val blocks = extractTextBlocks(text)
                    if (blocks.isNotEmpty()) {
                        recognitionResults.add(blocks to scriptName)
                    }
                }
            } catch (e: Exception) {
                // Continue with other recognizers
            }
        }

        // If we have results, pick the one with the most recognized text
        if (recognitionResults.isNotEmpty()) {
            // Sort by total text length to find the best match
            val bestResult = recognitionResults.maxByOrNull { (blocks, _) ->
                blocks.sumOf { it.text.length }
            }

            bestResult?.let { (blocks, scriptName) ->
                // Add script information to blocks
                allResults.addAll(blocks.map { block ->
                    block.copy(detectedLanguage = scriptName)
                })
            }
        }

        return allResults
    }

    private fun getRecognizerForScript(script: RecognitionScript): TextRecognizer {
        return when (script) {
            RecognitionScript.CHINESE -> chineseRecognizer
            RecognitionScript.JAPANESE -> japaneseRecognizer
            RecognitionScript.KOREAN -> koreanRecognizer
            RecognitionScript.DEVANAGARI -> devanagariRecognizer
            else -> latinRecognizer
        }
    }

    private suspend fun identifyLanguages(blocks: List<RecognizedTextBlock>): List<RecognizedTextBlock> {
        return blocks.map { block ->
            try {
                val languageCode = Tasks.await(languageIdentifier.identifyLanguage(block.text))
                block.copy(detectedLanguage = if (languageCode != "und") languageCode else null)
            } catch (e: Exception) {
                block
            }
        }
    }

    private fun extractTextBlocks(visionText: Text): List<RecognizedTextBlock> {
        return visionText.textBlocks.map { block ->
            RecognizedTextBlock(
                text = block.text,
                boundingBox = block.boundingBox,
                lines = block.lines.map { line ->
                    RecognizedTextLine(
                        text = line.text,
                        boundingBox = line.boundingBox,
                        elements = line.elements.map { element ->
                            RecognizedTextElement(
                                text = element.text,
                                boundingBox = element.boundingBox
                            )
                        }
                    )
                }
            )
        }
    }

    fun getAllText(blocks: List<RecognizedTextBlock>): String {
        return blocks.joinToString("\n") { it.text }
    }

    fun close() {
        latinRecognizer.close()
        chineseRecognizer.close()
        japaneseRecognizer.close()
        koreanRecognizer.close()
        devanagariRecognizer.close()
        languageIdentifier.close()
    }
}