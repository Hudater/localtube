package dev.hudater.localtube.ui.channel

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import dev.hudater.localtube.core.model.Playlist
import dev.hudater.localtube.core.model.Video
import dev.hudater.localtube.ui.resolveThumbnailModel

@androidx.compose.material3.ExperimentalMaterial3Api
@Composable
fun ChannelScreen(
    viewModel: ChannelViewModel,
    onVideoClick: (Video) -> Unit,
    onPlaylistClick: (Playlist) -> Unit,
    onBack: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsState()

    Column {
        TopAppBar(
            title = { Text(uiState.channelName) },
            navigationIcon = {
                androidx.compose.material3.IconButton(onClick = onBack) {
                    androidx.compose.material3.Icon(
                        androidx.compose.material.icons.Icons.Filled.ArrowBack,
                        contentDescription = "Back",
                    )
                }
            },
        )

        TabRow(selectedTabIndex = uiState.selectedTab.ordinal) {
            Tab(
                selected = uiState.selectedTab == ChannelTab.VIDEOS,
                onClick = { viewModel.selectTab(ChannelTab.VIDEOS) },
                text = { Text("Videos") },
            )
            Tab(
                selected = uiState.selectedTab == ChannelTab.PLAYLISTS,
                onClick = { viewModel.selectTab(ChannelTab.PLAYLISTS) },
                text = { Text("Playlists") },
            )
        }

        when (uiState.selectedTab) {
            ChannelTab.VIDEOS -> LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
                items(uiState.standaloneVideos, key = { it.id.value }) { video ->
                    ChannelVideoRow(video = video, onClick = { onVideoClick(video) })
                }
            }
            ChannelTab.PLAYLISTS -> LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
                items(uiState.playlists, key = { it.id.value }) { playlist ->
                    PlaylistRow(playlist = playlist, onClick = { onPlaylistClick(playlist) })
                }
            }
        }
    }
}

@Composable
private fun ChannelVideoRow(video: Video, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = resolveThumbnailModel(video.sourceId, video.thumbnailPath),
            contentDescription = video.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier.aspectRatio(16f / 9f).padding(end = 8.dp),
        )
        Text(video.title, style = MaterialTheme.typography.bodyMedium, maxLines = 2)
    }
}

@Composable
private fun PlaylistRow(playlist: Playlist, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.aspectRatio(16f / 9f)) {
            AsyncImage(
                model = resolveThumbnailModel(playlist.sourceId, playlist.thumbnailPath),
                contentDescription = playlist.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Text(playlist.name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 8.dp))
    }
}
