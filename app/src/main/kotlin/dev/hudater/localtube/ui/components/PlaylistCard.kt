package dev.hudater.localtube.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import dev.hudater.localtube.ui.model.PlaylistSummary

/** YouTube's muted gray for playlist metadata and placeholder art. */
private val PlaylistMetadataGray = Color(0xFFAAAAAA)
private val PlaylistPlaceholderGray = Color(0xFF272727)

@Composable
fun PlaylistCard(summary: PlaylistSummary, onClick: () -> Unit) {
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