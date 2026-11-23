package com.cl600.obd.music

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

/**
 * Pure Kotlin policies and coordinators backing [MusicSidebarController] and
 * [MediaNotificationListenerService] so lifecycle, routing, and threading rules
 * are verified directly under JVM unit tests.
 */
internal class LazyWebViewInitializer<T> {
    var instance: T? = null
        private set

    fun shouldInitializeOnStart(initialMode: MusicMode): Boolean =
        initialMode == MusicMode.WEB

    fun ensureInitialized(
        createAndConfigure: () -> T,
        onFailure: () -> Unit
    ): T? {
        instance?.let { return it }
        return try {
            createAndConfigure().also { instance = it }
        } catch (_: Throwable) {
            instance = null
            onFailure()
            null
        }
    }

    fun reset() {
        instance = null
    }
}

internal class WebViewCallbackGate {
    @Volatile
    var destroyed: Boolean = false
        private set

    private var navigationGeneration: Int = 0
    private var authCheckGeneration: Int = 0

    fun onNavigationStarted() {
        if (destroyed) return
        navigationGeneration++
        authCheckGeneration++
    }

    fun beginAuthCheck(): Int? {
        if (destroyed) return null
        return ++authCheckGeneration
    }

    fun isAuthCheckCurrent(gen: Int): Boolean =
        !destroyed && gen == authCheckGeneration

    fun captureWebCallbackGeneration(): Int? {
        if (destroyed) return null
        return navigationGeneration
    }

    fun isWebCallbackCurrent(gen: Int): Boolean =
        !destroyed && gen == navigationGeneration

    fun teardown(
        stopLoading: () -> Unit,
        clearClients: () -> Unit,
        removeFromParent: () -> Unit,
        destroyWebView: () -> Unit
    ) {
        destroyed = true
        navigationGeneration++
        authCheckGeneration++
        stopLoading()
        clearClients()
        removeFromParent()
        destroyWebView()
    }
}

internal class RefreshCoalescer(
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher,
    private val action: suspend () -> Unit
) {
    private val lock = Any()
    private var currentJob: Job? = null
    private var dirty = false

    fun request(): Job {
        synchronized(lock) {
            dirty = true
            val existing = currentJob
            if (existing != null && existing.isActive) {
                return existing
            }
            var created: Job? = null
            val launched = scope.launch(ioDispatcher) {
                try {
                    while (true) {
                        synchronized(lock) {
                            if (!dirty) {
                                if (currentJob === created) {
                                    currentJob = null
                                }
                                return@launch
                            }
                            dirty = false
                        }
                        action()
                    }
                } finally {
                    synchronized(lock) {
                        if (currentJob === created) {
                            currentJob = null
                        }
                    }
                }
            }
            created = launched
            currentJob = launched
            return launched
        }
    }
}

internal object DrawerLayoutGeometry {
    fun collapsedTranslationX(drawerWidthPx: Int, isRtl: Boolean): Float =
        if (isRtl) -drawerWidthPx.toFloat() else drawerWidthPx.toFloat()

    fun shouldReapplyCollapsedTranslation(
        prevWidthPx: Int,
        newWidthPx: Int,
        prevRtl: Boolean?,
        currentRtl: Boolean
    ): Boolean = newWidthPx > 0 && (newWidthPx != prevWidthPx || prevRtl != currentRtl)

    fun bannerSlideOffsetX(distancePx: Float, isRtl: Boolean): Float =
        if (isRtl) -distancePx else distancePx
}

internal enum class CommandTarget {
    WEB,
    NATIVE
}

internal data class DisplayedPlayback(
    val state: MusicState,
    val target: CommandTarget
)

internal object MusicPlaybackRouter {
    fun resolve(
        mode: MusicMode,
        webState: MusicState,
        appState: MusicState?,
        webViewReadyForPlayer: Boolean
    ): DisplayedPlayback {
        return when (mode) {
            MusicMode.WEB -> when {
                webState.hasActiveSession && webViewReadyForPlayer -> DisplayedPlayback(
                    state = webState,
                    target = CommandTarget.WEB
                )
                appState != null -> DisplayedPlayback(
                    state = appState.copy(mode = MusicMode.WEB),
                    target = CommandTarget.NATIVE
                )
                else -> DisplayedPlayback(
                    state = if (webViewReadyForPlayer) webState else MusicState(mode = MusicMode.WEB),
                    target = if (webViewReadyForPlayer) CommandTarget.WEB else CommandTarget.NATIVE
                )
            }
            MusicMode.APP -> DisplayedPlayback(
                state = appState ?: MusicState(mode = MusicMode.APP),
                target = CommandTarget.NATIVE
            )
        }
    }
}

internal data class SessionCandidate<T>(
    val handle: T,
    val packageName: String,
    val token: Any,
    val isPlaying: Boolean,
    val isPaused: Boolean,
    val likeStatus: LikeStatus = LikeStatus.NONE
)

internal enum class TransportIntent {
    PLAY,
    PAUSE,
    NEXT,
    PREVIOUS,
    SET_LIKE,
    SET_DISLIKE,
    CLEAR_RATING
}

internal data class ResolvedNativeCommand<T>(
    val target: SessionCandidate<T>,
    val intent: TransportIntent
)

internal object NativeSessionSelector {
    fun <T> selectPreferred(
        candidates: List<SessionCandidate<T>>,
        ytmPackage: String
    ): SessionCandidate<T>? {
        return candidates.firstOrNull { it.packageName == ytmPackage && it.isPlaying }
            ?: candidates.firstOrNull { it.isPlaying }
            ?: candidates.firstOrNull { it.packageName == ytmPackage && it.isPaused }
            ?: candidates.firstOrNull { it.isPaused }
            ?: candidates.firstOrNull { it.packageName == ytmPackage }
            ?: candidates.firstOrNull()
    }

    fun <T> resolveCommand(
        candidates: List<SessionCandidate<T>>,
        action: MusicAction,
        expectedState: MusicState?,
        ytmPackage: String
    ): ResolvedNativeCommand<T>? {
        val expectedToken = expectedState?.sessionToken
        // State not sourced from a native session must never drive a native command.
        if (expectedState != null && expectedToken == null && expectedState.mode == MusicMode.WEB) {
            return null
        }
        val target = if (expectedToken != null) {
            candidates.firstOrNull { it.token == expectedToken } ?: return null
        } else {
            selectPreferred(candidates, ytmPackage) ?: return null
        }

        val boundState = expectedState?.takeIf { it.sessionToken != null }
        val renderedPlaying = boundState?.isPlaying ?: target.isPlaying
        val renderedLike = boundState?.likeStatus ?: target.likeStatus
        val intent = when (action) {
            MusicAction.PLAY_PAUSE ->
                if (renderedPlaying) TransportIntent.PAUSE else TransportIntent.PLAY
            MusicAction.NEXT -> TransportIntent.NEXT
            MusicAction.PREVIOUS -> TransportIntent.PREVIOUS
            MusicAction.LIKE ->
                if (renderedLike == LikeStatus.LIKED) TransportIntent.CLEAR_RATING
                else TransportIntent.SET_LIKE
            MusicAction.DISLIKE ->
                if (renderedLike == LikeStatus.DISLIKED) TransportIntent.CLEAR_RATING
                else TransportIntent.SET_DISLIKE
        }
        return ResolvedNativeCommand(target, intent)
    }
}

internal class PostCommandRefreshCoordinator {
    private val revision = AtomicLong(0L)
    private val lock = Any()
    private var pendingJob: Job? = null

    val stateRevision: Long
        get() = revision.get()

    fun onStateEmitted() {
        revision.incrementAndGet()
    }

    fun scheduleVerificationRefresh(
        scope: CoroutineScope,
        ioDispatcher: CoroutineDispatcher,
        graceMs: Long = 250L,
        refreshAction: suspend () -> Unit
    ): Job {
        synchronized(lock) {
            pendingJob?.cancel()
            val baseline = revision.get()
            val job = scope.launch(ioDispatcher) {
                delay(graceMs)
                if (revision.get() == baseline) {
                    refreshAction()
                }
            }
            pendingJob = job
            return job
        }
    }

    fun cancelPending() {
        synchronized(lock) {
            pendingJob?.cancel()
            pendingJob = null
        }
    }
}

internal object MusicPollLoopPolicy {
    fun shouldCollectNativeUpdate(
        mode: MusicMode,
        webHasActiveSession: Boolean,
        @Suppress("UNUSED_PARAMETER") appStatePresent: Boolean = true
    ): Boolean = mode == MusicMode.APP || !webHasActiveSession

    fun shouldRefreshNativeOnPollTick(
        mode: MusicMode,
        webHasActiveSession: Boolean
    ): Boolean = mode == MusicMode.APP || !webHasActiveSession
}

internal class RenderedPlaybackStore {
    private var lastRendered: DisplayedPlayback? = null

    fun onRendered(playback: DisplayedPlayback) {
        lastRendered = playback
    }

    fun snapshotForTap(): DisplayedPlayback? = lastRendered
}

internal object WebActionFallbackPolicy {
    fun shouldDispatchGlobalMediaKey(expectedState: MusicState?): Boolean {
        if (expectedState == null) return true
        if (expectedState.sessionToken != null) return false
        if (expectedState.mode == MusicMode.WEB && expectedState.hasActiveSession) return false
        return true
    }
}

internal class NativeSessionStateStore {
    private val stateLock = Any()
    private val _appState = MutableStateFlow<MusicState?>(null)
    val appState: StateFlow<MusicState?> = _appState.asStateFlow()

    @Volatile
    var isListenerConnected: Boolean = false
        private set

    private var disconnectEpoch: Long = 0L

    fun onConnected() {
        synchronized(stateLock) {
            disconnectEpoch++
            isListenerConnected = true
        }
    }

    fun onDisconnected(
        cancelPendingVerification: () -> Unit,
        clearObserver: () -> Unit,
        onSessionChanged: () -> Unit
    ) {
        synchronized(stateLock) {
            isListenerConnected = false
            disconnectEpoch++
            cancelPendingVerification()
            emitLocked(null, onSessionChanged)
        }
        clearObserver()
    }

    fun captureEpoch(): Long = synchronized(stateLock) { disconnectEpoch }

    fun isEpochCurrent(epoch: Long): Boolean =
        synchronized(stateLock) { epoch == disconnectEpoch }

    fun publishIfCurrent(
        capturedEpoch: Long,
        state: MusicState?,
        onSessionChanged: () -> Unit
    ): Boolean {
        synchronized(stateLock) {
            if (capturedEpoch != disconnectEpoch) return false
            emitLocked(state, onSessionChanged)
            return true
        }
    }

    private fun emitLocked(state: MusicState?, onSessionChanged: () -> Unit) {
        val prev = _appState.value
        _appState.value = state
        val sessionChanged = prev?.sessionToken != state?.sessionToken ||
            prev?.isPlaying != state?.isPlaying ||
            prev?.title != state?.title ||
            prev?.likeStatus != state?.likeStatus
        if (sessionChanged) {
            onSessionChanged()
        }
    }
}
