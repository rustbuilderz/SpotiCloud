package com.nexus.spotifydesktop.auth

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/**
 * Stores Spotify web-player session (`sp_dc`) + short-lived access token.
 * Unofficial — not the Developer Dashboard OAuth client.
 */
class TokenStore(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("spotify_web_session", Context.MODE_PRIVATE)

    var spDc: String?
        get() = prefs.getString(KEY_SP_DC, null)
        set(value) = prefs.edit { putString(KEY_SP_DC, value) }

    var spT: String?
        get() = prefs.getString(KEY_SP_T, null)
        set(value) = prefs.edit { putString(KEY_SP_T, value) }

    var accessToken: String?
        get() = prefs.getString(KEY_ACCESS, null)
        set(value) = prefs.edit { putString(KEY_ACCESS, value) }

    var webClientId: String?
        get() = prefs.getString(KEY_CLIENT_ID, null)
        set(value) = prefs.edit { putString(KEY_CLIENT_ID, value) }

    var clientVersion: String?
        get() = prefs.getString(KEY_CLIENT_VER, null)
        set(value) = prefs.edit { putString(KEY_CLIENT_VER, value) }

    var expiresAtMs: Long
        get() = prefs.getLong(KEY_EXPIRES, 0L)
        set(value) = prefs.edit { putLong(KEY_EXPIRES, value) }

    var userName: String?
        get() = prefs.getString(KEY_NAME, null)
        set(value) = prefs.edit { putString(KEY_NAME, value) }

    // Official Dashboard OAuth (PKCE) — separate from web sp_dc

    var oauthAccessToken: String?
        get() = prefs.getString(KEY_OAUTH_ACCESS, null)
        set(value) = prefs.edit { putString(KEY_OAUTH_ACCESS, value) }

    var oauthRefreshToken: String?
        get() = prefs.getString(KEY_OAUTH_REFRESH, null)
        set(value) = prefs.edit { putString(KEY_OAUTH_REFRESH, value) }

    var oauthExpiresAtMs: Long
        get() = prefs.getLong(KEY_OAUTH_EXPIRES, 0L)
        set(value) = prefs.edit { putLong(KEY_OAUTH_EXPIRES, value) }

    var pendingPkceVerifier: String?
        get() = prefs.getString(KEY_PKCE_VERIFIER, null)
        set(value) = prefs.edit { putString(KEY_PKCE_VERIFIER, value) }

    val oauthExpired: Boolean
        get() = System.currentTimeMillis() >= oauthExpiresAtMs - 60_000

    val hasOauthSession: Boolean
        get() = !oauthAccessToken.isNullOrBlank() || !oauthRefreshToken.isNullOrBlank()

    fun saveOauthTokens(access: String, refresh: String?, expiresInSec: Int) {
        oauthAccessToken = access
        if (!refresh.isNullOrBlank()) oauthRefreshToken = refresh
        oauthExpiresAtMs = System.currentTimeMillis() + expiresInSec * 1000L
    }

    fun clearOauth() = prefs.edit {
        remove(KEY_OAUTH_ACCESS)
        remove(KEY_OAUTH_REFRESH)
        remove(KEY_OAUTH_EXPIRES)
        remove(KEY_PKCE_VERIFIER)
    }

    val isExpired: Boolean
        get() = System.currentTimeMillis() >= expiresAtMs - 60_000

    val hasSession: Boolean
        get() = !spDc.isNullOrBlank()

    fun clear() = prefs.edit { clear() }

    companion object {
        private const val KEY_SP_DC = "sp_dc"
        private const val KEY_SP_T = "sp_t"
        private const val KEY_ACCESS = "access"
        private const val KEY_CLIENT_ID = "web_client_id"
        private const val KEY_CLIENT_VER = "client_version"
        private const val KEY_EXPIRES = "expires"
        private const val KEY_NAME = "user_name"
        private const val KEY_OAUTH_ACCESS = "oauth_access"
        private const val KEY_OAUTH_REFRESH = "oauth_refresh"
        private const val KEY_OAUTH_EXPIRES = "oauth_expires"
        private const val KEY_PKCE_VERIFIER = "pkce_verifier"
    }
}
