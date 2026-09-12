package dev.hudater.localtube.ui.player

import android.app.Activity
import android.content.pm.ActivityInfo
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.ui.PlayerView
import dev.hudater.localtube.core.model.Video
import dev.hudater.localtube.playback.PlaybackManager
import dev.hudater.localtube.playback.PlaybackState
import kotlinx.coroutines.delay

/**
 * YouTube-style player: custom Compose overlay (PlayerView's built-in
 * controller is disabled) with tap-to-toggle controls, double-tap side
 * seek, ±10s, scrub bar, playback speed, and a fullscreen button that
 * rotates to landscape and hides the system bars.
 *
 * Backing playback lives in the app-scoped [PlaybackManager], so leaving
 * this screen (back press) keeps the video running in the floating mini
 * player — that's what makes picture-in-picture-style browsing possible.
 */
@Composable
fun PlayerScreen(
    manager: PlaybackManager,
    video: Video,
    onBack: () -> Unit,
) {
    val playback by manager.state.collectAsState()
    val context = LocalContext.current
    val view = LocalView.current
    val activity = context as? Activity

    LaunchedEffect(video) { manager.open(video) }

    var isFullscreen by remember { mutableStateOf(false) }
    var controlsVisible by remember { mutableStateOf(true) }
    var positionMs by remember { mutableLongStateOf(0L) }
    var durationMs by remember { mutableLongStateOf(0L) }
    var isPlaying by remember { mutableStateOf(true) }
    var scrubPositionMs by remember { mutableLongStateOf(-1L) } // -1 = not scrubbing
    var playbackSpeed by remember { mutableStateOf(1f) }
    var showSpeedMenu by remember { mutableStateOf(false) }

    fun enterFullscreen() {
        isFullscreen = true
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
    }

    fun exitFullscreen() {
        isFullscreen = false
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }

    // Keep the screen on while this screen is showing.
    DisposableEffect(Unit) {
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            // Leaving the screen mid-fullscreen must restore system bars
            // and orientation even if fullscreen state was never toggled
            // back by the user.
            exitFullscreen()
        }
    }

    // Immersive mode while fullscreen.
    LaunchedEffect(isFullscreen) {
        val window = activity?.window ?: return@LaunchedEffect
        val controller = WindowCompat.getInsetsController(window, view)
        if (isFullscreen) {
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    BackHandler(enabled = isFullscreen) { exitFullscreen() }

    // Poll playback state for the overlay UI (position, duration, playing).
    val player = (playback as? PlaybackState.Playing)?.player
    LaunchedEffect(player) {
        while (player != null) {
            positionMs = player.currentPosition.coerceAtLeast(0)
            durationMs = player.duration.coerceAtLeast(0)
            isPlaying = player.isPlaying
            delay(500)
        }
    }

    // Auto-hide the controls after a few idle seconds while playing.
    LaunchedEffect(controlsVisible, isPlaying) {
        if (controlsVisible && isPlaying) {
            delay(3_000)
            controlsVisible = false
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = if (isFullscreen) {
                Modifier.fillMaxSize()
            } else {
                Modifier.fillMaxWidth().aspectRatio(16f / 9f)
            },
            contentAlignment = Alignment.Center,
        ) {
            when (val state = playback) {
                is PlaybackState.Idle, is PlaybackState.Loading -> CircularProgressIndicator()
                is PlaybackState.Failed -> Text(state.message, color = MaterialTheme.colorScheme.error)
                is PlaybackState.Playing -> {
                    AndroidView(
                        factory = { ctx ->
                            PlayerView(ctx).apply {
                                useController = false
                                setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                            }
                        },
                        update = { it.player = state.player },
                        modifier = Modifier.fillMaxSize(),
                    )

                    // Tap surface: single tap toggles controls, double tap
                    // seeks ±10s like the YouTube app.
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .pointerInput(state.player) {
                                detectTapGestures(
                                    onTap = { controlsVisible = !controlsVisible },
                                    onDoubleTap = { offset ->
                                        val seekBy = if (offset.x < size.width / 2f) -10_000L else 10_000L
                                        state.player.seekTo(
                                            (state.player.currentPosition + seekBy).coerceAtLeast(0),
                                        )
                                    },
                                )
                            },
                    )

                    if (controlsVisible) {
                        PlayerControls(
                            title = video.title,
                            isPlaying = isPlaying,
                            isFullscreen = isFullscreen,
                            positionMs = positionMs,
                            durationMs = durationMs,
                            scrubPositionMs = scrubPositionMs,
                            playbackSpeed = playbackSpeed,
                            onOpenSpeedMenu = {
                                controlsVisible = true
                                showSpeedMenu = true
                            },
                            onScrub = { scrubPositionMs = it },
                            onScrubFinished = {
                                if (scrubPositionMs >= 0) state.player.seekTo(scrubPositionMs)
                                scrubPositionMs = -1L
                            },
                            onPlayPause = manager::playPause,
                            onSeekBy = manager::seekBy,
                            onBack = onBack,
                            onToggleFullscreen = {
                                if (isFullscreen) exitFullscreen() else enterFullscreen()
                            },
                        )
                    }
                }
            }
        }

        if (!isFullscreen) {
            Text(
                text = video.title,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(16.dp),
            )
        }
    }

    // Playback speed picker: 0.25x to 3.0x in 0.25 steps, like YouTube's
    // settings sheet.
    if (showSpeedMenu) {
        AlertDialog(
            onDismissRequest = { showSpeedMenu = false },
            title = { Text("Playback speed") },
            text = {
                LazyColumn {
                    itemsIndexed(PLAYBACK_SPEEDS) { _, speed ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    playbackSpeed = speed
                                    manager.setPlaybackSpeed(speed)
                                    showSpeedMenu = false
                                }
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = playbackSpeed == speed,
                                onClick = null,
                            )
                            Text(
                                text = formatSpeed(speed),
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.padding(start = 8.dp),
                            )
                        }
                    }
                }
            },
            confirmButton = {},
        )
    }
}

/** 0.25x .. 3.0x in 0.25 increments. */
private val PLAYBACK_SPEEDS: List<Float> = (1..12).map { it * 0.25f }

private fun formatSpeed(speed: Float): String =
    "${speed.toString().trimEnd('0', '.')}x"

@Composable
private fun PlayerControls(
    title: String,
    isPlaying: Boolean,
    isFullscreen: Boolean,
    positionMs: Long,
    durationMs: Long,
    scrubPositionMs: Long,
    playbackSpeed: Float,
    onOpenSpeedMenu: () -> Unit,
    onScrub: (Long) -> Unit,
    onScrubFinished: () -> Unit,
    onPlayPause: () -> Unit,
    onSeekBy: (Long) -> Unit,
    onBack: () -> Unit,
    onToggleFullscreen: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.4f))
            // Keep controls clear of the status bar / camera cutout and
            // gesture bar — the gear/back row was unreachable under call
            // bubbles and the notch otherwise.
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(vertical = 4.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
            }
            Text(
                text = title,
                color = Color.White,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onOpenSpeedMenu) {
                Icon(Icons.Filled.Settings, contentDescription = "Playback settings", tint = Color.White)
            }
        }

        Row(
            modifier = Modifier.align(Alignment.Center),
            horizontalArrangement = Arrangement.spacedBy(32.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { onSeekBy(-10_000) }) {
                Icon(Icons.Filled.Replay10, contentDescription = "Back 10 seconds", tint = Color.White)
            }
            IconButton(onClick = onPlayPause) {
                Icon(
                    if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (isPlaying) "Pause" else "Play",
                    tint = Color.White,
                )
            }
            IconButton(onClick = { onSeekBy(10_000) }) {
                Icon(Icons.Filled.Forward10, contentDescription = "Forward 10 seconds", tint = Color.White)
            }
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
        ) {
            val sliderValue = if (scrubPositionMs >= 0) scrubPositionMs else positionMs
            Slider(
                value = if (durationMs > 0) sliderValue.toFloat() / durationMs else 0f,
                onValueChange = { onScrub((it * durationMs).toLong()) },
                onValueChangeFinished = onScrubFinished,
                colors = SliderDefaults.colors(
                    thumbColor = Color.White,
                    activeTrackColor = Color.White,
                    inactiveTrackColor = Color.White.copy(alpha = 0.3f),
                ),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "${formatTime(sliderValue)} / ${formatTime(durationMs)}",
                    color = Color.White,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onToggleFullscreen) {
                    Icon(
                        if (isFullscreen) Icons.Filled.FullscreenExit else Icons.Filled.Fullscreen,
                        contentDescription = if (isFullscreen) "Exit fullscreen" else "Fullscreen",
                        tint = Color.White,
                    )
                }
            }
        }
    }
}

private fun formatTime(totalMs: Long): String {
    val totalSeconds = totalMs / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%d:%02d".format(minutes, seconds)
    }
}
