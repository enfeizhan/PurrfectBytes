package com.purrfectbytes.android.services

import android.content.Context
import android.graphics.Rect
import android.net.Uri
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

data class RecognizedTextBlock(
    val text: String,
    val boundingBox: Rect?,
    val lines: List<RecognizedTextLine>,
    /** The recognizer that read the block. */
    val script: RecognitionScript? = null,
    /** The language of the text as a code such as "ja", when it could be told. */
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

enum class RecognitionScript(val displayName: String) {
    LATIN("Latin"),
    CHINESE("Chinese"),
    JAPANESE("Japanese"),
    KOREAN("Korean"),
    DEVANAGARI("Devanagari"),
    AUTO("Auto")
}

/**
 * The text found in a photo. The boxes of the blocks are positions in the upright
 * photo, which is [imageWidth] by [imageHeight] pixels.
 */
data class RecognizedText(
    val blocks: List<RecognizedTextBlock>,
    val imageWidth: Int,
    val imageHeight: Int,
    val script: RecognitionScript
)

@Singleton
class TextRecognitionProcessor @Inject constructor(
    @ApplicationContext private val context: Context,
    private val languageDetector: LanguageDetector
) {
    companion object {
        private const val TAG = "TextRecognition"
        private val SCRIPTS = RecognitionScript.values().filter { it != RecognitionScript.AUTO }
    }

    // Created when first used, and again after close()
    private val recognizers = mutableMapOf<RecognitionScript, TextRecognizer>()

    @Synchronized
    private fun recognizerFor(script: RecognitionScript): TextRecognizer =
        recognizers.getOrPut(script) {
            TextRecognition.getClient(
                when (script) {
                    RecognitionScript.CHINESE -> ChineseTextRecognizerOptions.Builder().build()
                    RecognitionScript.JAPANESE -> JapaneseTextRecognizerOptions.Builder().build()
                    RecognitionScript.KOREAN -> KoreanTextRecognizerOptions.Builder().build()
                    RecognitionScript.DEVANAGARI -> DevanagariTextRecognizerOptions.Builder().build()
                    else -> TextRecognizerOptions.DEFAULT_OPTIONS
                }
            )
        }

    suspend fun processImageFromUri(
        uri: Uri,
        script: RecognitionScript = RecognitionScript.AUTO
    ): Result<RecognizedText> {
        return withContext(Dispatchers.IO) {
            try {
                // ML Kit turns the photo upright before reading it, so the boxes it
                // reports belong to the upright photo
                val image = InputImage.fromFilePath(context, uri)

                val (blocks, usedScript) = if (script == RecognitionScript.AUTO) {
                    tryAllRecognizers(image)
                } else {
                    recognize(image, script) to script
                }

                Result.success(
                    RecognizedText(
                        blocks = identifyLanguages(blocks),
                        imageWidth = image.width,
                        imageHeight = image.height,
                        script = usedScript
                    )
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Text recognition failed", e)
                Result.failure(e)
            }
        }
    }

    private fun recognize(image: InputImage, script: RecognitionScript): List<RecognizedTextBlock> =
        extractTextBlocks(Tasks.await(recognizerFor(script).process(image)), script)

    /** Runs every recognizer at the same time and keeps the one that read the most text. */
    private suspend fun tryAllRecognizers(image: InputImage): Pair<List<RecognizedTextBlock>, RecognitionScript> =
        coroutineScope {
            val results = SCRIPTS.map { script ->
                async {
                    try {
                        script to recognize(image, script)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w(TAG, "The ${script.displayName} recognizer failed", e)
                        script to emptyList()
                    }
                }
            }.awaitAll()

            val best = results.maxByOrNull { (_, blocks) -> blocks.sumOf { it.text.length } }
            if (best == null || best.second.isEmpty()) {
                emptyList<RecognizedTextBlock>() to RecognitionScript.AUTO
            } else {
                best.second to best.first
            }
        }

    private suspend fun identifyLanguages(blocks: List<RecognizedTextBlock>): List<RecognizedTextBlock> {
        return blocks.map { block ->
            try {
                block.copy(detectedLanguage = languageDetector.detect(block.text))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                block
            }
        }
    }

    private fun extractTextBlocks(visionText: Text, script: RecognitionScript): List<RecognizedTextBlock> {
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
                },
                script = script
            )
        }
    }

    /** Frees the models. They are loaded again when next needed. */
    @Synchronized
    fun close() {
        recognizers.values.forEach { it.close() }
        recognizers.clear()
    }
}
