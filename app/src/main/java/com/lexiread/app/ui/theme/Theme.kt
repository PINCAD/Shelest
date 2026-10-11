package com.lexiread.app.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

enum class AppThemeMode(val label: String) {
    SYSTEM("Как в системе"), LIGHT("Светлая"), DARK("Тёмная")
}

enum class AccentChoice(val label: String, val light: Long, val dark: Long) {
    DEFAULT("Фирменный", 0xFFA8493D, 0xFFCB7C6E),
    BLUE("Синий", 0xFF3169A8, 0xFF8AB8EA),
    GREEN("Зелёный", 0xFF3A7652, 0xFF8BCAA0),
    VIOLET("Фиолетовый", 0xFF7756A6, 0xFFBA9DE2),
    AMBER("Янтарный", 0xFF9C621E, 0xFFE1B36C),
    TEAL("Бирюзовый", 0xFF257B80, 0xFF86C8C9)
}

private val DarkColorScheme = darkColorScheme(
    primary              = AccentDark,
    onPrimary            = OnAccentDark,
    primaryContainer     = DarkSurface2,
    onPrimaryContainer   = AccentDark,
    secondary            = AccentDark,
    onSecondary          = OnAccentDark,
    secondaryContainer   = DarkSurface2,
    onSecondaryContainer = DarkTextPrimary,
    background           = DarkSurface0,
    onBackground         = DarkTextPrimary,
    surface              = DarkSurface1,
    onSurface            = DarkTextPrimary,
    surfaceVariant       = DarkSurface2,
    onSurfaceVariant     = DarkTextSecondary,
    outline              = DarkBorder,
    outlineVariant       = DarkBorder,
    error                = ErrorRed,
    onError              = OnError,
)

private val LightColorScheme = lightColorScheme(
    primary              = AccentLight,
    onPrimary            = OnAccentLight,
    primaryContainer     = LightSurface2,
    onPrimaryContainer   = AccentLight,
    secondary            = AccentLight,
    onSecondary          = OnAccentLight,
    secondaryContainer   = LightSurface2,
    onSecondaryContainer = LightTextPrimary,
    background           = LightSurface0,
    onBackground         = LightTextPrimary,
    surface              = LightSurface1,
    onSurface            = LightTextPrimary,
    surfaceVariant       = LightSurface2,
    onSurfaceVariant     = LightTextSecondary,
    outline              = LightBorder,
    outlineVariant       = LightBorder,
    error                = ErrorRed,
    onError              = OnError,
)

@Composable
fun LexiReadTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    accentChoice: AccentChoice = AccentChoice.DEFAULT,
    content: @Composable () -> Unit
) {
    val base = if (darkTheme) DarkColorScheme else LightColorScheme
    val accent = Color(if (darkTheme) accentChoice.dark else accentChoice.light)
    val colorScheme = base.copy(
        primary = accent,
        onPrimary = if (darkTheme) OnAccentDark else OnAccentLight,
        onPrimaryContainer = accent,
        secondary = accent,
        onSecondary = if (darkTheme) OnAccentDark else OnAccentLight
    )
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor    = colorScheme.background.toArgb()
            window.navigationBarColor = colorScheme.background.toArgb()
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars     = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }
    MaterialTheme(
        colorScheme = colorScheme,
        typography  = LexiReadTypography,
        content     = content
    )
}
