package dev.hudater.localtube.ui.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.media3.exoplayer.ExoPlayer
import dev.hudater.localtube.core.data.repository.SourceRepository
import dev.hudater.localtube.core.model.Video
import dev.hudater.localtube.core.player.LocalTubeMediaItems
import dev.hudater.localtube.core.player.LocalTubePlayerFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Owns the [ExoPlayer] instance for the currently-open video. One
 * instance per navigation to the player screen — released in
 * [onCleared], not reused across videos, which keeps lifecycle simple at
 * the cost of a brief re-init on every video open. Fine for a personal
 * app; worth revisiting only if that re-init becomes noticeable.
 *
 * Player setup (looking up the video's [dev.hudater.localtube.core.model.Source]
 * and preparing ExoPlayer) happens asynchronously in [viewModelScope]
 * rather than blocking the constructor — see [player] being nullable
 * until ready, exposed via [uiState].
 */
class PlayerViewModel(
    private val video: Video,
    private val playerFactory: LocalTubePlayerFactory,
    private val sourceRepository: SourceRepository,
) : ViewModel() {

    private var exoPlayer: ExoPlayer? = null

    private val _uiState = MutableStateFlow<PlayerUiState>(PlayerUiState.Loading)
    val uiState: StateFlow<PlayerUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            val source = sourceRepository.observeAllSources().first()
                .firstOrNull { it.id == video.sourceId }

            if (source == null) {
                _uiState.value = PlayerUiState.Error("Source for this video no longer exists")
                return@launch
            }

            val player = playerFactory.create().apply {
                setMediaItem(LocalTubeMediaItems.buildMediaItem(source, video))
                prepare()
                playWhenReady = true
            }
            exoPlayer = player
            _uiState.value = PlayerUiState.Ready(player)
        }
    }

    override fun onCleared() {
        exoPlayer?.release()
    }

    class Factory(
        private val video: Video,
        private val playerFactory: LocalTubePlayerFactory,
        private val sourceRepository: SourceRepository,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            PlayerViewModel(video, playerFactory, sourceRepository) as T
    }
}

sealed interface PlayerUiState {
    data object Loading : PlayerUiState
    data class Ready(val player: ExoPlayer) : PlayerUiState
    data class Error(val message: String) : PlayerUiState
}
