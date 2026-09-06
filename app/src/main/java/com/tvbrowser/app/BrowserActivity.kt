package com.tvbrowser.app

import android.annotation.SuppressLint
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Message
import android.os.SystemClock
import android.util.Patterns
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.tvbrowser.app.adblock.AdBlockManager
import com.tvbrowser.app.model.Bookmark
import com.tvbrowser.app.model.BookmarkStore
import com.tvbrowser.app.model.BrowserSettings
import java.io.ByteArrayInputStream
import kotlin.math.min

/**
 * A single full-screen WebView acting as the "player". Ad hosts are dropped
 * at the network layer (see [tvWebViewClient]). Everything else about this
 * screen exists to make an arbitrary website - not just well-behaved ones -
 * controllable from a D-pad remote with no touchscreen and no keyboard: a
 * visible on-screen cursor the D-pad moves and OK "taps", instead of relying
 * on each site's own (often absent) keyboard/focus navigation.
 */
class BrowserActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var progressBar: ProgressBar
    private lateinit var overlayToolbar: LinearLayout
    private lateinit var adsBlockedBadge: TextView
    private lateinit var hintText: TextView
    private lateinit var fullscreenContainer: FrameLayout
    private lateinit var pointerCursor: ImageView
    private lateinit var store: BookmarkStore
    private lateinit var settings: BrowserSettings

    private var defaultUserAgent: String = ""

    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null
    private val isInFullscreenVideo: Boolean get() = customView != null

    private var toolbarVisible = false
    private var cursorHintShown = false

    // Cursor position, in WebView-local pixels.
    private var cursorX = 0f
    private var cursorY = 0f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_browser)
        store = BookmarkStore(this)
        settings = BrowserSettings(this)

        webView = findViewById(R.id.webView)
        progressBar = findViewById(R.id.progressBar)
        overlayToolbar = findViewById(R.id.overlayToolbar)
        adsBlockedBadge = findViewById(R.id.adsBlockedBadge)
        hintText = findViewById(R.id.playerHelpHint)
        fullscreenContainer = findViewById(R.id.fullscreenContainer)
        pointerCursor = findViewById(R.id.pointerCursor)

        setupWebView()
        setupToolbar()
        initCursor()

        val url = intent.getStringExtra(EXTRA_URL) ?: "https://www.google.com"
        webView.loadUrl(url)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
            mediaPlaybackRequiresUserGesture = false
            cacheMode = WebSettings.LOAD_DEFAULT
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = true
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
        }
        defaultUserAgent = webView.settings.userAgentString
        applyUserAgent(reload = false)

        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            webView.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, true)
        }

        webView.webViewClient = tvWebViewClient()
        webView.webChromeClient = tvWebChromeClient()
    }

    private fun applyUserAgent(reload: Boolean) {
        webView.settings.userAgentString = if (settings.desktopMode) {
            BrowserSettings.DESKTOP_USER_AGENT
        } else {
            defaultUserAgent
        }
        if (reload) webView.reload()
    }

    // -------------------------------------------------------------------
    // Ad blocking + navigation
    // -------------------------------------------------------------------

    private fun tvWebViewClient() = object : WebViewClient() {

        override fun shouldInterceptRequest(
            view: WebView,
            request: WebResourceRequest
        ): WebResourceResponse? {
            if (AdBlockManager.isBlocked(request.url.host)) {
                return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
            }
            return super.shouldInterceptRequest(view, request)
        }

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            if (request.isForMainFrame && AdBlockManager.isBlocked(request.url.host)) {
                return true // swallow navigation to a known ad/malware host entirely
            }
            return false
        }

        override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
            super.onPageStarted(view, url, favicon)
            progressBar.visibility = View.VISIBLE
            AdBlockManager.resetCounter()
        }

        override fun onPageFinished(view: WebView, url: String?) {
            super.onPageFinished(view, url)
            progressBar.visibility = View.GONE
            injectPlayerHelper()
            showAdsBlockedBadge()
            maybeShowCursorHint()
        }
    }

    private fun tvWebChromeClient() = object : WebChromeClient() {

        override fun onProgressChanged(view: WebView, newProgress: Int) {
            progressBar.progress = newProgress
        }

        override fun onShowCustomView(view: View, callback: CustomViewCallback) {
            if (customView != null) {
                callback.onCustomViewHidden()
                return
            }
            customView = view
            customViewCallback = callback
            fullscreenContainer.addView(
                view,
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            )
            fullscreenContainer.visibility = View.VISIBLE
            webView.visibility = View.GONE
            pointerCursor.visibility = View.GONE
            enterImmersiveMode()
            showHint(getString(R.string.player_help))
        }

        override fun onHideCustomView() {
            val view = customView ?: return
            fullscreenContainer.removeView(view)
            fullscreenContainer.visibility = View.GONE
            webView.visibility = View.VISIBLE
            pointerCursor.visibility = View.VISIBLE
            customView = null
            customViewCallback?.onCustomViewHidden()
            customViewCallback = null
            exitImmersiveMode()
        }

        override fun onCreateWindow(
            view: WebView,
            isDialog: Boolean,
            isUserGesture: Boolean,
            resultMsg: Message
        ): Boolean {
            // Most "open in new window" calls on ad-laden video sites are
            // pop-under ads. Resolve the target URL with a throwaway WebView
            // first and only follow it in the main WebView when it is not an
            // ad/malware host - legitimate same-site links keep working.
            val transport = WebView(this@BrowserActivity)
            transport.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(v: WebView, request: WebResourceRequest): Boolean {
                    val host = request.url.host
                    if (!AdBlockManager.isBlocked(host)) {
                        webView.loadUrl(request.url.toString())
                    }
                    return true
                }
            }
            (resultMsg.obj as WebView.WebViewTransport).webView = transport
            resultMsg.sendToTarget()
            return true
        }
    }

    private fun injectPlayerHelper() {
        val js = """
            (function() {
                window.__tvbrowser = {
                    seek: function(d) {
                        var v = document.querySelector('video');
                        if (v) { v.currentTime = Math.max(0, v.currentTime + d); }
                    },
                    toggle: function() {
                        var v = document.querySelector('video');
                        if (v) { if (v.paused || v.ended) { v.play(); } else { v.pause(); } }
                    },
                    pauseAll: function() {
                        document.querySelectorAll('video').forEach(function(v) { v.pause(); });
                    }
                };
            })();
        """.trimIndent()
        webView.evaluateJavascript(js, null)
    }

    private fun seekVideo(deltaSeconds: Int) {
        webView.evaluateJavascript("window.__tvbrowser && window.__tvbrowser.seek($deltaSeconds);", null)
    }

    private fun togglePlayback() {
        webView.evaluateJavascript("window.__tvbrowser && window.__tvbrowser.toggle();", null)
    }

    private fun pauseAllVideo() {
        webView.evaluateJavascript("window.__tvbrowser && window.__tvbrowser.pauseAll();", null)
    }

    // -------------------------------------------------------------------
    // D-pad pointer emulation: a visible cursor the D-pad moves and OK taps.
    // This is what makes arbitrary sites (video grids, custom players with
    // no keyboard support) usable, instead of relying on each page's own
    // (often missing) focus/keyboard handling.
    // -------------------------------------------------------------------

    private fun initCursor() {
        webView.post {
            cursorX = webView.width / 2f
            cursorY = webView.height / 2f
            updateCursorView()
        }
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density

    private fun cursorStep(event: KeyEvent): Float {
        val acceleration = min(event.repeatCount, 15) * dp(4f)
        return dp(20f) + acceleration
    }

    private fun moveCursor(dx: Float, dy: Float) {
        val width = webView.width.toFloat()
        val height = webView.height.toFloat()
        if (width <= 0f || height <= 0f) return

        val edge = CURSOR_EDGE_MARGIN_DP * resources.displayMetrics.density

        var newX = cursorX + dx
        if (dx < 0 && newX < edge) {
            webView.scrollBy(dx.toInt(), 0)
            newX = cursorX
        } else if (dx > 0 && newX > width - edge) {
            webView.scrollBy(dx.toInt(), 0)
            newX = cursorX
        }

        var newY = cursorY + dy
        if (dy < 0 && newY < edge) {
            webView.scrollBy(0, dy.toInt())
            newY = cursorY
        } else if (dy > 0 && newY > height - edge) {
            webView.scrollBy(0, dy.toInt())
            newY = cursorY
        }

        cursorX = newX.coerceIn(0f, width)
        cursorY = newY.coerceIn(0f, height)
        updateCursorView()
    }

    private fun updateCursorView() {
        pointerCursor.x = webView.x + cursorX - CURSOR_HOTSPOT_OFFSET_DP * resources.displayMetrics.density
        pointerCursor.y = webView.y + cursorY - CURSOR_HOTSPOT_OFFSET_DP * resources.displayMetrics.density
    }

    private fun clickAtCursor() {
        val downTime = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, cursorX, cursorY, 0)
        val up = MotionEvent.obtain(downTime, downTime + 60, MotionEvent.ACTION_UP, cursorX, cursorY, 0)
        webView.dispatchTouchEvent(down)
        webView.dispatchTouchEvent(up)
        down.recycle()
        up.recycle()
    }

    private fun maybeShowCursorHint() {
        if (cursorHintShown || isInFullscreenVideo) return
        cursorHintShown = true
        showHint(getString(R.string.cursor_help))
    }

    // -------------------------------------------------------------------
    // Fullscreen / immersive mode
    // -------------------------------------------------------------------

    @Suppress("DEPRECATION")
    private fun enterImmersiveMode() {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            )
    }

    @Suppress("DEPRECATION")
    private fun exitImmersiveMode() {
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE
    }

    private fun showHint(text: String) {
        hintText.text = text
        hintText.visibility = View.VISIBLE
        hintText.animate().setStartDelay(4000).alpha(0f).withEndAction {
            hintText.visibility = View.GONE
            hintText.alpha = 1f
        }.start()
    }

    private fun showAdsBlockedBadge() {
        val count = AdBlockManager.blockedCount
        if (count <= 0) return
        adsBlockedBadge.text = getString(R.string.ads_blocked_format, count)
        adsBlockedBadge.visibility = View.VISIBLE
        adsBlockedBadge.animate().setStartDelay(3000).alpha(0f).withEndAction {
            adsBlockedBadge.visibility = View.GONE
            adsBlockedBadge.alpha = 1f
        }.start()
    }

    // -------------------------------------------------------------------
    // Overlay toolbar (Back / Forward / Reload / Home / Bookmark / Desktop / URL)
    // -------------------------------------------------------------------

    private fun setupToolbar() {
        findViewById<View>(R.id.btnBack).setOnClickListener {
            if (webView.canGoBack()) webView.goBack()
            hideToolbar()
        }
        findViewById<View>(R.id.btnForward).setOnClickListener {
            if (webView.canGoForward()) webView.goForward()
            hideToolbar()
        }
        findViewById<View>(R.id.btnReload).setOnClickListener {
            webView.reload()
            hideToolbar()
        }
        findViewById<View>(R.id.btnHome).setOnClickListener {
            finish()
        }
        findViewById<View>(R.id.btnBookmark).setOnClickListener {
            val title = webView.title?.takeIf { it.isNotBlank() } ?: webView.url ?: "Bookmark"
            val url = webView.url
            if (url != null) {
                store.addBookmark(Bookmark(title, url))
                Toast.makeText(this, R.string.add_bookmark, Toast.LENGTH_SHORT).show()
            }
            hideToolbar()
        }
        findViewById<View>(R.id.btnDesktop).setOnClickListener {
            settings.desktopMode = !settings.desktopMode
            applyUserAgent(reload = true)
            val message = if (settings.desktopMode) R.string.desktop_mode_on_toast else R.string.desktop_mode_off_toast
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
            hideToolbar()
        }
        findViewById<View>(R.id.btnUrl).setOnClickListener { showUrlDialog() }
    }

    private fun toggleToolbar() {
        if (toolbarVisible) hideToolbar() else showToolbar()
    }

    private fun showToolbar() {
        toolbarVisible = true
        overlayToolbar.visibility = View.VISIBLE
        findViewById<TextView>(R.id.btnUrl).text = webView.url ?: getString(R.string.menu_url)
        findViewById<View>(R.id.btnBack).requestFocus()
    }

    private fun hideToolbar() {
        toolbarVisible = false
        overlayToolbar.visibility = View.GONE
    }

    private fun showUrlDialog() {
        val input = EditText(this).apply {
            hint = getString(R.string.url_hint)
            setSingleLine(true)
            setText(webView.url ?: "")
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 32, 48, 0)
            addView(input)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.open_url)
            .setView(container)
            .setPositiveButton(R.string.action_go) { _, _ ->
                val text = input.text.toString().trim()
                if (text.isNotEmpty()) {
                    webView.loadUrl(normalizeInput(text))
                    hideToolbar()
                }
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun normalizeInput(text: String): String {
        return when {
            text.startsWith("http://") || text.startsWith("https://") -> text
            Patterns.WEB_URL.matcher(text).matches() -> "https://$text"
            else -> "https://www.google.com/search?q=" + Uri.encode(text)
        }
    }

    // -------------------------------------------------------------------
    // Remote control key handling
    // -------------------------------------------------------------------

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_BACK -> {
                    if (isInFullscreenVideo) {
                        webView.webChromeClient?.onHideCustomView()
                        return true
                    }
                    if (toolbarVisible) {
                        hideToolbar()
                        return true
                    }
                    if (webView.canGoBack()) {
                        webView.goBack()
                        return true
                    }
                }
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                    togglePlayback()
                    return true
                }
                KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, KeyEvent.KEYCODE_MEDIA_NEXT -> {
                    seekVideo(10)
                    return true
                }
                KeyEvent.KEYCODE_MEDIA_REWIND, KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                    seekVideo(-10)
                    return true
                }
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    if (isInFullscreenVideo) {
                        seekVideo(-10)
                        return true
                    }
                    if (!toolbarVisible) {
                        moveCursor(-cursorStep(event), 0f)
                        return true
                    }
                }
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    if (isInFullscreenVideo) {
                        seekVideo(10)
                        return true
                    }
                    if (!toolbarVisible) {
                        moveCursor(cursorStep(event), 0f)
                        return true
                    }
                }
                KeyEvent.KEYCODE_DPAD_UP -> {
                    if (!isInFullscreenVideo && !toolbarVisible) {
                        moveCursor(0f, -cursorStep(event))
                        return true
                    }
                }
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    if (!isInFullscreenVideo && !toolbarVisible) {
                        moveCursor(0f, cursorStep(event))
                        return true
                    }
                }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                    if (isInFullscreenVideo) {
                        togglePlayback()
                        return true
                    }
                    if (!toolbarVisible) {
                        clickAtCursor()
                        return true
                    }
                }
                KeyEvent.KEYCODE_MENU -> {
                    toggleToolbar()
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onStop() {
        super.onStop()
        pauseAllVideo()
        webView.onPause()
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
    }

    override fun onDestroy() {
        fullscreenContainer.removeAllViews()
        webView.destroy()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_URL = "extra_url"
        private const val CURSOR_EDGE_MARGIN_DP = 32f
        private const val CURSOR_HOTSPOT_OFFSET_DP = 3f
    }
}
