package dev.hudater.localtube.ui.theme

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

private val LocalTubeDarkColors = darkColorScheme(
    background = Color.Black,
    surface = Color(0xFF0F0F0F),
    primary = Color(0xFF3EA6FF), // YouTube-style accent blue
    onBackground = Color.White,
    onSurface = Color.White,
)

@Composable
fun LocalTubeTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = LocalTubeDarkColors) {
        // Texts without an explicit color read LocalContentColor, which
        // defaults to BLACK when no Surface ancestor provides it — on this
        // all-black scheme every title rendered black-on-black (the
        // "tappable but invisible" feed). Root-provide white + a Surface.
        CompositionLocalProvider(LocalContentColor provides Color.White) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.background,
            ) {
                content()
            }
        }
    }
}
