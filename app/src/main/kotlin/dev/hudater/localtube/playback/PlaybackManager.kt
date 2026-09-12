package dev.hudater.localtube.playback

import android.content.Context
import androidx.media3.exoplayer.ExoPlayer
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

/**
 * App-scoped owner of the single ExoPlayer instance. Playback must
 * outlive the player screen (that's the whole point of the floating
 * mini player), so the player lives here — one per process — instead of
 * in a per-navigation ViewModel that releases on back-press.
 *
 * [open] is a no-op when the requested video is already the one playing,
 * so recomposition/navigation can call it freely. Opening a different
 * video reuses the same ExoPlayer (setMediaItem replaces the item)
 * instead of paying a fresh player init each time.
 */
class PlaybackManager(
    @Suppress("unused") private val appContext: Context,
    private val playerFactory: LocalTubePlayerFactory,
    private val sourceRepository: SourceRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var exoPlayer: ExoPlayer? = null

    private val _state = MutableStateFlow<PlaybackState>(PlaybackState.Idle)
    val state: StateFlow<PlaybackState> = _state.asStateFlow()

    /** Convenience for callers that just need the current video, if any. */
    val currentVideo: Video?
        get() = (_state.value as? PlaybackState.Playing)?.video

    fun open(video: Video) {
        if (currentVideo?.id == video.id && _state.value is PlaybackState.Playing) return
        scope.launch {
            _state.value = PlaybackState.Loading
            val source = sourceRepository.getSource(video.sourceId)
            if (source == null) {
                _state.value = PlaybackState.Failed(video, "Source for this video no longer exists")
                return@launch
            }
            val player = exoPlayer ?: playerFactory.create().also { exoPlayer = it }
            player.setMediaItem(LocalTubeMediaItems.buildMediaItem(source, video))
            player.prepare()
            player.playWhenReady = true
            _state.value = PlaybackState.Playing(player, video)
        }
    }

    fun playPause() {
        exoPlayer?.let { if (it.isPlaying) it.pause() else it.play() }
    }

    fun seekBy(deltaMs: Long) {
        val player = exoPlayer ?: return
        player.seekTo((player.currentPosition + deltaMs).coerceAtLeast(0))
    }

    fun setPlaybackSpeed(speed: Float) {
        exoPlayer?.setPlaybackSpeed(speed)
    }

    /** Stops playback entirely (mini player's close button). */
    fun close() {
        exoPlayer?.release()
        exoPlayer = null
        _state.value = PlaybackState.Idle
    }
}

sealed interface PlaybackState {
    data object Idle : PlaybackState
    data object Loading : PlaybackState
    data class Playing(val player: ExoPlayer, val video: Video) : PlaybackState
    data class Failed(val video: Video?, val message: String) : PlaybackState
}
