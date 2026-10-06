package com.purrfectbytes.android.services

import com.google.api.client.googleapis.json.GoogleJsonResponseException
import com.google.api.client.http.HttpRequestInitializer
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.youtube.YouTube
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

data class YouTubePlaylist(
    val id: String,
    val title: String
)

data class YouTubeChannel(
    val id: String,
    val title: String,
    val thumbnailUrl: String? = null
)

/** A YouTube Data API client that signs every request with [accessToken]. */
internal fun youTubeService(accessToken: String): YouTube {
    val requestInitializer = HttpRequestInitializer { request ->
        request.headers.authorization = "Bearer $accessToken"
        request.connectTimeout = 30_000
        request.readTimeout = 120_000
    }
    return YouTube.Builder(NetHttpTransport(), GsonFactory.getDefaultInstance(), requestInitializer)
        .setApplicationName("PurrfectBytes")
        .build()
}

/** True when YouTube refused the request because the sign-in is no longer accepted. */
internal fun Throwable.isYouTubeSignInProblem(): Boolean =
    this is YouTubeSignInRequiredException ||
        (this is GoogleJsonResponseException && statusCode == 401)

/** What went wrong in YouTube's own words, without the JSON around it. */
internal fun Throwable.youTubeMessage(): String =
    (this as? GoogleJsonResponseException)?.details?.message
        ?: message
        ?: javaClass.simpleName

/** Reads the channels and playlists of the signed-in account. */
@Singleton
open class YouTubeAccountService @Inject constructor() {

    open suspend fun channels(accessToken: String): List<YouTubeChannel> = withContext(Dispatchers.IO) {
        val response = youTubeService(accessToken).channels()
            .list("snippet")
            .setMine(true)
            .execute()

        response.items.orEmpty().map { channel ->
            YouTubeChannel(
                id = channel.id ?: "",
                title = channel.snippet?.title ?: "Unknown Channel",
                thumbnailUrl = channel.snippet?.thumbnails?.default?.url
            )
        }
    }

    open suspend fun playlists(accessToken: String): List<YouTubePlaylist> = withContext(Dispatchers.IO) {
        val service = youTubeService(accessToken)
        val playlists = mutableListOf<YouTubePlaylist>()
        var pageToken: String? = null
        do {
            val response = service.playlists()
                .list("snippet")
                .setMine(true) // Native Android OAuth binds to the main identity, so we use mine=true
                .setMaxResults(50L)
                .setPageToken(pageToken)
                .execute()

            response.items.orEmpty().mapTo(playlists) { playlist ->
                YouTubePlaylist(
                    id = playlist.id ?: "",
                    title = playlist.snippet?.title ?: "Unknown Playlist"
                )
            }
            pageToken = response.nextPageToken
        } while (!pageToken.isNullOrEmpty())
        playlists
    }
}
