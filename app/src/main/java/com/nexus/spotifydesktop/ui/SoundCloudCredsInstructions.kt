package com.nexus.spotifydesktop.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.nexus.spotifydesktop.ui.theme.SpotiCloudColors

/** Step-by-step DevTools guide (same flow as SpiceCloud’s AuthScreen). */
@Composable
internal fun SoundCloudCredsInstructions(modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            "Get your credentials from DevTools",
            style = MaterialTheme.typography.titleSmall,
            color = SpotiCloudColors.SoundCloudOrange,
        )
        Spacer(Modifier.height(8.dp))
        Step("1", "Open soundcloud.com in a desktop browser and log in.")
        Step("2", "Open DevTools (F12 / Ctrl+Shift+I) → Network tab.")
        Step("3", "Filter by api-v2.soundcloud.com, then reload the page so requests appear.")
        Step(
            "4",
            "Click any XHR/fetch request to api-v2.soundcloud.com. " +
                "In the request URL, copy the value after client_id= " +
                "(long alphanumeric string).",
        )
        Step(
            "5",
            "In Request Headers, find Authorization. Copy the value after " +
                "Authorization: — it looks like OAuth 2-123456-… " +
                "(you can paste with or without the “OAuth ” prefix).",
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "These are your live session credentials. Don’t share them. " +
                "Tokens expire after a few weeks — paste fresh ones when Connect fails.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun Step(num: String, body: String) {
    Text(
        "$num. $body",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 6.dp),
    )
}
