package dev.hudater.localtube.playback

import android.app.PictureInPictureParams
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Rational
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import dev.hudater.localtube.core.data.repository.SourceRepository
import dev.hudater.localtube.core.model.Video
import dev.hudater.localtube.core.player.LocalTubeMediaItems
import dev.hudater.localtube.core.player.LocalTubePlayerFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * App-scoped handle on playback. The player itself now lives inside
 * [PlaybackService] (a MediaSessionService) so the system gives us the
 * media notification and audio-focus handling; this class drives it
 * remotely through a [MediaController]. Playback still outlives the
 * player screen — that's the whole point of the floating mini player —
 * because the session survives as long as the service does.
 *
 * [open] is a no-op when the requested video is already the one playing,
 * so recomposition/navigation can call it freely. Opening a different
 * video reuses the same session/player (setMediaItem replaces the item).
 *
 * NOTE: the public API here is frozen — MainActivity, PlayerScreen,
 * MiniPlayer and the PiP wiring all build against it.
 */
class PlaybackManager(
    private val appContext: Context,
    // Kept only so the AppContainer construction site stays stable —
    // the service builds the ExoPlayer through the same factory now.
    @Suppress("unused") private val playerFactory: LocalTubePlayerFactory,
    private val sourceRepository: SourceRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _state = MutableStateFlow<PlaybackState>(PlaybackState.Idle)
    val state: StateFlow<PlaybackState> = _state.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)

    /** Convenience for callers that just need the current video, if any. */
    val currentVideo: Video?
        get() = (_state.value as? PlaybackState.Playing)?.video

    /**
     * Whether audio is actually playing right now. Fed by the controller's
     * [Player.Listener] (session-side events, so play/pause from the media
     * notification is reflected here too). Drives PiP auto-enter.
     */
    val isPlaying: Boolean
        get() = _isPlaying.value

    private var controller: MediaController? = null
    private var controllerFuture: ListenableFuture<MediaController>? = null

    private val playbackListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _isPlaying.value = isPlaying
        }
    }

    private val mainExecutor: Executor
        get() = ContextCompat.getMainExecutor(appContext)

    fun open(video: Video) {
        if (currentVideo?.id == video.id && _state.value is PlaybackState.Playing) return
        scope.launch {
            _state.value = PlaybackState.Loading
            try {
                val source = sourceRepository.getSource(video.sourceId)
                if (source == null) {
                    _state.value = PlaybackState.Failed(video, "Source for this video no longer exists")
                    return@launch
                }
                val controller = awaitController()
                // The media notification renders straight from the item's
                // MediaMetadata — without a title it would just say the app
                // name. Artwork is skipped: thumbnailPath is a source-relative
                // path, not something the notification's loader can fetch.
                val mediaItem = LocalTubeMediaItems.buildMediaItem(source, video)
                    .buildUpon()
                    .setMediaMetadata(
                        MediaMetadata.Builder()
                            .setTitle(video.title)
                            .build(),
                    )
                    .build()
                controller.setMediaItem(mediaItem)
                controller.prepare()
                controller.playWhenReady = true
                _state.value = PlaybackState.Playing(controller, video)
            } catch (t: Throwable) {
                if (_state.value is PlaybackState.Loading) {
                    _state.value = PlaybackState.Failed(video, t.message ?: "Could not start playback")
                }
            }
        }
    }

    fun playPause() {
        controller?.let { if (it.isPlaying) it.pause() else it.play() }
    }

    fun seekBy(deltaMs: Long) {
        val controller = controller ?: return
        controller.seekTo((controller.currentPosition + deltaMs).coerceAtLeast(0))
    }

    fun setPlaybackSpeed(speed: Float) {
        controller?.setPlaybackSpeed(speed)
    }

    /** Stops playback entirely (mini player's close button). */
    fun close() {
        // Order matters: stop the session-side player first (that's what
        // drops the media notification), then disconnect, then stop the
        // service so it doesn't linger in the background.
        controller?.stop()
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controller = null
        controllerFuture = null
        appContext.stopService(Intent(appContext, PlaybackService::class.java))
        _isPlaying.value = false
        _state.value = PlaybackState.Idle
    }

    /**
     * 16:9 picture-in-picture window params. On Android 12+ these carry
     * setAutoEnterEnabled so leaving the app while playing pops straight
     * into PiP with no transition jank; MainActivity keeps them updated
     * on play/pause changes (auto-enter must be OFF while paused, or the
     * system would show a frozen frame). On API 26-30 MainActivity falls
     * back to an explicit enter from onUserLeaveHint with these params.
     */
    fun pipParams(context: Context): PictureInPictureParams {
        val builder = PictureInPictureParams.Builder()
            .setAspectRatio(Rational(16, 9))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setAutoEnterEnabled(isPlaying)
        }
        return builder.build()
    }

    /**
     * Connects to [PlaybackService]'s session, reusing the existing
     * controller across opens. The guava future is bridged to the
     * coroutine world on the main executor (MediaController demands all
     * calls from the thread it was created on).
     */
    private suspend fun awaitController(): MediaController {
        controller?.let { return it }
        return suspendCancellableCoroutine { continuation ->
            val future = MediaController.Builder(
                appContext,
                SessionToken(appContext, ComponentName(appContext, PlaybackService::class.java)),
            )
                // onDisconnected can only be registered at build time —
                // a connected controller's addListener only accepts
                // Player.Listener.
                .addListener(
                    object : MediaController.Listener {
                        override fun onDisconnected(controller: MediaController) {
                            if (this@PlaybackManager.controller === controller) {
                                this@PlaybackManager.controller = null
                                this@PlaybackManager.controllerFuture = null
                                _isPlaying.value = false
                                _state.value = PlaybackState.Idle
                            }
                        }
                    },
                )
                .buildAsync()
            controllerFuture = future
            continuation.invokeOnCancellation { future.cancel(false) }
            future.addListener(
                {
                    try {
                        val connected = future.get()
                        controller = connected
                        connected.addListener(playbackListener)
                        continuation.resume(connected)
                    } catch (t: Throwable) {
                        if (controllerFuture === future) controllerFuture = null
                        continuation.resumeWithException(t)
                    }
                },
                mainExecutor,
            )
        }
    }
}

sealed interface PlaybackState {
    data object Idle : PlaybackState
    data object Loading : PlaybackState
    data class Playing(val player: Player, val video: Video) : PlaybackState
    data class Failed(val video: Video?, val message: String) : PlaybackState
}
