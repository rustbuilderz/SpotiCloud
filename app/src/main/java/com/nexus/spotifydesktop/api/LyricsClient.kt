package com.nexus.spotifydesktop.api

import com.nexus.spotifydesktop.data.LyricLine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

/**
 * Timed lyrics via lrclib.net (open lyrics database).
 */
class LyricsClient {
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    suspend fun fetch(
        trackName: String,
        artistName: String,
        albumName: String = "",
        durationMs: Long = 0,
    ): List<LyricLine> = withContext(Dispatchers.IO) {
        if (trackName.isBlank()) return@withContext emptyList()
        val primaryArtist = artistName.substringBefore(',').trim()
        val durationSec = (durationMs / 1000L).toInt().takeIf { it > 0 }

        // Prefer exact get, then search fallback
        val synced = getExact(trackName, primaryArtist, albumName, durationSec)
            ?: searchBest(trackName, primaryArtist)
        parseLrc(synced.orEmpty())
    }

    private fun getExact(
        track: String,
        artist: String,
        album: String,
        durationSec: Int?,
    ): String? {
        val q = buildString {
            append("track_name=${enc(track)}")
            append("&artist_name=${enc(artist)}")
            if (album.isNotBlank()) append("&album_name=${enc(album)}")
            if (durationSec != null) append("&duration=$durationSec")
        }
        val req = Request.Builder()
            .url("https://lrclib.net/api/get?$q")
            .header("User-Agent", "SpotiCloud/1.0")
            .get()
            .build()
        return try {
            http.newCall(req).execute().use { res ->
                if (!res.isSuccessful) return null
                val json = JSONObject(res.body?.string().orEmpty())
                json.optString("syncedLyrics").takeIf { it.isNotBlank() }
                    ?: json.optString("plainLyrics").takeIf { it.isNotBlank() }?.let { plainToFakeLrc(it) }
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun searchBest(track: String, artist: String): String? {
        val q = "track_name=${enc(track)}&artist_name=${enc(artist)}"
        val req = Request.Builder()
            .url("https://lrclib.net/api/search?$q")
            .header("User-Agent", "SpotiCloud/1.0")
            .get()
            .build()
        return try {
            http.newCall(req).execute().use { res ->
                if (!res.isSuccessful) return null
                val arr = JSONArray(res.body?.string().orEmpty())
                if (arr.length() == 0) return null
                // Prefer an entry that has synced lyrics
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val synced = o.optString("syncedLyrics")
                    if (synced.isNotBlank()) return synced
                }
                val first = arr.optJSONObject(0) ?: return null
                first.optString("syncedLyrics").takeIf { it.isNotBlank() }
                    ?: first.optString("plainLyrics").takeIf { it.isNotBlank() }?.let { plainToFakeLrc(it) }
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun plainToFakeLrc(plain: String): String =
        plain.lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .mapIndexed { i, line -> "[%02d:%02d.00]%s".format(i / 2, (i % 2) * 30, line) }
            .joinToString("\n")

    private fun parseLrc(raw: String): List<LyricLine> {
        if (raw.isBlank()) return emptyList()
        val re = Regex("""\[(\d{1,2}):(\d{2})(?:\.(\d{1,3}))?](.*)""")
        return raw.lineSequence().mapNotNull { line ->
            val m = re.find(line.trim()) ?: return@mapNotNull null
            val min = m.groupValues[1].toLongOrNull() ?: return@mapNotNull null
            val sec = m.groupValues[2].toLongOrNull() ?: return@mapNotNull null
            val frac = m.groupValues[3]
            val ms = when {
                frac.isEmpty() -> 0L
                frac.length == 1 -> frac.toLong() * 100
                frac.length == 2 -> frac.toLong() * 10
                else -> frac.take(3).toLong()
            }
            val text = m.groupValues[4].trim()
            if (text.isBlank()) null else LyricLine(timeMs = min * 60_000 + sec * 1_000 + ms, text = text)
        }.sortedBy { it.timeMs }.toList()
    }

    private fun enc(s: String) = URLEncoder.encode(s, StandardCharsets.UTF_8.name())
}
