package com.muclip.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider

private fun muClipColorScheme(p: MuClipPalette) = if (p.isDark) {
    darkColorScheme(
        primary = p.primary,
        onPrimary = p.onPrimary,
        primaryContainer = p.surface,
        onPrimaryContainer = p.textPrimary,
        secondary = p.primaryDark,
        onSecondary = p.onPrimary,
        secondaryContainer = p.card,
        onSecondaryContainer = p.textPrimary,
        background = p.background,
        onBackground = p.textPrimary,
        surface = p.card,
        onSurface = p.textPrimary,
        surfaceVariant = p.surface,
        onSurfaceVariant = p.textSecondary,
        surfaceContainerLowest = p.background,
        surfaceContainerLow = p.surface,
        surfaceContainer = p.surface,
        surfaceContainerHigh = p.card,
        surfaceContainerHighest = p.card,
        outline = p.cardBorder,
        outlineVariant = p.cardBorder,
        error = p.red,
        onError = p.background,
        errorContainer = p.red.copy(alpha = 0.18f),
        onErrorContainer = p.red,
        inverseSurface = p.textPrimary,
        inverseOnSurface = p.background,
        inversePrimary = p.primaryDark,
        surfaceTint = p.primary
    )
} else {
    lightColorScheme(
        primary = p.primary,
        onPrimary = p.onPrimary,
        primaryContainer = p.surface,
        onPrimaryContainer = p.textPrimary,
        secondary = p.primaryDark,
        onSecondary = p.onPrimary,
        secondaryContainer = p.background,
        onSecondaryContainer = p.textPrimary,
        background = p.background,
        onBackground = p.textPrimary,
        surface = p.card,
        onSurface = p.textPrimary,
        surfaceVariant = p.surface,
        onSurfaceVariant = p.textSecondary,
        surfaceContainerLowest = p.surface,
        surfaceContainerLow = p.surface,
        surfaceContainer = p.surface,
        surfaceContainerHigh = p.surface,
        surfaceContainerHighest = p.background,
        outline = p.cardBorder,
        outlineVariant = p.cardBorder,
        error = p.red,
        onError = p.onPrimary,
        errorContainer = p.red.copy(alpha = 0.12f),
        onErrorContainer = p.red,
        inverseSurface = p.textPrimary,
        inverseOnSurface = p.surface,
        inversePrimary = p.primary,
        surfaceTint = p.primary
    )
}

/**
 * 明暗跟随系统 `uiMode`；不提供手动开关——用户侧只在系统中切换，避免出现
 * 「系统是暗色、App 是浅色」的割裂感。
 *
 * 同时把语义色板塞进 [LocalMuClipPalette]，所有 `MuClip*` 属性据此解析。
 */
@Composable
fun MuClipTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val palette = if (darkTheme) DarkMuClipPalette else LightMuClipPalette
    CompositionLocalProvider(LocalMuClipPalette provides palette) {
        MaterialTheme(
            colorScheme = muClipColorScheme(palette),
            content = content
        )
    }
}

/** 供非 Compose 层（如 Activity 设置系统栏）判断当前是否暗色。 */
@Composable
fun isMuClipDark(): Boolean = LocalMuClipPalette.current.isDark
