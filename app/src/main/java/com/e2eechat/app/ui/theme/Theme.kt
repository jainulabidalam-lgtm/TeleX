package com.e2eechat.app.ui.theme

import android.app.Activity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val DarkColorScheme = darkColorScheme(
    primary = AccentBlueViolet,
    onPrimary = TextOnAccent,
    primaryContainer = AccentMutedBg,
    onPrimaryContainer = SecurityLockBadge,
    secondary = AccentLightViolet,
    background = DarkBackground,
    surface = DarkSurface,
    surfaceVariant = DarkSurfaceVariant,
    onBackground = TextPrimary,
    onSurface = TextPrimary,
    onSurfaceVariant = TextSecondary,
    outline = DarkBorder
)

private val LightColorScheme = lightColorScheme(
    primary = CaramelAccent,
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = CaramelAccentLight,
    onPrimaryContainer = MilkCoffeeTextPrimary,
    secondary = CaramelAccentDark,
    background = MilkCoffeeBackground,
    surface = MilkCoffeeSurface,
    surfaceVariant = MilkCoffeeSurfaceVariant,
    onBackground = MilkCoffeeTextPrimary,
    onSurface = MilkCoffeeTextPrimary,
    onSurfaceVariant = MilkCoffeeTextSecondary,
    outline = MilkCoffeeBorder
)

@Composable
fun E2EEChatTheme(
    darkTheme: Boolean = true,
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
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}

