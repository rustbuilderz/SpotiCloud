package com.nexus.spotifydesktop.auth

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.math.pow

/**
 * Unofficial web-player token bootstrap:
 * sp_dc cookie → HOTP challenge → open.spotify.com/api/token → Bearer accessToken.
 *
 * Secrets feeds move often (repos get taken down). We try several mirrors.
 */
class WebTokenProvider(
    private val tokenStore: TokenStore,
) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()
    private val mutex = Mutex()

    private var totpVer: Int = 0
    private var hotpSecret: ByteArray = ByteArray(0)
    private var secretsLoadedAt: Long = 0L
    private var useXorDerive: Boolean = true

    suspend fun ensureAccessToken(): String = mutex.withLock {
        withContext(Dispatchers.IO) {
            val spDc = tokenStore.spDc?.trim().orEmpty()
            if (spDc.isBlank()) error("Not logged in — open Spotify web login")

            val cached = tokenStore.accessToken
            val hasClientId = !tokenStore.webClientId.isNullOrBlank() &&
                !tokenStore.webClientId.equals("unknown", ignoreCase = true)
            if (!cached.isNullOrBlank() && !tokenStore.isExpired && hasClientId) {
                return@withContext cached
            }

            ensureSecrets()

            // Try current secret mode, then flip format once if Spotify rejects TOTP
            var lastError: String? = null
            repeat(2) { attempt ->
                val nowMs = System.currentTimeMillis()
                val hotp = generateHotp(nowMs)
                val url = buildString {
                    append("https://open.spotify.com/api/token")
                    append("?reason=init&productType=web-player")
                    append("&totp=$hotp&totpServer=$hotp&totpVer=$totpVer")
                }
                val req = Request.Builder()
                    .url(url)
                    .header("User-Agent", USER_AGENT)
                    .header("App-Platform", "WebPlayer")
                    .header("Cookie", buildCookieHeader())
                    .get()
                    .build()

                http.newCall(req).execute().use { res ->
                    // Persist sp_t if Spotify sets it
                    for (raw in res.headers("Set-Cookie")) {
                        val part = raw.substringBefore(';')
                        if (part.startsWith("sp_t=")) {
                            tokenStore.spT = part.removePrefix("sp_t=").trim()
                        }
                    }
                    val text = res.body?.string().orEmpty()
                    if (res.isSuccessful) {
                        val json = JSONObject(text)
                        if (json.optBoolean("isAnonymous", false)) {
                            tokenStore.clear()
                            error("Session expired — log in again (sp_dc invalid)")
                        }
                        val access = json.optString("accessToken").ifBlank {
                            error("Web token missing accessToken")
                        }
                        val exp = json.optLong("accessTokenExpirationTimestampMs")
                            .takeIf { it > 0 } ?: (nowMs + 3_600_000L)
                        val clientId = json.optString("clientId").trim()
                        if (clientId.isBlank() || clientId.equals("unknown", ignoreCase = true)) {
                            error("Web token missing clientId — try logging in again")
                        }
                        tokenStore.accessToken = access
                        tokenStore.expiresAtMs = exp
                        tokenStore.webClientId = clientId
                        return@withContext access
                    }
                    lastError = "Web token failed (${res.code}): ${text.take(180)}"
                    // Likely wrong secret transform — flip and reload secret bytes
                    if (attempt == 0 && (text.contains("totp", ignoreCase = true) || res.code == 400)) {
                        useXorDerive = !useXorDerive
                        secretsLoadedAt = 0L
                        ensureSecrets()
                    }
                }
            }
            error(lastError ?: "Web token failed")
        }
    }

    private fun buildCookieHeader(): String = buildString {
        append("sp_dc=${tokenStore.spDc!!.trim()}")
        tokenStore.spT?.takeIf { it.isNotBlank() }?.let { append("; sp_t=$it") }
    }

    private fun ensureSecrets() {
        val fresh = System.currentTimeMillis() - secretsLoadedAt < 6 * 60 * 60 * 1000L
        if (fresh && hotpSecret.isNotEmpty() && totpVer > 0) return

        var lastStatus = 0
        for (url in SECRETS_URLS) {
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .get()
                .build()
            try {
                http.newCall(req).execute().use { res ->
                    lastStatus = res.code
                    val text = res.body?.string().orEmpty()
                    if (!res.isSuccessful || text.isBlank()) return@use
                    if (applySecretsJson(text)) {
                        secretsLoadedAt = System.currentTimeMillis()
                        return
                    }
                }
            } catch (_: Exception) {
                // try next mirror
            }
        }
        error(
            "Can't load Spotify web secrets (last HTTP $lastStatus). " +
                "The community secrets mirror may be down — try again later.",
        )
    }

    private fun applySecretsJson(text: String): Boolean {
        val json = JSONObject(text)
        // Format A: { "8": [52,52,...], "9": [...] }
        val dictKeys = json.keys().asSequence().mapNotNull { it.toIntOrNull() }.toList()
        if (dictKeys.isNotEmpty()) {
            val latest = dictKeys.maxOrNull()!!
            val arr = json.getJSONArray(latest.toString())
            val cipher = IntArray(arr.length()) { arr.getInt(it) }
            totpVer = latest
            hotpSecret = secretBytesFromCipher(cipher)
            return hotpSecret.isNotEmpty()
        }

        // Format B: [ {"version":8,"secret":[...]}, ... ] or {"secrets":[...]}
        val arr: JSONArray = when {
            json.has("secrets") -> json.getJSONArray("secrets")
            else -> return false
        }
        var bestVer = -1
        var bestCipher: IntArray? = null
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val ver = o.optInt("version", -1)
            val secretArr = o.optJSONArray("secret") ?: continue
            if (ver > bestVer) {
                bestVer = ver
                bestCipher = IntArray(secretArr.length()) { secretArr.getInt(it) }
            }
        }
        if (bestVer < 0 || bestCipher == null) return false
        totpVer = bestVer
        hotpSecret = secretBytesFromCipher(bestCipher)
        return hotpSecret.isNotEmpty()
    }

    private fun secretBytesFromCipher(cipher: IntArray): ByteArray {
        val plain = if (useXorDerive) {
            // Thereallo-style cipher → digit string
            cipher.mapIndexed { i, byte -> byte xor ((i % 33) + 9) }
                .joinToString("") { it.toString() }
        } else {
            // grabber-style: array of char codes for the secret string
            buildString {
                for (code in cipher) {
                    if (code in 32..126) append(code.toChar())
                }
            }
        }
        return plain.toByteArray(Charsets.UTF_8)
    }

    private fun generateHotp(timestampMs: Long): String {
        val counter = timestampMs / 1000L / HOTP_PERIOD
        val counterBytes = ByteArray(8)
        var c = counter
        for (i in 7 downTo 0) {
            counterBytes[i] = (c and 0xff).toByte()
            c = c ushr 8
        }
        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(hotpSecret, "HmacSHA1"))
        val hash = mac.doFinal(counterBytes)
        val offset = hash.last().toInt() and 0x0f
        val binary =
            ((hash[offset].toInt() and 0x7f) shl 24) or
                ((hash[offset + 1].toInt() and 0xff) shl 16) or
                ((hash[offset + 2].toInt() and 0xff) shl 8) or
                (hash[offset + 3].toInt() and 0xff)
        val mod = 10.0.pow(HOTP_DIGITS).toInt()
        return (binary % mod).toString().padStart(HOTP_DIGITS, '0')
    }

    companion object {
        private val SECRETS_URLS = listOf(
            "https://raw.githubusercontent.com/xyloflake/spot-secrets-go/main/secrets/secretDict.json",
            "https://cdn.jsdelivr.net/gh/xyloflake/spot-secrets-go@main/secrets/secretDict.json",
            "https://git.gay/thereallo/totp-secrets/raw/branch/main/secrets/secretDict.json",
            "https://raw.git.gay/thereallo/totp-secrets/main/secrets/secretDict.json",
        )
        private const val HOTP_PERIOD = 30L
        private const val HOTP_DIGITS = 6
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/145.0.0.0 Safari/537.36"
    }
}
