package com.nexus.spotifydesktop.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun AmbientBackground(
    accent: Color = Color(0xFF1DB954),
    playing: Boolean = false,
) {
    val transition = rememberInfiniteTransition(label = "ambient")
    val t by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(if (playing) 9000 else 14000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "phase",
    )
    val pulse by transition.animateFloat(
        initialValue = 0.85f,
        targetValue = if (playing) 1.15f else 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(if (playing) 2200 else 4000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulse",
    )

    // Secondary wash: purple-ish for Spotify green, warm brown for SoundCloud orange
    val isOrange = accent.red > 0.7f && accent.green < 0.5f
    val secondary = if (isOrange) Color(0xFF5C2E00) else Color(0xFF3D2B6D)
    val tertiary = if (isOrange) Color(0xFF3D1A00) else Color(0xFF0B3D2E)

    Canvas(modifier = Modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        drawRect(Color(0xFF0A0A0A))

        val angle = t * Math.PI.toFloat() * 2f
        val c1 = Offset(
            x = w * (0.25f + 0.12f * cos(angle)),
            y = h * (0.18f + 0.08f * sin(angle * 0.8f)),
        )
        val c2 = Offset(
            x = w * (0.78f + 0.1f * sin(angle * 1.1f)),
            y = h * (0.35f + 0.1f * cos(angle * 0.7f)),
        )
        val c3 = Offset(
            x = w * (0.5f + 0.15f * cos(angle * 0.6f + 1f)),
            y = h * (0.75f + 0.08f * sin(angle * 0.9f)),
        )

        val r1 = w * 0.55f * pulse
        val r2 = w * 0.45f * pulse
        val r3 = w * 0.5f * pulse

        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(accent.copy(alpha = 0.28f), Color.Transparent),
                center = c1,
                radius = r1,
            ),
            radius = r1,
            center = c1,
        )
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(secondary.copy(alpha = 0.22f), Color.Transparent),
                center = c2,
                radius = r2,
            ),
            radius = r2,
            center = c2,
        )
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(tertiary.copy(alpha = 0.25f), Color.Transparent),
                center = c3,
                radius = r3,
            ),
            radius = r3,
            center = c3,
        )
    }
}
