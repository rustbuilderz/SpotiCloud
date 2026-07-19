package com.nexus.spotifydesktop.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/** Device-local secrets — never ship these in the APK/repo. */
class AppSecretsStore(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("spoticloud_secrets", Context.MODE_PRIVATE)

    var spotifyClientId: String?
        get() = prefs.getString(KEY_SPOTIFY_CID, null)?.trim()?.takeIf { it.isNotBlank() }
        set(value) = prefs.edit {
            putString(KEY_SPOTIFY_CID, value?.trim().orEmpty())
        }

    var setupAgreed: Boolean
        get() = prefs.getBoolean(KEY_SETUP_AGREED, false)
        set(value) = prefs.edit { putBoolean(KEY_SETUP_AGREED, value) }

    var setupComplete: Boolean
        get() = prefs.getBoolean(KEY_SETUP_COMPLETE, false)
        set(value) = prefs.edit { putBoolean(KEY_SETUP_COMPLETE, value) }

    fun clearSetupFlags() {
        prefs.edit {
            putBoolean(KEY_SETUP_AGREED, false)
            putBoolean(KEY_SETUP_COMPLETE, false)
        }
    }

    companion object {
        private const val KEY_SPOTIFY_CID = "spotify_client_id"
        private const val KEY_SETUP_AGREED = "setup_agreed"
        private const val KEY_SETUP_COMPLETE = "setup_complete"
    }
}
