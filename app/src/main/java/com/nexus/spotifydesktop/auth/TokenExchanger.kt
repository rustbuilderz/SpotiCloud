package com.nexus.spotifydesktop.auth

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

data class TokenResponse(
    val accessToken: String,
    val refreshToken: String?,
    val expiresIn: Int,
)

object TokenExchanger {
    private val client = OkHttpClient()

    suspend fun exchangeCode(
        clientId: String,
        code: String,
        codeVerifier: String,
    ): TokenResponse = withContext(Dispatchers.IO) {
        val body = FormBody.Builder()
            .add("client_id", clientId.trim())
            .add("grant_type", "authorization_code")
            .add("code", code)
            .add("redirect_uri", DevOAuth.REDIRECT_URI)
            .add("code_verifier", codeVerifier)
            .build()
        postToken(body)
    }

    suspend fun refresh(clientId: String, refreshToken: String): TokenResponse =
        withContext(Dispatchers.IO) {
            val body = FormBody.Builder()
                .add("client_id", clientId.trim())
                .add("grant_type", "refresh_token")
                .add("refresh_token", refreshToken)
                .build()
            postToken(body)
        }

    private fun postToken(body: FormBody): TokenResponse {
        val req = Request.Builder()
            .url("https://accounts.spotify.com/api/token")
            .post(body)
            .build()
        client.newCall(req).execute().use { res ->
            val text = res.body?.string().orEmpty()
            if (!res.isSuccessful) {
                error("Token exchange failed (${res.code}): $text")
            }
            val json = JSONObject(text)
            return TokenResponse(
                accessToken = json.getString("access_token"),
                refreshToken = json.optString("refresh_token").ifBlank { null },
                expiresIn = json.getInt("expires_in"),
            )
        }
    }
}
