package com.purrfectbytes.android.services

import android.content.Context
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.http.InputStreamContent
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.youtube.YouTube
import com.google.api.services.youtube.model.Video
import com.google.api.services.youtube.model.VideoSnippet
import com.google.api.services.youtube.model.VideoStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class YouTubeVideoUploader @Inject constructor(private val context: Context) {

    suspend fun uploadVideo(
        videoFile: File,
        title: String,
        description: String,
        credential: GoogleAccountCredential
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val transport = NetHttpTransport()
            val jsonFactory = GsonFactory.getDefaultInstance()

            val youtubeService = YouTube.Builder(transport, jsonFactory, credential)
                .setApplicationName("PurrfectBytes")
                .build()

            val videoObjectDefiningMetadataAndVideo = Video().apply {
                snippet = VideoSnippet().apply {
                    this.title = title
                    this.description = description
                    tags = listOf("shorts", "texttospeech", "purrfectbytes")
                    categoryId = "22" // People & Blogs
                }
                status = VideoStatus().apply {
                    privacyStatus = "private" // default private for testing
                    selfDeclaredMadeForKids = false
                }
            }

            val mediaContent = InputStreamContent(
                "video/*",
                BufferedInputStream(FileInputStream(videoFile))
            )
            mediaContent.length = videoFile.length()

            val videoInsert = youtubeService.videos()
                .insert("snippet,statistics,status", videoObjectDefiningMetadataAndVideo, mediaContent)

            val returnedVideo = videoInsert.execute()
            
            Result.success(returnedVideo.id)
        } catch (e: Exception) {
            e.printStackTrace()
            Result.failure(e)
        }
    }
}
