package dev.hudater.localtube.ui.home

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.hudater.localtube.core.data.repository.LibraryRepository
import dev.hudater.localtube.core.data.repository.SourceRepository
import dev.hudater.localtube.core.filesystem.SourceFileSystem
import dev.hudater.localtube.core.model.Channel
import dev.hudater.localtube.core.model.Source
import dev.hudater.localtube.core.model.SourceType
import dev.hudater.localtube.core.model.Video
import dev.hudater.localtube.ui.SelectionState
import dev.hudater.localtube.work.ScanScheduler
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class HomeUiState(
    val visibleSources: List<Source> = emptyList(),
    val channels: List<Channel> = emptyList(),
    val videos: List<Video> = emptyList(),
    val isRefreshing: Boolean = false,
)

class HomeViewModel(
    private val appContext: Context,
    private val sourceRepository: SourceRepository,
    private val libraryRepository: LibraryRepository,
    private val selectionState: SelectionState,
    private val fileSystemsByType: Map<SourceType, SourceFileSystem>,
) : ViewModel() {

    private val isRefreshing = MutableStateFlow(false)

    val uiState: StateFlow<HomeUiState> =
        combine(
            sourceRepository.observeVisibleSources(),
            selectionState.selectedSourceIds,
            selectionState.selectedChannelId,
            isRefreshing,
        ) { visibleSources, _, selectedChannelId, refreshing ->
            Triple(visibleSources, selectedChannelId, refreshing)
        }.flatMapLatest { (visibleSources, selectedChannelId, refreshing) ->
            val effectiveSourceIds = selectionState.effectiveSourceIds(visibleSources)
            combine(
                libraryRepository.observeChannels(effectiveSourceIds),
                libraryRepository.observeFeed(effectiveSourceIds, selectedChannelId),
            ) { channels, videos ->
                // Diagnostic: a DB with rows + an empty feed shows up here
                // as videos=0, narrowing "scan didn't write" vs "UI can't
                // read" in one logcat line.
                Log.d(
                    "LocalTubeFeed",
                    "sourceIds=${effectiveSourceIds.map { it.value }} channelId=${selectedChannelId?.value} " +
                        "channels=${channels.size} videos=${videos.size}",
                )
                HomeUiState(
                    visibleSources = visibleSources,
                    channels = channels,
                    videos = videos,
                    isRefreshing = refreshing,
                )
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())

    fun onRefresh() {
        isRefreshing.value = true
        ScanScheduler.scanNow(appContext)
        // WorkManager scans asynchronously; we don't currently observe
        // completion, so the refresh spinner is time-boxed rather than
        // tied to work status — see docs/ARCHITECTURE.md open items for
        // wiring WorkInfo observation if this feels too abrupt in practice.
        viewModelScope.launch {
            kotlinx.coroutines.delay(1_500)
            isRefreshing.value = false
        }
    }

    /**
     * Deletes the video file and its sidecars (thumbnail, .info.json) on
     * disk, then removes the row. The DB row is only removed when every
     * file delete succeeded, so a half-failed delete stays visible and
     * retryable. The next scan reconciles regardless: gone file → row
     * removed as stale.
     */
    fun deleteVideo(video: Video) {
        viewModelScope.launch {
            val source = sourceRepository.getSource(video.sourceId) ?: return@launch
            val fileSystem = fileSystemsByType[source.type] ?: return@launch
            val filesToDelete = buildList {
                add(video.filePath)
                video.thumbnailPath?.let(::add)
                add(video.filePath.substringBeforeLast('.') + ".info.json")
            }.distinct()

            var allDeleted = true
            for (path in filesToDelete) {
                try {
                    fileSystem.deleteFile(source, path)
                } catch (e: Exception) {
                    allDeleted = false
                    Log.e("LocalTubeScan", "Delete failed: '$path' on '${source.label}'", e)
                }
            }
            if (allDeleted) {
                libraryRepository.deleteVideo(video.id)
            }
        }
    }

    class Factory(
        private val appContext: Context,
        private val sourceRepository: SourceRepository,
        private val libraryRepository: LibraryRepository,
        private val selectionState: SelectionState,
        private val fileSystemsByType: Map<SourceType, SourceFileSystem>,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            HomeViewModel(appContext, sourceRepository, libraryRepository, selectionState, fileSystemsByType) as T
    }
}
