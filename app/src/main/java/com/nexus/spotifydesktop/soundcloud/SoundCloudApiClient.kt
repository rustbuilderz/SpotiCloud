package com.nexus.spotifydesktop.soundcloud

import android.app.Application
import com.nexus.spotifydesktop.data.ScPage
import com.nexus.spotifydesktop.data.ScPlaylist
import com.nexus.spotifydesktop.data.ScTrack
import com.nexus.spotifydesktop.data.scArtworkLarge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** SoundCloud api-v2: client_id + oauth_token. Likes prefer WebBridge cookies. */
class SoundCloudApiClient(
    private val store: SoundCloudTokenStore,
    app: Application? = null,
) {
    private val webBridge: SoundCloudWebBridge? =
        app?.let { SoundCloudWebBridge.get(it, store) }

    var onLog: ((com.nexus.spotifydesktop.data.ConsoleLevel, String, String) -> Unit)? = null

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    companion object {
        private const val API_BASE = "https://api-v2.soundcloud.com"
        private const val APP_VERSION = "1779975447"

        fun extractCaptchaUrl(text: String?): String? {
            if (text.isNullOrBlank()) return null
            return Regex("""https?://[^"'\s<>]*captcha-delivery\.com[^"'\s<>]*""")
                .find(text)?.value
        }
    }

    suspend fun ensureClientId(force: Boolean = false): String = withContext(Dispatchers.IO) {
        val existing = store.clientId
        if (!force && !existing.isNullOrBlank()) return@withContext existing
        val id = SoundCloudClientIdFetcher.fetch()
            ?: error("Couldn't auto-fetch SoundCloud client_id — paste one in Settings")
        store.clientId = id
        id
    }

    /** Validate OAuth and persist user profile. */
    suspend fun connect(oauthTokenRaw: String, clientIdOverride: String? = null): Pair<Long, String> =
        withContext(Dispatchers.IO) {
            val token = oauthTokenRaw.trim().replace(Regex("^OAuth\\s+", RegexOption.IGNORE_CASE), "")
            if (token.isBlank()) error("Paste your SoundCloud OAuth token")
            val clientId = clientIdOverride?.trim()?.takeIf { it.isNotBlank() }
                ?: ensureClientId()
            store.clientId = clientId
            store.oauthToken = token
            val me = getMe()
            store.userId = me.first
            store.userName = me.second
            me
        }

    suspend fun getMe(): Pair<Long, String> = withContext(Dispatchers.IO) {
        val json = scGet("/me")
        val id = json.optLong("id")
        val name = json.optString("username").ifBlank { "SoundCloud" }
        if (id == 0L) error("Invalid SoundCloud session")
        store.userId = id
        store.userName = name
        id to name
    }

    suspend fun getFeed(limit: Int = 30): ScPage<ScTrack> = withContext(Dispatchers.IO) {
        val json = scGet("/stream", mapOf("limit" to limit.toString()))
        parseStreamPage(json)
    }

    suspend fun getLikedTracks(limit: Int = 50): ScPage<ScTrack> = withContext(Dispatchers.IO) {
        val uid = ensureUserId()
        val json = scGet("/users/$uid/track_likes", mapOf("limit" to limit.toString()))
        parseLikedTracksPage(json)
    }

    suspend fun getPlaylists(limit: Int = 40): ScPage<ScPlaylist> = withContext(Dispatchers.IO) {
        val uid = ensureUserId()
        val json = scGet("/users/$uid/playlists", mapOf("limit" to limit.toString()))
        parsePlaylistsPage(json)
    }

    suspend fun getPlaylist(id: Long): Pair<ScPlaylist, List<ScTrack>> = withContext(Dispatchers.IO) {
        val json = scGet("/playlists/$id")
        val pl = parsePlaylist(json) ?: error("Playlist not found")
        val tracksArr = json.optJSONArray("tracks") ?: JSONArray()
        val tracks = buildList {
            for (i in 0 until tracksArr.length()) {
                parseTrack(tracksArr.optJSONObject(i))?.let(::add)
            }
        }
        pl to tracks
    }

    suspend fun searchTracks(query: String, limit: Int = 30): ScPage<ScTrack> =
        withContext(Dispatchers.IO) {
            val q = query.trim()
            if (q.isEmpty()) return@withContext ScPage(emptyList())
            val json = scGet("/search/tracks", mapOf("q" to q, "limit" to limit.toString()))
            parseSearchPage(json)
        }

    suspend fun searchUsers(query: String, limit: Int = 20): List<com.nexus.spotifydesktop.data.ScSearchUser> =
        withContext(Dispatchers.IO) {
            val q = query.trim()
            if (q.isEmpty()) return@withContext emptyList()
            val json = scGet("/search/users", mapOf("q" to q, "limit" to limit.toString()))
            parseUserSearch(json)
        }

    suspend fun searchAlbums(query: String, limit: Int = 20): List<ScPlaylist> =
        withContext(Dispatchers.IO) {
            val q = query.trim()
            if (q.isEmpty()) return@withContext emptyList()
            val json = runCatching {
                scGet("/search/albums", mapOf("q" to q, "limit" to limit.toString()))
            }.getOrElse {
                scGet("/search/playlists", mapOf("q" to q, "limit" to limit.toString()))
            }
            parsePlaylistSearch(json)
        }

    data class ScSearchBundle(
        val tracks: List<ScTrack> = emptyList(),
        val users: List<com.nexus.spotifydesktop.data.ScSearchUser> = emptyList(),
        val albums: List<ScPlaylist> = emptyList(),
    ) {
        val totalCount: Int get() = tracks.size + users.size + albums.size
        val isEmpty: Boolean get() = totalCount == 0
    }

    suspend fun searchAll(query: String, limit: Int = 20): ScSearchBundle = withContext(Dispatchers.IO) {
        val q = query.trim()
        if (q.isEmpty()) return@withContext ScSearchBundle()
        val tracks = runCatching { searchTracks(q, limit).items }.getOrDefault(emptyList())
        val users = runCatching { searchUsers(q, limit) }.getOrDefault(emptyList())
        val albums = runCatching { searchAlbums(q, limit) }.getOrDefault(emptyList())
        ScSearchBundle(tracks = tracks, users = users, albums = albums)
    }

    /**
     * Resolve a SoundCloud user id from an optional id, profile/track permalink, or username.
     * Needed when cached likes were saved without [ScTrack.userId].
     */
    suspend fun resolveUserId(
        userId: Long?,
        artistName: String,
        permalinkUrl: String? = null,
    ): Long = withContext(Dispatchers.IO) {
        userId?.takeIf { it > 0L }?.let { return@withContext it }

        val fromPermalink = permalinkUrl
            ?.let { extractSoundCloudUsername(it) }
            ?.let { username ->
                runCatching {
                    val json = scGet("/resolve", mapOf("url" to "https://soundcloud.com/$username"))
                    jsonLongId(json)
                }.getOrNull()
            }
        if (fromPermalink != null && fromPermalink > 0L) return@withContext fromPermalink

        val name = artistName.trim()
        if (name.isBlank()) error("Missing SoundCloud artist id")
        val users = searchUsers(name, limit = 10)
        val exact = users.firstOrNull { it.name.equals(name, ignoreCase = true) }
            ?: users.firstOrNull()
            ?: error("Couldn't find SoundCloud artist “$name”")
        exact.id
    }

    /** Most recent uploads for a user (artist page). */
    suspend fun getUserTracks(userId: Long, limit: Int = 50): ScPage<ScTrack> =
        withContext(Dispatchers.IO) {
            val json = scGet(
                "/users/$userId/tracks",
                mapOf(
                    "limit" to limit.coerceIn(1, 50).toString(),
                    "representation" to "full",
                ),
            )
            val page = parseUserTracksPage(json)
            if (page.items.isNotEmpty()) return@withContext page
            // Some responses only include id stubs — hydrate
            val ids = extractTrackIds(json)
            if (ids.isEmpty()) return@withContext page
            val hydrated = ids.mapNotNull { id ->
                runCatching { fetchTrack(id) }.getOrNull()
            }
            ScPage(hydrated, page.nextHref)
        }

    suspend fun likeTrack(trackId: Long, permalinkUrl: String? = null) = withContext(Dispatchers.IO) {
        val uid = ensureUserId()
        val permalink = permalinkUrl?.takeIf { it.isNotBlank() }
            ?: runCatching { fetchTrack(trackId).permalinkUrl }.getOrNull()
        val notes = mutableListOf<String>()
        val bridge = webBridge

        fun throwIfCaptcha(text: String?) {
            val url = parseCaptchaUrl(text.orEmpty()) ?: return
            throw SoundCloudCaptchaNeeded(url)
        }

        // 1) Click SoundCloud's own Like button
        if (bridge != null && !permalink.isNullOrBlank()) {
            val res = runCatching { bridge.likeViaTrackPage(permalink) }
                .onFailure { e ->
                    if (e is SoundCloudCaptchaNeeded) throw e
                    throwIfCaptcha(e.message)
                    notes += "page-click: ${e.message ?: e.javaClass.simpleName}"
                }
                .getOrNull()
            if (res != null) {
                if (res.status in 200..299) return@withContext
                throwIfCaptcha(res.body)
                notes += "page-click HTTP ${res.status} ${res.body.take(80)}"
            }
        } else {
            notes += if (bridge == null) "no-webview" else "no-permalink"
        }

        // 2) In-page fetch PUT
        if (bridge != null) {
            val clientId = store.clientId.orEmpty()
            val oauth = store.oauthToken.orEmpty()
            val apiUrl =
                "https://api-v2.soundcloud.com/users/$uid/track_likes/$trackId" +
                    "?client_id=$clientId&app_version=$APP_VERSION&app_locale=en&oauth_token=$oauth"
            val res = runCatching { bridge.mutateApi("PUT", apiUrl) }
                .onFailure { e ->
                    if (e is SoundCloudCaptchaNeeded) throw e
                    throwIfCaptcha(e.message)
                    notes += "webview-put: ${e.message ?: e.javaClass.simpleName}"
                }
                .getOrNull()
            if (res != null) {
                if (res.status in 200..299 || res.status == 201 || res.status == 204) return@withContext
                if (res.status == 409 || res.status == 422) return@withContext
                throwIfCaptcha(res.body)
                notes += "webview-put HTTP ${res.status} ${res.body.take(80)}"
            }
        }

        // 3) OkHttp PUT
        val attempts = listOf(
            MutateAttempt("PUT", "/users/$uid/track_likes/$trackId"),
            MutateAttempt("PUT", "/me/track_likes/$trackId"),
            MutateAttempt("PUT", "/users/$uid/likes/tracks/$trackId"),
        )
        var lastHttp: String? = null
        for (attempt in attempts) {
            try {
                scMutate(attempt.method, attempt.endpoint, attempt.publicApi)
                return@withContext
            } catch (e: SoundCloudCaptchaNeeded) {
                throw e
            } catch (e: Exception) {
                throwIfCaptcha(e.message)
                lastHttp = e.message
                notes += "okhttp ${attempt.endpoint}: ${lastHttp ?: "fail"}"
            }
        }

        // Last chance — dig captcha out of accumulated notes
        throwIfCaptcha(notes.joinToString(" "))
        error(
            "Like failed · " + notes.joinToString(" | ").ifBlank {
                lastHttp ?: "unknown error"
            },
        )
    }

    suspend fun unlikeTrack(trackId: Long, permalinkUrl: String? = null) = withContext(Dispatchers.IO) {
        val uid = ensureUserId()
        val permalink = permalinkUrl?.takeIf { it.isNotBlank() }
            ?: runCatching { fetchTrack(trackId).permalinkUrl }.getOrNull()
        val notes = mutableListOf<String>()

        val bridge = webBridge
        if (bridge != null && !permalink.isNullOrBlank()) {
            val res = runCatching { bridge.unlikeViaTrackPage(permalink) }.getOrNull()
            if (res != null && res.status in 200..299) return@withContext
            if (res != null) notes += "page-unlike HTTP ${res.status} ${res.body.take(60)}"
        }

        val attempts = listOf(
            MutateAttempt("DELETE", "/users/$uid/track_likes/$trackId"),
            MutateAttempt("DELETE", "/me/track_likes/$trackId"),
            MutateAttempt("DELETE", "/users/$uid/likes/tracks/$trackId"),
        )
        var lastHttp: String? = null
        for (attempt in attempts) {
            val result = runCatching { scMutate(attempt.method, attempt.endpoint, attempt.publicApi) }
            if (result.isSuccess) return@withContext
            lastHttp = result.exceptionOrNull()?.message
            notes += "okhttp ${attempt.endpoint}: ${lastHttp ?: "fail"}"
        }
        error("Unlike failed · " + notes.joinToString(" | ").ifBlank { lastHttp ?: "unknown" })
    }

    fun attachWebHost(activity: android.app.Activity) {
        webBridge?.attachHost(activity)
    }

    /** Warm the hidden WebView after login so the first like is faster. */
    suspend fun warmWebSession() {
        runCatching { webBridge?.ensureReady() }
    }

    private data class MutateAttempt(
        val method: String,
        val endpoint: String,
        val publicApi: Boolean = false,
    )

    private fun scMutateFirstSuccess(attempts: List<MutateAttempt>, action: String) {
        var lastError: String? = null
        for (attempt in attempts) {
            val result = runCatching { scMutate(attempt.method, attempt.endpoint, attempt.publicApi) }
            if (result.isSuccess) return
            lastError = result.exceptionOrNull()?.message
            // Auth/captcha — don't burn through every endpoint
            if (lastError?.contains("CAPTCHA", ignoreCase = true) == true ||
                lastError?.contains("403") == true && lastError?.contains("captcha", ignoreCase = true) == true
            ) {
                break
            }
        }
        error(lastError ?: "SoundCloud $action failed")
    }

    /** progressive → streams http_mp3_128 → HLS */
    suspend fun resolveStreamUrl(track: ScTrack): String = withContext(Dispatchers.IO) {
        val progressive = track.progressiveTranscodingUrl
        if (!progressive.isNullOrBlank()) {
            runCatching {
                resolveTranscodingUrl(progressive, track.trackAuthorization)
            }.getOrNull()?.takeIf { it.isNotBlank() }?.let { return@withContext it }
        }

        runCatching { getTrackStreams(track.id) }.getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?.let { return@withContext it }

        val hls = track.hlsTranscodingUrl
        if (!hls.isNullOrBlank()) {
            runCatching {
                resolveTranscodingUrl(hls, track.trackAuthorization)
            }.getOrNull()?.takeIf { it.isNotBlank() }?.let { return@withContext it }
        }

        // Fresh track fetch in case list payload omitted media.transcodings
        val fresh = fetchTrack(track.id)
        val freshProgressive = fresh.progressiveTranscodingUrl
        if (!freshProgressive.isNullOrBlank()) {
            runCatching {
                resolveTranscodingUrl(freshProgressive, fresh.trackAuthorization)
            }.getOrNull()?.takeIf { it.isNotBlank() }?.let { return@withContext it }
        }
        runCatching { getTrackStreams(track.id) }.getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?.let { return@withContext it }
        val freshHls = fresh.hlsTranscodingUrl
        if (!freshHls.isNullOrBlank()) {
            runCatching {
                resolveTranscodingUrl(freshHls, fresh.trackAuthorization)
            }.getOrNull()?.takeIf { it.isNotBlank() }?.let { return@withContext it }
        }

        error("No streamable URL for “${track.title}”")
    }

    suspend fun getTrackStreams(trackId: Long): String? = withContext(Dispatchers.IO) {
        val json = scGet("/tracks/$trackId/streams")
        json.optString("http_mp3_128_url").takeIf { it.isNotBlank() }
            ?: json.optString("hls_mp3_128_url").takeIf { it.isNotBlank() }
    }

    suspend fun resolveTranscodingUrl(
        transcodingApiUrl: String,
        trackAuthorization: String?,
    ): String = withContext(Dispatchers.IO) {
        val extra = mutableMapOf<String, String>()
        if (!trackAuthorization.isNullOrBlank()) {
            extra["track_authorization"] = trackAuthorization
        }
        val json = scGetAbsoluteOrEndpoint(transcodingApiUrl, extra)
        json.optString("url").takeIf { it.isNotBlank() }
            ?: error("Transcoding returned no URL")
    }

    suspend fun fetchTrack(trackId: Long): ScTrack = withContext(Dispatchers.IO) {
        val json = scGet("/tracks/$trackId")
        parseTrack(json) ?: error("Track $trackId not found")
    }

    suspend fun fetchNextTracks(nextHref: String): ScPage<ScTrack> = withContext(Dispatchers.IO) {
        val json = scGetAbsolute(nextHref)
        // next_href pages can be stream, likes, or search — try all shapes
        when {
            json.has("collection") && json.optJSONArray("collection")
                ?.optJSONObject(0)?.has("track") == true -> parseLikedTracksPage(json)
            json.has("collection") && json.optJSONArray("collection")
                ?.optJSONObject(0)?.has("type") == true -> parseStreamPage(json)
            else -> parseSearchPage(json)
        }
    }

    private suspend fun ensureUserId(): Long {
        val cached = store.userId
        if (cached != 0L) return cached
        return getMe().first
    }

    private fun scGet(endpoint: String, extra: Map<String, String> = emptyMap()): JSONObject {
        val url = buildUrl("$API_BASE$endpoint", extra)
        return executeGet(url)
    }

    private fun scGetAbsolute(href: String): JSONObject {
        return scGetAbsoluteOrEndpoint(href, emptyMap())
    }

    private fun scGetAbsoluteOrEndpoint(
        hrefOrPath: String,
        extra: Map<String, String>,
    ): JSONObject {
        val base = if (hrefOrPath.startsWith("http")) {
            hrefOrPath
        } else {
            "$API_BASE$hrefOrPath"
        }
        val builder = base.toHttpUrl().newBuilder()
        val clientId = store.clientId.orEmpty()
        val token = store.oauthToken.orEmpty()
        if (builder.build().queryParameter("client_id").isNullOrBlank() && clientId.isNotBlank()) {
            builder.setQueryParameter("client_id", clientId)
        }
        if (builder.build().queryParameter("oauth_token").isNullOrBlank() && token.isNotBlank()) {
            builder.setQueryParameter("oauth_token", token)
        }
        builder.setQueryParameter("app_version", APP_VERSION)
        builder.setQueryParameter("app_locale", "en")
        extra.forEach { (k, v) -> builder.setQueryParameter(k, v) }
        return executeGet(builder.build().toString())
    }

    private fun buildUrl(base: String, extra: Map<String, String>): String {
        val clientId = store.clientId ?: error("Missing SoundCloud client_id")
        val builder = base.toHttpUrl().newBuilder()
            .setQueryParameter("client_id", clientId)
            .setQueryParameter("app_version", APP_VERSION)
            .setQueryParameter("app_locale", "en")
            .setQueryParameter("linked_partitioning", "1")
        store.oauthToken?.let { builder.setQueryParameter("oauth_token", it) }
        extra.forEach { (k, v) -> builder.setQueryParameter(k, v) }
        return builder.build().toString()
    }

    private fun executeGet(url: String): JSONObject {
        val req = Request.Builder()
            .url(url)
            .header("Accept", "application/json; charset=utf-8")
            .header(
                "User-Agent",
                "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36",
            )
            .apply {
                store.oauthToken?.let { header("Authorization", "OAuth $it") }
            }
            .get()
            .build()

        http.newCall(req).execute().use { res ->
            val body = res.body?.string().orEmpty()
            if (res.code == 401 || res.code == 403) {
                onLog?.invoke(
                    com.nexus.spotifydesktop.data.ConsoleLevel.Warn,
                    "SoundCloud",
                    "HTTP ${res.code} · auth failed",
                )
                error("SoundCloud auth failed (${res.code}) — reconnect in Settings")
            }
            if (res.code == 429) {
                onLog?.invoke(
                    com.nexus.spotifydesktop.data.ConsoleLevel.RateLimit,
                    "SoundCloud",
                    "HTTP 429 · rate limited",
                )
            }
            if (!res.isSuccessful) {
                onLog?.invoke(
                    com.nexus.spotifydesktop.data.ConsoleLevel.Error,
                    "SoundCloud",
                    "HTTP ${res.code}: ${body.take(100)}",
                )
                error("SoundCloud ${res.code}: ${body.take(180)}")
            }
            return JSONObject(body.ifBlank { "{}" })
        }
    }

    private fun scMutate(method: String, endpoint: String, publicApi: Boolean = false) {
        val host = if (publicApi) "https://api.soundcloud.com" else API_BASE
        val url = if (publicApi) {
            val builder = "$host$endpoint".toHttpUrl().newBuilder()
            store.clientId?.let { builder.setQueryParameter("client_id", it) }
            builder.build().toString()
        } else {
            buildUrl("$host$endpoint", emptyMap())
        }

        val jsonBody = "{}".toRequestBody("application/json; charset=utf-8".toMediaType())
        val reqBuilder = Request.Builder()
            .url(url)
            .header("Accept", "application/json, text/javascript, */*; q=0.01")
            .header(
                "User-Agent",
                "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36",
            )
            .header("Origin", "https://soundcloud.com")
            .header("Referer", "https://soundcloud.com/")
            .header("X-Requested-With", "XMLHttpRequest")
            .apply {
                store.oauthToken?.let { header("Authorization", "OAuth $it") }
                cookieHeader()?.let { header("Cookie", it) }
            }

        val req = when (method.uppercase()) {
            // SoundCloud web sends PUT with JSON content-type (empty object)
            "PUT" -> reqBuilder.put(jsonBody).build()
            "POST" -> reqBuilder.post(jsonBody).build()
            "DELETE" -> reqBuilder.delete().build()
            else -> error("Unsupported $method")
        }

        http.newCall(req).execute().use { res ->
            val body = res.body?.string().orEmpty()
            if (res.isSuccessful || res.code == 201 || res.code == 204) return
            // Already liked / conflict — treat as success
            if (res.code == 409 || res.code == 422) return

            val captcha = parseCaptchaUrl(body)
            if ((res.code == 403 || res.code == 401) && captcha != null) {
                throw SoundCloudCaptchaNeeded(captcha)
            }
            // Body may still embed captcha URL on other codes
            if (captcha != null) {
                throw SoundCloudCaptchaNeeded(captcha)
            }
            if (res.code == 401 || res.code == 403) {
                error("SoundCloud $method failed (${res.code}): ${body.take(120)}")
            }
            error("SoundCloud $method failed (${res.code}): ${body.take(180)}")
        }
    }

    private fun cookieHeader(): String? {
        val cm = android.webkit.CookieManager.getInstance()
        val parts = listOf(
            cm.getCookie("https://soundcloud.com"),
            cm.getCookie("https://api-v2.soundcloud.com"),
            cm.getCookie("https://api.soundcloud.com"),
        ).filterNotNull()
        if (parts.isEmpty()) return null
        // Deduplicate cookie pairs
        val map = linkedMapOf<String, String>()
        for (chunk in parts) {
            for (pair in chunk.split(';')) {
                val trimmed = pair.trim()
                val eq = trimmed.indexOf('=')
                if (eq > 0) {
                    map[trimmed.substring(0, eq)] = trimmed.substring(eq + 1)
                }
            }
        }
        return map.entries.joinToString("; ") { "${it.key}=${it.value}" }.takeIf { it.isNotBlank() }
    }

    private fun parseCaptchaUrl(body: String): String? {
        if (body.isBlank()) return null
        // JSON { "url": "https://geo.captcha-delivery.com/..." }
        runCatching {
            val json = JSONObject(body)
            val url = json.optString("url")
            if (url.contains("captcha-delivery.com")) return url
        }
        // Sometimes nested or plain text
        val m = Regex("""https?://[^"'\s]*captcha-delivery\.com[^"'\s]*""")
            .find(body)
        return m?.value
    }

    private fun parseStreamPage(json: JSONObject): ScPage<ScTrack> {
        val arr = json.optJSONArray("collection") ?: JSONArray()
        val tracks = buildList {
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                parseTrack(item.optJSONObject("track"))?.let(::add)
            }
        }
        return ScPage(tracks, json.optString("next_href").takeIf { it.isNotBlank() })
    }

    private fun parseLikedTracksPage(json: JSONObject): ScPage<ScTrack> {
        val arr = json.optJSONArray("collection") ?: JSONArray()
        val tracks = buildList {
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                val created = item.optString("created_at").takeIf { it.isNotBlank() }
                parseTrack(item.optJSONObject("track"))?.copy(likedAtIso = created)?.let(::add)
            }
        }
        return ScPage(tracks, json.optString("next_href").takeIf { it.isNotBlank() })
    }

    private fun parseSearchPage(json: JSONObject): ScPage<ScTrack> {
        val arr = json.optJSONArray("collection") ?: JSONArray()
        val tracks = buildList {
            for (i in 0 until arr.length()) {
                parseTrack(arr.optJSONObject(i))?.let(::add)
            }
        }
        return ScPage(tracks, json.optString("next_href").takeIf { it.isNotBlank() })
    }

    /** User uploads: bare tracks, nested `track`, or id-only stubs. */
    private fun parseUserTracksPage(json: JSONObject): ScPage<ScTrack> {
        val arr = json.optJSONArray("collection") ?: JSONArray()
        val tracks = buildList {
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                val track = parseTrack(item) ?: parseTrack(item.optJSONObject("track"))
                if (track != null) add(track)
            }
        }
        return ScPage(tracks, json.optString("next_href").takeIf { it.isNotBlank() })
    }

    private fun extractTrackIds(json: JSONObject): List<Long> {
        val arr = json.optJSONArray("collection") ?: return emptyList()
        return buildList {
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                val id = jsonLongId(item)
                    ?: item.optJSONObject("track")?.let { jsonLongId(it) }
                if (id != null && id > 0L) add(id)
            }
        }.distinct()
    }

    private fun jsonLongId(obj: JSONObject): Long? {
        val direct = obj.optLong("id")
        if (direct != 0L) return direct
        val asString = obj.optString("id").takeIf { it.isNotBlank() }?.toLongOrNull()
        if (asString != null && asString != 0L) return asString
        val urn = obj.optString("urn").ifBlank { obj.optString("uri") }
        val fromUrn = Regex("""(?:users|tracks):(\d+)""").find(urn)?.groupValues?.getOrNull(1)?.toLongOrNull()
        return fromUrn?.takeIf { it > 0L }
    }

    private fun extractSoundCloudUsername(permalinkUrl: String): String? {
        val path = permalinkUrl
            .substringAfter("soundcloud.com/", "")
            .substringBefore('?')
            .trim('/')
        if (path.isBlank()) return null
        val first = path.split('/').firstOrNull()?.trim().orEmpty()
        if (first.isBlank() || first.equals("you", ignoreCase = true)) return null
        return first
    }

    private fun parseUserSearch(json: JSONObject): List<com.nexus.spotifydesktop.data.ScSearchUser> {
        val arr = json.optJSONArray("collection") ?: return emptyList()
        return buildList {
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val id = o.optLong("id")
                if (id == 0L) continue
                val name = o.optString("username").ifBlank {
                    o.optString("full_name")
                }.ifBlank { "User" }
                add(
                    com.nexus.spotifydesktop.data.ScSearchUser(
                        id = id,
                        name = name,
                        avatarUrl = scArtworkLarge(o.optString("avatar_url").takeIf { it.isNotBlank() }),
                    ),
                )
            }
        }
    }

    private fun parsePlaylistSearch(json: JSONObject): List<ScPlaylist> {
        val arr = json.optJSONArray("collection") ?: return emptyList()
        return buildList {
            for (i in 0 until arr.length()) {
                parsePlaylist(arr.optJSONObject(i))?.let(::add)
            }
        }
    }

    private fun parsePlaylistsPage(json: JSONObject): ScPage<ScPlaylist> {
        val arr = json.optJSONArray("collection") ?: JSONArray()
        val lists = buildList {
            for (i in 0 until arr.length()) {
                parsePlaylist(arr.optJSONObject(i))?.let(::add)
            }
        }
        return ScPage(lists, json.optString("next_href").takeIf { it.isNotBlank() })
    }

    private fun parsePlaylist(obj: JSONObject?): ScPlaylist? {
        if (obj == null) return null
        val id = obj.optLong("id")
        if (id == 0L) return null
        val user = obj.optJSONObject("user")
        return ScPlaylist(
            id = id,
            title = obj.optString("title").ifBlank { "Playlist" },
            artist = user?.optString("username").orEmpty(),
            artworkUrl = scArtworkLarge(
                obj.optString("artwork_url").takeIf { it.isNotBlank() }
                    ?: user?.optString("avatar_url"),
            ),
            permalinkUrl = obj.optString("permalink_url"),
            trackCount = obj.optInt("track_count", obj.optJSONArray("tracks")?.length() ?: 0),
            durationMs = obj.optLong("duration"),
        )
    }

    private fun parseTrack(obj: JSONObject?): ScTrack? {
        if (obj == null) return null
        // Incomplete playlist track stubs only have id — skip until hydrated
        if (!obj.has("title") && !obj.has("user")) return null
        val id = obj.optLong("id")
        if (id == 0L) return null
        val user = obj.optJSONObject("user")
        val media = obj.optJSONObject("media")
        val transcodings = media?.optJSONArray("transcodings")
        var progressive: String? = null
        var hls: String? = null
        if (transcodings != null) {
            for (i in 0 until transcodings.length()) {
                val t = transcodings.optJSONObject(i) ?: continue
                val url = t.optString("url").takeIf { it.isNotBlank() } ?: continue
                val protocol = t.optJSONObject("format")?.optString("protocol").orEmpty()
                when (protocol) {
                    "progressive" -> if (progressive == null) progressive = url
                    "hls" -> if (hls == null && !t.optBoolean("snipped")) hls = url
                }
            }
            if (hls == null) {
                for (i in 0 until transcodings.length()) {
                    val t = transcodings.optJSONObject(i) ?: continue
                    if (t.optJSONObject("format")?.optString("protocol") == "hls") {
                        hls = t.optString("url").takeIf { it.isNotBlank() }
                        break
                    }
                }
            }
        }
        val userId = user?.let { jsonLongId(it) }
            ?: obj.optLong("user_id").takeIf { it != 0L }
        return ScTrack(
            id = id,
            title = obj.optString("title").ifBlank { "Track" },
            artist = user?.optString("username").orEmpty()
                .ifBlank { user?.optString("permalink").orEmpty() },
            artworkUrl = scArtworkLarge(
                obj.optString("artwork_url").takeIf { it.isNotBlank() }
                    ?: user?.optString("avatar_url"),
            ),
            permalinkUrl = obj.optString("permalink_url"),
            durationMs = obj.optLong("duration"),
            likesCount = obj.optInt("likes_count"),
            playbackCount = obj.optInt("playback_count"),
            trackAuthorization = obj.optString("track_authorization").takeIf { it.isNotBlank() },
            progressiveTranscodingUrl = progressive,
            hlsTranscodingUrl = hls,
            userId = userId?.takeIf { it > 0L },
        )
    }
}
