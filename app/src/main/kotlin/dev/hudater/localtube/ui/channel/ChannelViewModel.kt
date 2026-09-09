package dev.hudater.localtube.ui.channel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.hudater.localtube.core.data.repository.LibraryRepository
import dev.hudater.localtube.core.data.repository.SourceRepository
import dev.hudater.localtube.core.model.ChannelId
import dev.hudater.localtube.core.model.Playlist
import dev.hudater.localtube.core.model.Video
import dev.hudater.localtube.ui.SelectionState
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn

enum class ChannelTab { VIDEOS, PLAYLISTS }

data class ChannelUiState(
    val channelName: String = "",
    val selectedTab: ChannelTab = ChannelTab.VIDEOS,
    val standaloneVideos: List<Video> = emptyList(),
    val playlists: List<Playlist> = emptyList(),
)

/**
 * Backs the channel page opened from tapping an avatar. Scoped to
 * whichever sources are currently selected (see [SelectionState]) plus
 * this one fixed [channelId] — see docs/ARCHITECTURE.md, "Channel page".
 */
class ChannelViewModel(
    private val channelId: ChannelId,
    private val channelName: String,
    private val sourceRepository: SourceRepository,
    private val libraryRepository: LibraryRepository,
    private val selectionState: SelectionState,
) : ViewModel() {

    private val selectedTab = kotlinx.coroutines.flow.MutableStateFlow(ChannelTab.VIDEOS)

    val uiState: StateFlow<ChannelUiState> =
        combine(
            sourceRepository.observeVisibleSources(),
            selectionState.selectedSourceIds,
            selectedTab,
        ) { visibleSources, _, tab -> visibleSources to tab }
            .flatMapLatest { (visibleSources, tab) ->
                val effectiveSourceIds = selectionState.effectiveSourceIds(visibleSources)
                combine(
                    libraryRepository.observeStandaloneVideos(effectiveSourceIds, channelId),
                    libraryRepository.observePlaylists(effectiveSourceIds, channelId),
                ) { videos, playlists ->
                    ChannelUiState(
                        channelName = channelName,
                        selectedTab = tab,
                        standaloneVideos = videos,
                        playlists = playlists,
                    )
                }
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ChannelUiState(channelName = channelName))

    fun selectTab(tab: ChannelTab) {
        selectedTab.value = tab
    }

    class Factory(
        private val channelId: ChannelId,
        private val channelName: String,
        private val sourceRepository: SourceRepository,
        private val libraryRepository: LibraryRepository,
        private val selectionState: SelectionState,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            ChannelViewModel(channelId, channelName, sourceRepository, libraryRepository, selectionState) as T
    }
}
