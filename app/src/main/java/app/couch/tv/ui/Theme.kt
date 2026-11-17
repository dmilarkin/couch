package app.couch.tv.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme

val Bg = Color(0xFF0A0A0D)
val PanelBg = Color(0xFF16161C)
val CardBg = Color(0xFF1E1E26)
val Muted = Color(0xFFA3A3AE)
val Faint = Color(0xFF6E6E78)
val Accent = Color(0xFFF5C451)
val Danger = Color(0xFFFF6B6B)

val ScreenPad = 48.dp
val TopBarHeight = 56.dp

@Composable
fun CouchTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Color.White,
            onPrimary = Color.Black,
            background = Bg,
            onBackground = Color.White,
            surface = PanelBg,
            onSurface = Color.White,
            surfaceVariant = CardBg,
            onSurfaceVariant = Muted,
        ),
    ) {
        CompositionLocalProvider(LocalContentColor provides Color.White) {
            content()
        }
    }
}
