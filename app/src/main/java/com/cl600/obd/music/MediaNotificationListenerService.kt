package com.cl600.obd.music

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.Rating
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.annotation.RequiresApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Companion NotificationListener + MediaSession bridge for controlling the native
 * YouTube Music Android app (or any active media player) when running in APP mode.
 * Safe down to minSdk 19 (MediaSessionManager paths are strictly gated on API 21+).
 */
class MediaNotificationListenerService : NotificationListenerService() {

    override fun onListenerConnected() {
        super.onListenerConnected()
        listenerConnected = true
        requestRefreshAsync(applicationContext)
    }

    override fun onListenerDisconnected() {
        listenerConnected = false
        postCommandCoordinator.cancelPending()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            Api21Helper.clearObservedController()
        }
        emitAppState(null)
        super.onListenerDisconnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (isMediaNotification(sbn)) {
            requestRefreshAsync(applicationContext)
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        if (isMediaNotification(sbn)) {
            requestRefreshAsync(applicationContext)
        }
    }

    private fun isMediaNotification(sbn: StatusBarNotification?): Boolean {
        if (sbn == null) return false
        var hasMediaSession = false
        var isTransport = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            val notif = sbn.notification
            hasMediaSession = notif?.extras?.containsKey(Notification.EXTRA_MEDIA_SESSION) == true
            isTransport = notif?.category == Notification.CATEGORY_TRANSPORT
        }
        return shouldRefreshForNotification(
            packageName = sbn.packageName,
            hasMediaSessionExtra = hasMediaSession,
            isTransportCategory = isTransport
        )
    }

    companion object {
        const val YTM_PACKAGE = "com.google.android.apps.youtube.music"

        internal fun shouldRefreshForNotification(
            packageName: String?,
            hasMediaSessionExtra: Boolean,
            isTransportCategory: Boolean
        ): Boolean = packageName == YTM_PACKAGE || hasMediaSessionExtra || isTransportCategory

        @Volatile
        private var listenerConnected = false

        @Volatile
        private var refreshContext: Context? = null

        private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val refreshCoalescer = RefreshCoalescer(serviceScope, Dispatchers.IO) {
            refreshContext?.let { ctx ->
                refreshFromContext(ctx)
            }
        }

        private val postCommandCoordinator = PostCommandRefreshCoordinator()

        private val _appState = MutableStateFlow<MusicState?>(null)
        val appState: StateFlow<MusicState?> = _appState.asStateFlow()

        private fun emitAppState(state: MusicState?) {
            val prev = _appState.value
            _appState.value = state
            val sessionChanged = prev?.sessionToken != state?.sessionToken ||
                prev?.isPlaying != state?.isPlaying ||
                prev?.title != state?.title ||
                prev?.likeStatus != state?.likeStatus
            if (sessionChanged) {
                postCommandCoordinator.onStateEmitted()
            }
        }

        fun requestRefreshAsync(context: Context): Job {
            refreshContext = context.applicationContext
            return refreshCoalescer.request()
        }

        internal fun isComponentEnabledInListenerSetting(
            flat: String?,
            targetPackage: String,
            targetClassName: String
        ): Boolean {
            if (flat.isNullOrBlank()) return false
            return flat.split(':').any { rawEntry ->
                val entry = rawEntry.trim()
                val slash = entry.indexOf('/')
                if (slash <= 0 || slash >= entry.length - 1) return@any false
                val pkg = entry.substring(0, slash)
                val rawCls = entry.substring(slash + 1)
                val resolvedCls = if (rawCls.startsWith(".")) "$pkg$rawCls" else rawCls
                pkg == targetPackage && resolvedCls == targetClassName
            }
        }

        fun isNotificationAccessEnabled(context: Context): Boolean {
            val flat = Settings.Secure.getString(
                context.contentResolver,
                "enabled_notification_listeners"
            ) ?: return false
            val target = ComponentName(context, MediaNotificationListenerService::class.java)
            return isComponentEnabledInListenerSetting(
                flat = flat,
                targetPackage = target.packageName,
                targetClassName = target.className
            )
        }

        fun refreshFromContext(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
                emitAppState(null)
                return
            }
            if (!isNotificationAccessEnabled(context) && !listenerConnected) {
                Api21Helper.clearObservedController()
                emitAppState(null)
                return
            }
            emitAppState(Api21Helper.pollState(context))
        }

        fun sendAction(
            context: Context,
            action: MusicAction,
            expectedState: MusicState? = null
        ): Boolean {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return false
            if (!isNotificationAccessEnabled(context) && !listenerConnected) return false
            val appCtx = context.applicationContext
            val handled = Api21Helper.sendAction(appCtx, action, expectedState)
            if (handled) {
                postCommandCoordinator.scheduleVerificationRefresh(
                    scope = serviceScope,
                    ioDispatcher = Dispatchers.IO,
                    graceMs = 250L
                ) {
                    refreshFromContext(appCtx)
                }
            }
            return handled
        }

        @RequiresApi(Build.VERSION_CODES.LOLLIPOP)
        private object Api21Helper {
            private val callbackLock = Any()
            private val mainHandler = Handler(Looper.getMainLooper())
            private var observedController: MediaController? = null
            private var observedCallback: MediaController.Callback? = null

            fun clearObservedController() {
                synchronized(callbackLock) {
                    val prev = observedController
                    val cb = observedCallback
                    if (prev != null && cb != null) {
                        try {
                            prev.unregisterCallback(cb)
                        } catch (_: Exception) {
                        }
                    }
                    observedController = null
                    observedCallback = null
                }
            }

            private fun ensureObservedController(
                appContext: Context,
                controller: MediaController
            ) {
                synchronized(callbackLock) {
                    if (observedController?.sessionToken == controller.sessionToken) return
                    clearObservedController()
                    val cb = object : MediaController.Callback() {
                        override fun onPlaybackStateChanged(state: PlaybackState?) {
                            requestRefreshAsync(appContext)
                        }

                        override fun onMetadataChanged(metadata: MediaMetadata?) {
                            requestRefreshAsync(appContext)
                        }

                        override fun onSessionDestroyed() {
                            clearObservedController()
                            requestRefreshAsync(appContext)
                        }
                    }
                    try {
                        controller.registerCallback(cb, mainHandler)
                        observedController = controller
                        observedCallback = cb
                    } catch (_: Exception) {
                    }
                }
            }

            fun pollState(context: Context): MusicState? {
                val selected = NativeSessionSelector.selectPreferred(
                    candidates = listCandidates(context),
                    ytmPackage = YTM_PACKAGE
                )
                if (selected == null) {
                    clearObservedController()
                    return null
                }
                val controller = selected.handle
                ensureObservedController(context.applicationContext, controller)
                val meta = controller.metadata
                val playback = controller.playbackState

                val title = meta?.getString(MediaMetadata.METADATA_KEY_TITLE)
                    ?: meta?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
                    ?: ""
                val artist = meta?.getString(MediaMetadata.METADATA_KEY_ARTIST)
                    ?: meta?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
                    ?: meta?.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)
                    ?: ""

                val pbState = playback?.state ?: PlaybackState.STATE_NONE
                val isPlaying = pbState == PlaybackState.STATE_PLAYING ||
                    pbState == PlaybackState.STATE_BUFFERING

                val durMs = meta?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
                val rawPosMs = playback?.position ?: 0L
                val posMs = if (pbState == PlaybackState.STATE_PLAYING &&
                    playback != null &&
                    playback.lastPositionUpdateTime > 0L
                ) {
                    val elapsed = (SystemClock.elapsedRealtime() - playback.lastPositionUpdateTime)
                        .coerceAtLeast(0L)
                    rawPosMs + (elapsed * playback.playbackSpeed).toLong()
                } else {
                    rawPosMs
                }

                val likeStatus = extractLikeStatus(meta?.getRating(MediaMetadata.METADATA_KEY_USER_RATING))

                return MusicState(
                    title = title.trim(),
                    artist = artist.trim(),
                    isPlaying = isPlaying,
                    likeStatus = likeStatus,
                    currentSec = (posMs.coerceAtLeast(0L)) / 1000f,
                    durationSec = (durMs.coerceAtLeast(0L)) / 1000f,
                    mode = MusicMode.APP,
                    signedIn = true,
                    hasActiveSession = title.isNotBlank() || playback != null,
                    sessionPackage = controller.packageName,
                    sessionToken = controller.sessionToken
                )
            }

            fun sendAction(
                context: Context,
                action: MusicAction,
                expectedState: MusicState?
            ): Boolean {
                val resolved = NativeSessionSelector.resolveCommand(
                    candidates = listCandidates(context),
                    action = action,
                    expectedState = expectedState,
                    ytmPackage = YTM_PACKAGE
                ) ?: return false
                val controls = resolved.target.handle.transportControls
                when (resolved.intent) {
                    TransportIntent.PAUSE -> controls.pause()
                    TransportIntent.PLAY -> controls.play()
                    TransportIntent.NEXT -> controls.skipToNext()
                    TransportIntent.PREVIOUS -> controls.skipToPrevious()
                    TransportIntent.SET_LIKE -> controls.setRating(Rating.newThumbRating(true))
                    TransportIntent.SET_DISLIKE -> controls.setRating(Rating.newThumbRating(false))
                    TransportIntent.CLEAR_RATING ->
                        controls.setRating(Rating.newUnratedRating(Rating.RATING_THUMB_UP_DOWN))
                }
                return true
            }

            private fun listCandidates(context: Context): List<SessionCandidate<MediaController>> {
                return try {
                    val mgr = context.getSystemService(Context.MEDIA_SESSION_SERVICE)
                        as? MediaSessionManager ?: return emptyList()
                    val component = ComponentName(
                        context,
                        MediaNotificationListenerService::class.java
                    )
                    mgr.getActiveSessions(component).map { controller ->
                        val state = controller.playbackState?.state ?: PlaybackState.STATE_NONE
                        val isPlaying = state == PlaybackState.STATE_PLAYING ||
                            state == PlaybackState.STATE_BUFFERING
                        val isPaused = state == PlaybackState.STATE_PAUSED
                        val like = extractLikeStatus(
                            controller.metadata?.getRating(MediaMetadata.METADATA_KEY_USER_RATING)
                        )
                        SessionCandidate(
                            handle = controller,
                            packageName = controller.packageName.orEmpty(),
                            token = controller.sessionToken,
                            isPlaying = isPlaying,
                            isPaused = isPaused,
                            likeStatus = like
                        )
                    }
                } catch (_: SecurityException) {
                    emptyList()
                } catch (_: Exception) {
                    emptyList()
                }
            }

            private fun extractLikeStatus(rating: Rating?): LikeStatus {
                if (rating == null || !rating.isRated) return LikeStatus.NONE
                return when (rating.ratingStyle) {
                    Rating.RATING_THUMB_UP_DOWN ->
                        if (rating.isThumbUp) LikeStatus.LIKED else LikeStatus.DISLIKED
                    Rating.RATING_HEART ->
                        if (rating.hasHeart()) LikeStatus.LIKED else LikeStatus.NONE
                    else -> LikeStatus.NONE
                }
            }
        }
    }
}
