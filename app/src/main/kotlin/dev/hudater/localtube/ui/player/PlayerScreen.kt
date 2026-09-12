package dev.hudater.localtube.ui.player

import android.app.Activity
import android.content.pm.ActivityInfo
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateDpAsState
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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.ui.PlayerView
import dev.hudater.localtube.core.model.Video
import dev.hudater.localtube.playback.PipState
import dev.hudater.localtube.playback.PlaybackManager
import dev.hudater.localtube.playback.PlaybackState
import kotlinx.coroutines.delay

/**
 * YouTube-style player: custom Compose overlay (PlayerView's built-in
 * controller is disabled) with tap-to-toggle controls, double-tap side
 * seek, ±10s, a slim scrub bar, playback speed, and a fullscreen button
 * that rotates to landscape. The system bars stay visible in every mode
 * and the video is inset between them — status bar above, gesture bar
 * below — so the notched top strip is always a reserved black band
 * instead of being covered by the picture.
 *
 * Like the YouTube app, the controls sit on gradients instead of a flat
 * scrim: a short top gradient carries back / title / settings, a bottom
 * gradient carries the time + timeline + speed + fullscreen row at the
 * very bottom edge, and the center buttons float over the video, scaled
 * up in fullscreen. The speed picker is a compact panel anchored inside
 * the surface (see [SpeedSheet]), opened from the gear or the circular
 * speed chip. In portrait a collapsible description sits under the title.
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
    val activity = context as? Activity

    LaunchedEffect(video) { manager.open(video) }

    var isFullscreen by remember { mutableStateOf(false) }
    var controlsVisible by remember { mutableStateOf(true) }
    var positionMs by remember { mutableLongStateOf(0L) }
    var durationMs by remember { mutableLongStateOf(0L) }
    var isPlaying by remember { mutableStateOf(true) }
    var scrubPositionMs by remember { mutableLongStateOf(-1L) } // -1 = not scrubbing
    var playbackSpeed by remember { mutableStateOf(1f) }
    var showSpeedSheet by remember { mutableStateOf(false) }
    val inPip = PipState.inPipMode
    LaunchedEffect(inPip) { if (inPip) controlsVisible = false }

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
            // Leaving the screen mid-fullscreen must restore the system
            // orientation even if fullscreen state was never toggled back
            // by the user. (The system bars are never hidden anymore, so
            // there is nothing to restore for them.)
            exitFullscreen()
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

    // Auto-hide the controls after a few idle seconds while playing — but
    // never while the speed sheet is open on top of them, and never in the
    // middle of a scrub (every scrub event restarts the timer, so the
    // seekbar can't vanish under the user's finger and strand the scrub
    // state without a release to commit it).
    LaunchedEffect(controlsVisible, isPlaying, showSpeedSheet, scrubPositionMs) {
        if (controlsVisible && isPlaying && !showSpeedSheet) {
            delay(3_000)
            controlsVisible = false
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = when {
                // Fullscreen: the video sits between the notch and the
                // gesture bar. The system bars stay visible (they blend
                // into the black background), so these insets are real
                // and reliably reserve the notched strip.
                isFullscreen -> Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
                // PiP: the system already sizes the window to the video,
                // don't inset it further.
                inPip -> Modifier.fillMaxSize()
                // Portrait: the 16:9 strip starts below the status bar /
                // camera cutout. The controls are drawn inside this box,
                // so they inherit the inset automatically.
                else -> Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .aspectRatio(16f / 9f)
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
                                showSpeedSheet = true
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

                    if (showSpeedSheet) {
                        SpeedSheet(
                            currentSpeed = playbackSpeed,
                            isLandscape = isFullscreen,
                            onSpeedSelected = { speed ->
                                playbackSpeed = speed
                                manager.setPlaybackSpeed(speed)
                                showSpeedSheet = false
                            },
                            onDismiss = { showSpeedSheet = false },
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
        }

        if (!isFullscreen && !inPip) {
            Text(
                text = video.title,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(16.dp),
            )
            // Collapsible description, YouTube watch-page style — only
            // when the scan found a description sidecar for this video.
            // Suppressed in fullscreen/PiP together with the title.
            video.description?.let { description ->
                VideoDescription(
                    description = description,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                )
            }
        }
    }
}

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
    // Everything is one notch bigger in fullscreen/landscape, where the
    // video fills the screen and there is no UI around it.
    val rowIconSize = if (isFullscreen) 28.dp else 24.dp
    val seekIconSize = if (isFullscreen) 56.dp else 40.dp
    val seekButtonSize = if (isFullscreen) 72.dp else 56.dp
    val playPauseIconSize = if (isFullscreen) 64.dp else 48.dp
    val playPauseButtonSize = if (isFullscreen) 84.dp else 64.dp

    Box(modifier = Modifier.fillMaxSize()) {
        // Top gradient: back / title / settings, fading into the video.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(120.dp)
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Black.copy(alpha = 0.6f), Color.Transparent),
                    ),
                ),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    // Keep the back/title/gear row clear of the status bar /
                    // camera cutout. The video box above already applies (and
                    // consumes) this inset, so in practice this is a no-op
                    // safeguard for wherever the controls get hosted.
                    .statusBarsPadding()
                    .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack, modifier = Modifier.size(seekButtonSize)) {
                    Icon(
                        Icons.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = Color.White,
                        modifier = Modifier.size(rowIconSize),
                    )
                }
                Text(
                    text = title,
                    color = Color.White,
                    style = if (isFullscreen) {
                        MaterialTheme.typography.titleMedium
                    } else {
                        MaterialTheme.typography.titleSmall
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onOpenSpeedMenu, modifier = Modifier.size(seekButtonSize)) {
                    Icon(
                        Icons.Filled.Settings,
                        contentDescription = "Playback settings",
                        tint = Color.White,
                        modifier = Modifier.size(rowIconSize),
                    )
                }
            }
        }

        // Center controls, floating over the video like YouTube's.
        Row(
            modifier = Modifier.align(Alignment.Center),
            horizontalArrangement = Arrangement.spacedBy(if (isFullscreen) 48.dp else 32.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { onSeekBy(-10_000) }, modifier = Modifier.size(seekButtonSize)) {
                Icon(
                    Icons.Filled.Replay10,
                    contentDescription = "Back 10 seconds",
                    tint = Color.White,
                    modifier = Modifier.size(seekIconSize),
                )
            }
            IconButton(onClick = onPlayPause, modifier = Modifier.size(playPauseButtonSize)) {
                Icon(
                    if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (isPlaying) "Pause" else "Play",
                    tint = Color.White,
                    modifier = Modifier.size(playPauseIconSize),
                )
            }
            IconButton(onClick = { onSeekBy(10_000) }, modifier = Modifier.size(seekButtonSize)) {
                Icon(
                    Icons.Filled.Forward10,
                    contentDescription = "Forward 10 seconds",
                    tint = Color.White,
                    modifier = Modifier.size(seekIconSize),
                )
            }
        }

        // Bottom gradient: one bottom row — time, slim timeline, speed,
        // fullscreen — pinned to the bottom edge of the video.
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(140.dp)
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Transparent, Color.Black.copy(alpha = 0.6f)),
                    ),
                )
                // In fullscreen the video box above already reserves the
                // navigation-bar inset, so this is a no-op safeguard; in
                // portrait the video sits at the top of the screen, where
                // the navigation-bar inset must NOT push the timeline up
                // and detach it from the video's bottom edge.
                .then(if (isFullscreen) Modifier.navigationBarsPadding() else Modifier),
        ) {
            Spacer(modifier = Modifier.weight(1f))
            val scrubbing = scrubPositionMs >= 0
            val shownPositionMs = if (scrubbing) scrubPositionMs else positionMs
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "${formatTime(shownPositionMs)} / ${formatTime(durationMs)}",
                    color = Color.White,
                    style = MaterialTheme.typography.labelSmall,
                )
                SlimSeekbar(
                    positionMs = shownPositionMs,
                    durationMs = durationMs,
                    scrubbing = scrubbing,
                    onScrub = onScrub,
                    onScrubFinished = onScrubFinished,
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 8.dp),
                )
                // Circular speed selector (like YouTube landscape): the
                // current speed on a 1.5dp white ring, shown in both
                // orientations; opens the same [SpeedSheet] as the gear.
                Box(
                    modifier = Modifier
                        .padding(start = 8.dp)
                        .size(28.dp)
                        .clip(CircleShape)
                        .border(1.5.dp, Color.White, CircleShape)
                        .clickable(onClick = onOpenSpeedMenu),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = formatSpeed(playbackSpeed),
                        color = Color.White,
                        fontSize = 10.sp,
                        maxLines = 1,
                    )
                }
                IconButton(onClick = onToggleFullscreen, modifier = Modifier.size(seekButtonSize)) {
                    Icon(
                        if (isFullscreen) Icons.Filled.FullscreenExit else Icons.Filled.Fullscreen,
                        contentDescription = if (isFullscreen) "Exit fullscreen" else "Fullscreen",
                        tint = Color.White,
                        modifier = Modifier.size(rowIconSize),
                    )
                }
            }
        }
    }
}

/**
 * Slim YouTube-style timeline: a 3dp rounded track spanning the full
 * width — played portion solid white, remainder White 30%, no visible
 * thumb at rest. While the finger is down ([scrubbing]) the track
 * thickens to 5dp and a 12dp white dot marks the position. Tap seeks and
 * drag scrubs: positions are reported through [onScrub] and committed on
 * release through [onScrubFinished] (the caller owns the scrub state and
 * passes the scrub-aware [positionMs]; its idle sentinel lives there).
 */
@Composable
private fun SlimSeekbar(
    positionMs: Long,
    durationMs: Long,
    scrubbing: Boolean,
    onScrub: (Long) -> Unit,
    onScrubFinished: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Read the latest callbacks/duration inside the gesture detectors —
    // pointerInput captures them once, not per recomposition.
    val currentOnScrub by rememberUpdatedState(onScrub)
    val currentOnScrubFinished by rememberUpdatedState(onScrubFinished)
    val currentDurationMs by rememberUpdatedState(durationMs)

    val fraction = if (durationMs > 0) {
        (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
    } else {
        0f
    }
    // YouTube grows the track slightly while the user is touching it.
    val trackHeight by animateDpAsState(targetValue = if (scrubbing) 5.dp else 3.dp)

    Canvas(
        modifier = modifier
            // Fixed-height touch target; the thin track is drawn
            // vertically centered inside it.
            .height(24.dp)
            .pointerInput(Unit) {
                // Drag = scrub. Absolute local x of the tracked pointer
                // (no delta accumulation → no drift), mapped linearly to
                // the duration.
                detectDragGestures(
                    onDrag = { change, _ ->
                        change.consume()
                        val x = change.position.x.coerceIn(0f, size.width.toFloat())
                        currentOnScrub((x / size.width * currentDurationMs).toLong())
                    },
                    onDragEnd = { currentOnScrubFinished() },
                    onDragCancel = { currentOnScrubFinished() },
                )
            }
            .pointerInput(Unit) {
                // Tap = seek straight to the tapped position.
                detectTapGestures(
                    onTap = { offset ->
                        val tapFraction = (offset.x / size.width).coerceIn(0f, 1f)
                        currentOnScrub((tapFraction * currentDurationMs).toLong())
                        // A tap is a complete scrub gesture: commit now.
                        currentOnScrubFinished()
                    },
                )
            },
    ) {
        val trackHeightPx = trackHeight.toPx()
        val trackTop = (size.height - trackHeightPx) / 2f
        val corner = CornerRadius(trackHeightPx / 2f)
        // Remaining portion.
        drawRoundRect(
            color = Color.White.copy(alpha = 0.3f),
            topLeft = Offset(0f, trackTop),
            size = Size(size.width, trackHeightPx),
            cornerRadius = corner,
        )
        // Played portion.
        val playedWidth = size.width * fraction
        drawRoundRect(
            color = Color.White,
            topLeft = Offset(0f, trackTop),
            size = Size(playedWidth, trackHeightPx),
            cornerRadius = corner,
        )
        // Thumb only while the user is scrubbing.
        if (scrubbing) {
            drawCircle(
                color = Color.White,
                radius = 6.dp.toPx(),
                center = Offset(playedWidth, size.height / 2f),
            )
        }
    }
}

/**
 * Collapsible video description under the title, YouTube watch-page
 * style: collapsed shows two lines plus a "More" toggle, expanded shows
 * the full text plus "Less". Rendered only when the video actually has
 * a description sidecar — see [Video.description].
 */
@Composable
private fun VideoDescription(
    description: String,
    modifier: Modifier = Modifier,
) {
    // Collapse again if another video's description is shown here.
    var expanded by remember(description) { mutableStateOf(false) }

    Column(modifier = modifier) {
        Text(
            text = description,
            style = MaterialTheme.typography.bodySmall,
            color = Color(0xFFAAAAAA),
            maxLines = if (expanded) Int.MAX_VALUE else 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = if (expanded) "Less" else "More",
            style = MaterialTheme.typography.bodySmall,
            color = Color(0xFF3EA6FF),
            modifier = Modifier
                .clickable { expanded = !expanded }
                .padding(top = 4.dp),
        )
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
