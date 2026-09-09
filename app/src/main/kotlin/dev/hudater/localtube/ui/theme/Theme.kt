package dev.hudater.localtube.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
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
    MaterialTheme(
        colorScheme = LocalTubeDarkColors,
        content = content,
    )
}
