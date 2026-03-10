package com.purrfectbytes.android.services

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class VideoGeneratorService @Inject constructor(private val context: Context) {

    suspend fun generateVideo(text: String, audioFile: File): File? {
        return withContext(Dispatchers.IO) {
            try {
                // 1. Generate text image
                val imageFile = createTextImage(text)
                
                // 2. Prepare output video file
                val outputVideoDir = File(context.cacheDir, "videos").apply { mkdirs() }
                val outputVideoFile = File(outputVideoDir, "output_${UUID.randomUUID()}.mp4")
                
                // 3. Build FFmpeg Command
                // -loop 1: loop the single image frame
                // -i image: input image
                // -i audio: input audio
                // -c:v libx264: encoding format (H.264 is the most widely supported codec on Android hardware)
                // -c:a aac: encode audio to AAC
                // -b:a 192k: audio bitrate
                // -pix_fmt yuv420p: standard 4:2:0 chroma format for Android/web playback
                // -shortest: cut the video exactly when the audio finishes
                val command = "-loop 1 -i \"${imageFile.absolutePath}\" -i \"${audioFile.absolutePath}\" " +
                        "-c:v libx264 -preset veryfast -crf 24 -c:a aac -b:a 192k -pix_fmt yuv420p -shortest \"${outputVideoFile.absolutePath}\""
                
                val session = FFmpegKit.execute(command)
                
                // Cleanup temp image
                if (imageFile.exists()) {
                    imageFile.delete()
                }
                
                if (ReturnCode.isSuccess(session.returnCode)) {
                    outputVideoFile
                } else {
                    val logs = session.allLogsAsString
                    println("FFmpeg Error: $logs")
                    null 
                }
            } catch (e: Exception) {
                e.printStackTrace()
                null
            }
        }
    }

    private fun createTextImage(text: String): File {
        // Reduced to 720p because older Snapdragon/Android devices don't support 1080p MP4 (MPEG4 part 2) decoding natively.
        val width = 720
        val height = 1280
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        
        // Background
        canvas.drawColor(Color.parseColor("#1a1a28"))
        
        // Paint
        val textPaint = TextPaint().apply {
            color = Color.WHITE
            textSize = 56f
            isAntiAlias = true
            textAlign = Paint.Align.LEFT
            typeface = Typeface.DEFAULT_BOLD
        }
        
        // Text Layout (Centered block)
        val textWidth = width - 100 // 50 horizontal padding
        val staticLayout = StaticLayout.Builder.obtain(text, 0, text.length, textPaint, textWidth)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setLineSpacing(0f, 1.2f)
            .setIncludePad(false)
            .build()
            
        val textHeight = staticLayout.height
        val x = 50f // left padding offset
        val y = (height - textHeight) / 2f // vertical center
        
        canvas.save()
        canvas.translate(x, y)
        staticLayout.draw(canvas)
        canvas.restore()
        
        // Save Bitmap to a temp file
        val tempDir = File(context.cacheDir, "temp_images").apply { mkdirs() }
        val imageFile = File(tempDir, "frame_${UUID.randomUUID()}.png")
        FileOutputStream(imageFile).use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        bitmap.recycle()
        
        return imageFile
    }
}
