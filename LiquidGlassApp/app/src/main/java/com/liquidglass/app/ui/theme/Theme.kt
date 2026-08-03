package com.liquidglass.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color

@Composable
fun LiquidGlassTheme(content: @Composable () -> Unit) {
    // v2.9.2: 监听系统深浅色模式，驱动"系统自动"主题切换
    val systemDark = isSystemInDarkTheme()
    ThemeManager.onSystemDarkModeChanged(systemDark)

    val theme = ThemeManager.currentThemeState.value
    val colorScheme = if (theme.isLight) {
        lightColorScheme(
            primary = theme.accentPrimary,
            secondary = theme.fluidCyan,
            tertiary = theme.fluidPurple,
            background = theme.bgDark,
            surface = theme.glassMedium,
            onPrimary = Color.White,
            onSecondary = Color.White,
            onTertiary = Color.White,
            onBackground = theme.textPrimary,
            onSurface = theme.textPrimary
        )
    } else {
        darkColorScheme(
            primary = theme.accentPrimary,
            secondary = theme.fluidCyan,
            tertiary = theme.fluidPurple,
            background = theme.bgDark,
            surface = theme.glassClear,
            onPrimary = Color.White,
            onSecondary = Color.White,
            onTertiary = Color.White,
            onBackground = theme.textPrimary,
            onSurface = theme.textPrimary
        )
    }

    CompositionLocalProvider(LocalAppTheme provides theme) {
        MaterialTheme(
            colorScheme = colorScheme,
            content = content
        )
    }
}
