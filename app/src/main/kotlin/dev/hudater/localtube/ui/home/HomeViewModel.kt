package dev.hudater.localtube.ui.home

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.hudater.localtube.core.data.repository.LibraryRepository
import dev.hudater.localtube.core.data.repository.SourceRepository
import dev.hudater.localtube.core.model.Channel
import dev.hudater.localtube.core.model.Source
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

    class Factory(
        private val appContext: Context,
        private val sourceRepository: SourceRepository,
        private val libraryRepository: LibraryRepository,
        private val selectionState: SelectionState,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            HomeViewModel(appContext, sourceRepository, libraryRepository, selectionState) as T
    }
}
