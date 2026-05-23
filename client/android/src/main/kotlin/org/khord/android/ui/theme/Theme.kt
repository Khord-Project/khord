package org.khord.android.ui.theme

import android.content.Context
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.khord.android.AppContainer

/**
 * Three pickable themes (Teal / Forest / Minimal) with light + dark variants
 * each, plus an extended palette for chat-bubble tints that don't have a
 * natural Material3 ColorScheme slot.
 *
 * The user's chosen theme is persisted in [PREFS_NAME] / [PREFS_KEY] (plain
 * SharedPreferences — not in the encrypted DB; theme choice isn't sensitive)
 * and surfaced as a [kotlinx.coroutines.flow.StateFlow] on AppContainer so
 * picking a new theme in Settings recomposes every screen immediately,
 * without any restart.
 */

enum class KhordThemeChoice(val displayName: String) {
    TEAL("Teal"),
    FOREST("Forest"),
    MINIMAL("Minimal"),
    MATRIX_PHOSPHOR("Matrix Phosphor"),
    MATRIX_TERMINAL("Matrix Terminal"),
}

/**
 * Single representative color for each theme — used by the Settings
 * picker as a small color swatch next to the radio button. Mirrors the
 * deep "primary" used by each theme's light palette.
 */
val KhordThemeChoice.swatchColor: Color
    get() = when (this) {
        KhordThemeChoice.TEAL -> Color(0xFF1A535C)
        KhordThemeChoice.FOREST -> Color(0xFF2D4739)
        KhordThemeChoice.MINIMAL -> Color(0xFF1C1C1E)
        // Both Matrix variants share the same accent green; the
        // received-bubble tint is the actual visual differentiator
        // (phosphor cyan-green vs terminal grey-green). Show a
        // smaller secondary swatch in the picker if we ever care.
        KhordThemeChoice.MATRIX_PHOSPHOR -> Color(0xFF00FF41)
        KhordThemeChoice.MATRIX_TERMINAL -> Color(0xFF00FF41)
    }

/**
 * Khord-specific colors not in the Material3 ColorScheme: the chat bubble
 * fills + their content colors. Provided via CompositionLocal so screens
 * read them with [LocalKhordChatColors.current].
 */
data class KhordChatColors(
    val sentBubble: Color,
    val sentText: Color,
    val receivedBubble: Color,
    val receivedText: Color,
)

val LocalKhordChatColors = compositionLocalOf<KhordChatColors> {
    // Default fallback so any preview-mode composable that bypasses
    // KhordTheme still renders something sensible. Real usage always
    // goes through KhordTheme which provides the real palette.
    KhordChatColors(
        sentBubble = Color(0xFF1A535C),
        sentText = Color.White,
        receivedBubble = Color(0xFFE0E0E0),
        receivedText = Color(0xFF1A1A1A),
    )
}

/** Returns Material ColorScheme + chat-color extension for one theme + mode. */
internal fun palette(choice: KhordThemeChoice, dark: Boolean): Pair<ColorScheme, KhordChatColors> =
    when (choice) {
        KhordThemeChoice.TEAL -> if (dark) tealDark() else tealLight()
        KhordThemeChoice.FOREST -> if (dark) forestDark() else forestLight()
        KhordThemeChoice.MINIMAL -> if (dark) minimalDark() else minimalLight()
        // Both Matrix variants are intentionally dark-only — a
        // "light Matrix" makes no design sense. Return the dark
        // palette regardless of the device's day/night setting.
        KhordThemeChoice.MATRIX_PHOSPHOR -> matrixPhosphor()
        KhordThemeChoice.MATRIX_TERMINAL -> matrixTerminal()
    }

// ── Teal ─────────────────────────────────────────────────────────────────────
// Primary: deep teal #1A535C. Accent: warm amber #F4A261.
// Sent bubble: amber tint. Received: light teal / dark teal.

private fun tealLight(): Pair<ColorScheme, KhordChatColors> {
    val primary = Color(0xFF1A535C)
    val secondary = Color(0xFFF4A261)
    val bg = Color(0xFFFAFAF8)
    return lightColorScheme(
        primary = primary,
        onPrimary = Color.White,
        secondary = secondary,
        onSecondary = Color(0xFF1A1A1A),
        background = bg,
        onBackground = Color(0xFF1A1A1A),
        surface = bg,
        onSurface = Color(0xFF1A1A1A),
        surfaceVariant = Color(0xFFE6F1F2),
        onSurfaceVariant = Color(0xFF3F4948),
    ) to KhordChatColors(
        sentBubble = secondary,
        sentText = Color(0xFF1A1A1A),
        receivedBubble = Color(0xFFD9ECEC),
        receivedText = Color(0xFF1A1A1A),
    )
}

private fun tealDark(): Pair<ColorScheme, KhordChatColors> {
    val primary = Color(0xFF7FCDD3)
    val secondary = Color(0xFFF4A261)
    val bg = Color(0xFF1A1A1A)
    return darkColorScheme(
        primary = primary,
        onPrimary = Color(0xFF003740),
        secondary = secondary,
        onSecondary = Color(0xFF1A1A1A),
        background = bg,
        onBackground = Color(0xFFE6E1E5),
        surface = bg,
        onSurface = Color(0xFFE6E1E5),
        surfaceVariant = Color(0xFF2A3F42),
        onSurfaceVariant = Color(0xFFC4D4D6),
    ) to KhordChatColors(
        sentBubble = secondary,
        sentText = Color(0xFF1A1A1A),
        receivedBubble = Color(0xFF1A535C),
        receivedText = Color(0xFFE6E1E5),
    )
}

// ── Forest ───────────────────────────────────────────────────────────────────
// Primary: deep forest #2D4739. Accent: copper #B87333.
// Sent: copper tint. Received: light green / dark forest.

private fun forestLight(): Pair<ColorScheme, KhordChatColors> {
    val primary = Color(0xFF2D4739)
    val secondary = Color(0xFFB87333)
    val bg = Color(0xFFFAF7F2)
    return lightColorScheme(
        primary = primary,
        onPrimary = Color.White,
        secondary = secondary,
        onSecondary = Color.White,
        background = bg,
        onBackground = Color(0xFF1A1A1A),
        surface = bg,
        onSurface = Color(0xFF1A1A1A),
        surfaceVariant = Color(0xFFE0EAE2),
        onSurfaceVariant = Color(0xFF404B43),
    ) to KhordChatColors(
        sentBubble = secondary,
        sentText = Color.White,
        receivedBubble = Color(0xFFD4E5D7),
        receivedText = Color(0xFF1A1A1A),
    )
}

private fun forestDark(): Pair<ColorScheme, KhordChatColors> {
    val primary = Color(0xFF8FB099)
    val secondary = Color(0xFFB87333)
    val bg = Color(0xFF1C1C1A)
    return darkColorScheme(
        primary = primary,
        onPrimary = Color(0xFF14241C)
        ,
        secondary = secondary,
        onSecondary = Color.White,
        background = bg,
        onBackground = Color(0xFFE6E1E5),
        surface = bg,
        onSurface = Color(0xFFE6E1E5),
        surfaceVariant = Color(0xFF2D3B33),
        onSurfaceVariant = Color(0xFFC2D1C7),
    ) to KhordChatColors(
        sentBubble = secondary,
        sentText = Color.White,
        receivedBubble = Color(0xFF2D4739),
        receivedText = Color(0xFFE6E1E5),
    )
}

// ── Minimal ──────────────────────────────────────────────────────────────────
// Primary: near-black #1C1C1E. Accent: warm orange #E07A3A.
// Sent: orange tint. Received: light grey / dark grey.

private fun minimalLight(): Pair<ColorScheme, KhordChatColors> {
    val primary = Color(0xFF1C1C1E)
    val secondary = Color(0xFFE07A3A)
    val bg = Color(0xFFFFFFFF)
    return lightColorScheme(
        primary = primary,
        onPrimary = Color.White,
        secondary = secondary,
        onSecondary = Color.White,
        background = bg,
        onBackground = Color(0xFF1C1C1E),
        surface = bg,
        onSurface = Color(0xFF1C1C1E),
        surfaceVariant = Color(0xFFF0F0F0),
        onSurfaceVariant = Color(0xFF424246),
    ) to KhordChatColors(
        sentBubble = secondary,
        sentText = Color.White,
        receivedBubble = Color(0xFFF0F0F0),
        receivedText = Color(0xFF1C1C1E),
    )
}

private fun minimalDark(): Pair<ColorScheme, KhordChatColors> {
    val primary = Color(0xFFE6E1E5)
    val secondary = Color(0xFFE07A3A)
    val bg = Color(0xFF121212)
    return darkColorScheme(
        primary = primary,
        onPrimary = Color(0xFF1C1C1E),
        secondary = secondary,
        onSecondary = Color.White,
        background = bg,
        onBackground = Color(0xFFE6E1E5),
        surface = bg,
        onSurface = Color(0xFFE6E1E5),
        surfaceVariant = Color(0xFF2C2C2E),
        onSurfaceVariant = Color(0xFFC4C4C8),
    ) to KhordChatColors(
        sentBubble = secondary,
        sentText = Color.White,
        receivedBubble = Color(0xFF2C2C2E),
        receivedText = Color(0xFFE6E1E5),
    )
}

// ── Matrix Phosphor ──────────────────────────────────────────────────────────
// Pure phosphor green-on-black. Both sent and received text glow in
// the matrix-green spectrum (sent: brighter green, received: cyan-
// leaning green). Dark-only.

private fun matrixPhosphor(): Pair<ColorScheme, KhordChatColors> {
    val accent = Color(0xFF00FF41)       // matrix green
    val muted = Color(0xFF008F72)         // cyan-green muted
    val bg = Color(0xFF000000)
    val surface = Color(0xFF0a1a1a)       // received bubble bg
    val sentBg = Color(0xFF0d2b0d)        // sent bubble bg
    val receivedText = Color(0xFF00CCA3)  // cyan-leaning green
    return darkColorScheme(
        primary = accent,
        onPrimary = bg,
        secondary = accent,
        onSecondary = bg,
        background = bg,
        onBackground = accent,
        surface = surface,
        onSurface = accent,
        surfaceVariant = surface,
        onSurfaceVariant = muted,
    ) to KhordChatColors(
        sentBubble = sentBg,
        sentText = accent,
        receivedBubble = surface,
        receivedText = receivedText,
    )
}

// ── Matrix Terminal ──────────────────────────────────────────────────────────
// Green sent text + neutral grey-green received text. Closer to a
// classic linux terminal feel where your own input is highlighted
// and other output is dimmer. Dark-only.

private fun matrixTerminal(): Pair<ColorScheme, KhordChatColors> {
    val accent = Color(0xFF00FF41)       // matrix green (sent + accent)
    val muted = Color(0xFF708070)         // grey-green muted
    val bg = Color(0xFF000000)
    val surface = Color(0xFF111111)       // received bubble bg
    val sentBg = Color(0xFF0d2b0d)        // sent bubble bg
    val receivedText = Color(0xFFA0B0A0)  // light grey-green
    return darkColorScheme(
        primary = accent,
        onPrimary = bg,
        secondary = accent,
        onSecondary = bg,
        background = bg,
        onBackground = accent,
        surface = surface,
        onSurface = accent,
        surfaceVariant = surface,
        onSurfaceVariant = muted,
    ) to KhordChatColors(
        sentBubble = sentBg,
        sentText = accent,
        receivedBubble = surface,
        receivedText = receivedText,
    )
}

@Composable
fun KhordTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val choice by AppContainer.themeChoice.collectAsStateWithLifecycle()
    val (colorScheme, chatColors) = remember(choice, darkTheme) { palette(choice, darkTheme) }
    CompositionLocalProvider(LocalKhordChatColors provides chatColors) {
        MaterialTheme(colorScheme = colorScheme, content = content)
    }
}

// ── Theme persistence ────────────────────────────────────────────────────────

private const val PREFS_NAME = "khord_theme"
private const val PREFS_KEY = "theme_name"

/** Read the stored theme choice. Returns [KhordThemeChoice.TEAL] on first launch. */
fun loadThemeChoice(context: Context): KhordThemeChoice {
    val name = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .getString(PREFS_KEY, null)
    // Alpha.13 shipped a single MATRIX theme; alpha.14+ splits it
    // into MATRIX_PHOSPHOR and MATRIX_TERMINAL. Auto-promote any
    // existing alpha.13 selection to the closer variant (phosphor)
    // so upgraders don't get silently reset to TEAL.
    if (name == "MATRIX") return KhordThemeChoice.MATRIX_PHOSPHOR
    return KhordThemeChoice.entries.firstOrNull { it.name == name }
        ?: KhordThemeChoice.TEAL
}

/**
 * Persist a theme choice. Sync commit() (not apply) — small string write,
 * negligible cost, and we want the value visible on next launch even if the
 * process is killed (e.g., user toggled theme then immediately panic'd).
 */
fun saveThemeChoice(context: Context, choice: KhordThemeChoice) {
    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .edit()
        .putString(PREFS_KEY, choice.name)
        .commit()
}
