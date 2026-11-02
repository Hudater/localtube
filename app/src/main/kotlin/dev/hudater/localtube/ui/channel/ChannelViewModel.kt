package dev.hudater.localtube.ui.channel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.hudater.localtube.core.data.repository.LibraryRepository
import dev.hudater.localtube.core.data.repository.SourceRepository
import dev.hudater.localtube.core.model.Channel
import dev.hudater.localtube.core.model.ChannelId
import dev.hudater.localtube.core.model.Playlist
import dev.hudater.localtube.core.model.Video
import dev.hudater.localtube.ui.SelectionState
import dev.hudater.localtube.ui.resolveThumbnailModel
import dev.hudater.localtube.ui.model.PlaylistSummary
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

enum class ChannelTab { VIDEOS, PLAYLISTS }

/**
 * Backs the channel page opened from tapping an avatar. Scoped to
 * whichever sources are currently selected (see [SelectionState]) plus
 * this one fixed [channelId] — see docs/ARCHITECTURE.md, "Channel page".
 */
data class ChannelUiState(
    val channelName: String = "",
    val selectedTab: ChannelTab = ChannelTab.VIDEOS,
    /** The page's channel, when found among the loaded sources — feeds the avatar/byline on video rows. */
    val channel: Channel? = null,
    val standaloneVideos: List<Video> = emptyList(),
    val playlists: List<PlaylistSummary> = emptyList(),
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

    private val selectedTab = MutableStateFlow(ChannelTab.VIDEOS)

    val uiState: StateFlow<ChannelUiState> =
        combine(
            sourceRepository.observeVisibleSources(),
            selectionState.selectedSourceIds,
            selectedTab,
        ) { visibleSources, _, tab -> visibleSources to tab }
            .flatMapLatest { (visibleSources, tab) ->
                val effectiveSourceIds = selectionState.effectiveSourceIds(visibleSources)
                combine(
                    libraryRepository.observeChannels(effectiveSourceIds),
                    libraryRepository.observeStandaloneVideos(effectiveSourceIds, channelId),
                    libraryRepository.observePlaylists(effectiveSourceIds, channelId),
                ) { channels, videos, playlists ->
                    Triple(channels.firstOrNull { it.id == channelId }, videos, playlists)
                }.flatMapLatest { (channel, videos, playlists) ->
                    playlistSummaries(playlists).map { summaries ->
                        ChannelUiState(
                            channelName = channelName,
                            selectedTab = tab,
                            channel = channel,
                            standaloneVideos = videos,
                            playlists = summaries,
                        )
                    }
                }
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ChannelUiState(channelName = channelName))

    fun selectTab(tab: ChannelTab) {
        selectedTab.value = tab
    }

    /**
     * Observes each playlist's videos to derive the Playlists-tab card
     * bits: the video count and the first video's thumbnail (falling
     * back to the playlist's own captured thumbnail when the playlist
     * has no videos). There is no single "playlist summaries" repository
     * query, and one extra Room flow per playlist is cheap for a single
     * channel page; every Room flow emits its initial query result on
     * collection, so the combine fires immediately.
     */
    private fun playlistSummaries(playlists: List<Playlist>): Flow<List<PlaylistSummary>> {
        if (playlists.isEmpty()) return flowOf(emptyList())
        return combine(playlists.map { libraryRepository.observePlaylistVideos(it.id) }) { videoLists ->
            playlists.mapIndexed { index, playlist ->
                val playlistVideos = videoLists[index]
                PlaylistSummary(
                    playlist = playlist,
                    videoCount = playlistVideos.size,
                    previewThumbnailModel = playlistVideos.firstOrNull()?.let { firstVideo ->
                        resolveThumbnailModel(firstVideo.sourceId, firstVideo.thumbnailPath)
                    } ?: resolveThumbnailModel(playlist.sourceId, playlist.thumbnailPath),
                )
            }
        }
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
