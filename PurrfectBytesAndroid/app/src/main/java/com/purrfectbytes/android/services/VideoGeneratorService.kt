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

    suspend fun generateVideo(text: String, audioFile: File): File? {
        return withContext(Dispatchers.IO) {
            try {
                var durationMs = 0L
                try {
                    val retriever = MediaMetadataRetriever()
                    retriever.setDataSource(audioFile.absolutePath)
                    val time = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    durationMs = time?.toLong() ?: (text.length * 100L)
                    retriever.release()
                } catch (e: Exception) {
                    durationMs = text.length * 100L
                }

                val fps = 10
                val totalFrames = ((durationMs / 1000.0) * fps).toInt()
                val (bg, qr, logo) = loadAssets()
                
                val framesDir = File(context.cacheDir, "frames_${UUID.randomUUID()}").apply { mkdirs() }
                
                // Roughly map frames to character indices
                val charsExtracted = text.filter { !it.isWhitespace() }
                val numChars = charsExtracted.length
                
                for (i in 0 until totalFrames) {
                    val progress = i.toFloat() / totalFrames.toFloat()
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
                val outputVideoFile = File(outputVideoDir, "output_${UUID.randomUUID()}.mp4")
                
                // Build FFmpeg command to stitch frames
                val command = "-framerate $fps -i \"${framesDir.absolutePath}/frame_%04d.jpg\" -i \"${audioFile.absolutePath}\" " +
                        "-c:v libx264 -preset ultrafast -crf 24 -c:a aac -b:a 192k -pix_fmt yuv420p -shortest \"${outputVideoFile.absolutePath}\""
                
                val session = FFmpegKit.execute(command)
                
                framesDir.deleteRecursively()
                
                if (ReturnCode.isSuccess(session.returnCode)) {
                    outputVideoFile
                } else {
                    println("FFmpeg Error: ${session.allLogsAsString}")
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
        val words = text.split(Regex("(?<=\\s)|(?=\\s)")).filter { it.isNotEmpty() }
        var currentY = 150f
        var currentX = horizontalPadding.toFloat()
        val lineHeight = 80f

        var charIdx = 0
        var logoX = -1f
        var logoY = -1f

        for (word in words) {
            val wordWidth = textPaint.measureText(word)
            if (currentX + wordWidth > width - horizontalPadding && word.isNotBlank()) {
                currentX = horizontalPadding.toFloat()
                currentY += lineHeight
            }

            for (char in word) {
                val str = char.toString()
                val charW = textPaint.measureText(str)
                
                if (!char.isWhitespace()) {
                    val isActive = (charIdx == highlightIndex)
                    if (isActive) {
                        canvas.drawRect(currentX - 4, currentY - 60, currentX + charW + 4, currentY + 10, boxPaint)
                        canvas.drawText(str, currentX, currentY, highlightPaint)
                        logoX = currentX + charW / 2
                        logoY = currentY - 60
                    } else {
                        canvas.drawText(str, currentX, currentY, textPaint)
                    }
                    charIdx++
                } else {
                    canvas.drawText(str, currentX, currentY, textPaint)
                }
                
                currentX += charW
            }
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
