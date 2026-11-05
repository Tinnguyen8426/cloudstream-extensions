package com.anime47.enricher

import android.content.Context
import com.anime47.enricher.models.LoginRequest
import com.anime47.enricher.models.LoginResponse
import com.anime47.enricher.models.RefreshTokenRequest
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.nicehttp.NiceResponse
import java.util.concurrent.atomic.AtomicBoolean


/**
 * Authentication manager for Anime47 (backend API at https://animevui.com/api).
 * Handles login, session token caching, and proactive JWT token refresh.
 */
object Anime47Auth {

    const val API_BASE_URL = "https://animevui.com/api"
    const val SITE_ORIGIN = "https://anime47.best"
    const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    const val DEFAULT_EMAIL = "thankhoaibe12@gmail.com"
    const val DEFAULT_PASSWORD = "Matkhau@123"
    private const val PREFS_NAME = "anime47_enricher_prefs"
    private const val KEY_EMAIL = "anime47_email"
    private const val KEY_PASSWORD = "anime47_password"

    // Default account credentials from plan.md (can be updated via Settings)
    var email: String = DEFAULT_EMAIL
    var password: String = DEFAULT_PASSWORD

    private var accessToken: String? = null
    private var refreshToken: String? = null
    private var tokenExpiresAtMs: Long = 0L

    private val isAuthenticating = AtomicBoolean(false)

    /**
     * Standard headers required by the Anime47 / AnimeVui API to pass CORS and routing.
     */
    fun defaultHeaders(token: String? = null): Map<String, String> {
        val headers = mutableMapOf(
            "Accept" to "application/json",
            "Origin" to SITE_ORIGIN,
            "Referer" to "$SITE_ORIGIN/",
            "User-Agent" to USER_AGENT
        )
        if (!token.isNullOrBlank()) {
            headers["Authorization"] = "Bearer $token"
        }
        return headers
    }

    /**
     * Retrieves a valid Bearer access token.
     * Automatically handles token refresh or fresh login if needed.
     */
    suspend fun getValidToken(forceRefresh: Boolean = false): String? {
        val now = System.currentTimeMillis()
        val bufferMs = 60_000L // 1 minute safety buffer

        if (!forceRefresh && accessToken != null && now < (tokenExpiresAtMs - bufferMs)) {
            return accessToken
        }

        // Token expired or force refresh requested
        synchronized(this) {
            val currentNow = System.currentTimeMillis()
            if (!forceRefresh && accessToken != null && currentNow < (tokenExpiresAtMs - bufferMs)) {
                return accessToken
            }
        }

        // Try refreshing first if we have a refresh token
        val savedRefreshToken = refreshToken
        if (!savedRefreshToken.isNullOrBlank() && !forceRefresh) {
            val refreshSuccess = attemptTokenRefresh(savedRefreshToken)
            if (refreshSuccess && accessToken != null) {
                return accessToken
            }
        }

        // Full login if refresh failed or was not available
        val loginSuccess = attemptLogin()
        return if (loginSuccess) accessToken else null
    }

    /**
     * Executes POST /auth/login with the configured credentials.
     * Uses form-urlencoded payload for maximum compatibility with NiceHttp and Cloudflare.
     */
    suspend fun attemptLogin(): Boolean {
        return try {
            val response = app.post(
                url = "$API_BASE_URL/auth/login",
                headers = defaultHeaders(),
                data = mapOf(
                    "login" to email.trim(),
                    "password" to password,
                    "remember" to "true"
                ),
                timeout = 15
            )

            if (response.isSuccessful) {
                var token: String? = null
                var refresh: String? = null
                var expSec = 3600L
                val text = response.safeText() ?: ""

                try {
                    val loginResponse = parseJson<LoginResponse>(text)
                    token = loginResponse.accessToken
                    refresh = loginResponse.refreshToken
                    expSec = loginResponse.expiresIn ?: 3600L
                } catch (e: Throwable) {
                    // Fallback regex in case of Jackson TypeReference bytecode mismatch
                    token = Regex(""""access_token"\s*:\s*"([^"]+)"""").find(text)?.groupValues?.get(1)
                    refresh = Regex(""""refresh_token"\s*:\s*"([^"]+)"""").find(text)?.groupValues?.get(1)
                }

                if (!token.isNullOrBlank()) {
                    accessToken = token
                    refreshToken = refresh ?: refreshToken
                    tokenExpiresAtMs = System.currentTimeMillis() + (expSec * 1000L)
                    return true
                }
            }
            false
        } catch (e: Throwable) {
            e.printStackTrace()
            false
        }
    }

    /**
     * Executes POST /auth/refresh-token using the cached refresh token.
     */
    suspend fun attemptTokenRefresh(token: String): Boolean {
        return try {
            val response = app.post(
                url = "$API_BASE_URL/auth/refresh-token",
                headers = defaultHeaders(),
                data = mapOf(
                    "refresh_token" to token
                ),
                timeout = 15
            )

            if (response.isSuccessful) {
                var freshToken: String? = null
                var expSec = 3600L
                val text = response.safeText() ?: ""

                try {
                    val refreshResp = parseJson<LoginResponse>(text)
                    freshToken = refreshResp.accessToken
                    expSec = refreshResp.expiresIn ?: 3600L
                } catch (e: Throwable) {
                    freshToken = Regex(""""access_token"\s*:\s*"([^"]+)"""").find(text)?.groupValues?.get(1)
                }

                if (!freshToken.isNullOrBlank()) {
                    accessToken = freshToken
                    tokenExpiresAtMs = System.currentTimeMillis() + (expSec * 1000L)
                    return true
                }
            }
            false
        } catch (e: Throwable) {
            e.printStackTrace()
            false
        }
    }

    /**
     * Clears all cached tokens.
     */
    fun clearSession() {
        accessToken = null
        refreshToken = null
        tokenExpiresAtMs = 0L
    }

    /**
     * Updates credentials dynamically (e.g. from plugin settings UI).
     */
    fun updateCredentials(newEmail: String, newPass: String) {
        if (newEmail != email || newPass != password) {
            email = newEmail
            password = newPass
            clearSession()
        }
    }

    /**
     * Loads credentials from persistent SharedPreferences.
     */
    fun initFromPreferences(context: Context) {
        try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val savedEmail = prefs.getString(KEY_EMAIL, DEFAULT_EMAIL) ?: DEFAULT_EMAIL
            val savedPass = prefs.getString(KEY_PASSWORD, DEFAULT_PASSWORD) ?: DEFAULT_PASSWORD
            updateCredentials(savedEmail, savedPass)
        } catch (e: Throwable) {
            e.printStackTrace()
        }
    }

    /**
     * Saves credentials to persistent SharedPreferences.
     */
    fun saveToPreferences(context: Context, newEmail: String, newPass: String) {
        try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit()
                .putString(KEY_EMAIL, newEmail.trim())
                .putString(KEY_PASSWORD, newPass)
                .apply()
            updateCredentials(newEmail.trim(), newPass)
        } catch (e: Throwable) {
            e.printStackTrace()
        }
    }

    /**
     * Resets credentials back to the default account.
     */
    fun resetToDefault(context: Context) {
        try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().clear().apply()
            updateCredentials(DEFAULT_EMAIL, DEFAULT_PASSWORD)
        } catch (e: Throwable) {
            e.printStackTrace()
        }
    }
}
