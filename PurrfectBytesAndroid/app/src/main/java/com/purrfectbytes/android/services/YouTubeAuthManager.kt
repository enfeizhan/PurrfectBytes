package com.purrfectbytes.android.services

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.content.edit
import net.openid.appauth.AuthState
import net.openid.appauth.AuthorizationException
import net.openid.appauth.AuthorizationRequest
import net.openid.appauth.AuthorizationResponse
import net.openid.appauth.AuthorizationService
import net.openid.appauth.AuthorizationServiceConfiguration
import net.openid.appauth.ResponseTypeValues
import org.json.JSONException
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

@Singleton
class YouTubeAuthManager @Inject constructor(@ApplicationContext private val context: Context) {
    private val authService = AuthorizationService(context)
    private val prefs = context.getSharedPreferences("youtube_auth_prefs", Context.MODE_PRIVATE)

    companion object {
        private const val CLIENT_ID = "667110250632-d9rq2oroo43aagg5g48f0mlga49rr0hv.apps.googleusercontent.com"
        private val REDIRECT_URI = Uri.parse("com.googleusercontent.apps.667110250632-d9rq2oroo43aagg5g48f0mlga49rr0hv:/oauth2redirect")
        private const val AUTH_STATE_KEY = "auth_state"
    }

    private val serviceConfiguration = AuthorizationServiceConfiguration(
        Uri.parse("https://accounts.google.com/o/oauth2/v2/auth"),
        Uri.parse("https://oauth2.googleapis.com/token")
    )

    fun getAuthIntent(): Intent {
        val authRequestBuilder = AuthorizationRequest.Builder(
            serviceConfiguration,
            CLIENT_ID,
            ResponseTypeValues.CODE,
            REDIRECT_URI
        ).setScope("https://www.googleapis.com/auth/youtube.readonly https://www.googleapis.com/auth/youtube.upload")
         .setPrompt("consent select_account") // Forces the user to pick channel/brand account
         .setAdditionalParameters(mapOf("access_type" to "offline"))

        return authService.getAuthorizationRequestIntent(authRequestBuilder.build())
    }

    suspend fun handleAuthResult(intent: Intent): Boolean = suspendCoroutine { cont ->
        val response = AuthorizationResponse.fromIntent(intent)
        val error = AuthorizationException.fromIntent(intent)

        if (response != null) {
            authService.performTokenRequest(response.createTokenExchangeRequest()) { tr, ex ->
                val authState = AuthState(response, error)
                authState.update(tr, ex)
                saveAuthState(authState)
                cont.resume(tr != null)
            }
        } else {
            cont.resume(false)
        }
    }

    fun isAuthorized(): Boolean {
        return getAuthState()?.isAuthorized ?: false
    }

    fun logout() {
        prefs.edit { remove(AUTH_STATE_KEY) }
    }

    suspend fun getFreshAccessToken(): String = suspendCoroutine { cont ->
        val authState = getAuthState()
        if (authState == null) {
            cont.resumeWithException(Exception("Not authenticated"))
            return@suspendCoroutine
        }

        authState.performActionWithFreshTokens(authService) { accessToken, _, ex ->
            if (ex != null) {
                // If the refresh token is expired or revoked
                if (ex.type == AuthorizationException.TYPE_OAUTH_TOKEN_ERROR) {
                    logout()
                }
                cont.resumeWithException(ex)
            } else if (accessToken != null) {
                saveAuthState(authState) // Save updated tokens if refreshed
                cont.resume(accessToken)
            } else {
                cont.resumeWithException(Exception("Unknown error getting fresh token"))
            }
        }
    }

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
