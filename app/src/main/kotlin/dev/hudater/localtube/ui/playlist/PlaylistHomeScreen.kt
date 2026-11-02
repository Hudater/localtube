package dev.hudater.localtube.ui.playlist

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.hudater.localtube.ui.model.PlaylistSummary
import dev.hudater.localtube.ui.components.PlaylistCard

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistHomeScreen(
    viewModel: PlaylistHomeViewModel,
    onPlaylistClick: (dev.hudater.localtube.core.model.Playlist) -> Unit,
    onBack: () -> Unit,
) {
    val playlists by viewModel.playlists.collectAsState()

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("All Playlists") },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                }
            },
        )

        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f),
            verticalArrangement = Arrangement.spacedBy(24.dp),
            contentPadding = PaddingValues(top = 8.dp, bottom = 16.dp),
        ) {
            items(playlists, key = { it.playlist.id.value }) { summary ->
                PlaylistCard(summary = summary, onClick = { onPlaylistClick(summary.playlist) })
            }
        }
    }
}