package com.nexus.spotifydesktop.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

object SpotiCloudColors {
    val SpotifyGreen = Color(0xFF1DB954)
    val SoundCloudOrange = Color(0xFFFF5500)
    val Bg = Color(0xFF121212)
    val Surface = Color(0xFF181818)
    val Surface2 = Color(0xFF282828)
    val TextMain = Color(0xFFFFFFFF)
    val TextMuted = Color(0xFFB3B3B3)
}

private val type = Typography(
    headlineMedium = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Bold, color = SpotiCloudColors.TextMain),
    titleLarge = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.Bold, color = SpotiCloudColors.TextMain),
    titleMedium = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = SpotiCloudColors.TextMain),
    bodyLarge = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium, color = SpotiCloudColors.TextMain),
    bodyMedium = TextStyle(fontSize = 13.sp, color = SpotiCloudColors.TextMuted),
    bodySmall = TextStyle(fontSize = 12.sp, color = SpotiCloudColors.TextMuted),
    labelLarge = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold),
    labelSmall = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium, color = SpotiCloudColors.TextMuted),
)

fun spotiCloudColorScheme(accent: Color) = darkColorScheme(
    primary = accent,
    onPrimary = Color.Black,
    background = SpotiCloudColors.Bg,
    surface = SpotiCloudColors.Surface,
    surfaceVariant = SpotiCloudColors.Surface2,
    onBackground = SpotiCloudColors.TextMain,
    onSurface = SpotiCloudColors.TextMain,
    onSurfaceVariant = SpotiCloudColors.TextMuted,
    error = Color(0xFFE91429),
)

@Composable
fun AppTheme(
    accent: Color = SpotiCloudColors.SpotifyGreen,
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = spotiCloudColorScheme(accent),
        typography = type,
        content = content,
    )
}
