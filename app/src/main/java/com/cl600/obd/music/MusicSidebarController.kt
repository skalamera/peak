package com.cl600.obd.music

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import androidx.core.widget.ImageViewCompat
import com.cl600.obd.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Controls the 3-state collapsible YouTube Music sidebar:
 * - RAIL (default): 44dp slim cockpit rail with quick play/pause, prev/next, like/dislike, vol+/-, hide
 * - PEEK: 22x58dp right-edge floating tab for unobstructed full-width gauges
 * - EXPANDED: 364dp overlay slide-over drawer with persistent WebView or APP Companion mode
 */
class MusicSidebarController(
    private val activity: Activity,
    private val rootView: View,
    private val onDrawerStateChanged: (expanded: Boolean) -> Unit = {}
) {
    private enum class SidebarState { RAIL, PEEK, EXPANDED }

    private val prefs = activity.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val audioManager = activity.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    // Rail & overlay chrome
    private val layoutMusicRail: View = rootView.findViewById(R.id.layoutMusicRail)
    private val viewRailProgressTrack: View = rootView.findViewById(R.id.viewRailProgressTrack)
    private val viewRailProgress: View = rootView.findViewById(R.id.viewRailProgress)
    private val btnRailExpand: ImageButton = rootView.findViewById(R.id.btnRailExpand)
    private val btnRailLike: ImageButton = rootView.findViewById(R.id.btnRailLike)
    private val btnRailPrev: ImageButton = rootView.findViewById(R.id.btnRailPrev)
    private val btnRailPlayPause: ImageButton = rootView.findViewById(R.id.btnRailPlayPause)
    private val btnRailNext: ImageButton = rootView.findViewById(R.id.btnRailNext)
    private val btnRailDislike: ImageButton = rootView.findViewById(R.id.btnRailDislike)
    private val btnRailVolUp: ImageButton = rootView.findViewById(R.id.btnRailVolUp)
    private val btnRailVolDown: ImageButton = rootView.findViewById(R.id.btnRailVolDown)
    private val btnRailHide: ImageButton = rootView.findViewById(R.id.btnRailHide)

    private val btnMusicPeekTab: View = rootView.findViewById(R.id.btnMusicPeekTab)
    private val viewMusicScrim: View = rootView.findViewById(R.id.viewMusicScrim)
    private val musicDrawerRoot: View = rootView.findViewById(R.id.musicDrawerRoot)

    // Auto-fading track banner
    private val layoutNowPlayingBanner: View = rootView.findViewById(R.id.layoutNowPlayingBanner)
    private val tvBannerTitle: TextView = rootView.findViewById(R.id.tvBannerTitle)
    private val tvBannerArtist: TextView = rootView.findViewById(R.id.tvBannerArtist)

    // Drawer views
    private val btnModeWeb: TextView = musicDrawerRoot.findViewById(R.id.btnModeWeb)
    private val btnModeApp: TextView = musicDrawerRoot.findViewById(R.id.btnModeApp)
    private val btnYtmReload: TextView = musicDrawerRoot.findViewById(R.id.btnYtmReload)
    private val btnDrawerCollapse: ImageButton = musicDrawerRoot.findViewById(R.id.btnDrawerCollapse)
    private val tvDrawerTrack: TextView = musicDrawerRoot.findViewById(R.id.tvDrawerTrack)
    private val tvDrawerArtist: TextView = musicDrawerRoot.findViewById(R.id.tvDrawerArtist)
    private val btnDrawerLike: ImageButton = musicDrawerRoot.findViewById(R.id.btnDrawerLike)
    private val btnDrawerPrev: ImageButton = musicDrawerRoot.findViewById(R.id.btnDrawerPrev)
    private val btnDrawerPlayPause: ImageButton = musicDrawerRoot.findViewById(R.id.btnDrawerPlayPause)
    private val btnDrawerNext: ImageButton = musicDrawerRoot.findViewById(R.id.btnDrawerNext)
    private val btnDrawerDislike: ImageButton = musicDrawerRoot.findViewById(R.id.btnDrawerDislike)
    private val viewDrawerProgress: View = musicDrawerRoot.findViewById(R.id.viewDrawerProgress)

    // WEB pane
    private val layoutWebPane: View = musicDrawerRoot.findViewById(R.id.layoutWebPane)
    private val webViewHost: FrameLayout = musicDrawerRoot.findViewById(R.id.webViewHost)
    private var webViewYtm: WebView? = null
    private val progressBarYtmWeb: ProgressBar = musicDrawerRoot.findViewById(R.id.progressBarYtmWeb)
    private val layoutWebAuthBanner: View = musicDrawerRoot.findViewById(R.id.layoutWebAuthBanner)
    private val btnBannerRetryHome: TextView = musicDrawerRoot.findViewById(R.id.btnBannerRetryHome)
    private val btnBannerSwitchApp: TextView = musicDrawerRoot.findViewById(R.id.btnBannerSwitchApp)

    // APP companion pane
    private val layoutAppPane: ScrollView = musicDrawerRoot.findViewById(R.id.layoutAppPane)
    private val tvAppSessionStatus: TextView = musicDrawerRoot.findViewById(R.id.tvAppSessionStatus)
    private val tvAppBigTitle: TextView = musicDrawerRoot.findViewById(R.id.tvAppBigTitle)
    private val tvAppBigArtist: TextView = musicDrawerRoot.findViewById(R.id.tvAppBigArtist)
    private val btnOpenYtmApp: TextView = musicDrawerRoot.findViewById(R.id.btnOpenYtmApp)
    private val tvNotifAccessDesc: TextView = musicDrawerRoot.findViewById(R.id.tvNotifAccessDesc)
    private val btnGrantNotifAccess: TextView = musicDrawerRoot.findViewById(R.id.btnGrantNotifAccess)
    private val btnAppPrev: ImageButton = musicDrawerRoot.findViewById(R.id.btnAppPrev)
    private val btnAppPlayPause: ImageButton = musicDrawerRoot.findViewById(R.id.btnAppPlayPause)
    private val btnAppNext: ImageButton = musicDrawerRoot.findViewById(R.id.btnAppNext)
    private val btnAppVolDown: ImageButton = musicDrawerRoot.findViewById(R.id.btnAppVolDown)
    private val btnAppVolUp: ImageButton = musicDrawerRoot.findViewById(R.id.btnAppVolUp)
    private val btnAppLike: ImageButton = musicDrawerRoot.findViewById(R.id.btnAppLike)
    private val btnAppDislike: ImageButton = musicDrawerRoot.findViewById(R.id.btnAppDislike)

    private val density = activity.resources.displayMetrics.density
    private var drawerWidthPx = (364f * density).toInt()
    private var lastAppliedCollapsedRtl: Boolean? = null
    private var sidebarState = loadSavedCollapsedState()
    private var lastCollapsedState = sidebarState
    private var musicMode = loadSavedMode()
    private val lazyWebView = LazyWebViewInitializer<WebView>()
    private val callbackGate = WebViewCallbackGate()
    private var webViewInitialized = false
    private var latestWebState = MusicState(mode = MusicMode.WEB)
    private var lastNotifiedTrackKey = ""
    private var bannerHideRunnable: Runnable? = null
    private var pollJob: Job? = null
    private var capabilityRefreshJob: Job? = null
    private var cachedYtmInstalled: Boolean = false
    private var cachedNotifAccessEnabled: Boolean = false

    init {
        // Keep drawer off-screen synchronously before first frame draws
        musicDrawerRoot.visibility = View.VISIBLE
        musicDrawerRoot.translationX = collapsedDrawerTranslation()
        musicDrawerRoot.addOnLayoutChangeListener { v, left, _, right, _, _, _, _, _ ->
            val w = right - left
            val currentRtl = isRtlLayout()
            if (DrawerLayoutGeometry.shouldReapplyCollapsedTranslation(
                    prevWidthPx = drawerWidthPx,
                    newWidthPx = w,
                    prevRtl = lastAppliedCollapsedRtl,
                    currentRtl = currentRtl
                )
            ) {
                drawerWidthPx = w
                if (sidebarState != SidebarState.EXPANDED) {
                    v.translationX = collapsedDrawerTranslation()
                } else {
                    lastAppliedCollapsedRtl = currentRtl
                }
            }
        }
        viewRailProgressTrack.addOnLayoutChangeListener { _, _, top, _, bottom, _, _, _, _ ->
            if (bottom > top) {
                updateProgressHairlines(currentDisplayedState().progressFraction())
            }
        }

        bindClickListeners()
        if (lazyWebView.shouldInitializeOnStart(musicMode)) {
            initWebViewIfSupported()
        }
        applyCollapsedStateImmediate(sidebarState)
        applyModeUi(loadInitialPage = (musicMode == MusicMode.WEB))
        refreshCompanionCapabilitiesAsync()
        startObservers()
    }

    fun isExpanded(): Boolean = sidebarState == SidebarState.EXPANDED

    fun collapseDrawer() {
        if (sidebarState == SidebarState.EXPANDED) {
            transitionTo(lastCollapsedState, animate = true)
        }
    }

    fun expandDrawer() {
        transitionTo(SidebarState.EXPANDED, animate = true)
    }

    fun onResume() {
        MediaNotificationListenerService.requestRefreshAsync(activity.applicationContext)
        refreshCompanionCapabilitiesAsync()
        renderMusicState(currentDisplayedState())
    }

    fun destroy() {
        val wv = webViewYtm
        webViewInitialized = false
        webViewYtm = null
        lazyWebView.reset()
        capabilityRefreshJob?.cancel()
        pollJob?.cancel()
        bannerHideRunnable?.let { layoutNowPlayingBanner.removeCallbacks(it) }
        bannerHideRunnable = null
        layoutNowPlayingBanner.animate().cancel()
        viewMusicScrim.animate().cancel()
        musicDrawerRoot.animate().cancel()
        scope.cancel()
        try {
            callbackGate.teardown(
                stopLoading = { wv?.stopLoading() },
                clearClients = {
                    if (wv != null) {
                        wv.webChromeClient = null
                        wv.webViewClient = WebViewClient()
                    }
                },
                removeFromParent = {
                    (wv?.parent as? ViewGroup)?.removeView(wv)
                },
                destroyWebView = { wv?.destroy() }
            )
        } catch (_: Exception) {
        }
    }

    private fun bindClickListeners() {
        btnRailExpand.setOnClickListener { expandDrawer() }
        btnRailHide.setOnClickListener {
            lastCollapsedState = SidebarState.PEEK
            saveCollapsedState(SidebarState.PEEK)
            transitionTo(SidebarState.PEEK, animate = true)
        }
        btnMusicPeekTab.setOnClickListener {
            lastCollapsedState = SidebarState.RAIL
            saveCollapsedState(SidebarState.RAIL)
            transitionTo(SidebarState.RAIL, animate = true)
        }
        layoutNowPlayingBanner.setOnClickListener { expandDrawer() }
        viewMusicScrim.setOnClickListener { collapseDrawer() }
        btnDrawerCollapse.setOnClickListener { collapseDrawer() }

        btnModeWeb.setOnClickListener { switchMode(MusicMode.WEB) }
        btnModeApp.setOnClickListener { switchMode(MusicMode.APP) }
        btnYtmReload.setOnClickListener {
            if (musicMode == MusicMode.WEB) {
                layoutWebAuthBanner.visibility = View.GONE
                callbackGate.onNavigationStarted()
                if (!webViewInitialized) initWebViewIfSupported()
                webViewYtm?.loadUrl(YtmScriptBridge.YTM_HOME_URL)
            } else {
                MediaNotificationListenerService.requestRefreshAsync(activity.applicationContext)
                refreshCompanionCapabilitiesAsync()
                renderMusicState(currentDisplayedState())
            }
        }

        btnBannerRetryHome.setOnClickListener {
            layoutWebAuthBanner.visibility = View.GONE
            callbackGate.onNavigationStarted()
            if (!webViewInitialized) initWebViewIfSupported()
            webViewYtm?.loadUrl(YtmScriptBridge.YTM_HOME_URL)
        }
        btnBannerSwitchApp.setOnClickListener { switchMode(MusicMode.APP) }

        btnOpenYtmApp.setOnClickListener { openYoutubeMusicAppOrStore() }
        btnGrantNotifAccess.setOnClickListener { openNotificationListenerSettings() }

        // Transport bindings across Rail, Drawer strip, and APP companion pad
        listOf(btnRailPlayPause, btnDrawerPlayPause, btnAppPlayPause).forEach {
            it.setOnClickListener { performMusicAction(MusicAction.PLAY_PAUSE) }
        }
        listOf(btnRailNext, btnDrawerNext, btnAppNext).forEach {
            it.setOnClickListener { performMusicAction(MusicAction.NEXT) }
        }
        listOf(btnRailPrev, btnDrawerPrev, btnAppPrev).forEach {
            it.setOnClickListener { performMusicAction(MusicAction.PREVIOUS) }
        }
        listOf(btnRailLike, btnDrawerLike, btnAppLike).forEach {
            it.setOnClickListener { performMusicAction(MusicAction.LIKE) }
        }
        listOf(btnRailDislike, btnDrawerDislike, btnAppDislike).forEach {
            it.setOnClickListener { performMusicAction(MusicAction.DISLIKE) }
        }
        listOf(btnRailVolUp, btnAppVolUp).forEach {
            it.setOnClickListener { adjustMusicVolume(AudioManager.ADJUST_RAISE) }
        }
        listOf(btnRailVolDown, btnAppVolDown).forEach {
            it.setOnClickListener { adjustMusicVolume(AudioManager.ADJUST_LOWER) }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun initWebViewIfSupported() {
        if (callbackGate.destroyed || webViewInitialized) return
        var createdView: WebView? = null
        val initializedView = lazyWebView.ensureInitialized(
            createAndConfigure = {
                val webView = webViewYtm ?: WebView(activity).also { wv ->
                    createdView = wv
                    webViewHost.addView(
                        wv,
                        FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                    )
                    webViewYtm = wv
                }
                val settings = webView.settings
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.databaseEnabled = true
                settings.mediaPlaybackRequiresUserGesture = false
                settings.useWideViewPort = true
                settings.loadWithOverviewMode = true
                settings.cacheMode = WebSettings.LOAD_DEFAULT
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                settings.userAgentString =
                    YtmScriptBridge.sanitizeChromeUserAgent(settings.userAgentString)

                val cookieManager = CookieManager.getInstance()
                cookieManager.setAcceptCookie(true)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    cookieManager.setAcceptThirdPartyCookies(webView, true)
                }

                webView.setBackgroundColor(Color.BLACK)
                webView.webChromeClient = object : WebChromeClient() {
                    override fun onProgressChanged(view: WebView?, newProgress: Int) {
                        if (callbackGate.destroyed || view !== webViewYtm) return
                        if (newProgress in 1..94) {
                            progressBarYtmWeb.visibility = View.VISIBLE
                        } else {
                            progressBarYtmWeb.visibility = View.GONE
                        }
                    }
                }
                webView.webViewClient = object : WebViewClient() {
                    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
                    override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                        return if (callbackGate.destroyed) true else handleWebNavigation(url)
                    }

                    @RequiresApi(Build.VERSION_CODES.LOLLIPOP)
                    override fun shouldOverrideUrlLoading(
                        view: WebView?,
                        request: WebResourceRequest?
                    ): Boolean {
                        return if (callbackGate.destroyed) true
                        else handleWebNavigation(request?.url?.toString())
                    }

                    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                        super.onPageStarted(view, url, favicon)
                        if (callbackGate.destroyed || view !== webViewYtm) return
                        callbackGate.onNavigationStarted()
                        progressBarYtmWeb.visibility = View.VISIBLE
                        if (YtmScriptBridge.isGoogleWebViewAuthBlocked(url, null, null)) {
                            layoutWebAuthBanner.visibility = View.VISIBLE
                        }
                    }

                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        if (callbackGate.destroyed || view !== webViewYtm) return
                        progressBarYtmWeb.visibility = View.GONE
                        flushCookiesCompat()
                        checkGoogleAuthBlock(view, url)
                        pollWebViewOnce()
                    }
                }
                webView
            },
            onFailure = {
                createdView?.let { partial ->
                    try {
                        webViewHost.removeView(partial)
                        partial.destroy()
                    } catch (_: Throwable) {
                    }
                }
                webViewYtm = null
                webViewInitialized = false
                layoutWebAuthBanner.visibility = View.VISIBLE
            }
        )
        webViewInitialized = initializedView != null
    }

    private fun handleWebNavigation(url: String?): Boolean {
        return !YtmScriptBridge.isAllowedNavigationUrl(url)
    }

    private fun checkGoogleAuthBlock(view: WebView?, url: String?) {
        if (view == null || callbackGate.destroyed || view !== webViewYtm) return
        val gen = callbackGate.beginAuthCheck() ?: return
        val title = view.title
        if (YtmScriptBridge.isGoogleWebViewAuthBlocked(url, title, null)) {
            layoutWebAuthBanner.visibility = View.VISIBLE
            return
        }
        view.evaluateJavascript(
            "(document.body && document.body.innerText) ? document.body.innerText.slice(0, 600) : ''"
        ) { raw ->
            if (!callbackGate.isAuthCheckCurrent(gen) || view !== webViewYtm) return@evaluateJavascript
            val snippet = raw?.removeSurrounding("\"")
                ?.replace("\\n", " ")
                ?.replace("\\\"", "\"")
            val blocked = YtmScriptBridge.isGoogleWebViewAuthBlocked(url, view.title, snippet)
            layoutWebAuthBanner.visibility = if (blocked) View.VISIBLE else View.GONE
        }
    }

    private fun startObservers() {
        scope.launch {
            MediaNotificationListenerService.appState.collect { appMusicState ->
                if (MusicPollLoopPolicy.shouldCollectNativeUpdate(
                        mode = musicMode,
                        webHasActiveSession = latestWebState.hasActiveSession,
                        appStatePresent = appMusicState != null
                    )
                ) {
                    renderMusicState(currentDisplayedState())
                    updateCompanionSessionStatusOnly()
                }
            }
        }

        pollJob = scope.launch {
            while (isActive) {
                if (musicMode == MusicMode.WEB && webViewInitialized) {
                    pollWebViewOnce()
                }
                if (MusicPollLoopPolicy.shouldRefreshNativeOnPollTick(
                        mode = musicMode,
                        webHasActiveSession = latestWebState.hasActiveSession
                    )
                ) {
                    withContext(Dispatchers.IO) {
                        MediaNotificationListenerService.refreshFromContext(activity.applicationContext)
                    }
                }
                val activePlaying = currentDisplayedState().isPlaying ||
                    sidebarState == SidebarState.EXPANDED
                delay(if (activePlaying) 1000L else 2400L)
            }
        }
    }

    private fun pollWebViewOnce() {
        val wv = webViewYtm ?: return
        if (callbackGate.destroyed || !webViewInitialized) return
        if (!YtmScriptBridge.isYtmPlayerUrl(wv.url)) {
            if (latestWebState.hasActiveSession) {
                latestWebState = MusicState(mode = MusicMode.WEB)
                if (musicMode == MusicMode.WEB) {
                    renderMusicState(currentDisplayedState())
                }
            }
            return
        }
        val gen = callbackGate.captureWebCallbackGeneration() ?: return
        try {
            wv.evaluateJavascript(YtmScriptBridge.buildStatePollingJs()) { raw ->
                if (!callbackGate.isWebCallbackCurrent(gen) || wv !== webViewYtm) {
                    return@evaluateJavascript
                }
                val parsed = YtmScriptBridge.parseTrackPayload(raw, MusicMode.WEB)
                if (parsed != null) {
                    latestWebState = parsed
                    if (musicMode == MusicMode.WEB) {
                        renderMusicState(currentDisplayedState())
                    }
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun currentDisplayedPlayback(): DisplayedPlayback {
        val wv = webViewYtm
        val webReady = webViewInitialized && wv != null && YtmScriptBridge.isYtmPlayerUrl(wv.url)
        return MusicPlaybackRouter.resolve(
            mode = musicMode,
            webState = latestWebState,
            appState = MediaNotificationListenerService.appState.value,
            webViewReadyForPlayer = webReady
        )
    }

    private fun currentDisplayedState(): MusicState =
        currentDisplayedPlayback().state

    private fun switchMode(target: MusicMode) {
        if (musicMode == target) return
        musicMode = target
        prefs.edit().putString(KEY_MODE, target.name).apply()
        if (target == MusicMode.WEB && !webViewInitialized) {
            initWebViewIfSupported()
        }
        applyModeUi(loadInitialPage = (target == MusicMode.WEB && webViewYtm?.url.isNullOrBlank()))
        if (target == MusicMode.APP) {
            MediaNotificationListenerService.requestRefreshAsync(activity.applicationContext)
            refreshCompanionCapabilitiesAsync()
        } else {
            pollWebViewOnce()
        }
        renderMusicState(currentDisplayedState())
    }

    private fun applyModeUi(loadInitialPage: Boolean) {
        val isWeb = musicMode == MusicMode.WEB
        btnModeWeb.setBackgroundResource(if (isWeb) R.drawable.bg_tab_pill else 0)
        btnModeWeb.setTextColor(
            ContextCompat.getColor(
                activity,
                if (isWeb) R.color.text_primary else R.color.text_secondary
            )
        )
        btnModeApp.setBackgroundResource(if (!isWeb) R.drawable.bg_tab_pill else 0)
        btnModeApp.setTextColor(
            ContextCompat.getColor(
                activity,
                if (!isWeb) R.color.text_primary else R.color.text_secondary
            )
        )
        btnYtmReload.visibility = if (isWeb) View.VISIBLE else View.GONE
        layoutWebPane.visibility = if (isWeb) View.VISIBLE else View.GONE
        layoutAppPane.visibility = if (isWeb) View.GONE else View.VISIBLE

        if (isWeb && loadInitialPage && webViewInitialized && webViewYtm?.url.isNullOrBlank()) {
            webViewYtm?.loadUrl(YtmScriptBridge.YTM_HOME_URL)
        }
        updateCompanionCardUiFromCache()
    }

    private fun performMusicAction(action: MusicAction) {
        if (callbackGate.destroyed) return
        val displayed = currentDisplayedPlayback()
        val wv = webViewYtm
        if (displayed.target == CommandTarget.WEB &&
            webViewInitialized &&
            wv != null &&
            YtmScriptBridge.isYtmPlayerUrl(wv.url)
        ) {
            val gen = callbackGate.captureWebCallbackGeneration() ?: return
            wv.evaluateJavascript(YtmScriptBridge.buildActionJs(action)) { res ->
                if (!callbackGate.isWebCallbackCurrent(gen) || wv !== webViewYtm) {
                    return@evaluateJavascript
                }
                val handled = res?.trim() == "true"
                if (!handled) {
                    handleFallbackCompanionAction(action, displayed.state)
                } else {
                    // Immediate re-poll after DOM state settles
                    scope.launch {
                        delay(220L)
                        pollWebViewOnce()
                    }
                }
            }
            return
        }
        handleFallbackCompanionAction(action, displayed.state)
    }

    private fun handleFallbackCompanionAction(
        action: MusicAction,
        expectedState: MusicState? = null
    ) {
        if (MediaNotificationListenerService.sendAction(
                context = activity.applicationContext,
                action = action,
                expectedState = expectedState
            )
        ) {
            return
        }
        // If command was bound to a specific rendered MediaSession token that vanished/changed,
        // refresh instead of firing a stray unbound media key to another app.
        if (expectedState?.sessionToken != null) {
            MediaNotificationListenerService.requestRefreshAsync(activity.applicationContext)
            return
        }
        when (action) {
            MusicAction.PLAY_PAUSE -> dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
            MusicAction.NEXT -> dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_NEXT)
            MusicAction.PREVIOUS -> dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
            MusicAction.LIKE, MusicAction.DISLIKE -> {
                val hint = if (musicMode == MusicMode.WEB) {
                    "Sign in inside YT Music or switch to APP mode for ratings"
                } else {
                    "Enable Track & Rating Sync in Music → APP"
                }
                Toast.makeText(activity, hint, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun dispatchMediaKey(keyCode: Int) {
        val mgr = audioManager ?: return
        mgr.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
        mgr.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
        scope.launch {
            delay(250L)
            withContext(Dispatchers.IO) {
                MediaNotificationListenerService.refreshFromContext(activity.applicationContext)
            }
            renderMusicState(currentDisplayedState())
        }
    }

    private fun adjustMusicVolume(direction: Int) {
        audioManager?.adjustStreamVolume(
            AudioManager.STREAM_MUSIC,
            direction,
            AudioManager.FLAG_SHOW_UI
        )
    }

    private fun renderMusicState(state: MusicState) {
        val title = state.title.ifBlank {
            if (musicMode == MusicMode.WEB) "YouTube Music" else "Companion Ready"
        }
        val subtitle = state.artist.ifBlank {
            if (musicMode == MusicMode.WEB) {
                if (state.signedIn) "Ready • Tap Expand to browse" else "Tap YT icon to sign in"
            } else {
                if (cachedNotifAccessEnabled) {
                    "Waiting for playback"
                } else {
                    "Press Play or grant Track Sync"
                }
            }
        }

        tvDrawerTrack.text = title
        tvDrawerArtist.text = subtitle
        tvAppBigTitle.text = state.title.ifBlank { "Ready for Native YT Music" }
        tvAppBigArtist.text = state.artist.ifBlank { "Launch YouTube Music app or press Play" }

        val playIcon = if (state.isPlaying) R.drawable.ic_media_pause else R.drawable.ic_media_play
        btnRailPlayPause.setImageResource(playIcon)
        btnDrawerPlayPause.setImageResource(playIcon)
        btnAppPlayPause.setImageResource(playIcon)

        val neutralColor = Color.parseColor("#B4B4BC")
        val likeActiveColor = Color.parseColor("#38BDF8")
        val dislikeActiveColor = Color.parseColor("#EF4444")

        val upTint = ColorStateList.valueOf(
            if (state.likeStatus == LikeStatus.LIKED) likeActiveColor else neutralColor
        )
        val downTint = ColorStateList.valueOf(
            if (state.likeStatus == LikeStatus.DISLIKED) dislikeActiveColor else neutralColor
        )
        listOf(btnRailLike, btnDrawerLike, btnAppLike).forEach {
            ImageViewCompat.setImageTintList(it, upTint)
        }
        listOf(btnRailDislike, btnDrawerDislike, btnAppDislike).forEach {
            ImageViewCompat.setImageTintList(it, downTint)
        }

        updateProgressHairlines(state.progressFraction())

        if (state.title.isNotBlank()) {
            val trackKey = "${state.title}|${state.artist}"
            if (trackKey != lastNotifiedTrackKey) {
                lastNotifiedTrackKey = trackKey
                if (sidebarState != SidebarState.EXPANDED) {
                    showNowPlayingBanner(state.title, state.artist)
                }
            }
        }
    }

    private fun updateProgressHairlines(fraction: Float) {
        val clamped = fraction.coerceIn(0f, 1f)
        val railH = viewRailProgressTrack.height
        if (railH > 0) {
            viewRailProgress.pivotX = 0f
            viewRailProgress.pivotY = railH.toFloat()
            viewRailProgress.scaleY = clamped
        }
        viewDrawerProgress.pivotX = 0f
        viewDrawerProgress.pivotY = 0f
        viewDrawerProgress.scaleX = clamped
    }

    private fun showNowPlayingBanner(title: String, artist: String) {
        tvBannerTitle.text = title
        tvBannerArtist.text = artist.ifBlank { "YouTube Music" }

        val rtl = isRtlLayout()
        bannerHideRunnable?.let { layoutNowPlayingBanner.removeCallbacks(it) }
        layoutNowPlayingBanner.animate().cancel()
        layoutNowPlayingBanner.visibility = View.VISIBLE
        layoutNowPlayingBanner.alpha = 0f
        layoutNowPlayingBanner.translationX =
            DrawerLayoutGeometry.bannerSlideOffsetX(24f * density, rtl)
        layoutNowPlayingBanner.animate()
            .alpha(1f)
            .translationX(0f)
            .setDuration(200L)
            .setInterpolator(DecelerateInterpolator())
            .start()

        val hideTask = Runnable {
            if (callbackGate.destroyed) return@Runnable
            layoutNowPlayingBanner.animate()
                .alpha(0f)
                .translationX(DrawerLayoutGeometry.bannerSlideOffsetX(16f * density, isRtlLayout()))
                .setDuration(240L)
                .withEndAction {
                    if (!callbackGate.destroyed) {
                        layoutNowPlayingBanner.visibility = View.GONE
                    }
                }
                .start()
        }
        bannerHideRunnable = hideTask
        layoutNowPlayingBanner.postDelayed(hideTask, 3800L)
    }

    private fun refreshCompanionCapabilitiesAsync() {
        if (callbackGate.destroyed) return
        val appCtx = activity.applicationContext
        capabilityRefreshJob?.cancel()
        capabilityRefreshJob = scope.launch {
            val (installed, notifGranted) = withContext(Dispatchers.IO) {
                val ytm = runCatching {
                    appCtx.packageManager
                        .getLaunchIntentForPackage(MediaNotificationListenerService.YTM_PACKAGE) != null
                }.getOrDefault(false)
                val access = runCatching {
                    MediaNotificationListenerService.isNotificationAccessEnabled(appCtx)
                }.getOrDefault(false)
                ytm to access
            }
            if (callbackGate.destroyed) return@launch
            cachedYtmInstalled = installed
            cachedNotifAccessEnabled = notifGranted
            updateCompanionCardUiFromCache()
            renderMusicState(currentDisplayedState())
        }
    }

    private fun updateCompanionSessionStatusOnly() {
        val notifGranted = cachedNotifAccessEnabled
        val active = MediaNotificationListenerService.appState.value?.hasActiveSession == true
        tvAppSessionStatus.text = when {
            active && notifGranted -> "COMPANION SYNC ACTIVE"
            notifGranted -> "TRACK & RATING SYNC READY"
            else -> "MEDIA KEYS READY • TRACK SYNC OPTIONAL"
        }
    }

    private fun updateCompanionCardUiFromCache() {
        btnOpenYtmApp.text = if (cachedYtmInstalled) {
            "OPEN YOUTUBE MUSIC APP"
        } else {
            "GET YOUTUBE MUSIC ON PLAY STORE"
        }
        updateCompanionSessionStatusOnly()
        if (cachedNotifAccessEnabled) {
            tvNotifAccessDesc.text =
                "✓ Track metadata, progress bar, and Like/Dislike thumbs synced with active media session."
            btnGrantNotifAccess.text = "NOTIFICATION SYNC ENABLED"
        } else {
            tvNotifAccessDesc.text =
                "Media keys & volume work now. Grant Notification Listener access to sync track titles, progress, and Like/Dislike thumbs from the YT Music app."
            btnGrantNotifAccess.text = "ENABLE TRACK & RATING SYNC"
        }
    }

    private fun openYoutubeMusicAppOrStore() {
        val launchIntent = activity.packageManager
            .getLaunchIntentForPackage(MediaNotificationListenerService.YTM_PACKAGE)
        if (launchIntent != null) {
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            activity.startActivity(launchIntent)
            return
        }
        try {
            activity.startActivity(
                Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse("market://details?id=${MediaNotificationListenerService.YTM_PACKAGE}")
                )
            )
        } catch (_: Exception) {
            try {
                activity.startActivity(
                    Intent(
                        Intent.ACTION_VIEW,
                        Uri.parse("https://play.google.com/store/apps/details?id=${MediaNotificationListenerService.YTM_PACKAGE}")
                    )
                )
            } catch (_: Exception) {
                Toast.makeText(activity, "Install YouTube Music or use WEB mode", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun openNotificationListenerSettings() {
        try {
            val action = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
                Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS
            } else {
                "android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"
            }
            activity.startActivity(Intent(action))
        } catch (_: Exception) {
            try {
                activity.startActivity(Intent(Settings.ACTION_SETTINGS))
            } catch (_: Exception) {
                Toast.makeText(activity, "Open Settings → Apps → Notification access", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun isRtlLayout(): Boolean {
        val dir = if (rootView.isLaidOut) {
            rootView.layoutDirection
        } else {
            activity.resources.configuration.layoutDirection
        }
        return dir == View.LAYOUT_DIRECTION_RTL
    }

    private fun collapsedDrawerTranslation(): Float {
        val rtl = isRtlLayout()
        lastAppliedCollapsedRtl = rtl
        return DrawerLayoutGeometry.collapsedTranslationX(drawerWidthPx, rtl)
    }

    private fun applyCollapsedStateImmediate(state: SidebarState) {
        layoutMusicRail.visibility = if (state == SidebarState.RAIL) View.VISIBLE else View.GONE
        btnMusicPeekTab.visibility = if (state == SidebarState.PEEK) View.VISIBLE else View.GONE
        viewMusicScrim.visibility = View.GONE
        viewMusicScrim.alpha = 0f
        musicDrawerRoot.translationX = collapsedDrawerTranslation()
        onDrawerStateChanged(false)
    }

    private fun transitionTo(target: SidebarState, animate: Boolean) {
        sidebarState = target
        when (target) {
            SidebarState.EXPANDED -> {
                bannerHideRunnable?.let { layoutNowPlayingBanner.removeCallbacks(it) }
                layoutNowPlayingBanner.visibility = View.GONE

                viewMusicScrim.visibility = View.VISIBLE
                musicDrawerRoot.visibility = View.VISIBLE
                if (animate) {
                    viewMusicScrim.animate().cancel()
                    viewMusicScrim.animate().alpha(1f).setDuration(220L).start()
                    musicDrawerRoot.animate().cancel()
                    musicDrawerRoot.animate()
                        .translationX(0f)
                        .setDuration(240L)
                        .setInterpolator(DecelerateInterpolator())
                        .start()
                } else {
                    viewMusicScrim.alpha = 1f
                    musicDrawerRoot.translationX = 0f
                }
                if (musicMode == MusicMode.WEB) {
                    if (!webViewInitialized) initWebViewIfSupported()
                    if (webViewInitialized && webViewYtm?.url.isNullOrBlank()) {
                        webViewYtm?.loadUrl(YtmScriptBridge.YTM_HOME_URL)
                    }
                }
                onDrawerStateChanged(true)
            }
            SidebarState.RAIL, SidebarState.PEEK -> {
                val showRail = (target == SidebarState.RAIL)
                layoutMusicRail.visibility = if (showRail) View.VISIBLE else View.GONE
                btnMusicPeekTab.visibility = if (showRail) View.GONE else View.VISIBLE

                if (animate) {
                    viewMusicScrim.animate().cancel()
                    viewMusicScrim.animate()
                        .alpha(0f)
                        .setDuration(200L)
                        .withEndAction { viewMusicScrim.visibility = View.GONE }
                        .start()
                    musicDrawerRoot.animate().cancel()
                    musicDrawerRoot.animate()
                        .translationX(collapsedDrawerTranslation())
                        .setDuration(220L)
                        .setInterpolator(DecelerateInterpolator())
                        .start()
                } else {
                    applyCollapsedStateImmediate(target)
                }
                onDrawerStateChanged(false)
            }
        }
    }

    private fun flushCookiesCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            try {
                CookieManager.getInstance().flush()
            } catch (_: Exception) {
            }
        }
    }

    private fun loadSavedMode(): MusicMode {
        val raw = prefs.getString(KEY_MODE, MusicMode.WEB.name)
        return try {
            MusicMode.valueOf(raw ?: MusicMode.WEB.name)
        } catch (_: Exception) {
            MusicMode.WEB
        }
    }

    private fun loadSavedCollapsedState(): SidebarState {
        val raw = prefs.getString(KEY_COLLAPSED_STATE, SidebarState.RAIL.name)
        return if (raw == SidebarState.PEEK.name) SidebarState.PEEK else SidebarState.RAIL
    }

    private fun saveCollapsedState(state: SidebarState) {
        if (state == SidebarState.RAIL || state == SidebarState.PEEK) {
            prefs.edit().putString(KEY_COLLAPSED_STATE, state.name).apply()
        }
    }

    companion object {
        private const val PREFS_NAME = "cl600_music_prefs"
        private const val KEY_MODE = "music_mode"
        private const val KEY_COLLAPSED_STATE = "collapsed_state"
    }
}
