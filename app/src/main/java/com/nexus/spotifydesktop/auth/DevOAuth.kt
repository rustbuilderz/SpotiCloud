package com.nexus.spotifydesktop.auth

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import com.nexus.spotifydesktop.SpotifyConfig

/**
 * Developer-Dashboard OAuth via browser/Custom Tabs (not Spotify app SSO).
 */
object DevOAuth {
    /** Must match Dashboard Redirect URI + MainActivity intent-filter exactly. */
    val REDIRECT_URI: String = SpotifyConfig.REDIRECT_URI

    /** Scopes needed for Liked Songs only — avoid `streaming` (can trip server_error). */
    private val SCOPES = arrayOf(
        "user-library-read",
        "user-read-email",
        "user-read-private",
    )

    fun openLogin(activity: Activity, clientId: String, codeChallenge: String) {
        require(clientId.isNotBlank()) { "Spotify Client ID required" }
        val authUri = Uri.parse("https://accounts.spotify.com/authorize").buildUpon()
            .appendQueryParameter("client_id", clientId.trim())
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("redirect_uri", REDIRECT_URI)
            .appendQueryParameter("code_challenge_method", "S256")
            .appendQueryParameter("code_challenge", codeChallenge)
            .appendQueryParameter("scope", SCOPES.joinToString(" "))
            .appendQueryParameter("show_dialog", "true")
            .build()
        try {
            CustomTabsIntent.Builder().build().launchUrl(activity, authUri)
        } catch (_: Exception) {
            activity.startActivity(Intent(Intent.ACTION_VIEW, authUri))
        }
    }

    fun isDevRedirect(uri: Uri?): Boolean {
        if (uri == null) return false
        val expected = Uri.parse(REDIRECT_URI)
        return uri.scheme.equals(expected.scheme, ignoreCase = true) &&
            uri.host.equals(expected.host, ignoreCase = true)
    }

    fun codeFromRedirect(uri: Uri): String? = uri.getQueryParameter("code")

    fun errorFromRedirect(uri: Uri): String? =
        uri.getQueryParameter("error_description")
            ?: uri.getQueryParameter("error")
}
