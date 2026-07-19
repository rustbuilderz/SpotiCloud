package com.nexus.spotifydesktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.nexus.spotifydesktop.ui.theme.SpotiCloudColors

enum class SetupStep { Welcome, Spotify, SoundCloud }

@Composable
fun SetupScreen(
    step: SetupStep,
    spotifyAuthed: Boolean,
    spotifyUserName: String,
    soundCloudAuthed: Boolean,
    soundCloudUserName: String,
    spotifyClientId: String,
    scLoading: Boolean,
    error: String?,
    onAgreeAndStart: () -> Unit,
    onSaveSpotifyClientId: (String) -> Unit,
    onSpotifyLogin: () -> Unit,
    onContinueFromSpotify: () -> Unit,
    onConnectSoundCloud: (oauth: String, clientId: String) -> Unit,
    onFinish: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.statusBars)
            .windowInsetsPadding(WindowInsets.navigationBars)
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
    ) {
        Text("SpotiCloud", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(8.dp))

        when (step) {
            SetupStep.Welcome -> WelcomeStep(onAgreeAndStart = onAgreeAndStart)
            SetupStep.Spotify -> SpotifySetupStep(
                authed = spotifyAuthed,
                userName = spotifyUserName,
                clientId = spotifyClientId,
                error = error,
                onSaveClientId = onSaveSpotifyClientId,
                onLogin = onSpotifyLogin,
                onContinue = onContinueFromSpotify,
            )
            SetupStep.SoundCloud -> SoundCloudSetupStep(
                authed = soundCloudAuthed,
                userName = soundCloudUserName,
                loading = scLoading,
                error = error,
                onConnect = onConnectSoundCloud,
                onFinish = onFinish,
            )
        }
    }
}

@Composable
private fun WelcomeStep(onAgreeAndStart: () -> Unit) {
    var agreed by rememberSaveable { mutableStateOf(false) }

    Text("Welcome", style = MaterialTheme.typography.titleLarge)
    Spacer(Modifier.height(12.dp))
    Text(
        "SpotiCloud uses unofficial Spotify web sessions and SoundCloud API tokens. " +
            "That can break, get rate-limited, or get your account restricted.",
        style = MaterialTheme.typography.bodyMedium,
    )
    Spacer(Modifier.height(16.dp))
    Text(
        "By continuing you agree:\n" +
            "• This is unofficial / unsupported by Spotify or SoundCloud\n" +
            "• You accept the risk of bans, locks, or ToS action\n" +
            "• We are not responsible for account loss or damages\n" +
            "• Use carefully — don’t share tokens or run this on accounts you can’t lose",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(16.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = agreed, onCheckedChange = { agreed = it })
        Text(
            "I understand the risks and want to continue",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(start = 4.dp),
        )
    }
    Spacer(Modifier.height(24.dp))
    Button(
        onClick = onAgreeAndStart,
        enabled = agreed,
        modifier = Modifier.fillMaxWidth().height(48.dp),
        shape = RoundedCornerShape(24.dp),
        colors = ButtonDefaults.buttonColors(containerColor = SpotiCloudColors.SpotifyGreen),
    ) {
        Text("Setup")
    }
}

@Composable
private fun SpotifySetupStep(
    authed: Boolean,
    userName: String,
    clientId: String,
    error: String?,
    onSaveClientId: (String) -> Unit,
    onLogin: () -> Unit,
    onContinue: () -> Unit,
) {
    var cidDraft by rememberSaveable(clientId) { mutableStateOf(clientId) }

    Text("Spotify", style = MaterialTheme.typography.titleLarge)
    Spacer(Modifier.height(8.dp))
    Text(
        if (authed) "Signed in as ${userName.ifBlank { "Spotify user" }}"
        else "Log in with Spotify in the in-app browser (web session).",
        style = MaterialTheme.typography.bodyMedium,
    )
    Spacer(Modifier.height(16.dp))

    if (!authed) {
        Button(
            onClick = onLogin,
            modifier = Modifier.fillMaxWidth().height(48.dp),
            shape = RoundedCornerShape(24.dp),
            colors = ButtonDefaults.buttonColors(containerColor = SpotiCloudColors.SpotifyGreen),
        ) {
            Text("Log in with Spotify")
        }
    }

    Spacer(Modifier.height(20.dp))
    Text("Optional · Developer Client ID", style = MaterialTheme.typography.titleSmall)
    Text(
        "Only needed for App Remote playback / optional Dev token features. " +
            "Paste your Spotify Dashboard Client ID if you have one.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        value = cidDraft,
        onValueChange = { cidDraft = it },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        label = { Text("Spotify Client ID (optional)") },
        colors = setupFieldColors(),
    )
    Spacer(Modifier.height(8.dp))
    OutlinedButton(
        onClick = { onSaveClientId(cidDraft) },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text("Save Client ID")
    }

    error?.let {
        Spacer(Modifier.height(12.dp))
        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    }

    Spacer(Modifier.height(24.dp))
    Button(
        onClick = {
            onSaveClientId(cidDraft)
            onContinue()
        },
        enabled = authed,
        modifier = Modifier.fillMaxWidth().height(48.dp),
        shape = RoundedCornerShape(24.dp),
        colors = ButtonDefaults.buttonColors(containerColor = SpotiCloudColors.SpotifyGreen),
    ) {
        Text(if (authed) "Continue to SoundCloud" else "Sign in first")
    }
}

@Composable
private fun SoundCloudSetupStep(
    authed: Boolean,
    userName: String,
    loading: Boolean,
    error: String?,
    onConnect: (oauth: String, clientId: String) -> Unit,
    onFinish: () -> Unit,
) {
    var oauthDraft by rememberSaveable { mutableStateOf("") }
    var cidDraft by rememberSaveable { mutableStateOf("") }

    Text("SoundCloud", style = MaterialTheme.typography.titleLarge)
    Spacer(Modifier.height(8.dp))
    Text(
        if (authed) "Connected as ${userName.ifBlank { "user" }}"
        else "No SoundCloud developer app needed — use your own session token + client_id.",
        style = MaterialTheme.typography.bodyMedium,
    )
    Spacer(Modifier.height(14.dp))
    SoundCloudCredsInstructions()
    Spacer(Modifier.height(16.dp))

    if (!authed) {
        OutlinedTextField(
            value = cidDraft,
            onValueChange = { cidDraft = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("Client ID") },
            placeholder = { Text("IRnK0myxxLJdwXXjybXQo71m…") },
            colors = setupFieldColors(),
        )
        Text(
            "From the request URL: the value after client_id=",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp, bottom = 10.dp),
        )
        OutlinedTextField(
            value = oauthDraft,
            onValueChange = { oauthDraft = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("OAuth Token") },
            placeholder = { Text("2-123456-7890123-xxxxxxxx") },
            colors = setupFieldColors(),
        )
        Text(
            "Paste with or without the “OAuth ” prefix — both work.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp, bottom = 16.dp),
        )
        Button(
            onClick = { onConnect(oauthDraft, cidDraft) },
            enabled = !loading && oauthDraft.isNotBlank() && cidDraft.isNotBlank(),
            modifier = Modifier.fillMaxWidth().height(48.dp),
            shape = RoundedCornerShape(24.dp),
            colors = ButtonDefaults.buttonColors(containerColor = SpotiCloudColors.SoundCloudOrange),
        ) {
            if (loading) {
                CircularProgressIndicator(
                    modifier = Modifier.height(22.dp).padding(end = 8.dp),
                    strokeWidth = 2.dp,
                    color = Color.Black,
                )
            }
            Text("Connect SoundCloud")
        }
    }

    error?.let {
        Spacer(Modifier.height(12.dp))
        Text(it, color = Color(0xFFFF8A80), style = MaterialTheme.typography.bodySmall)
    }

    Spacer(Modifier.height(24.dp))
    Button(
        onClick = onFinish,
        enabled = authed && !loading,
        modifier = Modifier.fillMaxWidth().height(48.dp),
        shape = RoundedCornerShape(24.dp),
        colors = ButtonDefaults.buttonColors(containerColor = SpotiCloudColors.SoundCloudOrange),
    ) {
        Text("Finish setup")
    }
}

@Composable
private fun setupFieldColors() = TextFieldDefaults.colors(
    focusedContainerColor = Color(0xFF1A1A1A),
    unfocusedContainerColor = Color(0xFF1A1A1A),
    focusedIndicatorColor = Color.Transparent,
    unfocusedIndicatorColor = Color.Transparent,
)
