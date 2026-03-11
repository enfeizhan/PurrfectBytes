package com.purrfectbytes.android.services

import android.content.Context
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.http.InputStreamContent
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.youtube.YouTube
import com.google.api.services.youtube.model.PlaylistItem
import com.google.api.services.youtube.model.PlaylistItemSnippet
import com.google.api.services.youtube.model.ResourceId
import com.google.api.services.youtube.model.Video
import com.google.api.services.youtube.model.VideoSnippet
import com.google.api.services.youtube.model.VideoStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import com.google.api.client.http.HttpRequestInitializer
import javax.inject.Inject
import javax.inject.Singleton
class YouTubeVideoUploader @Inject constructor(private val context: Context) {

    suspend fun uploadVideo(
        videoFile: File,
        title: String,
        description: String,
        playlistId: String? = null,
        accessToken: String
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val transport = NetHttpTransport()
            val jsonFactory = GsonFactory.getDefaultInstance()

            val requestInitializer = HttpRequestInitializer { request ->
                request.headers.authorization = "Bearer $accessToken"
            }

            val youtubeService = YouTube.Builder(transport, jsonFactory, requestInitializer)
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
            
            val videoId = returnedVideo?.id ?: return@withContext Result.failure(Exception("Failed to upload"))

            // Add the video to the specified playlist if a playlist ID was provided
            if (!playlistId.isNullOrEmpty()) {
                try {
                    val playlistItem = PlaylistItem().apply {
                        snippet = PlaylistItemSnippet().apply {
                            this.playlistId = playlistId
                            resourceId = ResourceId().apply {
                                kind = "youtube#video"
                                this.videoId = videoId
                            }
                        }
                    }
                    youtubeService.playlistItems()
                        .insert("snippet", playlistItem)
                        .execute()
                } catch (e: Exception) {
                    // Log but don't fail the whole video upload
                    e.printStackTrace()
                }
            }
            
            Result.success(videoId)
        } catch (e: Exception) {
            e.printStackTrace()
            Result.failure(e)
        }
    }
}
