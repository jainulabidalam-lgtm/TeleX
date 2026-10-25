package com.e2eechat.app.ui.theme

import android.app.Activity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val LightColorScheme = lightColorScheme(
    primary = TeleXPrimary,
    onPrimary = TeleXTextOnPrimary,
    primaryContainer = TeleXPrimaryContainer,
    onPrimaryContainer = TeleXPrimaryDeep,
    secondary = TeleXPrimaryDark,
    onSecondary = TeleXTextOnPrimary,
    secondaryContainer = TeleXPrimaryContainer,
    onSecondaryContainer = TeleXPrimaryDeep,
    background = TeleXBackground,
    onBackground = TeleXTextPrimary,
    surface = TeleXSurface,
    onSurface = TeleXTextPrimary,
    surfaceVariant = TeleXSurfaceInset,
    onSurfaceVariant = TeleXTextSecondary,
    outline = TeleXOutline,
    outlineVariant = TeleXOutlineVariant,
    error = TeleXError,
    onError = TeleXOnError,
    errorContainer = TeleXErrorContainer,
    onErrorContainer = TeleXOnErrorContainer
)

private val DarkColorScheme = darkColorScheme(
    primary = TeleXPrimary,
    onPrimary = TeleXTextOnPrimary,
    primaryContainer = TeleXPrimaryDark,
    onPrimaryContainer = TeleXTextOnPrimarySubtle,
    secondary = TeleXPrimaryLight,
    onSecondary = TeleXTextOnPrimary,
    background = TeleXBackground,
    onBackground = TeleXTextPrimary,
    surface = TeleXSurface,
    onSurface = TeleXTextPrimary,
    surfaceVariant = TeleXSurfaceInset,
    onSurfaceVariant = TeleXTextSecondary,
    outline = TeleXOutline
)

@Composable
fun E2EEChatTheme(
    darkTheme: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = colorScheme.background.toArgb()
            window.navigationBarColor = colorScheme.background.toArgb()
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = true
                isAppearanceLightNavigationBars = true
            }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
