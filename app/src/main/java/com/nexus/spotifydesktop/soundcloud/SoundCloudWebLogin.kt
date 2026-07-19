package com.nexus.spotifydesktop.soundcloud

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
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
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.viewinterop.AndroidView
import com.nexus.spotifydesktop.ui.theme.SpotiCloudColors

/**
 * SoundCloud login WebView — captures OAuth from API requests after sign-in
 * (Authorization header via JS bridge, or oauth_token / client_id in URLs).
 */
@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun SoundCloudWebLogin(
    onCaptured: (oauthToken: String, clientId: String?) -> Unit,
    onCancel: () -> Unit,
) {
    var captured by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("SoundCloud login") },
                navigationIcon = {
                    IconButton(onClick = onCancel) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF1A1410),
                    titleContentColor = Color.White,
                    navigationIconContentColor = SpotiCloudColors.SoundCloudOrange,
                ),
            )
        },
        containerColor = Color(0xFF121212),
    ) { padding ->
        AndroidView(
            factory = { context ->
                WebView(context).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    CookieManager.getInstance().setAcceptCookie(true)
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

                    val bridge = object {
                        @JavascriptInterface
                        fun onOAuth(token: String) {
                            if (captured) return
                            val cleaned = token.trim().replace(Regex("^OAuth\\s+", RegexOption.IGNORE_CASE), "")
                            if (cleaned.length < 10) return
                            captured = true
                            post { onCaptured(cleaned, null) }
                        }

                        @JavascriptInterface
                        fun onClientId(id: String) {
                            // stored only if oauth arrives with it via URL intercept
                        }
                    }
                    addJavascriptInterface(bridge, "SpotiCloudSC")

                    webViewClient = object : WebViewClient() {
                        override fun shouldInterceptRequest(
                            view: WebView?,
                            request: WebResourceRequest?,
                        ): android.webkit.WebResourceResponse? {
                            val url = request?.url?.toString().orEmpty()
                            if (!captured && url.contains("api-v2.soundcloud.com")) {
                                val oauth = request?.url?.getQueryParameter("oauth_token")
                                    ?: request?.requestHeaders?.entries
                                        ?.firstOrNull { it.key.equals("Authorization", true) }
                                        ?.value
                                        ?.replace(Regex("^OAuth\\s+", RegexOption.IGNORE_CASE), "")
                                        ?.trim()
                                val clientId = request?.url?.getQueryParameter("client_id")
                                if (!oauth.isNullOrBlank() && oauth.length > 10) {
                                    captured = true
                                    post { onCaptured(oauth, clientId) }
                                }
                            }
                            return super.shouldInterceptRequest(view, request)
                        }

                        override fun onPageFinished(view: WebView?, url: String?) {
                            super.onPageFinished(view, url)
                            view?.evaluateJavascript(INJECT_CAPTURE, null)
                            // Also try cookie oauth_token
                            if (!captured) {
                                val cookies = CookieManager.getInstance()
                                    .getCookie("https://api-v2.soundcloud.com")
                                    ?: CookieManager.getInstance().getCookie("https://soundcloud.com")
                                    ?: return
                                val oauth = cookies.split(";")
                                    .map { it.trim() }
                                    .firstOrNull { it.startsWith("oauth_token=") }
                                    ?.removePrefix("oauth_token=")
                                    ?.trim()
                                if (!oauth.isNullOrBlank() && oauth.length > 10) {
                                    captured = true
                                    onCaptured(oauth, null)
                                }
                            }
                        }
                    }
                    loadUrl("https://soundcloud.com/signin")
                }
            },
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        )
    }
}

private const val INJECT_CAPTURE = """
(function() {
  if (window.__nexusScHooked) return;
  window.__nexusScHooked = true;
  function emit(v) {
    try {
      if (v && window.SpotiCloudSC && window.SpotiCloudSC.onOAuth) {
        var t = String(v).replace(/^OAuth\s+/i, '');
        if (t.length > 10) window.SpotiCloudSC.onOAuth(t);
      }
    } catch (e) {}
  }
  try {
    var xo = XMLHttpRequest.prototype.open;
    var xs = XMLHttpRequest.prototype.setRequestHeader;
    var xsend = XMLHttpRequest.prototype.send;
    XMLHttpRequest.prototype.open = function(m, u) {
      this.__u = u;
      return xo.apply(this, arguments);
    };
    XMLHttpRequest.prototype.setRequestHeader = function(k, v) {
      if (String(k).toLowerCase() === 'authorization') emit(v);
      return xs.apply(this, arguments);
    };
    XMLHttpRequest.prototype.send = function() {
      try {
        var u = String(this.__u || '');
        var m = u.match(/[?&]oauth_token=([^&]+)/);
        if (m) emit(decodeURIComponent(m[1]));
      } catch (e) {}
      return xsend.apply(this, arguments);
    };
  } catch (e) {}
  try {
    var ofetch = window.fetch;
    window.fetch = function(input, init) {
      try {
        var url = typeof input === 'string' ? input : (input && input.url);
        if (url) {
          var m = String(url).match(/[?&]oauth_token=([^&]+)/);
          if (m) emit(decodeURIComponent(m[1]));
        }
        if (init && init.headers) {
          var h = init.headers;
          if (h.get) emit(h.get('Authorization') || h.get('authorization'));
          else if (h.Authorization) emit(h.Authorization);
          else if (h.authorization) emit(h.authorization);
        }
      } catch (e) {}
      return ofetch.apply(this, arguments);
    };
  } catch (e) {}
})();
"""
