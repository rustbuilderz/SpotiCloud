package com.nexus.spotifydesktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nexus.spotifydesktop.data.ConsoleLevel
import com.nexus.spotifydesktop.data.ConsoleLine
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val ConsoleBg = Color(0xFF0B0D0F)
private val ConsolePanel = Color(0xFF12151A)
private val ConsoleBorder = Color(0xFF1E2430)

@Composable
fun ConsolePane(
    lines: List<ConsoleLine>,
    onBack: () -> Unit,
    onClear: () -> Unit,
) {
    val listState = rememberLazyListState()
    LaunchedEffect(lines.lastOrNull()?.id) {
        if (lines.isNotEmpty()) {
            listState.animateScrollToItem(lines.lastIndex)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(ConsoleBg)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = Color(0xFFB0B8C4),
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "Console",
                    style = MaterialTheme.typography.titleLarge,
                    color = Color(0xFFE8ECF2),
                )
                Text(
                    "${lines.size} lines · live",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFF7A8494),
                )
            }
            TextButton(onClick = onClear) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = Color(0xFFFF8A80),
                )
                Spacer(Modifier.width(6.dp))
                Text("Clear", color = Color(0xFFFF8A80))
            }
        }

        Spacer(Modifier.height(8.dp))

        // Legend
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            LegendChip("INFO", Color(0xFF8AB4F8))
            LegendChip("OK", Color(0xFF81C995))
            LegendChip("WARN", Color(0xFFFDD663))
            LegendChip("ERROR", Color(0xFFF28B82))
            LegendChip("429", Color(0xFFFF8AC4))
        }

        Spacer(Modifier.height(10.dp))

        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(12.dp))
                .background(ConsolePanel)
                .border(1.dp, ConsoleBorder, RoundedCornerShape(12.dp)),
        ) {
            if (lines.isEmpty()) {
                Text(
                    "Waiting for events…\nLoads, auth, rate limits, and errors show up here.",
                    color = Color(0xFF6B7380),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(24.dp),
                )
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(10.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(lines, key = { it.id }) { line ->
                        ConsoleRow(line)
                    }
                }
            }
        }
    }
}

@Composable
private fun LegendChip(label: String, color: Color) {
    Text(
        label,
        color = color,
        fontSize = 10.sp,
        fontWeight = FontWeight.SemiBold,
        fontFamily = FontFamily.Monospace,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(color.copy(alpha = 0.12f))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

@Composable
private fun ConsoleRow(line: ConsoleLine) {
    val color = colorFor(line.level)
    val time = rememberTime(line.atMs)
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            time,
            color = Color(0xFF5C6573),
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            levelLabel(line.level),
            color = color,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.width(36.dp),
        )
        Text(
            line.tag,
            color = Color(0xFF9AA5B5),
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.width(78.dp),
        )
        Text(
            line.message,
            color = color.copy(alpha = 0.95f),
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            lineHeight = 16.sp,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun rememberTime(atMs: Long): String {
    // Stable format per composition; fine for console rows
    return SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(atMs))
}

private fun levelLabel(level: ConsoleLevel): String = when (level) {
    ConsoleLevel.Debug -> "DBG"
    ConsoleLevel.Info -> "INF"
    ConsoleLevel.Ok -> "OK "
    ConsoleLevel.Warn -> "WRN"
    ConsoleLevel.Error -> "ERR"
    ConsoleLevel.RateLimit -> "429"
}

private fun colorFor(level: ConsoleLevel): Color = when (level) {
    ConsoleLevel.Debug -> Color(0xFF7A8494)
    ConsoleLevel.Info -> Color(0xFF8AB4F8)
    ConsoleLevel.Ok -> Color(0xFF81C995)
    ConsoleLevel.Warn -> Color(0xFFFDD663)
    ConsoleLevel.Error -> Color(0xFFF28B82)
    ConsoleLevel.RateLimit -> Color(0xFFFF8AC4)
}
