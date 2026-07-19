package com.nexus.spotifydesktop.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Disk cache for Spotify sidebar library (playlists + albums). */
class SpotifyLibraryCache(context: Context) {
    private val file = File(context.filesDir, "liked_cache/spotify_library.json").also {
        it.parentFile?.mkdirs()
    }

    fun load(): List<LibraryItem>? = runCatching {
        if (!file.isFile) return null
        val root = JSONObject(file.readText())
        val arr = root.optJSONArray("items") ?: return null
        val out = mutableListOf<LibraryItem>(LibraryItem.Liked)
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            when (o.optString("kind")) {
                "playlist" -> out += LibraryItem.Playlist(
                    id = o.optString("id"),
                    name = o.optString("name"),
                    uri = o.optString("uri"),
                    imageUrl = o.optString("imageUrl").takeIf { it.isNotBlank() },
                )
                "album" -> out += LibraryItem.Album(
                    id = o.optString("id"),
                    name = o.optString("name"),
                    uri = o.optString("uri"),
                    imageUrl = o.optString("imageUrl").takeIf { it.isNotBlank() },
                )
            }
        }
        out.ifEmpty { null }
    }.getOrNull()

    fun save(items: List<LibraryItem>) {
        runCatching {
            val arr = JSONArray()
            items.forEach { item ->
                when (item) {
                    is LibraryItem.Liked -> Unit
                    is LibraryItem.Playlist -> arr.put(
                        JSONObject()
                            .put("kind", "playlist")
                            .put("id", item.id)
                            .put("name", item.name)
                            .put("uri", item.uri)
                            .put("imageUrl", item.imageUrl ?: ""),
                    )
                    is LibraryItem.Album -> arr.put(
                        JSONObject()
                            .put("kind", "album")
                            .put("id", item.id)
                            .put("name", item.name)
                            .put("uri", item.uri)
                            .put("imageUrl", item.imageUrl ?: ""),
                    )
                }
            }
            file.writeText(
                JSONObject()
                    .put("items", arr)
                    .put("savedAtMs", System.currentTimeMillis())
                    .toString(),
            )
        }
    }

    fun clear() {
        runCatching { file.delete() }
    }
}
