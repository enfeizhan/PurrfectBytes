package com.purrfectbytes.android.services

import android.content.Context
import android.graphics.*
import android.media.MediaMetadataRetriever
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import com.purrfectbytes.android.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class VideoGeneratorService @Inject constructor(private val context: Context) {

    private fun loadAssets(): Triple<Bitmap?, Bitmap?, Bitmap?> {
        val options = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
        val background = try { BitmapFactory.decodeResource(context.resources, R.drawable.background, options) } catch (e: Exception) { null }
        val qrCode = try { BitmapFactory.decodeResource(context.resources, R.drawable.paypal_qr, options) } catch (e: Exception) { null }
        val logo = try { BitmapFactory.decodeResource(context.resources, R.drawable.logo_small, options) } catch (e: Exception) { null }
        return Triple(background, qrCode, logo)
    }

    suspend fun getPreviewImage(text: String): File? {
        return withContext(Dispatchers.IO) {
            try {
                val (bg, qr, logo) = loadAssets()
                val bitmap = renderFrame(text, bg, qr, logo, highlightIndex = 0)
                
                val tempDir = File(context.cacheDir, "preview_images").apply { mkdirs() }
                val imageFile = File(tempDir, "preview_${UUID.randomUUID()}.png")
                FileOutputStream(imageFile).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                }
                bitmap.recycle()
                bg?.recycle()
                qr?.recycle()
                logo?.recycle()
                
                imageFile
            } catch (e: Exception) {
                e.printStackTrace()
                null
            }
        }
    }

    suspend fun generateVideo(text: String, audioFile: File, repetitions: Int): File? {
        return withContext(Dispatchers.IO) {
            try {
                // Get duration of the 1-rep audio file
                var singleRepDurationMs = 0L
                try {
                    val retriever = MediaMetadataRetriever()
                    retriever.setDataSource(audioFile.absolutePath)
                    val time = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    singleRepDurationMs = time?.toLong() ?: (text.length * 100L)
                    retriever.release()
                } catch (e: Exception) {
                    singleRepDurationMs = text.length * 100L
                }

                val fps = 10
                // Generate frames for ONE repetition only
                val singleRepFrames = ((singleRepDurationMs / 1000.0) * fps).toInt().coerceAtLeast(1)
                
                val (bg, qr, logo) = loadAssets()
                val framesDir = File(context.cacheDir, "frames_${UUID.randomUUID()}").apply { mkdirs() }
                
                // Count characters - use simple non-whitespace count for highlight
                val numChars = text.count { !it.isWhitespace() }
                
                for (i in 0 until singleRepFrames) {
                    val progress = i.toFloat() / singleRepFrames.toFloat()
                    var highlightIndex = (progress * numChars).toInt()
                    if (highlightIndex >= numChars) highlightIndex = numChars - 1
                    if (numChars == 0) highlightIndex = -1

                    val bitmap = renderFrame(text, bg, qr, logo, highlightIndex)
                    val frameFile = File(framesDir, String.format("frame_%04d.jpg", i))
                    FileOutputStream(frameFile).use { out ->
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
                    }
                    bitmap.recycle()
                }
                
                bg?.recycle()
                qr?.recycle()
                logo?.recycle()
                
                val outputVideoDir = File(context.cacheDir, "videos").apply { mkdirs() }
                val singleRepVideo = File(outputVideoDir, "single_rep_${UUID.randomUUID()}.mp4")
                val outputVideoFile = File(outputVideoDir, "output_${UUID.randomUUID()}.mp4")
                
                // Step 1: Create single-repetition video from frames AND audio
                val step1Command = "-framerate $fps -i \"${framesDir.absolutePath}/frame_%04d.jpg\" " +
                    "-i \"${audioFile.absolutePath}\" " +
                    "-c:v libx264 -preset ultrafast -crf 24 -c:a aac -b:a 192k -pix_fmt yuv420p " +
                    "-shortest \"${singleRepVideo.absolutePath}\""
                
                val step1Session = FFmpegKit.execute(step1Command)
                framesDir.deleteRecursively()
                
                if (!ReturnCode.isSuccess(step1Session.returnCode)) {
                    println("FFmpeg Step1 Error: ${step1Session.allLogsAsString}")
                    return@withContext null
                }
                
                // Step 2: Concat the 1-rep video N times
                val concatListFile = File(outputVideoDir, "concat_txt_${UUID.randomUUID()}.txt")
                concatListFile.bufferedWriter().use { writer ->
                    repeat(maxOf(1, repetitions)) {
                        writer.write("file '${singleRepVideo.absolutePath}'\n")
                    }
                }
                
                val step2Command = "-f concat -safe 0 -i \"${concatListFile.absolutePath}\" -c copy \"${outputVideoFile.absolutePath}\""
                val step2Session = FFmpegKit.execute(step2Command)
                
                singleRepVideo.delete()
                concatListFile.delete()
                
                if (ReturnCode.isSuccess(step2Session.returnCode)) {
                    outputVideoFile
                } else {
                    println("FFmpeg Step2 Error: ${step2Session.allLogsAsString}")
                    null
                }
            } catch (e: Exception) {
                e.printStackTrace()
                null
            }
        }
    }

    private fun renderFrame(text: String, bg: Bitmap?, qr: Bitmap?, logo: Bitmap?, highlightIndex: Int): Bitmap {
        val width = 1280
        val height = 720
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        
        if (bg != null) {
            val src = Rect(0, 0, bg.width, bg.height)
            val dst = Rect(0, 0, width, height)
            canvas.drawBitmap(bg, src, dst, null)
        } else {
            canvas.drawColor(Color.parseColor("#1e1e28"))
        }

        val textPaint = TextPaint().apply {
            color = Color.parseColor("#50321e") // brown unhighlighted
            textSize = 64f
            isAntiAlias = true
            typeface = Typeface.DEFAULT_BOLD
        }
        val highlightPaint = TextPaint(textPaint).apply { color = Color.WHITE }
        val boxPaint = Paint().apply { color = Color.parseColor("#dc3232") } // red box

        val horizontalPadding = 120
        val textWidth = width - horizontalPadding * 2

        // Simple layout logic: word wrap and draw character by character for highlight
        val words = text.trim().split(Regex("\\s+"))
        val lineHeight = 80f
        
        // 1. First Pass: Word-wrap into lines
        val lineWords = mutableListOf<MutableList<String>>()
        var currentLine = mutableListOf<String>()
        var currentLineWidth = 0f
        
        for (word in words) {
            val wordWidth = textPaint.measureText(word)
            val spaceWidth = textPaint.measureText(" ")
            val neededWidth = if (currentLine.isEmpty()) wordWidth else spaceWidth + wordWidth
            if (currentLineWidth + neededWidth > width - horizontalPadding * 2 && currentLine.isNotEmpty()) {
                lineWords.add(currentLine)
                currentLine = mutableListOf(word)
                currentLineWidth = wordWidth
            } else {
                currentLine.add(word)
                currentLineWidth += neededWidth
            }
        }
        if (currentLine.isNotEmpty()) {
            lineWords.add(currentLine)
        }

        val totalHeight = lineWords.size * lineHeight
        var currentY = (height - totalHeight) / 2f + 60f

        var charIdx = 0 // Counts only non-whitespace characters
        var logoX = -1f
        var logoY = -1f

        // 2. Second Pass: Draw horizontally centered lines
        for (line in lineWords) {
            // Measure precise width of this exact line
            val fullLineText = line.joinToString(" ")
            val exactLineWidth = textPaint.measureText(fullLineText)
            
            // X position starting strictly centered
            var currentX = (width - exactLineWidth) / 2f

            for (i in line.indices) {
                val word = line[i]
                
                for (char in word) {
                    val str = char.toString()
                    val charW = textPaint.measureText(str)
                    
                    val isActive = (charIdx == highlightIndex)
                    if (isActive) {
                        canvas.drawRect(currentX - 4, currentY - 60, currentX + charW + 4, currentY + 10, boxPaint)
                        canvas.drawText(str, currentX, currentY, highlightPaint)
                        logoX = currentX + charW / 2
                        logoY = currentY - 60
                    } else {
                        canvas.drawText(str, currentX, currentY, textPaint)
                    }
                    charIdx++ // Only non-whitespace chars (words don't contain whitespace)
                    
                    currentX += charW
                }
                
                // Draw space between words
                if (i < line.size - 1) {
                    val spaceW = textPaint.measureText(" ")
                    currentX += spaceW
                }
            }
            currentY += lineHeight
        }

        if (logo != null && logoX != -1f && logoY != -1f) {
            val resizedLogo = Bitmap.createScaledBitmap(logo, 80, 80, true)
            canvas.drawBitmap(resizedLogo, logoX - 40, logoY - 90, null)
            resizedLogo.recycle()
        }

        if (qr != null) {
            val qrSize = 120
            val qrMargin = 20
            val resizedQr = Bitmap.createScaledBitmap(qr, qrSize, qrSize, true)
            val p = Paint().apply { alpha = (255 * 0.9).toInt() }
            canvas.drawBitmap(resizedQr, qrMargin.toFloat(), (height - qrSize - qrMargin).toFloat(), p)
            resizedQr.recycle()
        }

        return bitmap
    }
}
