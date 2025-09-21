package com.purrfectbytes.android.services

import android.content.Context
import android.graphics.Rect
import android.net.Uri
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TextRecognitionParallel @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val latinRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val chineseRecognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    private val japaneseRecognizer = TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
    private val koreanRecognizer = TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())

    suspend fun processImageParallel(uri: Uri): Result<List<RecognizedTextBlock>> {
        return withContext(Dispatchers.IO) {
            try {
                val image = InputImage.fromFilePath(context, uri)

                // Run all recognizers in parallel
                val jobs = listOf(
                    async { runRecognizer(image, latinRecognizer, "Latin/English") },
                    async { runRecognizer(image, japaneseRecognizer, "Japanese") },
                    async { runRecognizer(image, chineseRecognizer, "Chinese") },
                    async { runRecognizer(image, koreanRecognizer, "Korean") }
                )

                // Collect all results
                val allResults = jobs.awaitAll().flatten()

                // Merge overlapping boxes and remove duplicates
                val mergedResults = mergeOverlappingResults(allResults)

                Result.success(mergedResults)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    private suspend fun runRecognizer(
        image: InputImage,
        recognizer: com.google.mlkit.vision.text.TextRecognizer,
        language: String
    ): List<RecognizedTextBlock> {
        return try {
            val result = Tasks.await(recognizer.process(image))
            extractTextBlocks(result, language)
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun extractTextBlocks(visionText: Text, language: String): List<RecognizedTextBlock> {
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
                detectedLanguage = language
            )
        }
    }

    private fun mergeOverlappingResults(results: List<RecognizedTextBlock>): List<RecognizedTextBlock> {
        if (results.isEmpty()) return emptyList()

        val merged = mutableListOf<RecognizedTextBlock>()
        val used = mutableSetOf<Int>()

        results.forEachIndexed { i, block1 ->
            if (i in used) return@forEachIndexed

            var bestMatch = block1
            var bestScore = block1.text.length

            // Find overlapping blocks
            results.forEachIndexed { j, block2 ->
                if (i != j && j !in used &&
                    block1.boundingBox != null && block2.boundingBox != null &&
                    isOverlapping(block1.boundingBox, block2.boundingBox)) {

                    // Keep the one with more text or better quality
                    if (block2.text.length > bestScore) {
                        bestMatch = block2
                        bestScore = block2.text.length
                        used.add(i)
                        used.add(j)
                    } else {
                        used.add(j)
                    }
                }
            }

            if (i !in used) {
                merged.add(bestMatch)
                used.add(i)
            }
        }

        return merged
    }

    private fun isOverlapping(rect1: Rect, rect2: Rect): Boolean {
        // Calculate intersection
        val intersects = rect1.left < rect2.right &&
                        rect2.left < rect1.right &&
                        rect1.top < rect2.bottom &&
                        rect2.top < rect1.bottom

        if (!intersects) return false

        // Calculate overlap percentage
        val intersectionArea = calculateIntersectionArea(rect1, rect2)
        val minArea = minOf(rect1.width() * rect1.height(), rect2.width() * rect2.height())

        // Consider overlapping if more than 50% overlap
        return intersectionArea > minArea * 0.5
    }

    private fun calculateIntersectionArea(rect1: Rect, rect2: Rect): Int {
        val left = maxOf(rect1.left, rect2.left)
        val top = maxOf(rect1.top, rect2.top)
        val right = minOf(rect1.right, rect2.right)
        val bottom = minOf(rect1.bottom, rect2.bottom)

        return if (left < right && top < bottom) {
            (right - left) * (bottom - top)
        } else {
            0
        }
    }

    fun close() {
        latinRecognizer.close()
        chineseRecognizer.close()
        japaneseRecognizer.close()
        koreanRecognizer.close()
    }
}