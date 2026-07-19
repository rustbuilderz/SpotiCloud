package com.nexus.spotifydesktop.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.nexus.spotifydesktop.R

/** Official-style Spotify mark (green disc). Pass [tint] only for monochrome contexts. */
@Composable
fun SpotifyLogo(
    modifier: Modifier = Modifier,
    size: Dp = 24.dp,
    tint: ColorFilter? = null,
) {
    Image(
        painter = painterResource(R.drawable.ic_spotify),
        contentDescription = "Spotify",
        modifier = modifier.size(size),
        contentScale = ContentScale.Fit,
        colorFilter = tint,
    )
}
