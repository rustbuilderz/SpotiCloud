package com.nexus.spotifydesktop.soundcloud

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

class SoundCloudTokenStore(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("soundcloud_session", Context.MODE_PRIVATE)

    var clientId: String?
        get() = prefs.getString(KEY_CLIENT_ID, null)?.takeIf { it.isNotBlank() }
        set(value) = prefs.edit { putString(KEY_CLIENT_ID, value) }

    var oauthToken: String?
        get() = prefs.getString(KEY_OAUTH, null)?.takeIf { it.isNotBlank() }
        set(value) = prefs.edit { putString(KEY_OAUTH, value) }

    var userId: Long
        get() = prefs.getLong(KEY_USER_ID, 0L)
        set(value) = prefs.edit { putLong(KEY_USER_ID, value) }

    var userName: String?
        get() = prefs.getString(KEY_USER_NAME, null)
        set(value) = prefs.edit { putString(KEY_USER_NAME, value) }

    var likeMode: ScLikeMode
        get() = when (prefs.getString(KEY_LIKE_MODE, ScLikeMode.WebView.name)) {
            ScLikeMode.Api.name -> ScLikeMode.Api
            else -> ScLikeMode.WebView
        }
        set(value) = prefs.edit { putString(KEY_LIKE_MODE, value.name) }

    val hasSession: Boolean
        get() = !oauthToken.isNullOrBlank() && !clientId.isNullOrBlank()

    fun clear() = prefs.edit {
        val mode = likeMode
        clear()
        putString(KEY_LIKE_MODE, mode.name)
    }

    companion object {
        private const val KEY_CLIENT_ID = "client_id"
        private const val KEY_OAUTH = "oauth_token"
        private const val KEY_USER_ID = "user_id"
        private const val KEY_USER_NAME = "user_name"
        private const val KEY_LIKE_MODE = "like_mode"
    }
}
