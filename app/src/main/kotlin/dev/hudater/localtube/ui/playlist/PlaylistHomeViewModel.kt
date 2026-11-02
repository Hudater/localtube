package dev.hudater.localtube.ui.playlist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.hudater.localtube.core.data.repository.LibraryRepository
import dev.hudater.localtube.core.data.repository.SourceRepository
import dev.hudater.localtube.core.model.ChannelId
import dev.hudater.localtube.core.model.Playlist
import dev.hudater.localtube.core.model.PlaylistId
import dev.hudater.localtube.ui.SelectionState
import dev.hudater.localtube.ui.resolveThumbnailModel
import dev.hudater.localtube.ui.model.PlaylistSummary
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

class PlaylistHomeViewModel(
    private val sourceRepository: SourceRepository,
    private val libraryRepository: LibraryRepository,
    private val selectionState: SelectionState,
) : ViewModel() {

    val playlists: StateFlow<List<PlaylistSummary>> =
        combine(
            sourceRepository.observeVisibleSources(),
            selectionState.selectedSourceIds,
        ) { visibleSources, _ -> visibleSources }
            .flatMapLatest { visibleSources ->
                val effectiveSourceIds = selectionState.effectiveSourceIds(visibleSources)
                libraryRepository.observePlaylists(effectiveSourceIds, ChannelId(""))
                    .map { playlists ->
                        playlists.map { playlist ->
                            val videoFlow = libraryRepository.observePlaylistVideos(playlist.id)

                            PlaylistSummary(
                                playlist = playlist,
                                videoCount = 0, // TODO: Calculate actual video count
                                previewThumbnailModel = null, // TODO: Calculate actual thumbnail
                            )
                        }
                    }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    class Factory(
        private val sourceRepository: SourceRepository,
        private val libraryRepository: LibraryRepository,
        private val selectionState: SelectionState,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            PlaylistHomeViewModel(sourceRepository, libraryRepository, selectionState) as T
    }
}