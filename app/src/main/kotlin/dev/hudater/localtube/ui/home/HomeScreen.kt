package dev.hudater.localtube.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshContainer
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import dev.hudater.localtube.core.model.Channel
import dev.hudater.localtube.core.model.Source
import dev.hudater.localtube.core.model.Video
import dev.hudater.localtube.ui.SelectionState
import dev.hudater.localtube.ui.resolveThumbnailModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    selectionState: SelectionState,
    onVideoClick: (Video) -> Unit,
    onChannelClick: (Channel) -> Unit,
    onOpenSettings: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsState()

    Column {
        TopBar(
            visibleSources = uiState.visibleSources,
            selectionState = selectionState,
            onOpenSettings = onOpenSettings,
        )

        ChannelAvatarStrip(
            channels = uiState.channels,
            selectionState = selectionState,
            onChannelClick = onChannelClick,
        )

        val pullState = rememberPullToRefreshState()
        if (pullState.isRefreshing) {
            androidx.compose.runtime.LaunchedEffect(Unit) {
                viewModel.onRefresh()
            }
        }
        androidx.compose.runtime.LaunchedEffect(uiState.isRefreshing) {
            if (!uiState.isRefreshing) pullState.endRefresh()
        }
        Box(
            modifier = Modifier.nestedScroll(pullState.nestedScrollConnection),
        ) {
            LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
                items(uiState.videos, key = { it.id.value }) { video ->
                    VideoRow(video = video, onClick = { onVideoClick(video) })
                }
            }
            PullToRefreshContainer(
                state = pullState,
                modifier = Modifier.align(Alignment.TopCenter),
            )
        }
    }
}

/**
 * Replaces the app-name slot from the UI reference with a multi-select
 * source dropdown — see docs/ARCHITECTURE.md, "Source selector".
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TopBar(
    visibleSources: List<Source>,
    selectionState: SelectionState,
    onOpenSettings: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val selectedIds by selectionState.selectedSourceIds.collectAsState()

    TopAppBar(
        title = {
            Row(
                modifier = Modifier.clickable { menuExpanded = true },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val label = if (selectionState.isAllSelected()) {
                    "All sources"
                } else {
                    "${selectedIds.size} source${if (selectedIds.size == 1) "" else "s"}"
                }
                Text(label)
                Icon(Icons.Filled.ArrowDropDown, contentDescription = "Select sources")
            }

            DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                DropdownMenuItem(
                    text = { Text("All") },
                    leadingIcon = {
                        Checkbox(checked = selectionState.isAllSelected(), onCheckedChange = { selectionState.selectAll() })
                    },
                    onClick = { selectionState.selectAll() },
                )
                visibleSources.forEach { source ->
                    DropdownMenuItem(
                        text = { Text(source.label) },
                        leadingIcon = {
                            Checkbox(
                                checked = source.id in selectedIds,
                                onCheckedChange = { selectionState.toggleSource(source.id) },
                            )
                        },
                        onClick = { selectionState.toggleSource(source.id) },
                    )
                }
                DropdownMenuItem(
                    text = { Text("Manage sources") },
                    leadingIcon = { Icon(Icons.Filled.Visibility, contentDescription = null) },
                    onClick = {
                        menuExpanded = false
                        onOpenSettings()
                    },
                )
            }
        },
    )
}

@Composable
private fun ChannelAvatarStrip(
    channels: List<Channel>,
    selectionState: SelectionState,
    onChannelClick: (Channel) -> Unit,
) {
    val selectedChannelId by selectionState.selectedChannelId.collectAsState()

    LazyRow(
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            ChannelAvatar(
                name = "All",
                thumbnailModel = null,
                isSelected = selectedChannelId == null,
                onClick = { selectionState.selectChannel(null) },
            )
        }
        items(channels, key = { it.id.value }) { channel ->
            ChannelAvatar(
                name = channel.name,
                // Channel thumbnails are not tied to one source (a
                // channel can span sources), so channel avatars only
                // render a plain URL/file if one was captured — cross-
                // source channel thumbnail resolution is an open item,
                // see docs/ARCHITECTURE.md.
                thumbnailModel = channel.thumbnailPath,
                isSelected = selectedChannelId == channel.id,
                onClick = { onChannelClick(channel) },
            )
        }
    }
}

@Composable
private fun ChannelAvatar(
    name: String,
    thumbnailModel: Any?,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier.clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(CircleShape)
                .background(
                    if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
                ),
        ) {
            if (thumbnailModel != null) {
                AsyncImage(
                    model = thumbnailModel,
                    contentDescription = name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxWidth().aspectRatio(1f).clip(CircleShape),
                )
            }
        }
        Text(
            text = name,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            modifier = Modifier.size(width = 64.dp, height = 16.dp),
        )
    }
}

@Composable
private fun VideoRow(video: Video, onClick: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Box {
            AsyncImage(
                model = resolveThumbnailModel(video.sourceId, video.thumbnailPath),
                contentDescription = video.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f),
            )
            video.durationSeconds?.let { duration ->
                Text(
                    text = formatDuration(duration),
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(4.dp)
                        .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.7f))
                        .padding(horizontal = 4.dp, vertical = 2.dp),
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
        Text(
            text = video.title,
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 2,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        )
    }
}

private fun formatDuration(totalSeconds: Long): String {
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%d:%02d".format(minutes, seconds)
    }
}
