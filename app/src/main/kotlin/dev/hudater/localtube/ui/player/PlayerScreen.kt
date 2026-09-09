package dev.hudater.localtube.ui.player

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.ui.PlayerView
import dev.hudater.localtube.core.model.Video

@Composable
fun PlayerScreen(
    viewModel: PlayerViewModel,
    video: Video,
) {
    val uiState by viewModel.uiState.collectAsState()

    Column {
        Box(
            modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f),
            contentAlignment = Alignment.Center,
        ) {
            when (val state = uiState) {
                is PlayerUiState.Loading -> CircularProgressIndicator()
                is PlayerUiState.Error -> Text(state.message, color = MaterialTheme.colorScheme.error)
                is PlayerUiState.Ready -> AndroidView(
                    factory = { context ->
                        PlayerView(context).apply { player = state.player }
                    },
                    modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f),
                )
            }
        }

        Text(
            text = video.title,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(16.dp),
        )
    }
}
