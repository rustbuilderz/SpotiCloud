package com.nexus.spotifydesktop.api

import android.app.Application
import com.nexus.spotifydesktop.auth.TokenStore
import com.nexus.spotifydesktop.auth.WebPlayerBridge
import com.nexus.spotifydesktop.data.ConsoleLevel
import com.nexus.spotifydesktop.data.LibraryItem
import com.nexus.spotifydesktop.data.TrackItem
import com.nexus.spotifydesktop.data.parseIsoToEpochMs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

class SpotifyApiClient(
    private val tokenStore: TokenStore,
    app: Application,
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val pathfinder = PathfinderClient(tokenStore, WebPlayerBridge.get(app))
    private val rateLimitedUntilMs = AtomicLong(0L)
    var onLog: ((ConsoleLevel, String, String) -> Unit)? = null

    fun rateLimitRemainingMs(): Long =
        (rateLimitedUntilMs.get() - System.currentTimeMillis()).coerceAtLeast(0L)

    fun isRateLimited(): Boolean = rateLimitRemainingMs() > 0L

    fun clearRateLimit() {
        rateLimitedUntilMs.set(0L)
    }

    fun invalidateClientToken() {
        pathfinder.invalidateClientToken()
    }

    suspend fun currentUser(): Pair<String, String> {
        val p = pathfinder.profile()
        return p.id to p.name
    }

    suspend fun currentUserId(): String = currentUser().first

    suspend fun currentUserName(): String = currentUser().second

    suspend fun loadLibrary(): Pair<List<LibraryItem>, String?> = pathfinder.loadLibrary()

    // Pathfinder only — do not hit web-token GET /v1/me/tracks (hard 429s).
    suspend fun likedTracksProgressive(onPage: (List<TrackItem>) -> Unit): List<TrackItem> =
        pathfinder.likedTracks(onPage)

    suspend fun likedTracksPreview(limit: Int = 15): List<TrackItem> =
        pathfinder.likedTracksPreview(limit.coerceIn(1, 50))

    /** Optional Developer Dashboard bearer path for Liked Songs. */
    suspend fun likedTracksWithBearer(
        accessToken: String,
        onPage: (List<TrackItem>) -> Unit = {},
    ): List<TrackItem> = withContext(Dispatchers.IO) {
        val all = mutableListOf<TrackItem>()
        var offset = 0
        var total = Int.MAX_VALUE
        var guard = 0
        while (offset < total && guard < 200) {
            guard++
            val path = "/me/tracks?limit=50&offset=$offset"
            val json = JSONObject(apiRequestWithBearer(accessToken, "GET", path))
            if (json.has("total")) total = json.optInt("total")
            val items = json.optJSONArray("items") ?: JSONArray()
            if (items.length() == 0) break
            for (i in 0 until items.length()) {
                val row = items.optJSONObject(i) ?: continue
                val likedAt = parseIsoToEpochMs(row.optString("added_at").takeIf { it.isNotBlank() })
                parseTrack(row.optJSONObject("track"))?.copy(likedAtMs = likedAt)?.let(all::add)
            }
            onPage(all.toList())
            offset += items.length()
            if (items.length() < 50) break
            delay(900)
        }
        all
    }

    private suspend fun apiRequestWithBearer(
        accessToken: String,
        method: String,
        path: String,
    ): String = withContext(Dispatchers.IO) {
        val builder = Request.Builder()
            .url("$API$path")
            .header("Authorization", "Bearer $accessToken")
            .header("Content-Type", "application/json")
        when (method) {
            "GET" -> builder.get()
            else -> error("Unsupported $method")
        }
        client.newCall(builder.build()).execute().use { res ->
            val text = res.body?.string().orEmpty()
            if (res.code == 429) {
                val retryAfterSec = res.header("Retry-After")?.toLongOrNull()?.coerceIn(1L, 120L) ?: 30L
                rateLimitedUntilMs.set(System.currentTimeMillis() + retryAfterSec * 1000L)
                onLog?.invoke(
                    ConsoleLevel.RateLimit,
                    "Spotify",
                    "HTTP 429 · Dev token · $path · wait ${retryAfterSec}s",
                )
                error("HTTP 429 on $method $path (Retry-After ${retryAfterSec}s)")
            }
            if (res.code == 401) {
                error("Developer token expired or revoked (HTTP 401) — authorize again")
            }
            if (!res.isSuccessful && res.code != 204) {
                error(parseError(res.code, text, path))
            }
            text
        }
    }

    suspend fun playlistTracks(playlistId: String): List<TrackItem> =
        playlistTracksProgressive(playlistId) { }

    suspend fun playlistTracksProgressive(
        playlistId: String,
        onPage: (List<TrackItem>) -> Unit,
    ): List<TrackItem> = pathfinder.playlistTracks(playlistId, onPage)

    suspend fun albumTracks(albumId: String): List<TrackItem> =
        albumTracksProgressive(albumId) { }

    suspend fun albumTracksProgressive(
        albumId: String,
        onPage: (List<TrackItem>) -> Unit,
    ): List<TrackItem> = pathfinder.albumTracks(albumId, onPage)

    suspend fun searchTracks(query: String): List<TrackItem> =
        pathfinder.searchTracks(query)

    suspend fun search(query: String) = pathfinder.search(query)

    suspend fun artistTopTracks(
        artistId: String?,
        artistName: String,
        limit: Int = 10,
    ): List<TrackItem> = pathfinder.artistTopTracks(artistId, artistName, limit)

    suspend fun isTrackSaved(trackId: String): Boolean = withContext(Dispatchers.IO) {
        pathfinder.isLiked(trackId.removePrefix("spotify:track:"))
    }

    suspend fun saveTrack(trackId: String) {
        pathfinder.setLiked(trackId.removePrefix("spotify:track:"), liked = true)
    }

    suspend fun removeTrack(trackId: String) {
        pathfinder.setLiked(trackId.removePrefix("spotify:track:"), liked = false)
    }

    private fun parseTrack(track: JSONObject?): TrackItem? {
        if (track == null) return null
        if (track.optString("type") != "track" && track.has("type")) {
            if (track.optString("type").isNotBlank() && track.optString("type") != "track") return null
        }
        val id = track.optString("id")
        val uri = track.optString("uri")
        if (id.isBlank() || uri.isBlank()) return null
        val album = track.optJSONObject("album")
        val artistsArr = track.optJSONArray("artists")
        var primaryArtistId: String? = null
        if (artistsArr != null && artistsArr.length() > 0) {
            primaryArtistId = artistsArr.optJSONObject(0)?.optString("id")?.takeIf { it.isNotBlank() }
        }
        return TrackItem(
            id = id,
            uri = uri,
            name = track.optString("name"),
            artists = joinArtists(artistsArr),
            album = album?.optString("name").orEmpty(),
            imageUrl = album?.optJSONArray("images")?.optJSONObject(0)?.optString("url"),
            durationMs = track.optLong("duration_ms"),
            primaryArtistId = primaryArtistId,
        )
    }

    private fun joinArtists(arr: JSONArray?): String {
        if (arr == null) return ""
        return buildList {
            for (i in 0 until arr.length()) {
                add(arr.optJSONObject(i)?.optString("name").orEmpty())
            }
        }.filter { it.isNotBlank() }.joinToString(", ")
    }

    private fun parseError(code: Int, body: String, path: String = ""): String {
        return try {
            val err = JSONObject(body).optJSONObject("error")
            val msg = err?.optString("message")?.ifBlank { null } ?: body.take(200)
            val reason = err?.optString("reason")?.ifBlank { null }
            val where = if (path.isNotBlank()) " on $path" else ""
            when {
                code == 429 -> "HTTP 429$where: $msg"
                code == 403 && (msg.contains("playlist", ignoreCase = true) || body.contains("/items")) ->
                    "$msg — playlist tracks only work for playlists you own or collaborate on."
                reason != null -> "HTTP $code$where: $msg ($reason)"
                else -> if (code in 400..599) "HTTP $code$where: $msg" else msg
            }
        } catch (_: Exception) {
            "HTTP $code${if (path.isNotBlank()) " on $path" else ""}: ${body.take(200)}"
        }
    }

    companion object {
        private const val API = "https://api.spotify.com/v1"
    }
}
