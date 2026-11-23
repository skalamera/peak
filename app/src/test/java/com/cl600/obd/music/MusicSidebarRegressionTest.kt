package com.cl600.obd.music

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import javax.xml.parsers.DocumentBuilderFactory

class MusicSidebarRegressionTest {

    private fun resolveProjectFile(relativePath: String): File {
        val candidates = listOf(
            File(relativePath),
            File("app", relativePath)
        )
        return candidates.firstOrNull { it.exists() }
            ?: error("Could not locate $relativePath from ${File("").absolutePath}")
    }

    @Test
    fun crit72_drawerLayoutHasNoStaticWebViewAndProvidesHostContainer() {
        val layoutFile = resolveProjectFile("src/main/res/layout/view_music_drawer.xml")
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(layoutFile)

        val webViews = doc.getElementsByTagName("WebView")
        assertEquals(
            "view_music_drawer.xml must not statically inflate <WebView>",
            0,
            webViews.length
        )

        val frameLayouts = doc.getElementsByTagName("FrameLayout")
        var foundWebViewHost = false
        for (i in 0 until frameLayouts.length) {
            val attrs = frameLayouts.item(i).attributes
            val idAttr = attrs.getNamedItem("android:id")?.nodeValue
            if (idAttr == "@+id/webViewHost") {
                foundWebViewHost = true
                break
            }
        }
        assertTrue(
            "view_music_drawer.xml must provide FrameLayout @+id/webViewHost for lazy WebView creation",
            foundWebViewHost
        )
    }

    @Test
    fun crit72_lazyWebViewSkipsAppStartupAndSurvivesConstructorFailure() {
        val initializer = LazyWebViewInitializer<String>()
        assertFalse(
            "APP mode startup must not initialize WebView",
            initializer.shouldInitializeOnStart(MusicMode.APP)
        )
        assertTrue(
            "WEB mode startup should initialize WebView",
            initializer.shouldInitializeOnStart(MusicMode.WEB)
        )

        var failureNotified = false
        val failedResult = initializer.ensureInitialized(
            createAndConfigure = { throw RuntimeException("Simulated MissingWebViewPackageException") },
            onFailure = { failureNotified = true }
        )
        assertNull("Missing WebView package failure must be caught", failedResult)
        assertTrue("Failure handler must run when WebView creation throws", failureNotified)
        assertNull(initializer.instance)

        var createCount = 0
        val first = initializer.ensureInitialized(
            createAndConfigure = {
                createCount++
                "webview-$createCount"
            },
            onFailure = {}
        )
        val second = initializer.ensureInitialized(
            createAndConfigure = {
                createCount++
                "webview-$createCount"
            },
            onFailure = {}
        )
        assertEquals(1, createCount)
        assertSame(first, second)
    }

    @Test
    fun crit74_destroyDetachesBeforeDestroyAndDropsLateOrStaleCallbacks() {
        val gate = WebViewCallbackGate()

        val staleAuthGen = gate.beginAuthCheck()!!
        gate.onNavigationStarted()
        assertFalse(
            "Late auth callback from prior navigation must be dropped",
            gate.isAuthCheckCurrent(staleAuthGen)
        )

        val currentAuthGen = gate.beginAuthCheck()!!
        assertTrue(gate.isAuthCheckCurrent(currentAuthGen))

        val inFlightJsGen = gate.captureWebCallbackGeneration()!!
        val steps = mutableListOf<String>()
        gate.teardown(
            stopLoading = { steps += "stopLoading" },
            clearClients = { steps += "clearClients" },
            removeFromParent = { steps += "removeFromParent" },
            destroyWebView = { steps += "destroy" }
        )

        assertEquals(
            listOf("stopLoading", "clearClients", "removeFromParent", "destroy"),
            steps
        )
        assertTrue("Gate must report destroyed after teardown", gate.destroyed)
        assertFalse(
            "In-flight JS callback after destroy must be ignored",
            gate.isWebCallbackCurrent(inFlightJsGen)
        )
        assertFalse(
            "In-flight auth callback after destroy must be ignored",
            gate.isAuthCheckCurrent(currentAuthGen)
        )
        assertNull("Destroyed gate must reject new callback captures", gate.captureWebCallbackGeneration())
        assertNull("Destroyed gate must reject new auth checks", gate.beginAuthCheck())
    }

    @Test
    fun crit75_notificationFilteringAndOffMainCoalescedPolling() = runBlocking {
        // Non-media system/chat notifications must be ignored
        assertFalse(
            MediaNotificationListenerService.shouldRefreshForNotification(
                packageName = "com.google.android.gm",
                hasMediaSessionExtra = false,
                isTransportCategory = false
            )
        )
        assertFalse(
            MediaNotificationListenerService.shouldRefreshForNotification(
                packageName = null,
                hasMediaSessionExtra = false,
                isTransportCategory = false
            )
        )
        // Media notifications and YTM notifications trigger refresh
        assertTrue(
            MediaNotificationListenerService.shouldRefreshForNotification(
                packageName = MediaNotificationListenerService.YTM_PACKAGE,
                hasMediaSessionExtra = false,
                isTransportCategory = false
            )
        )
        assertTrue(
            MediaNotificationListenerService.shouldRefreshForNotification(
                packageName = "com.spotify.music",
                hasMediaSessionExtra = true,
                isTransportCategory = false
            )
        )
        assertTrue(
            MediaNotificationListenerService.shouldRefreshForNotification(
                packageName = "com.example.player",
                hasMediaSessionExtra = false,
                isTransportCategory = true
            )
        )

        val callerThread = Thread.currentThread().name
        val workerExecutor = Executors.newSingleThreadExecutor { r ->
            Thread(r, "test-io-worker")
        }
        val workerDispatcher = workerExecutor.asCoroutineDispatcher()
        try {
            val firstStarted = CompletableDeferred<Unit>()
            val allowFirstFinish = CompletableDeferred<Unit>()
            val runs = AtomicInteger(0)
            val seenThreads = mutableListOf<String>()

            val coalescer = RefreshCoalescer(
                scope = this,
                ioDispatcher = workerDispatcher,
                action = {
                    seenThreads += Thread.currentThread().name
                    val n = runs.incrementAndGet()
                    if (n == 1) {
                        firstStarted.complete(Unit)
                        allowFirstFinish.await()
                    }
                }
            )

            val job1 = coalescer.request()
            assertNotNull("Coalescer must schedule off-main job", job1)
            firstStarted.await()

            // Burst 5 overlapping requests while run 1 is in flight -> coalesce to 1 trailing rerun
            val queued = (1..5).map { coalescer.request() }
            allowFirstFinish.complete(Unit)
            job1.join()
            queued.forEach { it.join() }

            assertEquals("Overlapping burst must coalesce into at most 2 runs", 2, runs.get())
            assertTrue(
                "All refreshes must execute off caller/main thread",
                seenThreads.all { it != callerThread }
            )
        } finally {
            workerDispatcher.close()
        }
    }

    @Test
    fun crit78_collapsedDrawerMovesOffscreenInRtlAndUpdatesOnFirstLayout() {
        val workspaceWidth = 800
        val drawerWidth = 364

        // In RTL, logical end is the left edge (laid out left=0, right=364).
        val rtlTx = DrawerLayoutGeometry.collapsedTranslationX(drawerWidth, isRtl = true)
        val rtlVisualRight = drawerWidth + rtlTx
        assertTrue(
            "In RTL, collapsed drawer must translate off the left edge (visualRight=$rtlVisualRight <= 0)",
            rtlVisualRight <= 0f
        )

        // In LTR, logical end is the right edge (laid out left=436, right=800).
        val ltrTx = DrawerLayoutGeometry.collapsedTranslationX(drawerWidth, isRtl = false)
        val ltrVisualLeft = (workspaceWidth - drawerWidth) + ltrTx
        assertTrue(
            "In LTR, collapsed drawer must translate off the right edge (visualLeft=$ltrVisualLeft >= $workspaceWidth)",
            ltrVisualLeft >= workspaceWidth.toFloat()
        )

        // When width matches preset 364px on first layout, an RTL direction change still requires re-applying translationX
        assertTrue(
            "First layout resolving RTL with unchanged width must re-apply collapsed translationX",
            DrawerLayoutGeometry.shouldReapplyCollapsedTranslation(
                prevWidthPx = drawerWidth,
                newWidthPx = drawerWidth,
                prevRtl = false,
                currentRtl = true
            )
        )
        assertFalse(
            "Unchanged width and unchanged direction do not require re-applying",
            DrawerLayoutGeometry.shouldReapplyCollapsedTranslation(
                prevWidthPx = drawerWidth,
                newWidthPx = drawerWidth,
                prevRtl = true,
                currentRtl = true
            )
        )

        // Banner offset must enter from start edge cleanly in RTL vs LTR
        assertEquals(-24f, DrawerLayoutGeometry.bannerSlideOffsetX(24f, isRtl = true), 0.01f)
        assertEquals(24f, DrawerLayoutGeometry.bannerSlideOffsetX(24f, isRtl = false), 0.01f)
    }

    @Test
    fun crit70_webModeRoutesControlsToDisplayedNativeFallbackWhilePreservingIdleWebControls() {
        val idleWebState = MusicState(mode = MusicMode.WEB, hasActiveSession = false)
        val activeWebState = MusicState(
            title = "Web Track",
            artist = "Web Artist",
            isPlaying = true,
            mode = MusicMode.WEB,
            hasActiveSession = true
        )
        val playingNativeState = MusicState(
            title = "Native Song",
            artist = "Native Artist",
            isPlaying = true,
            mode = MusicMode.APP,
            hasActiveSession = true
        )

        // 1) Default WEB mode + idle WebView + playing native session -> displays native AND routes to NATIVE
        val fallbackResolved = MusicPlaybackRouter.resolve(
            mode = MusicMode.WEB,
            webState = idleWebState,
            appState = playingNativeState,
            webViewReadyForPlayer = true
        )
        assertEquals("Native Song", fallbackResolved.state.title)
        assertEquals(
            "Displayed native fallback in WEB mode must route commands to NATIVE, not idle WebView",
            CommandTarget.NATIVE,
            fallbackResolved.target
        )

        // 2) WEB mode + idle WebView + no native session -> preserves WebView controls
        val idleWebResolved = MusicPlaybackRouter.resolve(
            mode = MusicMode.WEB,
            webState = idleWebState,
            appState = null,
            webViewReadyForPlayer = true
        )
        assertEquals(CommandTarget.WEB, idleWebResolved.target)

        // 3) WEB mode + active WebView -> displays WebView AND routes to WEB
        val activeWebResolved = MusicPlaybackRouter.resolve(
            mode = MusicMode.WEB,
            webState = activeWebState,
            appState = playingNativeState,
            webViewReadyForPlayer = true
        )
        assertEquals("Web Track", activeWebResolved.state.title)
        assertEquals(CommandTarget.WEB, activeWebResolved.target)

        // 4) WEB mode when WebView not on player origin / unavailable -> falls back to NATIVE
        val nonYtmUrlResolved = MusicPlaybackRouter.resolve(
            mode = MusicMode.WEB,
            webState = idleWebState,
            appState = null,
            webViewReadyForPlayer = false
        )
        assertEquals(CommandTarget.NATIVE, nonYtmUrlResolved.target)
    }

    @Test
    fun crit71_playingSessionBeatsPausedYtmAndCommandsBindToRenderedToken() {
        val ytmPaused = SessionCandidate(
            handle = "ytm-ctrl",
            packageName = MediaNotificationListenerService.YTM_PACKAGE,
            token = "token-ytm-1",
            isPlaying = false,
            isPaused = true,
            likeStatus = LikeStatus.NONE
        )
        val spotifyPlaying = SessionCandidate(
            handle = "spotify-ctrl",
            packageName = "com.spotify.music",
            token = "token-spotify-1",
            isPlaying = true,
            isPaused = false,
            likeStatus = LikeStatus.LIKED
        )

        // Playing third-party session must win over paused YTM session
        val selected = NativeSessionSelector.selectPreferred(
            candidates = listOf(ytmPaused, spotifyPlaying),
            ytmPackage = MediaNotificationListenerService.YTM_PACKAGE
        )
        assertNotNull(selected)
        assertEquals("spotify-ctrl", selected!!.handle)

        // When both YTM and third-party are playing, YTM wins
        val ytmPlaying = ytmPaused.copy(isPlaying = true, isPaused = false, token = "token-ytm-2")
        val selectedBothPlaying = NativeSessionSelector.selectPreferred(
            candidates = listOf(spotifyPlaying, ytmPlaying),
            ytmPackage = MediaNotificationListenerService.YTM_PACKAGE
        )
        assertEquals("token-ytm-2", selectedBothPlaying?.token)

        // Command must bind strictly to rendered sessionToken (even if same package replaces token)
        val renderedSnapshot = MusicState(
            title = "Live Track",
            isPlaying = true,
            likeStatus = LikeStatus.LIKED,
            mode = MusicMode.APP,
            hasActiveSession = true,
            sessionPackage = "com.spotify.music",
            sessionToken = "token-spotify-1"
        )
        val replacementSpotifySamePkg = spotifyPlaying.copy(token = "token-spotify-replaced")
        val rejectedReplacedToken = NativeSessionSelector.resolveCommand(
            candidates = listOf(replacementSpotifySamePkg),
            action = MusicAction.PLAY_PAUSE,
            expectedState = renderedSnapshot,
            ytmPackage = MediaNotificationListenerService.YTM_PACKAGE
        )
        assertNull(
            "Replacing session token must prevent misrouting command even within the same package",
            rejectedReplacedToken
        )

        // Explicit intent derives from rendered state (Pause shown -> PAUSE intent, not inverted to PLAY)
        val flippedBeforeTap = spotifyPlaying.copy(isPlaying = false, isPaused = true)
        val resolvedPause = NativeSessionSelector.resolveCommand(
            candidates = listOf(flippedBeforeTap),
            action = MusicAction.PLAY_PAUSE,
            expectedState = renderedSnapshot,
            ytmPackage = MediaNotificationListenerService.YTM_PACKAGE
        )
        assertEquals(TransportIntent.PAUSE, resolvedPause?.intent)
        assertEquals("token-spotify-1", resolvedPause?.target?.token)
    }

    @Test
    fun crit76_notificationAccessCheckRejectsOtherPackagesWithSameSimpleName() {
        val pkg = "com.cl600.obd"
        val cls = "com.cl600.obd.music.MediaNotificationListenerService"

        // Other apps with the same simple class name or prefix-spoofed package must NOT match
        assertFalse(
            "Other package with same simpleName must return false",
            MediaNotificationListenerService.isComponentEnabledInListenerSetting(
                flat = "com.other.app/com.other.app.MediaNotificationListenerService:com.system.ui/.Dummy",
                targetPackage = pkg,
                targetClassName = cls
            )
        )
        assertFalse(
            "Prefix-spoofed package must return false",
            MediaNotificationListenerService.isComponentEnabledInListenerSetting(
                flat = "com.cl600.obd.evil/com.cl600.obd.music.MediaNotificationListenerService",
                targetPackage = pkg,
                targetClassName = cls
            )
        )

        // Exact full and short component entries must return true
        assertTrue(
            MediaNotificationListenerService.isComponentEnabledInListenerSetting(
                flat = "com.other.app/.Other:$pkg/$cls",
                targetPackage = pkg,
                targetClassName = cls
            )
        )
        assertTrue(
            MediaNotificationListenerService.isComponentEnabledInListenerSetting(
                flat = "$pkg/.music.MediaNotificationListenerService",
                targetPackage = pkg,
                targetClassName = cls
            )
        )
    }

    @Test
    fun crit77_postCommandRefreshUsesLiveCallbackAndSkipsRedundantFallback() = runBlocking {
        val callerThread = Thread.currentThread().name
        val workerExecutor = Executors.newSingleThreadExecutor { r ->
            Thread(r, "test-postcmd-io")
        }
        val workerDispatcher = workerExecutor.asCoroutineDispatcher()
        try {
            val coordinator = PostCommandRefreshCoordinator()
            val refreshCalls = AtomicInteger(0)
            val seenThreads = mutableListOf<String>()

            // 1) If a live MediaController callback emits updated state before grace timeout,
            //    redundant verification refresh must be skipped.
            val skippedJob = coordinator.scheduleVerificationRefresh(
                scope = this,
                ioDispatcher = workerDispatcher,
                graceMs = 60L,
                refreshAction = { refreshCalls.incrementAndGet() }
            )
            assertNotNull("Verification refresh job must be scheduled", skippedJob)
            coordinator.onStateEmitted()
            skippedJob.join()
            assertEquals(
                "Live callback emission must suppress redundant trailing refresh",
                0,
                refreshCalls.get()
            )

            // 2) If NO callback arrives within graceMs, one off-main verification refresh runs.
            val fallbackJob = coordinator.scheduleVerificationRefresh(
                scope = this,
                ioDispatcher = workerDispatcher,
                graceMs = 30L,
                refreshAction = {
                    seenThreads += Thread.currentThread().name
                    refreshCalls.incrementAndGet()
                }
            )
            assertNotNull(fallbackJob)
            fallbackJob.join()
            assertEquals(
                "Missing callback must trigger one verification refresh",
                1,
                refreshCalls.get()
            )
            assertTrue(
                "Verification refresh must run off caller/main thread",
                seenThreads.all { it != callerThread }
            )
        } finally {
            workerDispatcher.close()
        }
    }

    @Test
    fun crit79_webModeRefreshesIdleNativeFallbackAndRendersClearEvent() {
        // 1) In WEB mode while WebView player is idle, poll tick MUST refresh native fallback state
        assertTrue(
            "WEB mode with idle WebView must refresh native fallback on poll ticks",
            MusicPollLoopPolicy.shouldRefreshNativeOnPollTick(
                mode = MusicMode.WEB,
                webHasActiveSession = false
            )
        )

        // 2) In WEB mode while WebView IS actively playing, poll tick must NOT waste IPC polling native
        assertFalse(
            "WEB mode with active WebView must not poll native sessions",
            MusicPollLoopPolicy.shouldRefreshNativeOnPollTick(
                mode = MusicMode.WEB,
                webHasActiveSession = true
            )
        )

        // 3) APP mode always refreshes native sessions
        assertTrue(
            MusicPollLoopPolicy.shouldRefreshNativeOnPollTick(
                mode = MusicMode.APP,
                webHasActiveSession = false
            )
        )

        // 4) Collector in WEB mode with idle WebView MUST process null-clear emissions (appStatePresent = false)
        assertTrue(
            "Native session clear (null) in WEB mode must trigger UI update when WebView is idle",
            MusicPollLoopPolicy.shouldCollectNativeUpdate(
                mode = MusicMode.WEB,
                webHasActiveSession = false,
                appStatePresent = false
            )
        )
        assertFalse(
            "Collector should ignore native updates when active WebView owns playback",
            MusicPollLoopPolicy.shouldCollectNativeUpdate(
                mode = MusicMode.WEB,
                webHasActiveSession = true,
                appStatePresent = true
            )
        )
    }
}
