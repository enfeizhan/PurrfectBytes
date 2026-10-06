package com.purrfectbytes.android.services

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.edit
import androidx.core.net.toUri
import net.openid.appauth.AuthState
import net.openid.appauth.AuthorizationException
import net.openid.appauth.AuthorizationRequest
import net.openid.appauth.AuthorizationResponse
import net.openid.appauth.AuthorizationService
import net.openid.appauth.AuthorizationServiceConfiguration
import net.openid.appauth.ResponseTypeValues
import org.json.JSONException
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.suspendCancellableCoroutine
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** The saved sign-in can no longer be used; the user has to connect to YouTube again. */
class YouTubeSignInRequiredException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Open, like the other two that speak to YouTube, so that tests can put a stand-in in its place. */
@Singleton
open class YouTubeAuthManager @Inject constructor(@ApplicationContext private val context: Context) {
    private val authService = AuthorizationService(context)

    // Holds the refresh token. The file is private to the app and left out of backups
    // (res/xml/backup_rules.xml and data_extraction_rules.xml).
    private val prefs = context.getSharedPreferences("youtube_auth_prefs", Context.MODE_PRIVATE)

    companion object {
        private const val TAG = "YouTubeAuth"

        // Google only allows an app-owned redirect address like the one below for clients
        // of type "iOS", so this is an iOS client ID used from Android. It has no secret.
        private const val CLIENT_ID = "667110250632-d9rq2oroo43aagg5g48f0mlga49rr0hv.apps.googleusercontent.com"
        private val REDIRECT_URI = "com.googleusercontent.apps.667110250632-d9rq2oroo43aagg5g48f0mlga49rr0hv:/oauth2redirect".toUri()
        private const val AUTH_STATE_KEY = "auth_state"
    }

    private val serviceConfiguration = AuthorizationServiceConfiguration(
        "https://accounts.google.com/o/oauth2/v2/auth".toUri(),
        "https://oauth2.googleapis.com/token".toUri()
    )

    fun getAuthIntent(): Intent {
        val authRequestBuilder = AuthorizationRequest.Builder(
            serviceConfiguration,
            CLIENT_ID,
            ResponseTypeValues.CODE,
            REDIRECT_URI
        ).setScope("https://www.googleapis.com/auth/youtube")
         .setPrompt("consent select_account") // Forces the user to pick channel/brand account
         .setAdditionalParameters(mapOf("access_type" to "offline"))

        return authService.getAuthorizationRequestIntent(authRequestBuilder.build())
    }

    /** Finishes the sign-in with what the browser sent back. Fails with a message fit to show. */
    suspend fun handleAuthResult(intent: Intent): Result<Unit> = suspendCancellableCoroutine { cont ->
        val response = AuthorizationResponse.fromIntent(intent)
        val error = AuthorizationException.fromIntent(intent)

        if (response == null) {
            val message = when {
                error == null -> "YouTube did not answer the sign-in"
                error.code == AuthorizationException.GeneralErrors.USER_CANCELED_AUTH_FLOW.code ->
                    "The YouTube sign-in was cancelled"
                else -> "YouTube sign-in failed: ${describe(error)}"
            }
            cont.resume(Result.failure(Exception(message, error)))
            return@suspendCancellableCoroutine
        }

        authService.performTokenRequest(response.createTokenExchangeRequest()) { tokens, tokenError ->
            val result = if (tokens != null) {
                val authState = AuthState(response, error)
                authState.update(tokens, tokenError)
                saveAuthState(authState)
                Result.success(Unit)
            } else {
                Result.failure(Exception("YouTube sign-in failed: ${describe(tokenError)}", tokenError))
            }
            if (cont.isActive) cont.resume(result)
        }
    }

    open fun isAuthorized(): Boolean {
        return getAuthState()?.isAuthorized ?: false
    }

    /** Forgets the sign-in on this phone. */
    open fun logout() {
        prefs.edit { remove(AUTH_STATE_KEY) }
    }

    /**
     * A token that is valid right now, refreshed first when needed.
     * Throws [YouTubeSignInRequiredException] when the user has to connect again.
     */
    open suspend fun getFreshAccessToken(): String = suspendCancellableCoroutine { cont ->
        val authState = getAuthState()
        if (authState == null) {
            cont.resumeWithException(YouTubeSignInRequiredException("Not connected to YouTube"))
            return@suspendCancellableCoroutine
        }

        authState.performActionWithFreshTokens(authService) { accessToken, _, ex ->
            if (!cont.isActive) return@performActionWithFreshTokens
            if (ex != null) {
                Log.w(TAG, "Could not get a fresh token", ex)
                if (ex.type == AuthorizationException.TYPE_OAUTH_TOKEN_ERROR) {
                    // The refresh token is expired or revoked
                    logout()
                    cont.resumeWithException(
                        YouTubeSignInRequiredException("The YouTube sign-in has expired. Please connect again.", ex)
                    )
                } else {
                    cont.resumeWithException(
                        Exception("Could not reach YouTube to renew the sign-in: ${describe(ex)}", ex)
                    )
                }
            } else if (accessToken != null) {
                saveAuthState(authState) // Save updated tokens if refreshed
                cont.resume(accessToken)
            } else {
                cont.resumeWithException(Exception("Unknown error getting fresh token"))
            }
        }
    }

    private fun describe(error: AuthorizationException?): String =
        error?.errorDescription ?: error?.error ?: error?.message ?: "unknown error"

    private fun getAuthState(): AuthState? {
        val json = prefs.getString(AUTH_STATE_KEY, null) ?: return null
        return try {
            AuthState.jsonDeserialize(json)
        } catch (e: JSONException) {
            null
        }
    }

    private fun saveAuthState(authState: AuthState) {
        prefs.edit { putString(AUTH_STATE_KEY, authState.jsonSerializeString()) }
    }
}
