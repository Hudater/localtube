package dev.hudater.localtube.ui.channel

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import dev.hudater.localtube.core.model.Playlist
import dev.hudater.localtube.core.model.Video
import dev.hudater.localtube.ui.components.VideoCard

/** YouTube's muted gray for playlist metadata and placeholder art. */
private val PlaylistMetadataGray = Color(0xFFAAAAAA)
private val PlaylistPlaceholderGray = Color(0xFF272727)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChannelScreen(
    viewModel: ChannelViewModel,
    onVideoClick: (Video) -> Unit,
    onPlaylistClick: (Playlist) -> Unit,
    onBack: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsState()

    // The TopAppBar + TabRow sit OUTSIDE the scrolling region and the tab
    // content takes weight(1f), so only the list scrolls and the top bar
    // is always visible — previously an unweighted LazyColumn could
    // stretch underneath it.
    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(uiState.channelName) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
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
            ChannelTab.VIDEOS -> LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f),
                verticalArrangement = Arrangement.spacedBy(24.dp),
                contentPadding = PaddingValues(top = 8.dp, bottom = 16.dp),
            ) {
                items(uiState.standaloneVideos, key = { it.id.value }) { video ->
                    VideoCard(
                        video = video,
                        // All rows belong to this page's channel; null just
                        // hides the avatar/byline block gracefully.
                        channel = uiState.channel,
                        onClick = { onVideoClick(video) },
                    )
                }
            }
            ChannelTab.PLAYLISTS -> LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f),
                verticalArrangement = Arrangement.spacedBy(24.dp),
                contentPadding = PaddingValues(top = 8.dp, bottom = 16.dp),
            ) {
                items(uiState.playlists, key = { it.playlist.id.value }) { summary ->
                    PlaylistCard(summary = summary, onClick = { onPlaylistClick(summary.playlist) })
                }
            }
        }
    }
}

/**
 * YouTube-style playlist card for the Playlists tab: a square-ish 100dp
 * thumbnail (the first video's thumbnail, falling back to the
 * playlist's own captured thumbnail, then a gray placeholder) with the
 * playlist name + video count beside it.
 */
@Composable
private fun PlaylistCard(summary: PlaylistSummary, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(100.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(PlaylistPlaceholderGray),
            contentAlignment = Alignment.Center,
        ) {
            if (summary.previewThumbnailModel != null) {
                AsyncImage(
                    model = summary.previewThumbnailModel,
                    contentDescription = summary.playlist.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Icon(
                    Icons.AutoMirrored.Filled.PlaylistPlay,
                    contentDescription = null,
                    tint = PlaylistMetadataGray,
                )
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = summary.playlist.name,
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = playlistCountLabel(summary.videoCount),
                style = MaterialTheme.typography.bodyMedium,
                color = PlaylistMetadataGray,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

private fun playlistCountLabel(count: Int): String =
    if (count == 1) "1 video" else "$count videos"
