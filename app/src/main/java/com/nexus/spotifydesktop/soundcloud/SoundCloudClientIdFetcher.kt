package com.nexus.spotifydesktop.soundcloud

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** Scrapes anonymous `client_id` from SoundCloud website JS bundles. */
object SoundCloudClientIdFetcher {
    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val CLIENT_ID_QUOTED = Regex("""client_id:"([a-zA-Z0-9]{16,})"""")
    private val CLIENT_ID_EQ = Regex("""client_id=([a-zA-Z0-9]{16,})""")
    private val SCRIPT_SRC = Regex("""<script[^>]+src="([^"]+)"""")

    suspend fun fetch(): String? = withContext(Dispatchers.IO) {
        val html = getText("https://soundcloud.com/") ?: return@withContext null
        val scripts = SCRIPT_SRC.findAll(html)
            .map { it.groupValues[1] }
            .filter { it.startsWith("http") }
            .toList()
            .asReversed()

        for (url in scripts) {
            val js = getText(url) ?: continue
            val id = CLIENT_ID_QUOTED.find(js)?.groupValues?.get(1)
                ?: CLIENT_ID_EQ.find(js)?.groupValues?.get(1)
            if (!id.isNullOrBlank()) return@withContext id
        }
        null
    }

    private fun getText(url: String): String? {
        val req = Request.Builder()
            .url(url)
            .header(
                "User-Agent",
                "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36",
            )
            .header("Accept", "text/html,application/javascript,*/*")
            .build()
        return runCatching {
            http.newCall(req).execute().use { res ->
                if (!res.isSuccessful) null else res.body?.string()
            }
        }.getOrNull()
    }
}
