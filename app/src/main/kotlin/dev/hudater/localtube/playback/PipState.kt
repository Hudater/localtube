package dev.hudater.localtube.playback

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Compose-readable mirror of the Activity's picture-in-picture state.
 * MainActivity keeps it in sync from onPictureInPictureModeChanged.
 *
 * PlayerScreen SHOULD read [inPipMode] to force the control overlay
 * hidden and drop the title Text below the video while PiP is active —
 * the video surface must fill the system PiP window. (Left as a
 * one-liner for the integrating pass; PlayerScreen is owned elsewhere.)
 */
object PipState {
    var inPipMode by mutableStateOf(false)
}
