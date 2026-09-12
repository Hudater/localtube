package dev.hudater.localtube.ui.player

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * Speeds offered in the picker: 0.25x .. 3.0x in 0.25 steps — the same
 * values the old AlertDialog listed.
 */
internal val PLAYBACK_SPEEDS: List<Float> = (1..12).map { it * 0.25f }

/** "0.25x" / "1.5x" / "2.0x" — sheet row and portrait chip label. */
internal fun formatSpeed(speed: Float): String = "${formatSpeedValue(speed)}x"

/** "0.25" / "1.5" / "2.0" — compact numeric label for the landscape chip. */
internal fun formatSpeedValue(speed: Float): String {
    val trimmed = speed.toString().trimEnd('0', '.')
    return if ('.' in trimmed) trimmed else "$trimmed.0"
}

/**
 * YouTube-style playback-speed picker anchored INSIDE the player surface:
 * a dark rounded panel — bottom-end above the bottom controls in portrait,
 * vertically centered at the right edge in landscape — listing every speed
 * with the current one highlighted (accent text + check icon).
 *
 * This is an overlay, not a modal dialog: tapping anywhere outside the
 * panel dismisses it, and once it's gone the player's tap gestures work
 * untouched. The row list scrolls when the surface is too short to show
 * all of them at once (always the case in portrait, where the video area
 * is a 16:9 strip at the top of the screen).
 */
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
            // Any tap that doesn't land on the panel itself dismisses it;
            // row clicks are consumed by their own clickables first.
            .pointerInput(Unit) { detectTapGestures(onTap = { onDismiss() }) },
    ) {
        Surface(
            modifier = Modifier
                .align(if (isLandscape) Alignment.CenterEnd else Alignment.BottomEnd)
                .padding(
                    top = if (isLandscape) 12.dp else 0.dp,
                    end = 12.dp,
                    // Portrait: clear the bottom gradient's slider + time row.
                    bottom = if (isLandscape) 12.dp else 104.dp,
                )
                .width(280.dp)
                // Never grow past the surface this sheet lives in.
                .heightIn(max = maxHeight * 0.9f),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.12f)),
            shadowElevation = 8.dp,
        ) {
            Column {
                Text(
                    text = formatSpeed(currentSpeed),
                    color = Color.White,
                    style = MaterialTheme.typography.titleSmall,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp, bottom = 2.dp),
                )
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                ) {
                    PLAYBACK_SPEEDS.forEach { speed ->
                        val selected = speed == currentSpeed
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 36.dp)
                                .clickable { onSpeedSelected(speed) }
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = formatSpeed(speed),
                                color = if (selected) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    Color.White
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f),
                            )
                            if (selected) {
                                Icon(
                                    imageVector = Icons.Filled.Check,
                                    contentDescription = "Current speed",
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
