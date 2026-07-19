package com.nexus.spotifydesktop.auth

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

data class WebSessionCookies(
    val spDc: String,
    val spT: String?,
)

/**
 * Logs into open.spotify.com in a WebView and captures `sp_dc` (+ `sp_t` when present).
 */
@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebLoginScreen(
    onSession: (WebSessionCookies) -> Unit,
    onCancel: () -> Unit,
    onError: (String) -> Unit,
) {
    val cookieManager = remember { CookieManager.getInstance() }
    var captured by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        cookieManager.setAcceptCookie(true)
        cookieManager.removeAllCookies(null)
        cookieManager.flush()
        onDispose { }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Spotify login") },
                navigationIcon = {
                    IconButton(onClick = onCancel) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        AndroidView(
            factory = { context ->
                WebView(context).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView?, url: String?) {
                            super.onPageFinished(view, url)
                            if (captured) return
                            val cookies = cookieManager.getCookie("https://open.spotify.com")
                                ?: cookieManager.getCookie(url ?: return)
                                ?: return
                            val parts = cookies.split(";").map { it.trim() }
                            val spDc = parts.firstOrNull { it.startsWith("sp_dc=") }
                                ?.removePrefix("sp_dc=")
                                ?.trim()
                            val spT = parts.firstOrNull { it.startsWith("sp_t=") }
                                ?.removePrefix("sp_t=")
                                ?.trim()
                            if (!spDc.isNullOrBlank() &&
                                url?.contains("open.spotify.com") == true &&
                                !url.contains("accounts.spotify.com")
                            ) {
                                captured = true
                                onSession(WebSessionCookies(spDc = spDc, spT = spT))
                            }
                        }

                        override fun shouldOverrideUrlLoading(
                            view: WebView?,
                            request: WebResourceRequest?,
                        ): Boolean = false

                        override fun onReceivedError(
                            view: WebView?,
                            errorCode: Int,
                            description: String?,
                            failingUrl: String?,
                        ) {
                            onError(description ?: "WebView error $errorCode")
                        }

                        @Deprecated("Deprecated in Java")
                        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                            super.onPageStarted(view, url, favicon)
                        }
                    }
                    loadUrl(
                        "https://accounts.spotify.com/login?continue=" +
                            java.net.URLEncoder.encode("https://open.spotify.com/", Charsets.UTF_8.name()),
                    )
                }
            },
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        )
    }
}
