package com.cl600.obd.music

import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.Rating
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.annotation.RequiresApi
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
        refreshFromContext(applicationContext)
    }

    override fun onListenerDisconnected() {
        listenerConnected = false
        _appState.value = null
        super.onListenerDisconnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        refreshFromContext(applicationContext)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        refreshFromContext(applicationContext)
    }

    companion object {
        const val YTM_PACKAGE = "com.google.android.apps.youtube.music"

        @Volatile
        private var listenerConnected = false

        private val _appState = MutableStateFlow<MusicState?>(null)
        val appState: StateFlow<MusicState?> = _appState.asStateFlow()

        fun isNotificationAccessEnabled(context: Context): Boolean {
            val flat = Settings.Secure.getString(
                context.contentResolver,
                "enabled_notification_listeners"
            ) ?: return false
            val target = ComponentName(context, MediaNotificationListenerService::class.java)
            return flat.contains(target.flattenToString()) ||
                flat.contains(MediaNotificationListenerService::class.java.simpleName)
        }

        fun refreshFromContext(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
                _appState.value = null
                return
            }
            if (!isNotificationAccessEnabled(context) && !listenerConnected) {
                _appState.value = null
                return
            }
            _appState.value = Api21Helper.pollState(context)
        }

        fun sendAction(context: Context, action: MusicAction): Boolean {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return false
            if (!isNotificationAccessEnabled(context) && !listenerConnected) return false
            val handled = Api21Helper.sendAction(context, action)
            if (handled) {
                _appState.value = Api21Helper.pollState(context)
            }
            return handled
        }

        @RequiresApi(Build.VERSION_CODES.LOLLIPOP)
        private object Api21Helper {
            fun pollState(context: Context): MusicState? {
                val controller = findPreferredController(context) ?: return null
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
                    hasActiveSession = title.isNotBlank() || playback != null
                )
            }

            fun sendAction(context: Context, action: MusicAction): Boolean {
                val controller = findPreferredController(context) ?: return false
                val controls = controller.transportControls
                val currentState = controller.playbackState?.state ?: PlaybackState.STATE_NONE
                val currentLike = extractLikeStatus(
                    controller.metadata?.getRating(MediaMetadata.METADATA_KEY_USER_RATING)
                )

                when (action) {
                    MusicAction.PLAY_PAUSE -> {
                        if (currentState == PlaybackState.STATE_PLAYING ||
                            currentState == PlaybackState.STATE_BUFFERING
                        ) {
                            controls.pause()
                        } else {
                            controls.play()
                        }
                    }
                    MusicAction.NEXT -> controls.skipToNext()
                    MusicAction.PREVIOUS -> controls.skipToPrevious()
                    MusicAction.LIKE -> {
                        val rating = if (currentLike == LikeStatus.LIKED) {
                            Rating.newUnratedRating(Rating.RATING_THUMB_UP_DOWN)
                        } else {
                            Rating.newThumbRating(true)
                        }
                        controls.setRating(rating)
                    }
                    MusicAction.DISLIKE -> {
                        val rating = if (currentLike == LikeStatus.DISLIKED) {
                            Rating.newUnratedRating(Rating.RATING_THUMB_UP_DOWN)
                        } else {
                            Rating.newThumbRating(false)
                        }
                        controls.setRating(rating)
                    }
                }
                return true
            }

            private fun findPreferredController(context: Context): MediaController? {
                return try {
                    val mgr = context.getSystemService(Context.MEDIA_SESSION_SERVICE)
                        as? MediaSessionManager ?: return null
                    val component = ComponentName(
                        context,
                        MediaNotificationListenerService::class.java
                    )
                    val controllers = mgr.getActiveSessions(component)
                    controllers.firstOrNull { it.packageName == YTM_PACKAGE }
                        ?: controllers.firstOrNull {
                            val state = it.playbackState?.state
                            state == PlaybackState.STATE_PLAYING ||
                                state == PlaybackState.STATE_PAUSED
                        }
                        ?: controllers.firstOrNull()
                } catch (_: SecurityException) {
                    null
                } catch (_: Exception) {
                    null
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
