package com.nexus.spotifydesktop.soundcloud

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Application
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.lang.ref.WeakReference
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Hidden soundcloud.com WebView used for likes.
 *
 * OkHttp PUT likes are blocked by DataDome; DELETE unlikes often work.
 * Primary like strategy: open the track page and click SoundCloud's own Like button
 * (same cookies / JS environment as a real user).
 */
class SoundCloudWebBridge private constructor(
    private val app: Application,
    private val store: SoundCloudTokenStore,
) {
    private val main = Handler(Looper.getMainLooper())
    private val mutex = Mutex()
    private val pending = ConcurrentHashMap<String, CompletableDeferred<FetchResult>>()
    private var hostRef: WeakReference<Activity>? = null

    @Volatile private var webView: WebView? = null
    @Volatile private var pageReady = false
    private var pageReadySignal = CompletableDeferred<Unit>()

    data class FetchResult(val status: Int, val body: String)

    fun attachHost(activity: Activity) {
        hostRef = WeakReference(activity)
        main.post { ensureAttachedToHost() }
    }

    suspend fun ensureReady() {
        if (!store.hasSession) error("SoundCloud not connected")
        mutex.withLock {
            if (webView == null) {
                withContext(Dispatchers.Main) { createWebViewLocked() }
            } else {
                withContext(Dispatchers.Main) { ensureAttachedToHost() }
            }
        }
        withTimeout(45_000) { pageReadySignal.await() }
    }

    /**
     * Like by clicking the site Like button on the track page (bypasses API DataDome).
     */
    suspend fun likeViaTrackPage(permalinkUrl: String): FetchResult {
        ensureReady()
        val url = permalinkUrl.trim().ifBlank { error("Missing track URL") }
        val id = UUID.randomUUID().toString()
        val deferred = CompletableDeferred<FetchResult>()
        pending[id] = deferred

        withContext(Dispatchers.Main) {
            val wv = webView ?: error("SoundCloud WebView not ready")
            wv.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, finishedUrl: String?) {
                    super.onPageFinished(view, finishedUrl)
                    if (finishedUrl.isNullOrBlank()) return
                    // Wait a beat for React to paint the like button
                    view?.postDelayed({
                        view.evaluateJavascript(clickLikeScript(id), null)
                    }, 1200)
                }
            }
            wv.loadUrl(url)
        }

        return try {
            withTimeout(30_000) { deferred.await() }
        } finally {
            pending.remove(id)
        }
    }

    suspend fun unlikeViaTrackPage(permalinkUrl: String): FetchResult {
        ensureReady()
        val url = permalinkUrl.trim().ifBlank { error("Missing track URL") }
        val id = UUID.randomUUID().toString()
        val deferred = CompletableDeferred<FetchResult>()
        pending[id] = deferred

        withContext(Dispatchers.Main) {
            val wv = webView ?: error("SoundCloud WebView not ready")
            wv.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, finishedUrl: String?) {
                    super.onPageFinished(view, finishedUrl)
                    view?.postDelayed({
                        view.evaluateJavascript(clickUnlikeScript(id), null)
                    }, 1200)
                }
            }
            wv.loadUrl(url)
        }

        return try {
            withTimeout(30_000) { deferred.await() }
        } finally {
            pending.remove(id)
        }
    }

    /** API mutate from inside the page (fallback). */
    suspend fun mutateApi(method: String, apiUrl: String): FetchResult {
        ensureReady()
        // Make sure we're on soundcloud.com origin for CORS
        withContext(Dispatchers.Main) {
            val wv = webView ?: return@withContext
            val cur = wv.url.orEmpty()
            if (!cur.contains("soundcloud.com")) {
                val nav = CompletableDeferred<Unit>()
                wv.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        nav.complete(Unit)
                    }
                }
                wv.loadUrl("https://soundcloud.com/discover")
                withTimeout(20_000) { nav.await() }
            }
        }

        val id = UUID.randomUUID().toString()
        val deferred = CompletableDeferred<FetchResult>()
        pending[id] = deferred
        val oauth = store.oauthToken.orEmpty()
        val script = """
            (function(){
              var id=${JSONObject.quote(id)};
              var url=${JSONObject.quote(apiUrl)};
              var method=${JSONObject.quote(method)};
              var oauth=${JSONObject.quote(oauth)};
              var headers={
                'Accept':'application/json',
                'Content-Type':'application/json'
              };
              if(oauth) headers['Authorization']='OAuth '+oauth;
              var opts={method:method, credentials:'include', headers:headers};
              if(method==='PUT'||method==='POST') opts.body='{}';
              fetch(url, opts).then(function(r){
                return r.text().then(function(t){
                  if(window.SpotiCloudScBridge) window.SpotiCloudScBridge.onResult(id, r.status, t||'');
                });
              }).catch(function(e){
                if(window.SpotiCloudScBridge) window.SpotiCloudScBridge.onResult(id, 0, String(e&&e.message||e));
              });
            })();
        """.trimIndent()

        withContext(Dispatchers.Main) {
            webView?.evaluateJavascript(script, null)
        }
        return try {
            withTimeout(25_000) { deferred.await() }
        } finally {
            pending.remove(id)
        }
    }

    fun release() {
        main.post {
            (webView?.parent as? ViewGroup)?.removeView(webView)
            webView?.destroy()
            webView = null
            pageReady = false
            pageReadySignal = CompletableDeferred()
        }
    }

    private fun clickLikeScript(id: String): String = """
        (function(){
          var id=${JSONObject.quote(id)};
          function done(status, body){
            if(window.SpotiCloudScBridge) window.SpotiCloudScBridge.onResult(id, status, body||'');
          }
          try {
            var selected = document.querySelector(
              'button.sc-button-like.sc-button-selected, button.sc-button-like[aria-pressed="true"], button[aria-label="Unlike"]'
            );
            if (selected) { done(200, 'already-liked'); return; }
            var btn = document.querySelector('button.sc-button-like')
              || document.querySelector('button[aria-label="Like"]')
              || document.querySelector('button[aria-label*="Like"]')
              || document.querySelector('.playbackSoundBadge__like');
            if (!btn) { done(404, 'like-button-not-found'); return; }
            btn.click();
            setTimeout(function(){
              var ok = document.querySelector(
                'button.sc-button-like.sc-button-selected, button.sc-button-like[aria-pressed="true"], button[aria-label="Unlike"]'
              );
              done(ok ? 200 : 202, ok ? 'liked' : 'clicked');
            }, 1600);
          } catch(e) { done(0, String(e&&e.message||e)); }
        })();
    """.trimIndent()

    private fun clickUnlikeScript(id: String): String = """
        (function(){
          var id=${JSONObject.quote(id)};
          function done(status, body){
            if(window.SpotiCloudScBridge) window.SpotiCloudScBridge.onResult(id, status, body||'');
          }
          try {
            var btn = document.querySelector(
              'button.sc-button-like.sc-button-selected, button.sc-button-like[aria-pressed="true"], button[aria-label="Unlike"]'
            ) || document.querySelector('button.sc-button-like');
            if (!btn) { done(404, 'unlike-button-not-found'); return; }
            var pressed = btn.classList.contains('sc-button-selected')
              || btn.getAttribute('aria-pressed')==='true'
              || (btn.getAttribute('aria-label')||'').toLowerCase().indexOf('unlike')>=0;
            if (!pressed) { done(200, 'already-unliked'); return; }
            btn.click();
            setTimeout(function(){ done(200, 'unliked'); }, 1600);
          } catch(e) { done(0, String(e&&e.message||e)); }
        })();
    """.trimIndent()

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebViewLocked() {
        pageReady = false
        pageReadySignal = CompletableDeferred()
        CookieManager.getInstance().setAcceptCookie(true)

        val wv = WebView(app).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.userAgentString =
                "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            addJavascriptInterface(
                object {
                    @JavascriptInterface
                    fun onResult(id: String, status: Int, body: String) {
                        pending[id]?.complete(FetchResult(status, body))
                    }
                },
                "SpotiCloudScBridge",
            )
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    if (!pageReady && url?.contains("soundcloud.com") == true) {
                        pageReady = true
                        if (!pageReadySignal.isCompleted) pageReadySignal.complete(Unit)
                    }
                }
            }
            loadUrl("https://soundcloud.com/discover")
        }
        webView = wv
        ensureAttachedToHost()
    }

    private fun ensureAttachedToHost() {
        val wv = webView ?: return
        if (wv.parent != null) return
        val activity = hostRef?.get() ?: return
        val root = activity.window?.decorView as? ViewGroup ?: return
        root.addView(
            wv,
            FrameLayout.LayoutParams(1, 1).apply {
                // Keep off-screen / tiny so it can run JS + network
                leftMargin = -100
                topMargin = -100
            },
        )
    }

    companion object {
        @Volatile private var instance: SoundCloudWebBridge? = null

        fun get(app: Application, store: SoundCloudTokenStore): SoundCloudWebBridge {
            return instance ?: synchronized(this) {
                instance ?: SoundCloudWebBridge(app, store).also { instance = it }
            }
        }
    }
}
