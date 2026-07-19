package com.nexus.spotifydesktop

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.core.view.WindowCompat
import com.nexus.spotifydesktop.auth.DevOAuth
import com.nexus.spotifydesktop.ui.AppRoot
import com.nexus.spotifydesktop.ui.MainViewModel
import com.nexus.spotifydesktop.ui.theme.AppTheme

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContent {
            AppTheme {
                AppRoot(
                    viewModel = viewModel,
                    onRefreshSongsWithDevToken = {
                        viewModel.refreshSongsWithDeveloperToken(this)
                    },
                )
            }
        }
        handleDevAuthIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleDevAuthIntent(intent)
    }

    private fun handleDevAuthIntent(intent: Intent?) {
        val uri = intent?.data ?: return
        if (DevOAuth.isDevRedirect(uri)) {
            viewModel.onDeveloperAuthRedirect(uri)
        }
    }

    override fun onStart() {
        super.onStart()
        // App Remote must connect with an Activity (not Application) or play is silent
        viewModel.attachPlayerHost(this)
    }

    override fun onStop() {
        viewModel.detachPlayerHost(this)
        super.onStop()
    }
}
