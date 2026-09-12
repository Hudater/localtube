package dev.hudater.localtube.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.coerceAtMost
import androidx.compose.ui.unit.dp
import java.util.Locale

/**
 * Speeds offered in the picker: 0.25x .. 3.0x in 0.25 steps — the same
 * values the old AlertDialog listed, now laid out as the pill grid of the
 * redesigned panel (0.25x/0.5x/0.75x first, then 1.0x ("Normal") up to
 * 3.0x).
 */
internal val PLAYBACK_SPEEDS: List<Float> = (1..12).map { it * 0.25f }

/** Granularity of everything here: the slider, the −/+ buttons, the grid. */
private const val SPEED_STEP = 0.25f
private const val MIN_SPEED = 0.25f
private const val MAX_SPEED = 3f

/** The pill that carries the little gray "Normal" caption underneath. */
private const val NORMAL_SPEED = 1f

/** Discrete slider positions across [MIN_SPEED]..[MAX_SPEED] (12 total). */
private val SLIDER_STEPS = ((MAX_SPEED - MIN_SPEED) / SPEED_STEP).toInt() - 1

/** "0.25x" / "1.5x" / "2.0x" — sheet pill and portrait chip label. */
internal fun formatSpeed(speed: Float): String = "${formatSpeedValue(speed)}x"

/** "0.25" / "1.5" / "2.0" — compact numeric label for the landscape chip. */
internal fun formatSpeedValue(speed: Float): String {
    val trimmed = speed.toString().trimEnd('0', '.')
    return if ('.' in trimmed) trimmed else "$trimmed.0"
}

/** "2.00x" — the panel header, always two decimals like YouTube's. */
private fun formatSpeedHeader(speed: Float): String =
    "${String.format(Locale.US, "%.2f", speed)}x"

/**
 * YouTube-style playback-speed panel, shared by both orientations: a dark
 * rounded-24dp surface with the current speed ("2.00x") as a big centered
 * header, a [− slider +] row stepping by 0.25, and a FlowRow grid of pill
 * buttons for every speed in [PLAYBACK_SPEEDS] — the current one
 * highlighted, the "1.0x" pill carrying a small gray "Normal" caption.
 *
 * Landscape ([isLandscape]): the panel floats centered over the player
 * surface, min(520dp, 80% of the width), over a dim scrim. Portrait: the
 * same panel docks full-width at the bottom of the screen as a bottom
 * sheet with rounded top corners, over a full-screen scrim. Every change
 * calls [onSpeedSelected] immediately (live preview like YouTube) and the
 * panel stays open — only a tap on the scrim calls [onDismiss].
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SpeedSheet(
    currentSpeed: Float,
    isLandscape: Boolean,
    onSpeedSelected: (Float) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            // Dim scrim behind the panel in both orientations; any tap
            // that isn't swallowed by the panel dismisses the sheet.
            .background(Color.Black.copy(alpha = 0.6f))
            .pointerInput(Unit) { detectTapGestures(onTap = { onDismiss() }) },
        contentAlignment = if (isLandscape) Alignment.Center else Alignment.BottomCenter,
    ) {
        Surface(
            modifier = Modifier
                // Landscape: floating panel, min(520dp, 80% of the width).
                // Portrait: full-width bottom sheet.
                .then(
                    if (isLandscape) {
                        Modifier.width((maxWidth * 0.8f).coerceAtMost(520.dp))
                    } else {
                        Modifier.fillMaxWidth()
                    },
                )
                .heightIn(max = maxHeight * 0.9f)
                // Taps on the panel itself are swallowed, so only the
                // scrim can dismiss the sheet.
                .pointerInput(Unit) { detectTapGestures(onTap = { }) },
            shape = if (isLandscape) {
                RoundedCornerShape(24.dp)
            } else {
                RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
            },
            color = Color(0xFF1F1F1F),
            shadowElevation = 8.dp,
        ) {
            Column(
                modifier = Modifier
                    // Keep the grid above the gesture bar in portrait; in
                    // landscape the bars are hidden (immersive fullscreen).
                    .then(if (isLandscape) Modifier else Modifier.navigationBarsPadding())
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = formatSpeedHeader(currentSpeed),
                    color = Color.White,
                    style = MaterialTheme.typography.titleLarge,
                )

                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SpeedStepButton(
                        icon = Icons.Filled.Remove,
                        contentDescription = "Slower",
                        onClick = {
                            onSpeedSelected((currentSpeed - SPEED_STEP).coerceIn(MIN_SPEED, MAX_SPEED))
                        },
                    )
                    Slider(
                        value = currentSpeed,
                        // steps snaps the reported values to the 0.25 grid.
                        onValueChange = onSpeedSelected,
                        valueRange = MIN_SPEED..MAX_SPEED,
                        steps = SLIDER_STEPS,
                        colors = SliderDefaults.colors(
                            thumbColor = Color.White,
                            activeTrackColor = Color.White,
                            inactiveTrackColor = Color.White.copy(alpha = 0.3f),
                            activeTickColor = Color.Transparent,
                            inactiveTickColor = Color.Transparent,
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 10.dp),
                    )
                    SpeedStepButton(
                        icon = Icons.Filled.Add,
                        contentDescription = "Faster",
                        onClick = {
                            onSpeedSelected((currentSpeed + SPEED_STEP).coerceIn(MIN_SPEED, MAX_SPEED))
                        },
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    PLAYBACK_SPEEDS.forEach { speed ->
                        SpeedPill(
                            speed = speed,
                            selected = speed == currentSpeed,
                            onClick = { onSpeedSelected(speed) },
                        )
                    }
                }
            }
        }
    }
}

/** 36dp circular −/+ stepper of the slider row. */
@Composable
private fun SpeedStepButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.1f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = Color.White,
            modifier = Modifier.size(20.dp),
        )
    }
}

/**
 * One rounded-full speed pill of the grid — White 10% background, the
 * selected one White 25%; the 1.0x pill additionally gets the small gray
 * "Normal" caption underneath, like YouTube's.
 */
@Composable
private fun SpeedPill(
    speed: Float,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(
                    if (selected) Color.White.copy(alpha = 0.25f) else Color.White.copy(alpha = 0.1f),
                )
                .clickable(onClick = onClick)
                .padding(horizontal = 20.dp, vertical = 10.dp),
        ) {
            Text(
                text = formatSpeed(speed),
                color = Color.White,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        if (speed == NORMAL_SPEED) {
            Text(
                text = "Normal",
                color = Color(0xFFAAAAAA),
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}
