package dev.hudater.localtube.ui.playlist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.hudater.localtube.core.data.repository.LibraryRepository
import dev.hudater.localtube.core.model.PlaylistId
import dev.hudater.localtube.core.model.Video
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

class PlaylistViewModel(
    playlistId: PlaylistId,
    val playlistName: String,
    libraryRepository: LibraryRepository,
) : ViewModel() {

    val videos: StateFlow<List<Video>> =
        libraryRepository.observePlaylistVideos(playlistId)
            .map { it } // playlist_index ordering is already applied at the DAO query level
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    class Factory(
        private val playlistId: PlaylistId,
        private val playlistName: String,
        private val libraryRepository: LibraryRepository,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            PlaylistViewModel(playlistId, playlistName, libraryRepository) as T
    }
}
