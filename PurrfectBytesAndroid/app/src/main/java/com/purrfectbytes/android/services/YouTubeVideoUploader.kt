package com.purrfectbytes.android.services

import android.util.Log
import com.google.api.client.http.InputStreamContent
import com.google.api.services.youtube.model.PlaylistItem
import com.google.api.services.youtube.model.PlaylistItemSnippet
import com.google.api.services.youtube.model.ResourceId
import com.google.api.services.youtube.model.Video
import com.google.api.services.youtube.model.VideoSnippet
import com.google.api.services.youtube.model.VideoStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * An uploaded video. [playlistError] says why it could not be added to the chosen
 * playlist; the video itself is on YouTube either way.
 */
data class YouTubeUpload(val videoId: String, val playlistError: String? = null) {
    /** Where the video is watched. */
    val url: String get() = "https://www.youtube.com/watch?v=$videoId"
}

@Singleton
open class YouTubeVideoUploader @Inject constructor() {

    companion object {
        private const val TAG = "YouTubeUpload"
        private const val CATEGORY_EDUCATION = "27"
    }

    /** [tags] are what the video is found by; the web app sends the hashtags of the description. */
    open suspend fun uploadVideo(
        videoFile: File,
        title: String,
        description: String,
        tags: List<String> = emptyList(),
        privacyStatus: String = "private",
        playlistId: String? = null,
        accessToken: String
    ): Result<YouTubeUpload> = withContext(Dispatchers.IO) {
        try {
            val youtubeService = youTubeService(accessToken)

            val videoObjectDefiningMetadataAndVideo = Video().apply {
                snippet = VideoSnippet().apply {
                    this.title = title
                    this.description = description
                    if (tags.isNotEmpty()) this.tags = tags
                    categoryId = CATEGORY_EDUCATION
                }
                status = VideoStatus().apply {
                    this.privacyStatus = privacyStatus.lowercase()
                    selfDeclaredMadeForKids = false
                }
            }

            val returnedVideo = BufferedInputStream(FileInputStream(videoFile)).use { stream ->
                val mediaContent = InputStreamContent("video/*", stream)
                mediaContent.length = videoFile.length()

                youtubeService.videos()
                    .insert("snippet,status", videoObjectDefiningMetadataAndVideo, mediaContent)
                    .execute()
            }

            val videoId = returnedVideo?.id
                ?: return@withContext Result.failure(Exception("YouTube did not confirm the upload"))

            // Add the video to the specified playlist if a playlist ID was provided
            var playlistError: String? = null
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
                    // The video is uploaded; report the playlist problem without failing the upload
                    Log.e(TAG, "Could not add $videoId to playlist $playlistId", e)
                    playlistError = e.message ?: e.javaClass.simpleName
                }
            }

            Result.success(YouTubeUpload(videoId, playlistError))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Upload failed", e)
            Result.failure(e)
        }
    }
}
