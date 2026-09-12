package dev.hudater.localtube.ui.home

import android.content.Context
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import dev.hudater.localtube.core.model.Channel
import dev.hudater.localtube.core.model.Source
import dev.hudater.localtube.core.model.Video
import dev.hudater.localtube.ui.SelectionState
import dev.hudater.localtube.ui.components.VideoCard
import org.json.JSONArray
import org.json.JSONObject

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
    val allSources by viewModel.sources.collectAsState()
    var videoToDelete by remember { mutableStateOf<Video?>(null) }
    var searchActive by remember { mutableStateOf(false) }

    // Collapsing search (back arrow or system back) always resets the
    // query so the feed comes back unfiltered.
    fun collapseSearch() {
        searchActive = false
        viewModel.clearQuery()
    }
    BackHandler(enabled = searchActive) { collapseSearch() }

    // PullToRefreshBox wraps the WHOLE screen so a swipe anywhere below
    // the system bar triggers refresh — previously the top bar and avatar
    // strip sat outside it and swallowed gestures near the top.
    PullToRefreshBox(
        isRefreshing = uiState.isRefreshing,
        onRefresh = viewModel::onRefresh,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                // The root must clear the status bar in both orientations —
                // edge-to-edge otherwise renders the top bar under the
                // clock/notch. Descendants (TopAppBar) consume-aware insets
                // resolve to zero, so nothing gets padded twice.
                .statusBarsPadding(),
        ) {
            if (searchActive) {
                SearchBar(
                    query = uiState.query,
                    onQueryChange = viewModel::onQueryChange,
                    onCollapse = ::collapseSearch,
                )
            } else {
                TopBar(
                    visibleSources = uiState.visibleSources,
                    allSources = allSources,
                    selectionState = selectionState,
                    onOpenSettings = onOpenSettings,
                    onOpenSearch = { searchActive = true },
                )
            }

            ChannelAvatarStrip(
                channels = uiState.channels,
                selectionState = selectionState,
                onChannelClick = onChannelClick,
            )

            if (uiState.query.isNotEmpty() && uiState.videos.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "No results",
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    // ~24dp between rows so cards breathe instead of touching,
                    // matching the YouTube feed's row spacing. The bottom
                    // inset is folded into the content padding so the last
                    // card scrolls clear of the gesture bar.
                    verticalArrangement = Arrangement.spacedBy(24.dp),
                    contentPadding = PaddingValues(
                        top = 8.dp,
                        bottom = 16.dp +
                            WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding(),
                    ),
                ) {
                    items(uiState.videos, key = { it.id.value }) { video ->
                        VideoCard(
                            video = video,
                            // Feed rows show the channel avatar + name like
                            // YouTube; a video whose channel isn't in the
                            // loaded list just renders without the byline.
                            channel = video.channelId?.let { id ->
                                uiState.channels.firstOrNull { it.id == id }
                            },
                            onClick = { onVideoClick(video) },
                            onLongClick = { videoToDelete = video },
                        )
                    }
                }
            }
        }
    }

    videoToDelete?.let { video ->
        AlertDialog(
            onDismissRequest = { videoToDelete = null },
            title = { Text("Delete video?") },
            text = { Text("This permanently deletes \"${video.title}\" and its files from the source folder.") },
            confirmButton = {
                TextButton(onClick = {
                    videoToDelete = null
                    viewModel.deleteVideo(video)
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { videoToDelete = null }) { Text("Cancel") }
            },
        )
    }
}

/**
 * Replaces the app-name slot from the UI reference with a multi-select
 * source dropdown; the right side carries the YouTube-style search and
 * settings-gear buttons. [allSources] (hidden included) backs the gear
 * menu's source export.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TopBar(
    visibleSources: List<Source>,
    allSources: List<Source>,
    selectionState: SelectionState,
    onOpenSettings: () -> Unit,
    onOpenSearch: () -> Unit,
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
        actions = {
            IconButton(onClick = onOpenSearch) {
                Icon(Icons.Filled.Search, contentDescription = "Search")
            }
            SettingsMenuButton(
                sources = allSources,
                onOpenSettings = onOpenSettings,
            )
        },
    )
}

/**
 * The settings gear, grouped right of the search icon like YouTube's top
 * bar. Opens a menu with source management plus an export that writes
 * every configured source (label/type/path + SMB connection details, but
 * NEVER the stored password) as JSON to a user-picked document.
 */
@Composable
private fun SettingsMenuButton(
    sources: List<Source>,
    onOpenSettings: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        if (uri != null) exportSources(context, uri, sources)
    }

    Box {
        IconButton(onClick = { menuExpanded = true }) {
            Icon(Icons.Filled.Settings, contentDescription = "Settings")
        }
        DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
            DropdownMenuItem(
                text = { Text("Manage sources") },
                leadingIcon = { Icon(Icons.Filled.Visibility, contentDescription = null) },
                onClick = {
                    menuExpanded = false
                    onOpenSettings()
                },
            )
            DropdownMenuItem(
                text = { Text("Export sources…") },
                leadingIcon = { Icon(Icons.Filled.Upload, contentDescription = null) },
                onClick = {
                    menuExpanded = false
                    exportLauncher.launch("localtube-sources.json")
                },
            )
        }
    }
}

/**
 * Serialises the configured sources to a JSON array and streams it to
 * the document the user picked via SAF. org.json handles all escaping;
 * any failure is logged with a toast instead of crashing mid-share.
 */
private fun exportSources(context: Context, uri: Uri, sources: List<Source>) {
    try {
        val array = JSONArray()
        sources.forEach { source ->
            val obj = JSONObject()
                .put("label", source.label)
                .put("type", source.type.name)
                .put("path", source.path)
            source.smbConnectionInfo?.let { smb ->
                obj.put(
                    "smb",
                    JSONObject()
                        .put("host", smb.host)
                        .put("shareName", smb.shareName)
                        .put("remotePath", smb.remotePath)
                        .put("username", smb.username ?: JSONObject.NULL),
                )
            }
            array.put(obj)
        }
        context.contentResolver.openOutputStream(uri)?.use { stream ->
            stream.write(array.toString(2).toByteArray(Charsets.UTF_8))
        }
    } catch (e: Exception) {
        Log.e("LocalTubeExport", "Exporting sources failed", e)
        Toast.makeText(context, "Export failed", Toast.LENGTH_SHORT).show()
    }
}

/**
 * YouTube-style inline search: a rounded dark pill replacing the top
 * bar, with a back arrow to collapse, a leading magnifier and a
 * trailing clear button. Focused on expand so typing starts right away.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    onCollapse: () -> Unit,
) {
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
        keyboard?.show()
    }

    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = 12.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onCollapse) {
            Icon(Icons.Filled.ArrowBack, contentDescription = "Close search")
        }
        TextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier.weight(1f).focusRequester(focusRequester),
            placeholder = { Text("Search") },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { onQueryChange("") }) {
                        Icon(Icons.Filled.Close, contentDescription = "Clear search")
                    }
                }
            },
            singleLine = true,
            shape = RoundedCornerShape(28.dp),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surface,
                unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                disabledIndicatorColor = Color.Transparent,
            ),
        )
    }
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
                avatarModel = null,
                isSelected = selectedChannelId == null,
                onClick = { selectionState.selectChannel(null) },
            )
        }
        items(channels, key = { it.id.value }) { channel ->
            ChannelAvatar(
                name = channel.name,
                // The strip renders the fetched channel avatar (an
                // app-internal cached file, set by ChannelIconWorker) —
                // thumbnailPath is a source-relative value that is
                // effectively never set for channels and used to leave
                // every avatar as an empty dark circle. No icon yet falls
                // back to the letter circle, like the feed's bylines.
                avatarModel = channel.iconPath,
                isSelected = selectedChannelId == channel.id,
                onClick = { onChannelClick(channel) },
            )
        }
    }
}

/**
 * A strip avatar: the channel's cached icon file when one exists,
 * otherwise the same letter fallback the feed bylines use (primary
 * circle + white first letter). Selection keeps its highlight — the
 * filled primary background plus a light ring so it stays visible even
 * when a fetched icon covers the circle.
 */
@Composable
private fun ChannelAvatar(
    name: String,
    avatarModel: Any?,
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
                    // Letter fallbacks are always primary like VideoCard's
                    // byline avatar; with an icon, selection tints the
                    // circle behind the image.
                    if (isSelected || avatarModel == null) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.surface
                    },
                )
                .then(
                    if (isSelected) {
                        Modifier.border(2.dp, Color.White.copy(alpha = 0.85f), CircleShape)
                    } else {
                        Modifier
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (avatarModel != null) {
                AsyncImage(
                    model = avatarModel,
                    contentDescription = name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxWidth().aspectRatio(1f).clip(CircleShape),
                )
            } else {
                Text(
                    text = name.firstOrNull()?.uppercase() ?: "?",
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
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
