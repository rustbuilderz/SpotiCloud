package com.nexus.spotifydesktop.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.nexus.spotifydesktop.data.LyricLine
import com.nexus.spotifydesktop.data.NowPlaying
import kotlinx.coroutines.delay

@Composable
fun ExpandedPlayerOverlay(
    visible: Boolean,
    nowPlaying: NowPlaying?,
    liked: Boolean,
    lyrics: List<LyricLine>,
    lyricsLoading: Boolean,
    lyricsError: String?,
    onCollapse: () -> Unit,
    onToggle: () -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onLike: () -> Unit,
) {
    var lyricsFull by remember { mutableStateOf(false) }
    var pullDown by remember { mutableFloatStateOf(0f) }

    AnimatedVisibility(
        visible = visible && nowPlaying != null,
        enter = fadeIn() + slideInVertically { it / 3 },
        exit = fadeOut() + slideOutVertically { it / 3 },
    ) {
        val np = nowPlaying!!
        val displayProgress = rememberProgressMs(np)

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xF00A0A0A))
                .windowInsetsPadding(WindowInsets.statusBars)
                .windowInsetsPadding(WindowInsets.navigationBars),
        ) {
            if (lyricsFull) {
                FullscreenLyrics(
                    lyrics = lyrics,
                    progressMs = displayProgress,
                    loading = lyricsLoading,
                    error = lyricsError,
                    onClose = { lyricsFull = false },
                )
            } else {
                val listState = rememberLazyListState()
                val nested = remember(onCollapse) {
                    object : NestedScrollConnection {
                        var accum = 0f
                        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                            // Pull down at top → dismiss
                            if (available.y > 0f && !listState.canScrollBackward) {
                                accum += available.y
                                pullDown = (accum / 4f).coerceAtMost(120f)
                                if (accum > 140f) {
                                    accum = 0f
                                    pullDown = 0f
                                    onCollapse()
                                }
                                return Offset(0f, available.y)
                            }
                            if (available.y < 0f) {
                                accum = 0f
                                pullDown = 0f
                            }
                            return Offset.Zero
                        }

                        override suspend fun onPreFling(available: Velocity): Velocity {
                            if (available.y > 1800f && !listState.canScrollBackward) {
                                onCollapse()
                                return available
                            }
                            return Velocity.Zero
                        }
                    }
                }

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .nestedScroll(nested)
                        .pointerInput(Unit) {
                            detectVerticalDragGestures(
                                onDragEnd = {
                                    if (pullDown > 80f) onCollapse()
                                    pullDown = 0f
                                },
                                onVerticalDrag = { _, dragAmount ->
                                    if (dragAmount > 0 && !listState.canScrollBackward) {
                                        pullDown = (pullDown + dragAmount / 3f).coerceAtMost(140f)
                                    } else if (dragAmount < 0) {
                                        pullDown = (pullDown + dragAmount / 2f).coerceAtLeast(0f)
                                    }
                                },
                            )
                        },
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(onClick = onCollapse) {
                            Icon(
                                Icons.Default.KeyboardArrowDown,
                                contentDescription = "Close",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(modifier = Modifier.weight(1f))
                        Box(
                            modifier = Modifier
                                .width(40.dp)
                                .height(4.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(Color.White.copy(alpha = 0.25f)),
                        )
                        Spacer(modifier = Modifier.weight(1f))
                        Spacer(modifier = Modifier.size(48.dp))
                    }

                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(top = pullDown.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        // 1) Cover
                        item {
                            AsyncImage(
                                model = np.imageUrl,
                                contentDescription = null,
                                modifier = Modifier
                                    .padding(horizontal = 36.dp, vertical = 12.dp)
                                    .fillMaxWidth()
                                    .height(280.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(Color(0xFF1A1A1A)),
                                contentScale = ContentScale.Crop,
                            )
                        }

                        // 2) Name / artist / progress
                        item {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 24.dp),
                            ) {
                                Text(
                                    np.name,
                                    style = MaterialTheme.typography.headlineSmall,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    np.artists,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Spacer(modifier = Modifier.height(10.dp))
                                val frac = if (np.durationMs > 0) {
                                    (displayProgress.toFloat() / np.durationMs).coerceIn(0f, 1f)
                                } else 0f
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(4.dp)
                                        .clip(RoundedCornerShape(2.dp))
                                        .background(Color.White.copy(alpha = 0.15f)),
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth(frac)
                                            .height(4.dp)
                                            .background(MaterialTheme.colorScheme.primary),
                                    )
                                }
                                Spacer(modifier = Modifier.height(6.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Text(formatPlayerMs(displayProgress), style = MaterialTheme.typography.labelSmall)
                                    Text(formatPlayerMs(np.durationMs), style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }

                        // Transport + like
                        item {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 24.dp, vertical = 12.dp),
                                horizontalArrangement = Arrangement.SpaceEvenly,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                IconButton(onClick = onPrev) {
                                    Icon(
                                        Icons.Default.SkipPrevious,
                                        contentDescription = "Previous",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(36.dp),
                                    )
                                }
                                IconButton(
                                    onClick = onToggle,
                                    modifier = Modifier
                                        .size(64.dp)
                                        .clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.primary),
                                ) {
                                    Icon(
                                        if (np.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                        contentDescription = "Play",
                                        tint = Color.Black,
                                        modifier = Modifier.size(36.dp),
                                    )
                                }
                                IconButton(onClick = onLike) {
                                    Icon(
                                        if (liked) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                                        contentDescription = "Like",
                                        tint = if (liked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(28.dp),
                                    )
                                }
                                IconButton(onClick = onNext) {
                                    Icon(
                                        Icons.Default.SkipNext,
                                        contentDescription = "Next",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(36.dp),
                                    )
                                }
                            }
                        }

                        // 3) Lyric preview (clickable)
                        item {
                            LyricPreviewCard(
                                lyrics = lyrics,
                                progressMs = displayProgress,
                                loading = lyricsLoading,
                                error = lyricsError,
                                onClick = { lyricsFull = true },
                            )
                            Spacer(modifier = Modifier.height(32.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LyricPreviewCard(
    lyrics: List<LyricLine>,
    progressMs: Long,
    loading: Boolean,
    error: String?,
    onClick: () -> Unit,
) {
    val current = currentLyricIndex(lyrics, progressMs)
    val line = lyrics.getOrNull(current)?.text
    val next = lyrics.getOrNull(current + 1)?.text

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(
                Brush.verticalGradient(listOf(Color(0xFF1A2E22), Color(0xFF121212))),
            )
            .clickable(onClick = onClick)
            .padding(16.dp),
    ) {
        Text("Lyrics", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        Spacer(modifier = Modifier.height(10.dp))
        when {
            loading -> CircularProgressIndicator(
                modifier = Modifier.size(22.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.primary,
            )
            !error.isNullOrBlank() && lyrics.isEmpty() -> Text(
                error,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            else -> {
                Text(
                    line ?: "…",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!next.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        next,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "Tap for full lyrics",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun FullscreenLyrics(
    lyrics: List<LyricLine>,
    progressMs: Long,
    loading: Boolean,
    error: String?,
    onClose: () -> Unit,
) {
    val listState = rememberLazyListState()
    val current = currentLyricIndex(lyrics, progressMs)

    LaunchedEffect(current) {
        if (current >= 0 && lyrics.isNotEmpty()) {
            runCatching { listState.animateScrollToItem((current - 2).coerceAtLeast(0)) }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0B0B0B))
            .padding(horizontal = 16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Lyrics",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f).padding(start = 8.dp),
            )
            IconButton(onClick = onClose) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = "Close lyrics",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (loading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            }
        } else if (lyrics.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    error ?: "No lyrics",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 48.dp),
            ) {
                itemsIndexed(lyrics) { index, line ->
                    val active = index == current
                    Text(
                        text = line.text,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 10.dp, horizontal = 8.dp),
                        color = if (active) Color.White else Color.White.copy(alpha = 0.35f),
                        textAlign = TextAlign.Start,
                        fontSize = if (active) 26.sp else 20.sp,
                        fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                    )
                }
            }
        }
    }
}

@Composable
private fun rememberProgressMs(nowPlaying: NowPlaying): Long {
    var progress by remember(nowPlaying.uri, nowPlaying.progressMs) {
        mutableLongStateOf(nowPlaying.progressMs)
    }
    LaunchedEffect(nowPlaying.uri, nowPlaying.progressMs, nowPlaying.isPlaying) {
        progress = nowPlaying.progressMs
        if (!nowPlaying.isPlaying) return@LaunchedEffect
        while (true) {
            delay(250)
            progress = (progress + 250).coerceAtMost(nowPlaying.durationMs)
        }
    }
    return progress
}

internal fun currentLyricIndex(lyrics: List<LyricLine>, progressMs: Long): Int {
    if (lyrics.isEmpty()) return -1
    var idx = 0
    for (i in lyrics.indices) {
        if (lyrics[i].timeMs <= progressMs) idx = i else break
    }
    return idx
}

internal fun formatPlayerMs(ms: Long): String {
    val s = (ms / 1000).toInt().coerceAtLeast(0)
    val m = s / 60
    val r = s % 60
    return "%d:%02d".format(m, r)
}
