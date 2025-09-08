package com.purrfectbytes.android.data.repository

import android.content.Context
import com.purrfectbytes.android.data.api.PurrfectBytesApi
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TTSRepository @Inject constructor(
    private val api: PurrfectBytesApi,
    private val context: Context
) {
    
    suspend fun convertTextToSpeech(
        text: String,
        language: String = "en",
        slow: Boolean = false
    ): Result<File> {
        return try {
            val textBody = text.toRequestBody("text/plain".toMediaTypeOrNull())
            val languageBody = language.toRequestBody("text/plain".toMediaTypeOrNull())
            val slowBody = slow.toString().toRequestBody("text/plain".toMediaTypeOrNull())
            
            val response = api.convertTextToSpeech(textBody, languageBody, slowBody)
            
            if (response.isSuccessful && response.body() != null) {
                val ttsResponse = response.body()!!
                
                // Download the audio file
                val downloadResponse = api.downloadAudio(ttsResponse.filename)
                
                if (downloadResponse.isSuccessful && downloadResponse.body() != null) {
                    val file = saveAudioFile(ttsResponse.filename, downloadResponse.body()!!)
                    Result.success(file)
                } else {
                    Result.failure(Exception("Failed to download audio file"))
                }
            } else {
                Result.failure(Exception("Failed to convert text to speech"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    private fun saveAudioFile(filename: String, body: ResponseBody): File {
        val file = File(context.cacheDir, filename)
        file.outputStream().use { outputStream ->
            body.byteStream().use { inputStream ->
                inputStream.copyTo(outputStream)
            }
        }
        return file
    }
}