package org.khord.android.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Minimal Material 3 theme. PoC — defaults with a slate-blue primary. */
private val LightColors = lightColorScheme(
    primary = Color(0xFF455A64),
    onPrimary = Color.White,
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF90A4AE),
    onPrimary = Color.Black,
)

@Composable
fun KhordTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
