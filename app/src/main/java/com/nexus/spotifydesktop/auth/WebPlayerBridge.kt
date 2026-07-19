package com.nexus.spotifydesktop.auth

import android.annotation.SuppressLint
import android.app.Application
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

/**
 * Runs a real open.spotify.com WebView session (same cookies as login).
 *
 * 1. Loads the website so Spotify's own JS obtains tokens
 * 2. Captures Authorization + Client-Token from those browser requests
 * 3. Replays pathfinder / spclient calls via fetch() inside the page and returns JSON
 *
 * This avoids hand-rolling Client-Token from OkHttp (which Spotify often 400s).
 */
class WebPlayerBridge private constructor(
    private val app: Application,
) {
    private val main = Handler(Looper.getMainLooper())
    private val mutex = Mutex()
    private val accessToken = AtomicReference<String?>(null)
    private val clientToken = AtomicReference<String?>(null)
    private val pending = ConcurrentHashMap<String, CompletableDeferred<FetchResult>>()
    private val tokenStore = TokenStore(app)

    @Volatile private var webView: WebView? = null
    @Volatile private var pageReady = false
    private var pageReadySignal = CompletableDeferred<Unit>()

    data class FetchResult(val status: Int, val body: String)
    data class Tokens(val access: String, val client: String)

    suspend fun ensureReady() {
        mutex.withLock {
            if (webView == null) {
                withContext(Dispatchers.Main) { createWebViewLocked() }
            }
        }
        withTimeout(60_000) { pageReadySignal.await() }
        // Give the SPA a moment to fire its own auth / library XHRs
        waitForTokens(timeoutMs = 25_000)
    }

    suspend fun tokens(): Tokens {
        ensureReady()
        val a = accessToken.get()
        val c = clientToken.get()
        if (a.isNullOrBlank() || c.isNullOrBlank()) {
            error(
                "Couldn't capture Spotify web tokens from open.spotify.com. " +
                    "Log out, log in again, and keep the app open a few seconds.",
            )
        }
        return Tokens(access = a, client = c)
    }

    /** POST/GET from inside the WebView (same origin cookies + captured tokens). */
    suspend fun fetch(
        url: String,
        method: String,
        body: String? = null,
    ): FetchResult {
        val t = tokens()
        val id = UUID.randomUUID().toString()
        val deferred = CompletableDeferred<FetchResult>()
        pending[id] = deferred

        val script = buildString {
            append("(function(){")
            append("var id=${JSONObject.quote(id)};")
            append("var url=${JSONObject.quote(url)};")
            append("var method=${JSONObject.quote(method)};")
            append("var body=${body?.let { JSONObject.quote(it) } ?: "null"};")
            append("var access=${JSONObject.quote(t.access)};")
            append("var client=${JSONObject.quote(t.client)};")
            append(
                """
                fetch(url,{
                  method:method,
                  credentials:'include',
                  headers:{
                    'Authorization':'Bearer '+access,
                    'Client-Token':client,
                    'Content-Type':'application/json',
                    'Accept':'application/json',
                    'App-Platform':'WebPlayer',
                    'Origin':'https://open.spotify.com',
                    'Referer':'https://open.spotify.com/'
                  },
                  body: body===null?undefined:body
                }).then(function(res){
                  return res.text().then(function(text){
                    SpotiCloudBridge.onFetchResult(id, res.status, text);
                  });
                }).catch(function(err){
                  SpotiCloudBridge.onFetchError(id, String(err&&err.message?err.message:err));
                });
                """.trimIndent().replace('\n', ' '),
            )
            append("})();")
        }

        withContext(Dispatchers.Main) {
            val wv = webView ?: error("WebView not started")
            wv.evaluateJavascript(script, null)
        }

        return try {
            withTimeout(45_000) { deferred.await() }
        } finally {
            pending.remove(id)
        }
    }

    fun invalidate() {
        accessToken.set(null)
        clientToken.set(null)
        pageReady = false
        if (pageReadySignal.isCompleted) {
            pageReadySignal = CompletableDeferred()
        }
        // Don't auto-load /collection here — that SPA burst is a common fresh-429 cause
        main.post {
            webView?.stopLoading()
        }
    }

    fun destroy() {
        main.post {
            webView?.apply {
                stopLoading()
                destroy()
            }
            webView = null
            pageReady = false
        }
    }

    private inner class JsBridge {
        @JavascriptInterface
        fun onFetchResult(id: String, status: Int, body: String) {
            pending.remove(id)?.complete(FetchResult(status, body))
        }

        @JavascriptInterface
        fun onFetchError(id: String, message: String) {
            pending.remove(id)?.completeExceptionally(RuntimeException(message))
        }

        @JavascriptInterface
        fun onTokens(access: String?, client: String?) {
            if (!access.isNullOrBlank()) accessToken.set(access.trim())
            if (!client.isNullOrBlank()) clientToken.set(client.trim())
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebViewLocked() {
        val cm = CookieManager.getInstance()
        cm.setAcceptCookie(true)
        // Restore session cookies from login prefs into the shared CookieManager
        tokenStore.spDc?.trim()?.takeIf { it.isNotEmpty() }?.let { spDc ->
            cm.setCookie("https://open.spotify.com", "sp_dc=$spDc; Path=/; Domain=.spotify.com")
            cm.setCookie("https://accounts.spotify.com", "sp_dc=$spDc; Path=/; Domain=.spotify.com")
        }
        tokenStore.spT?.trim()?.takeIf { it.isNotEmpty() }?.let { spT ->
            cm.setCookie("https://open.spotify.com", "sp_t=$spT; Path=/; Domain=.spotify.com")
        }
        cm.flush()

        val wv = WebView(app).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            settings.userAgentString = USER_AGENT
            cm.setAcceptThirdPartyCookies(this, true)
            addJavascriptInterface(JsBridge(), "SpotiCloudBridge")
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(
                    view: WebView?,
                    request: WebResourceRequest?,
                ): android.webkit.WebResourceResponse? {
                    captureHeaders(request?.requestHeaders)
                    return super.shouldInterceptRequest(view, request)
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    injectHooks()
                    if (!pageReady && url?.contains("open.spotify.com") == true &&
                        url.contains("accounts.spotify.com").not()
                    ) {
                        pageReady = true
                        if (!pageReadySignal.isCompleted) pageReadySignal.complete(Unit)
                        // Don't navigate to /collection — SPA library fetch storms cause 429s
                    }
                }
            }
            loadUrl("https://open.spotify.com/")
        }
        webView = wv
    }

    private fun captureHeaders(headers: Map<String, String>?) {
        if (headers.isNullOrEmpty()) return
        for ((k, v) in headers) {
            when {
                k.equals("Authorization", ignoreCase = true) &&
                    v.startsWith("Bearer ", ignoreCase = true) ->
                    accessToken.set(v.substringAfter(' ').trim())
                k.equals("Client-Token", ignoreCase = true) && v.isNotBlank() ->
                    clientToken.set(v.trim())
            }
        }
    }

    private fun injectHooks() {
        val wv = webView ?: return
        val js =
            """
            (function(){
              if (window.__nexusHooked) return;
              window.__nexusHooked = true;
              function note(h){
                try{
                  if(!h) return;
                  var a=null,c=null;
                  if(typeof h.get==='function'){
                    a=h.get('authorization')||h.get('Authorization');
                    c=h.get('client-token')||h.get('Client-Token');
                  } else {
                    for (var k in h){
                      if(!Object.prototype.hasOwnProperty.call(h,k)) continue;
                      var lk=k.toLowerCase();
                      if(lk==='authorization') a=h[k];
                      if(lk==='client-token') c=h[k];
                    }
                  }
                  if(a && String(a).toLowerCase().indexOf('bearer ')===0){
                    SpotiCloudBridge.onTokens(String(a).slice(7).trim(), null);
                  }
                  if(c) SpotiCloudBridge.onTokens(null, String(c).trim());
                }catch(e){}
              }
              var of=window.fetch;
              window.fetch=function(input, init){
                try{ if(init) note(init.headers); }catch(e){}
                return of.apply(this, arguments);
              };
              var srh=XMLHttpRequest.prototype.setRequestHeader;
              XMLHttpRequest.prototype.setRequestHeader=function(name,value){
                try{
                  var n=String(name).toLowerCase();
                  if(n==='authorization' && String(value).toLowerCase().indexOf('bearer ')===0){
                    SpotiCloudBridge.onTokens(String(value).slice(7).trim(), null);
                  }
                  if(n==='client-token') SpotiCloudBridge.onTokens(null, String(value).trim());
                }catch(e){}
                return srh.apply(this, arguments);
              };
            })();
            """.trimIndent()
        wv.evaluateJavascript(js, null)
    }

    private suspend fun waitForTokens(timeoutMs: Long) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (!accessToken.get().isNullOrBlank() && !clientToken.get().isNullOrBlank()) return
            kotlinx.coroutines.delay(300)
        }
    }

    companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Mobile Safari/537.36"

        @Volatile private var instance: WebPlayerBridge? = null

        fun get(app: Application): WebPlayerBridge {
            return instance ?: synchronized(this) {
                instance ?: WebPlayerBridge(app).also { instance = it }
            }
        }
    }
}
