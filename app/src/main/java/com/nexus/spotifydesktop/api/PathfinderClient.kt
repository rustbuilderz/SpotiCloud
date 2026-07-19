package com.nexus.spotifydesktop.api

import com.nexus.spotifydesktop.auth.TokenStore
import com.nexus.spotifydesktop.auth.WebPlayerBridge
import com.nexus.spotifydesktop.data.LibraryItem
import com.nexus.spotifydesktop.data.SearchAlbum
import com.nexus.spotifydesktop.data.SearchArtist
import com.nexus.spotifydesktop.data.SpotifySearchResults
import com.nexus.spotifydesktop.data.TrackItem
import com.nexus.spotifydesktop.data.parseIsoToEpochMs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.Base64

/** Pathfinder GraphQL via WebPlayerBridge (same stack as open.spotify.com). */
class PathfinderClient(
    private val tokenStore: TokenStore,
    private val bridge: WebPlayerBridge,
) {
    data class Profile(val id: String, val name: String)

    fun invalidateClientToken() {
        bridge.invalidate()
    }

    suspend fun profile(): Profile = withContext(Dispatchers.IO) {
        val access = bridge.tokens().access
        decodeProfileFromJwt(access) ?: Profile(id = "me", name = tokenStore.userName ?: "Spotify")
    }

    suspend fun loadLibrary(): Pair<List<LibraryItem>, String?> = withContext(Dispatchers.IO) {
        val items = mutableListOf<LibraryItem>(LibraryItem.Liked)
        val warnings = mutableListOf<String>()

        items += loadFilterWithRetry("Playlists", warnings) { mapPlaylistItem(it) }
        items += loadFilterWithRetry("Albums", warnings) { mapAlbumItem(it) }

        finishLibrary(items, warnings)
    }

    private suspend fun loadFilterWithRetry(
        filterId: String,
        warnings: MutableList<String>,
        map: (JSONObject) -> LibraryItem?,
    ): List<LibraryItem> {
        var lastError: Exception? = null
        repeat(3) { attempt ->
            try {
                val page = fetchLibraryFilter(filterId, map)
                if (page.isEmpty() && attempt < 2) {
                    // Tokens / SPA may still be waking up
                    delay(800L * (attempt + 1))
                    return@repeat
                }
                return page
            } catch (e: Exception) {
                lastError = e
                delay(700L * (attempt + 1))
            }
        }
        warnings += lastError?.message ?: "$filterId failed"
        return emptyList()
    }

    private fun finishLibrary(
        items: List<LibraryItem>,
        warnings: MutableList<String>,
    ): Pair<List<LibraryItem>, String?> {
        val dedup = LinkedHashMap<String, LibraryItem>()
        for (item in items) dedup[item.id] = item
        val list = dedup.values.toList()
        if (list.size <= 1) {
            warnings += "Only Liked Songs loaded — tap Retry library"
        }
        return list to warnings.takeIf { it.isNotEmpty() }?.joinToString(" · ")
    }

    suspend fun likedTracks(onPage: (List<TrackItem>) -> Unit): List<TrackItem> =
        withContext(Dispatchers.IO) {
            fetchLikedTracks(onPage)
        }

    /** First N Liked Songs for Home (does not paginate the full library). */
    suspend fun likedTracksPreview(limit: Int = 15): List<TrackItem> =
        withContext(Dispatchers.IO) {
            fetchLikedTracksPage(offset = 0, limit = limit.coerceIn(1, 50)).first.take(limit)
        }

    suspend fun playlistTracks(playlistId: String, onPage: (List<TrackItem>) -> Unit): List<TrackItem> =
        withContext(Dispatchers.IO) {
            fetchPlaylistTracks("spotify:playlist:$playlistId", onPage)
        }

    suspend fun albumTracks(albumId: String, onPage: (List<TrackItem>) -> Unit): List<TrackItem> =
        withContext(Dispatchers.IO) {
            fetchAlbumTracks(albumId, onPage)
        }

    suspend fun search(query: String): SpotifySearchResults = withContext(Dispatchers.IO) {
        val q = query.trim()
        if (q.isEmpty()) return@withContext SpotifySearchResults()
        val json = pathfinderSearch(q)
        val root = json.optJSONObject("data")?.optJSONObject("searchV2")
            ?: return@withContext SpotifySearchResults()
        val songs = parseSearchTracks(root).toMutableList()
        val artists = parseSearchArtists(root).toMutableList()
        val albums = parseSearchAlbums(root).toMutableList()
        mergeTopResults(root, songs, artists, albums)
        SpotifySearchResults(
            songs = songs.distinctBy { it.id },
            artists = artists.distinctBy { it.id },
            albums = albums.distinctBy { it.id },
        )
    }

    suspend fun searchTracks(query: String): List<TrackItem> =
        search(query).songs

    // searchDesktop hashes rotate — try known ones in order
    private suspend fun pathfinderSearch(q: String): JSONObject {
        var last: Exception? = null
        for (hash in SEARCH_HASHES) {
            try {
                val body = JSONObject()
                    .put("operationName", "searchDesktop")
                    .put(
                        "variables",
                        JSONObject()
                            .put("searchTerm", q)
                            .put("offset", 0)
                            .put("limit", 20)
                            .put("numberOfTopResults", 10)
                            .put("includeAudiobooks", false)
                            .put("includeArtistHasConcertsField", false)
                            .put("includePreReleases", false)
                            .put("includeAuthors", false),
                    )
                    .put(
                        "extensions",
                        JSONObject().put(
                            "persistedQuery",
                            JSONObject().put("version", 1).put("sha256Hash", hash),
                        ),
                    )
                return pathfinder(body)
            } catch (e: Exception) {
                last = e
            }
        }
        throw last ?: error("Search failed")
    }
    private fun parseSearchTracks(root: JSONObject): List<TrackItem> {
        val items = root.optJSONObject("tracksV2")?.optJSONArray("items")
            ?: root.optJSONObject("tracks")?.optJSONArray("items")
            ?: return emptyList()
        return buildList {
            for (i in 0 until items.length()) {
                val row = items.optJSONObject(i) ?: continue
                val track = unwrapSearchEntity(row)
                    ?: continue
                parseTrackData(track)?.let(::add)
            }
        }
    }

    private fun parseSearchArtists(root: JSONObject): List<SearchArtist> {
        val items = root.optJSONObject("artists")?.optJSONArray("items") ?: return emptyList()
        return buildList {
            for (i in 0 until items.length()) {
                val data = unwrapSearchEntity(items.optJSONObject(i) ?: continue) ?: continue
                parseSearchArtist(data)?.let(::add)
            }
        }
    }

    private fun parseSearchAlbums(root: JSONObject): List<SearchAlbum> {
        val items = root.optJSONObject("albums")?.optJSONArray("items")
            ?: root.optJSONObject("albumsV2")?.optJSONArray("items")
            ?: return emptyList()
        return buildList {
            for (i in 0 until items.length()) {
                val data = unwrapSearchEntity(items.optJSONObject(i) ?: continue) ?: continue
                parseSearchAlbum(data)?.let(::add)
            }
        }
    }

    /** searchV2 rows are `{ data }` or `{ item: { data } }`. */
    private fun unwrapSearchEntity(row: JSONObject): JSONObject? {
        row.optJSONObject("data")?.let { return it }
        row.optJSONObject("item")?.optJSONObject("data")?.let { return it }
        row.optJSONObject("item")?.let { return it }
        return null
    }

    private fun parseSearchArtist(data: JSONObject): SearchArtist? {
        val uri = data.optString("uri")
        val id = when {
            uri.startsWith("spotify:artist:") -> uri.removePrefix("spotify:artist:")
            else -> data.optString("id")
        }
        val name = data.optJSONObject("profile")?.optString("name")
            ?: data.optString("name")
            ?: data.optString("displayName")
        if (id.isBlank() || name.isBlank()) return null
        val image = data.optJSONObject("visuals")
            ?.optJSONObject("avatarImage")
            ?.optJSONArray("sources")
            ?.optJSONObject(0)
            ?.optString("url")
            ?: data.optJSONObject("avatar")?.optJSONArray("sources")?.optJSONObject(0)?.optString("url")
            ?: data.optJSONArray("images")?.optJSONObject(0)?.optString("url")
        return SearchArtist(id = id, name = name, imageUrl = image?.takeIf { it.isNotBlank() })
    }

    private fun parseSearchAlbum(data: JSONObject): SearchAlbum? {
        val uri = data.optString("uri")
        val id = when {
            uri.startsWith("spotify:album:") -> uri.removePrefix("spotify:album:")
            else -> data.optString("id")
        }
        val name = data.optString("name")
        if (id.isBlank() || name.isBlank()) return null
        val artistsArr = data.optJSONObject("artists")?.optJSONArray("items")
            ?: data.optJSONArray("artists")
        val artistNames = buildList {
            if (artistsArr != null) {
                for (j in 0 until artistsArr.length()) {
                    val a = artistsArr.optJSONObject(j) ?: continue
                    val n = a.optJSONObject("profile")?.optString("name")
                        ?: a.optString("name")
                    if (!n.isNullOrBlank()) add(n)
                }
            }
        }.joinToString(", ")
        val image = data.optJSONObject("coverArt")?.optJSONArray("sources")
            ?.optJSONObject(0)?.optString("url")
            ?: data.optJSONArray("images")?.optJSONObject(0)?.optString("url")
        return SearchAlbum(
            id = id,
            name = name,
            artists = artistNames,
            imageUrl = image?.takeIf { it.isNotBlank() },
            uri = uri.ifBlank { "spotify:album:$id" },
        )
    }

    private fun mergeTopResults(
        root: JSONObject,
        songs: MutableList<TrackItem>,
        artists: MutableList<SearchArtist>,
        albums: MutableList<SearchAlbum>,
    ) {
        val items = root.optJSONObject("topResults")?.optJSONArray("itemsV2")
            ?: root.optJSONObject("topResults")?.optJSONArray("items")
            ?: return
        for (i in 0 until items.length()) {
            val data = unwrapSearchEntity(items.optJSONObject(i) ?: continue) ?: continue
            val uri = data.optString("uri")
            val type = data.optString("__typename")
            when {
                type.contains("Track", ignoreCase = true) || uri.startsWith("spotify:track:") ->
                    parseTrackData(data)?.let { t -> if (songs.none { it.id == t.id }) songs += t }
                type.contains("Artist", ignoreCase = true) || uri.startsWith("spotify:artist:") ->
                    parseSearchArtist(data)?.let { a -> if (artists.none { it.id == a.id }) artists += a }
                type.contains("Album", ignoreCase = true) || uri.startsWith("spotify:album:") ->
                    parseSearchAlbum(data)?.let { a -> if (albums.none { it.id == a.id }) albums += a }
            }
        }
    }

    /**
     * Artist Popular / Top tracks (official-app style). Tries Pathfinder overview,
     * falls back to search filtered by artist name.
     */
    suspend fun artistTopTracks(
        artistId: String? = null,
        artistName: String,
        limit: Int = 10,
    ): List<TrackItem> = withContext(Dispatchers.IO) {
        val id = artistId?.takeIf { it.isNotBlank() }
            ?: resolveArtistId(artistName)
            ?: error("Couldn't find artist “$artistName”")
        val fromOverview = runCatching { fetchArtistTopTracksPathfinder(id, limit) }.getOrNull()
        if (!fromOverview.isNullOrEmpty()) return@withContext fromOverview.take(limit)
        // Fallback: search tracks by artist name
        val q = if (artistName.contains(' ')) "artist:\"$artistName\"" else "artist:$artistName"
        searchTracks(q).take(limit).ifEmpty {
            searchTracks(artistName).filter {
                it.artists.contains(artistName, ignoreCase = true)
            }.take(limit)
        }.also {
            if (it.isEmpty()) error("No top tracks for $artistName")
        }
    }

    private suspend fun resolveArtistId(name: String): String? {
        val q = name.trim()
        if (q.isEmpty()) return null
        val json = runCatching { pathfinderSearch(q) }.getOrNull() ?: return null
        val root = json.optJSONObject("data")?.optJSONObject("searchV2") ?: return null
        return parseSearchArtists(root).firstOrNull()?.id
    }

    private suspend fun fetchArtistTopTracksPathfinder(artistId: String, limit: Int): List<TrackItem> {
        val uri = if (artistId.startsWith("spotify:artist:")) artistId else "spotify:artist:$artistId"
        var lastError: Exception? = null
        for (hash in ARTIST_OVERVIEW_HASHES) {
            try {
                val body = JSONObject()
                    .put("operationName", "queryArtistOverview")
                    .put(
                        "variables",
                        JSONObject()
                            .put("uri", uri)
                            .put("locale", "")
                            .put("includePrerelease", false),
                    )
                    .put(
                        "extensions",
                        JSONObject().put(
                            "persistedQuery",
                            JSONObject().put("version", 1).put("sha256Hash", hash),
                        ),
                    )
                val json = pathfinder(body)
                val tracks = parseArtistTopTracks(json, limit)
                if (tracks.isNotEmpty()) return tracks
            } catch (e: Exception) {
                lastError = e
            }
        }
        throw lastError ?: error("Artist overview returned no tracks")
    }

    private fun parseArtistTopTracks(json: JSONObject, limit: Int): List<TrackItem> {
        val root = json.optJSONObject("data") ?: return emptyList()
        val artist = root.optJSONObject("artistUnion")
            ?: root.optJSONObject("artist")
            ?: return emptyList()
        val candidates = listOfNotNull(
            artist.optJSONObject("discography")?.optJSONObject("topTracks")?.optJSONArray("items"),
            artist.optJSONObject("discography")?.optJSONObject("popularReleasesAlbums")?.optJSONArray("items"),
            artist.optJSONObject("relatedContent")?.optJSONObject("topTracks")?.optJSONArray("items"),
            artist.optJSONArray("topTracks"),
        )
        val out = mutableListOf<TrackItem>()
        for (arr in candidates) {
            for (i in 0 until arr.length()) {
                if (out.size >= limit) return out
                val item = arr.optJSONObject(i) ?: continue
                val trackObj = item.optJSONObject("track")
                    ?: item.optJSONObject("data")
                    ?: item.optJSONObject("item")?.optJSONObject("data")
                    ?: item
                // popularReleasesAlbums are albums — skip non-tracks
                val uri = trackObj.optString("uri")
                if (uri.isNotBlank() && !uri.startsWith("spotify:track:")) continue
                parseTrackData(trackObj)?.let(out::add)
            }
            if (out.isNotEmpty()) return out
        }
        // Deep scan for track uris (hash shapes drift)
        collectTracksDeep(artist, out, limit)
        return out
    }

    private fun collectTracksDeep(node: Any?, out: MutableList<TrackItem>, limit: Int) {
        if (out.size >= limit || node == null) return
        when (node) {
            is JSONObject -> {
                val uri = node.optString("uri")
                if (uri.startsWith("spotify:track:")) {
                    parseTrackData(node)?.let { t ->
                        if (out.none { it.id == t.id }) out += t
                    }
                }
                val keys = node.keys()
                while (keys.hasNext()) {
                    collectTracksDeep(node.opt(keys.next()), out, limit)
                    if (out.size >= limit) return
                }
            }
            is JSONArray -> {
                for (i in 0 until node.length()) {
                    collectTracksDeep(node.opt(i), out, limit)
                    if (out.size >= limit) return
                }
            }
        }
    }

    suspend fun setLiked(trackId: String, liked: Boolean) = withContext(Dispatchers.IO) {
        val profile = profile()
        val method = if (liked) "PUT" else "DELETE"
        val url =
            "https://spclient.wg.spotify.com/collection/v1/collection/${profile.id}/spotify:track:$trackId"
        val res = bridge.fetch(url, method, body = if (liked) "" else null)
        if (res.status !in 200..299 && res.status != 204) {
            error("Like failed (${res.status}): ${res.body.take(120)}")
        }
    }

    suspend fun isLiked(trackId: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val profile = profile()
            val url =
                "https://spclient.wg.spotify.com/collection/v1/contains/${profile.id}?uri=spotify:track:$trackId"
            val res = bridge.fetch(url, "GET")
            if (res.status !in 200..299) return@withContext false
            val text = res.body
            when {
                text.trim().startsWith("[") -> JSONArray(text).optBoolean(0)
                else -> JSONObject(text).optJSONArray("results")?.optBoolean(0) == true
            }
        }.getOrDefault(false)
    }

    private suspend fun fetchLibraryFilter(
        filterId: String,
        map: (JSONObject) -> LibraryItem?,
    ): List<LibraryItem> {
        val out = mutableListOf<LibraryItem>()
        var offset = 0
        var total = Int.MAX_VALUE
        var guard = 0
        var rawSeen = 0
        while (offset < total && guard < 40) {
            guard++
            val body = JSONObject()
                .put("operationName", "libraryV3")
                .put(
                    "variables",
                    JSONObject()
                        .put("filters", JSONArray().put(filterId))
                        .put("order", JSONObject.NULL)
                        .put("textFilter", "")
                        .put(
                            "features",
                            JSONArray()
                                .put("LIKED_SONGS")
                                .put("YOUR_EPISODES_V2")
                                .put("PRERELEASES")
                                .put("EVENTS"),
                        )
                        .put("limit", 50)
                        .put("offset", offset)
                        // flatten=true returns leaf playlists/albums inside folders.
                        // Without it we only see root folders → "Liked Songs" only.
                        .put("flatten", true)
                        .put("expandedFolders", JSONArray())
                        .put("folderUri", JSONObject.NULL)
                        .put("includeFoldersWhenFlattening", false),
                )
                .put(
                    "extensions",
                    JSONObject().put(
                        "persistedQuery",
                        JSONObject().put("version", 1).put("sha256Hash", HASH_LIBRARY_V3),
                    ),
                )
            val json = pathfinder(body)
            val lib = json.optJSONObject("data")?.optJSONObject("me")?.optJSONObject("libraryV3")
                ?: error("Empty library fetch (filter $filterId) — Spotify returned no data")
            if (lib.optString("__typename") != "LibraryPage") {
                error("libraryV3: ${lib.optString("message").ifBlank { lib.toString().take(120) }}")
            }
            if (lib.has("totalCount")) total = lib.optInt("totalCount")
            val items = lib.optJSONArray("items") ?: JSONArray()
            if (items.length() == 0) break
            for (i in 0 until items.length()) {
                val row = items.optJSONObject(i) ?: continue
                rawSeen++
                map(row)?.let(out::add)
            }
            offset += 50
            delay(750)
        }
        if (rawSeen > 0 && out.isEmpty()) {
            error(
                "Got $rawSeen $filterId items from Spotify but couldn't parse them — " +
                    "library response shape may have changed",
            )
        }
        return out
    }

    private fun mapPlaylistItem(row: JSONObject): LibraryItem.Playlist? {
        val wrapper = row.optJSONObject("item") ?: return null
        val typeName = wrapper.optString("__typename")
        // PlaylistResponseWrapper + CollaborativePlaylist* etc.
        if (typeName.isNotBlank() &&
            typeName != "PlaylistResponseWrapper" &&
            !typeName.contains("Playlist", ignoreCase = true)
        ) {
            return null
        }
        val d = wrapper.optJSONObject("data") ?: return null
        val dataType = d.optString("__typename")
        if (dataType.isNotBlank() && dataType != "Playlist") return null
        val uri = wrapper.optString("_uri").ifBlank {
            d.optString("uri").ifBlank { wrapper.optString("uri") }
        }
        val id = uriToId(uri) ?: d.optString("id").ifBlank { null } ?: return null
        if (uri.isBlank()) return null
        val images = d.optJSONObject("images")?.optJSONArray("items")
            ?: d.optJSONObject("coverArt")?.optJSONArray("sources")
        val imageUrl = when {
            images != null && images.length() > 0 -> {
                val first = images.optJSONObject(0)
                first?.optString("url")?.takeIf { it.isNotBlank() }
                    ?: first?.optJSONArray("sources")?.optJSONObject(0)?.optString("url")
                    ?: first?.optJSONObject("sources")?.optJSONArray("items")
                        ?.optJSONObject(0)?.optString("url")
            }
            else -> d.optJSONObject("coverArt")?.optJSONArray("sources")
                ?.optJSONObject(0)?.optString("url")
        }
        return LibraryItem.Playlist(
            id = id,
            name = d.optString("name").ifBlank { "Playlist" },
            uri = uri,
            imageUrl = imageUrl,
        )
    }

    private fun mapAlbumItem(row: JSONObject): LibraryItem.Album? {
        val wrapper = row.optJSONObject("item") ?: return null
        val typeName = wrapper.optString("__typename")
        if (typeName.isNotBlank() &&
            typeName != "AlbumResponseWrapper" &&
            !typeName.contains("Album", ignoreCase = true)
        ) {
            return null
        }
        val d = wrapper.optJSONObject("data") ?: return null
        val uri = wrapper.optString("_uri").ifBlank {
            d.optString("uri").ifBlank { wrapper.optString("uri") }
        }
        val id = uriToId(uri) ?: return null
        if (uri.isBlank()) return null
        val imageUrl = d.optJSONObject("coverArt")?.optJSONArray("sources")
            ?.optJSONObject(0)?.optString("url")
            ?: d.optJSONObject("images")?.optJSONArray("items")
                ?.optJSONObject(0)?.optJSONArray("sources")
                ?.optJSONObject(0)?.optString("url")
        return LibraryItem.Album(
            id = "album:$id",
            name = d.optString("name").ifBlank { "Album" },
            uri = uri,
            imageUrl = imageUrl,
        )
    }

    private suspend fun fetchLikedTracks(
        onPage: (List<TrackItem>) -> Unit,
    ): List<TrackItem> {
        val out = mutableListOf<TrackItem>()
        var offset = 0
        var total = Int.MAX_VALUE
        var guard = 0
        while (offset < total && guard < 200) {
            guard++
            val (page, totalCount) = fetchLikedTracksPage(offset = offset, limit = 50)
            if (totalCount != null) total = totalCount
            if (page.isEmpty()) break
            out += page
            onPage(out.toList())
            offset += 50
            delay(100)
        }
        return out
    }

    /** @return tracks + optional totalCount from Spotify */
    private suspend fun fetchLikedTracksPage(
        offset: Int,
        limit: Int,
    ): Pair<List<TrackItem>, Int?> {
        val body = JSONObject()
            .put("operationName", "fetchLibraryTracks")
            .put(
                "variables",
                JSONObject()
                    .put("offset", offset)
                    .put("limit", limit),
            )
            .put(
                "extensions",
                JSONObject().put(
                    "persistedQuery",
                    JSONObject().put("version", 1).put("sha256Hash", HASH_LIBRARY_TRACKS),
                ),
            )
        val json = pathfinder(body)
        val tracks = json.optJSONObject("data")
            ?.optJSONObject("me")
            ?.optJSONObject("library")
            ?.optJSONObject("tracks")
            ?: error("Empty Liked Songs fetch — Spotify returned no data")
        val total = if (tracks.has("totalCount")) tracks.optInt("totalCount") else null
        val items = tracks.optJSONArray("items") ?: JSONArray()
        val out = mutableListOf<TrackItem>()
        for (i in 0 until items.length()) {
            val item = items.optJSONObject(i) ?: continue
            val wrapper = item.optJSONObject("track")
            val data = wrapper?.optJSONObject("data") ?: continue
            val uriOverride = wrapper.optString("_uri").ifBlank { wrapper.optString("uri") }
            if (!uriOverride.isNullOrBlank() && data.optString("uri").isBlank()) {
                data.put("uri", uriOverride)
            }
            val likedAtMs = parseLibraryAddedAtMs(item)
            parseTrackData(data, likedAtMs = likedAtMs)?.let(out::add)
        }
        return out to total
    }

    /** Liked Songs / library item timestamp when present. */
    private fun parseLibraryAddedAtMs(item: JSONObject): Long? {
        val iso = item.optJSONObject("addedAt")?.optString("isoString")?.takeIf { it.isNotBlank() }
            ?: item.optJSONObject("addedAt")?.optString("iso")?.takeIf { it.isNotBlank() }
            ?: item.optString("addedAt").takeIf { it.contains('T') }
            ?: item.optString("added_at").takeIf { it.isNotBlank() }
        return parseIsoToEpochMs(iso)
    }

    private suspend fun fetchPlaylistTracks(
        playlistUri: String,
        onPage: (List<TrackItem>) -> Unit,
    ): List<TrackItem> {
        val out = mutableListOf<TrackItem>()
        var offset = 0
        var total = Int.MAX_VALUE
        var guard = 0
        while (offset < total && guard < 80) {
            guard++
            val body = JSONObject()
                .put("operationName", "fetchPlaylist")
                .put(
                    "variables",
                    JSONObject()
                        .put("uri", playlistUri)
                        .put("offset", offset)
                        .put("limit", 50)
                        .put("enableWatchFeedEntrypoint", false),
                )
                .put(
                    "extensions",
                    JSONObject().put(
                        "persistedQuery",
                        JSONObject().put("version", 1).put("sha256Hash", HASH_PLAYLIST),
                    ),
                )
            val json = pathfinder(body)
            val playlist = json.optJSONObject("data")?.optJSONObject("playlistV2")
            val content = playlist?.optJSONObject("content")
                ?: error("Empty playlist/album fetch for $playlistUri — Spotify returned no data")
            // Restricted / not found playlists
            val typename = playlist.optString("__typename")
            if (typename.equals("NotFound", ignoreCase = true) ||
                typename.contains("Error", ignoreCase = true)
            ) {
                error("Can't open this playlist ($typename)")
            }
            if (content.has("totalCount")) total = content.optInt("totalCount")
            val items = content.optJSONArray("items") ?: JSONArray()
            if (items.length() == 0) break
            var parsedThisPage = 0
            for (i in 0 until items.length()) {
                val item = items.optJSONObject(i) ?: continue
                // Current web player uses itemV2; older responses used item
                val wrapper = item.optJSONObject("itemV2")
                    ?: item.optJSONObject("item")
                    ?: item.optJSONObject("track")
                val data = wrapper?.optJSONObject("data")
                    ?: item.optJSONObject("data")
                    ?: continue
                if (data.optString("__typename").equals("NotFound", ignoreCase = true)) continue
                val uriOverride = wrapper?.optString("_uri")?.ifBlank { null }
                    ?: wrapper?.optString("uri")?.ifBlank { null }
                if (!uriOverride.isNullOrBlank() && data.optString("uri").isBlank()) {
                    data.put("uri", uriOverride)
                }
                if (parseTrackData(data)?.also { out.add(it) } != null) {
                    parsedThisPage++
                }
            }
            if (parsedThisPage == 0 && items.length() > 0 && out.isEmpty()) {
                error(
                    "Playlist returned ${items.length()} items but none parsed — " +
                        "response shape may have changed",
                )
            }
            onPage(out.toList())
            offset += 50
            delay(100)
        }
        return out
    }

    private suspend fun fetchAlbumTracks(
        albumId: String,
        onPage: (List<TrackItem>) -> Unit,
    ): List<TrackItem> {
        val body = JSONObject()
            .put("operationName", "getAlbum")
            .put(
                "variables",
                JSONObject()
                    .put("uri", "spotify:album:$albumId")
                    .put("locale", "")
                    .put("offset", 0)
                    .put("limit", 300),
            )
            .put(
                "extensions",
                JSONObject().put(
                    "persistedQuery",
                    JSONObject().put("version", 1).put("sha256Hash", HASH_ALBUM),
                ),
            )
        val json = runCatching { pathfinder(body) }.getOrElse {
            return fetchPlaylistTracks("spotify:album:$albumId", onPage)
        }
        val tracks = json.optJSONObject("data")
            ?.optJSONObject("albumUnion")
            ?.optJSONObject("tracksV2")
            ?.optJSONArray("items")
            ?: json.optJSONObject("data")
                ?.optJSONObject("albumUnion")
                ?.optJSONObject("tracks")
                ?.optJSONArray("items")
            ?: JSONArray()
        val albumName = json.optJSONObject("data")
            ?.optJSONObject("albumUnion")
            ?.optString("name")
            .orEmpty()
        val cover = json.optJSONObject("data")
            ?.optJSONObject("albumUnion")
            ?.optJSONObject("coverArt")
            ?.optJSONArray("sources")
            ?.optJSONObject(0)
            ?.optString("url")
        val out = mutableListOf<TrackItem>()
        for (i in 0 until tracks.length()) {
            val t = tracks.optJSONObject(i)?.optJSONObject("track")?.optJSONObject("data")
                ?: tracks.optJSONObject(i)?.optJSONObject("item")?.optJSONObject("data")
                ?: tracks.optJSONObject(i)?.optJSONObject("data")
                ?: continue
            parseTrackData(t, albumOverride = albumName, imageOverride = cover)?.let(out::add)
        }
        onPage(out)
        return out
    }

    private fun parseTrackData(
        track: JSONObject?,
        albumOverride: String = "",
        imageOverride: String? = null,
        likedAtMs: Long? = null,
    ): TrackItem? {
        if (track == null) return null
        val uriRaw = track.optString("uri").ifBlank { track.optString("_uri") }
        val id = uriToId(uriRaw) ?: track.optString("id").ifBlank { null } ?: return null
        val uri = uriRaw.ifBlank { "spotify:track:$id" }
        val artists = track.optJSONObject("artists")?.optJSONArray("items")
            ?: track.optJSONArray("artists")
        var primaryArtistId: String? = null
        val artistNames = buildList {
            if (artists != null) {
                for (i in 0 until artists.length()) {
                    val a = artists.optJSONObject(i) ?: continue
                    val profile = a.optJSONObject("profile")
                    val name = profile?.optString("name") ?: a.optString("name")
                    if (!name.isNullOrBlank()) add(name)
                    if (primaryArtistId == null) {
                        val aUri = a.optString("uri").ifBlank {
                            profile?.optString("uri").orEmpty()
                        }.ifBlank { a.optString("id") }
                        primaryArtistId = when {
                            aUri.startsWith("spotify:artist:") -> aUri.removePrefix("spotify:artist:")
                            aUri.isNotBlank() && !aUri.contains(':') -> aUri
                            else -> null
                        }
                    }
                }
            }
        }.joinToString(", ")
        val albumObj = track.optJSONObject("albumOfTrack") ?: track.optJSONObject("album")
        val album = albumOverride.ifBlank { albumObj?.optString("name").orEmpty() }
        val image = imageOverride
            ?: albumObj?.optJSONObject("coverArt")?.optJSONArray("sources")
                ?.optJSONObject(0)?.optString("url")
            ?: albumObj?.optJSONArray("images")?.optJSONObject(0)?.optString("url")
        val duration = track.optJSONObject("duration")?.optLong("totalMilliseconds")
            ?: track.optJSONObject("trackDuration")?.optLong("totalMilliseconds")
            ?: track.optLong("durationMs").takeIf { it > 0 }
            ?: track.optLong("duration_ms").takeIf { it > 0 }
            ?: 0L
        val name = track.optString("name")
        if (name.isBlank()) return null
        return TrackItem(
            id = id,
            uri = uri,
            name = name,
            artists = artistNames,
            album = album,
            imageUrl = image,
            durationMs = duration,
            likedAtMs = likedAtMs,
            primaryArtistId = primaryArtistId,
        )
    }

    private suspend fun pathfinder(body: JSONObject): JSONObject {
        val res = bridge.fetch(PATHFINDER_URL, "POST", body.toString())
        when (res.status) {
            429 -> error("HTTP 429 on pathfinder (real rate limit): ${res.body.take(100)}")
            401, 403 -> {
                bridge.invalidate()
                error(
                    "Pathfinder rejected token (HTTP ${res.status}). " +
                        "Log out and log in again. ${res.body.take(100)}",
                )
            }
            else -> if (res.status !in 200..299) {
                error("pathfinder ${res.status}: ${res.body.take(160)}")
            }
        }
        val json = JSONObject(res.body)
        val errors = json.optJSONArray("errors")
        if (errors != null && errors.length() > 0) {
            val msg = errors.optJSONObject(0)?.optString("message").orEmpty()
            error("Pathfinder GraphQL: ${msg.ifBlank { errors.toString().take(160) }}")
        }
        return json
    }

    private fun decodeProfileFromJwt(jwt: String): Profile? {
        return try {
            val parts = jwt.split(".")
            if (parts.size < 2) return null
            var payload = parts[1]
            val pad = (4 - payload.length % 4) % 4
            payload += "=".repeat(pad)
            val json = JSONObject(String(Base64.getUrlDecoder().decode(payload)))
            val id = json.optString("username").ifBlank {
                json.optString("sub").substringAfter(":", json.optString("sub"))
            }
            if (id.isBlank()) return null
            val name = json.optString("name").ifBlank { id }
            Profile(id = id, name = name)
        } catch (_: Exception) {
            null
        }
    }

    private fun uriToId(uri: String?): String? {
        if (uri.isNullOrBlank()) return null
        return uri.substringAfterLast(':').takeIf { it.isNotBlank() }
    }

    companion object {
        private const val PATHFINDER_URL = "https://api-partner.spotify.com/pathfinder/v2/query"
        private const val HASH_LIBRARY_V3 =
            "973e511ca44261fda7eebac8b653155e7caee3675abb4fb110cc1b8c78b091c3"
        // Liked Songs — NOT a playlist URI (fetchPlaylist rejects collection uris)
        private const val HASH_LIBRARY_TRACKS =
            "087278b20b743578a6262c2b0b4bcd20d879c503cc359a2285baf083ef944240"
        // Current web-player fetchPlaylist (requires enableWatchFeedEntrypoint)
        private const val HASH_PLAYLIST =
            "a65e12194ed5fc443a1cdebed5fabe33ca5b07b987185d63c72483867ad13cb4"
        private val SEARCH_HASHES = listOf(
            "3c9d3f60dac5dea3876b6db3f534192b1c1d90032c4233c1bbaba526db41eb31",
            "4801118d4a100f756e833d33984436a3899cff359c532f8fd3aaf174b60b3b49",
        )
        private const val HASH_ALBUM =
            "b9bfabef66ed756e5e13f68a942deb60bd4125ec1f1be8cc42769dc0259b4b10"
        // queryArtistOverview hashes rotate; try a few known ones
        private val ARTIST_OVERVIEW_HASHES = listOf(
            "79a4a9d7c3a3781d801e62b62ef11c7ee56fce2626772eb26cd20c69f84b3f49",
            "0e26e3960e1e6c0e12bc3f3fb5f2117aadf0170aea7ac07d8c4bcb75ac2cbbf9",
            "433e28d1e949372d3ca3aa6c47975cff428b5dc37b12f5325d9213accadf770a",
        )
    }
}
