package dev.hudater.localtube.ui.playlist

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import dev.hudater.localtube.core.model.Video
import dev.hudater.localtube.ui.resolveThumbnailModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistScreen(
    viewModel: PlaylistViewModel,
    onVideoClick: (Video) -> Unit,
    onBack: () -> Unit,
) {
    val videos by viewModel.videos.collectAsState()

    androidx.compose.foundation.layout.Column {
        TopAppBar(
            title = { Text(viewModel.playlistName) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                }
            },
        )

        LazyColumn {
            itemsIndexed(videos) { index, video ->
                PlaylistVideoRow(index = index, video = video, onClick = { onVideoClick(video) })
            }
        }
    }
}

@Composable
private fun PlaylistVideoRow(index: Int, video: Video, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "${index + 1}",
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(end = 8.dp),
        )
        AsyncImage(
            model = resolveThumbnailModel(video.sourceId, video.thumbnailPath),
            contentDescription = video.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier.aspectRatio(16f / 9f).padding(end = 8.dp),
        )
        Text(video.title, style = MaterialTheme.typography.bodyMedium, maxLines = 2)
    }
}
