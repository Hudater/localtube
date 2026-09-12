package dev.hudater.localtube.ui.mini

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.animation.core.Animatable
import androidx.media3.ui.PlayerView
import dev.hudater.localtube.playback.PlaybackManager
import dev.hudater.localtube.playback.PlaybackState
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The Morphe-style floating popup: while a video plays and the user is
 * NOT on the full player screen, a small draggable window anchored to a
 * corner keeps playback going with close / ±10s / play-pause controls.
 * Tapping the window expands back to the full player.
 *
 * The root Box is deliberately NOT clickable/pointer-input — it must let
 * touches fall through to whatever screen is underneath; only the mini
 * window itself intercepts input.
 */
@Composable
fun MiniPlayerOverlay(
    manager: PlaybackManager,
    hidden: Boolean,
    onExpand: () -> Unit,
) {
    val state by manager.state.collectAsState()
    val playing = state as? PlaybackState.Playing ?: return
    if (hidden) return

    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val dragX = remember { Animatable(0f) }
    var dragY by remember { mutableStateOf(0f) }
    var containerSize by remember { mutableStateOf(IntSize(1, 1)) }

    var isPlaying by remember { mutableStateOf(true) }
    LaunchedEffect(playing) {
        while (true) {
            isPlaying = playing.player.isPlaying
            kotlinx.coroutines.delay(500)
        }
    }

    val miniWidth = 180.dp
    val miniWidthPx = with(density) { miniWidth.toPx() }
    val horizontalMargin = with(density) { 12.dp.toPx() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { containerSize = it },
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .offset { IntOffset(dragX.value.roundToInt(), dragY.roundToInt()) }
                .padding(12.dp)
                .width(miniWidth)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surface)
                .border(
                    1.dp,
                    Color.White.copy(alpha = 0.15f),
                    RoundedCornerShape(12.dp),
                )
                .pointerInput(playing) {
                    detectDragGestures(
                        onDrag = { change, amount ->
                            change.consume()
                            val maxX = (containerSize.width - miniWidthPx - 2 * horizontalMargin)
                                .coerceAtLeast(0f)
                            val newX = (dragX.value + amount.x).coerceIn(-maxX, 0f)
                            scope.launch { dragX.snapTo(newX) }
                            dragY = (dragY + amount.y).coerceIn(
                                -(containerSize.height * 0.6f),
                                containerSize.height * 0.2f,
                            )
                        },
                        onDragEnd = {
                            // Snap to whichever horizontal side is closer.
                            val maxX = (containerSize.width - miniWidthPx - 2 * horizontalMargin)
                                .coerceAtLeast(0f)
                            val target = if (abs(dragX.value) < maxX / 2f) 0f else -maxX
                            scope.launch { dragX.animateTo(target) }
                        },
                    )
                }
                .clickable(onClick = onExpand),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .background(Color.Black),
            ) {
                AndroidView(
                    factory = { ctx ->
                        PlayerView(ctx).apply { useController = false }
                    },
                    update = { it.player = playing.player },
                    modifier = Modifier.fillMaxSize(),
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.65f)),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                IconButton(onClick = { manager.close() }, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Filled.Close, "Close", tint = Color.White)
                }
                IconButton(onClick = { manager.seekBy(-10_000) }, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Filled.Replay10, "Back 10 seconds", tint = Color.White)
                }
                IconButton(onClick = { manager.playPause() }, modifier = Modifier.size(36.dp)) {
                    Icon(
                        if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        if (isPlaying) "Pause" else "Play",
                        tint = Color.White,
                    )
                }
                IconButton(onClick = { manager.seekBy(10_000) }, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Filled.Forward10, "Forward 10 seconds", tint = Color.White)
                }
            }
            Text(
                text = playing.video.title,
                color = Color.White,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.65f))
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
    }
}
