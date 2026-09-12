package dev.hudater.localtube.ui.mini

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.ui.PlayerView
import dev.hudater.localtube.playback.PlaybackManager
import dev.hudater.localtube.playback.PlaybackState
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/** Smallest the popup can be resized to (see the bottom-left grip). */
private const val MIN_WINDOW_WIDTH_DP = 140f

/** Largest the popup can be resized to (see the bottom-left grip). */
private const val MAX_WINDOW_WIDTH_DP = 380f

/** Width when the popup first appears. */
private const val DEFAULT_WINDOW_WIDTH_DP = 180f

/**
 * The Morphe-style floating popup: while a video plays and the user is
 * NOT on the full player screen, a small draggable window anchored to a
 * corner keeps playback going. Like Morphe, the close (X) floats over
 * the video's top-right corner on a circular scrim that shows only for
 * 2s after the video was last tapped, and a single dark control row
 * below the video carries −10s / play-pause / +10s. The grip in the
 * bottom-left corner resizes the window (drag left/down to grow);
 * dragging anywhere else moves it with edge snapping. Tapping the
 * window expands back to the full player — but only while the close
 * scrim is hidden, so revealing the X never expands by accident.
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
    var windowWidthDp by remember { mutableFloatStateOf(DEFAULT_WINDOW_WIDTH_DP) }

    var isPlaying by remember { mutableStateOf(true) }
    LaunchedEffect(playing) {
        while (true) {
            isPlaying = playing.player.isPlaying
            kotlinx.coroutines.delay(500)
        }
    }

    // The close button's circular scrim is visible for 2s after the
    // video was last tapped; every tap on the video bumps touchTick,
    // which restarts that countdown (it starts visible so the X is
    // findable, and only starts ticking on the first tap).
    var scrimVisible by remember { mutableStateOf(true) }
    var touchTick by remember { mutableIntStateOf(0) }
    LaunchedEffect(touchTick) {
        if (touchTick > 0) {
            scrimVisible = true
            kotlinx.coroutines.delay(2000)
            scrimVisible = false
        }
    }
    val scrimAlpha by animateFloatAsState(
        targetValue = if (scrimVisible) 1f else 0f,
        animationSpec = tween(durationMillis = 250),
        label = "closeScrimAlpha",
    )

    val horizontalMarginPx = with(density) { 12.dp.toPx() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { containerSize = it },
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .offset { IntOffset(dragX.value.roundToInt(), dragY.roundToInt()) }
                // Clear the gesture bar: the popup must never sit under
                // the system navigation area.
                .navigationBarsPadding()
                .padding(12.dp)
                .width(windowWidthDp.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surface)
                .border(
                    1.dp,
                    Color.White.copy(alpha = 0.15f),
                    RoundedCornerShape(12.dp),
                )
                // Drag the window body to move it (the resize grip consumes
                // its own drags first, so resizing never moves the window).
                // Width is read from state on every event: the grip can
                // change it while this gesture handler stays alive.
                .pointerInput(playing) {
                    detectDragGestures(
                        onDrag = { change, amount ->
                            change.consume()
                            val windowWidthPx = with(density) { windowWidthDp.dp.toPx() }
                            val maxX = (containerSize.width - windowWidthPx - 2 * horizontalMarginPx)
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
                            val windowWidthPx = with(density) { windowWidthDp.dp.toPx() }
                            val maxX = (containerSize.width - windowWidthPx - 2 * horizontalMarginPx)
                                .coerceAtLeast(0f)
                            val target = if (abs(dragX.value) < maxX / 2f) 0f else -maxX
                            scope.launch { dragX.animateTo(target) }
                        },
                    )
                }
                // Expand only while the close scrim is hidden: with the
                // scrim up the user is reaching for the X, so a tap on
                // the window body must not throw them into the player.
                .clickable(onClick = { if (!scrimVisible) onExpand() }),
        ) {
            Column {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(16f / 9f)
                        .background(Color.Black)
                        // A tap on the video reveals the close button and
                        // restarts its 2s countdown; it never expands the
                        // player (the tap is consumed here, so the
                        // window's own clickable never sees it).
                        .pointerInput(Unit) {
                            detectTapGestures(onTap = { touchTick++ })
                        },
                ) {
                    AndroidView(
                        factory = { ctx ->
                            PlayerView(ctx).apply { useController = false }
                        },
                        update = { it.player = playing.player },
                        modifier = Modifier.fillMaxSize(),
                    )
                    // Close floats OVER the video, top-right, like
                    // Morphe: a 36dp circular scrim with a 20dp X that
                    // fades with the scrim. The click is disabled while
                    // faded out so the invisible X can't swallow taps
                    // meant for the video.
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(8.dp)
                            .alpha(scrimAlpha)
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.55f))
                            .clickable(
                                onClick = { manager.close() },
                                enabled = scrimVisible,
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = "Close",
                            tint = Color.White,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color.Black.copy(alpha = 0.65f)),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = { manager.seekBy(-10_000) }, modifier = Modifier.size(32.dp)) {
                        Icon(
                            Icons.Filled.Replay10,
                            contentDescription = "Back 10 seconds",
                            tint = Color.White,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                    IconButton(onClick = { manager.playPause() }, modifier = Modifier.size(32.dp)) {
                        Icon(
                            if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            contentDescription = if (isPlaying) "Pause" else "Play",
                            tint = Color.White,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                    IconButton(onClick = { manager.seekBy(10_000) }, modifier = Modifier.size(32.dp)) {
                        Icon(
                            Icons.Filled.Forward10,
                            contentDescription = "Forward 10 seconds",
                            tint = Color.White,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }

            // Resize grip: a 24dp corner whose diagonal drags scale the
            // window (drag left/down grows it, right/up shrinks it). The
            // no-op clickable swallows taps so a grip tap doesn't expand
            // the player.
            Canvas(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .size(24.dp)
                    .clickable(onClick = {})
                    .pointerInput(Unit) {
                        detectDragGestures(
                            onDrag = { change, amount ->
                                change.consume()
                                val maxWidthDp =
                                    (containerSize.width - 2 * horizontalMarginPx) / density.density
                                val upper = maxOf(
                                    MIN_WINDOW_WIDTH_DP,
                                    minOf(MAX_WINDOW_WIDTH_DP, maxWidthDp),
                                )
                                windowWidthDp = (windowWidthDp - amount.x + amount.y)
                                    .coerceIn(MIN_WINDOW_WIDTH_DP, upper)
                                // A wider/narrower window changes how far it
                                // may sit from the right edge — re-clamp so
                                // it never hangs off-screen after a resize.
                                val windowWidthPx = with(density) { windowWidthDp.dp.toPx() }
                                val maxX = (containerSize.width - windowWidthPx - 2 * horizontalMarginPx)
                                    .coerceAtLeast(0f)
                                scope.launch { dragX.snapTo(dragX.value.coerceIn(-maxX, 0f)) }
                            },
                        )
                    },
            ) {
                val strokeWidth = 2.dp.toPx()
                val step = 5.dp.toPx()
                val pad = 4.dp.toPx()
                for (i in 1..3) {
                    drawLine(
                        color = Color.White.copy(alpha = 0.6f),
                        start = Offset(pad + i * step, size.height - pad),
                        end = Offset(pad, size.height - pad - i * step),
                        strokeWidth = strokeWidth,
                        cap = StrokeCap.Round,
                    )
                }
            }
        }
    }
}
