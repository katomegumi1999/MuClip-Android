package com.muclip.app.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * MuClip 语义色板。
 *
 * 明暗两套色板由 [MuClipTheme] 依据系统的 `uiMode` 选择，全部 UI 通过下面的
 * `MuClip*` 属性读取，**不要**再写 `Color(0xFF...)` 字面量——否则暗色切浅色时
 * 会留下几块不跟随的硬编码色。
 */
@Immutable
data class MuClipPalette(
    val isDark: Boolean,
    /** 页面底色（Window 背景） */
    val background: Color,
    /** 次级容器：分组底、未选中的胶囊 */
    val surface: Color,
    /** 卡片底 */
    val card: Color,
    val cardBorder: Color,
    val primary: Color,
    val primaryDark: Color,
    /** 主色之上的文字/图标（FAB、主按钮） */
    val onPrimary: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val green: Color,
    val red: Color,
    /** 液态玻璃：玻璃体染色（带 alpha，透出下方内容） */
    val glassTint: Color,
    /** 玻璃上边缘高光（较亮） */
    val glassBorderTop: Color,
    /** 玻璃下边缘高光（较暗） */
    val glassBorderBottom: Color,
    /** 玻璃内部顶部的折射高光 */
    val glassHighlight: Color,
    /** 玻璃投影 */
    val glassShadow: Color,
    /** 选中胶囊的玻璃色 */
    val glassSelected: Color
)

val DarkMuClipPalette = MuClipPalette(
    isDark = true,
    background = Color(0xFF0B0F14),
    surface = Color(0xFF141A21),
    card = Color(0xFF1B222C),
    cardBorder = Color(0xFF2A3440),
    primary = Color(0xFF00E5FF),
    primaryDark = Color(0xFF00B0FF),
    onPrimary = Color(0xFF04141A),
    textPrimary = Color(0xFFF0F6FC),
    textSecondary = Color(0xFF8B98A8),
    green = Color(0xFF34D399),
    red = Color(0xFFF87171),
    // 0.72：再透就压不住从下方滚过的列表文字，再浓就丢掉玻璃感
    glassTint = Color(0xFF1C242E).copy(alpha = 0.72f),
    glassBorderTop = Color(0x47FFFFFF),
    glassBorderBottom = Color(0x0DFFFFFF),
    glassHighlight = Color(0x1FFFFFFF),
    glassShadow = Color(0x8C000000),
    glassSelected = Color(0x1FFFFFFF)
)

val LightMuClipPalette = MuClipPalette(
    isDark = false,
    background = Color(0xFFF2F5F9),
    surface = Color(0xFFFFFFFF),
    card = Color(0xFFFFFFFF),
    cardBorder = Color(0xFFE1E7EF),
    primary = Color(0xFF007E96),
    primaryDark = Color(0xFF00677B),
    onPrimary = Color(0xFFFFFFFF),
    textPrimary = Color(0xFF111820),
    textSecondary = Color(0xFF66727F),
    green = Color(0xFF0E9F6E),
    red = Color(0xFFD64545),
    // 浅色玻璃靠「白体 + 下缘淡灰轮廓 + 投影」立起来，纯靠白描边在白底上会消失
    glassTint = Color(0xF5FFFFFF),
    glassBorderTop = Color(0xFFFFFFFF),
    glassBorderBottom = Color(0x1F0A1A2A),
    glassHighlight = Color(0xB3FFFFFF),
    glassShadow = Color(0x2E0A1A2A),
    glassSelected = Color(0x14007E96)
)

val LocalMuClipPalette = staticCompositionLocalOf { DarkMuClipPalette }

// ——— 旧的常量名保留为「跟随主题的属性」————
// 335 处引用零改动即可自动跟随明暗；新代码请优先用 MuClip* 语义名。
// 注意：这些是 @Composable 属性，只能在 Compose 函数体内读取，
// 不能在 remember{} / 普通函数 / 顶层 val 里用。

val MuClipBackground: Color
    @Composable @ReadOnlyComposable
    get() = LocalMuClipPalette.current.background

val MuClipSurface: Color
    @Composable @ReadOnlyComposable
    get() = LocalMuClipPalette.current.surface

val MuClipCard: Color
    @Composable @ReadOnlyComposable
    get() = LocalMuClipPalette.current.card

val MuClipCardBorder: Color
    @Composable @ReadOnlyComposable
    get() = LocalMuClipPalette.current.cardBorder

val MuClipPrimary: Color
    @Composable @ReadOnlyComposable
    get() = LocalMuClipPalette.current.primary

val MuClipPrimaryDark: Color
    @Composable @ReadOnlyComposable
    get() = LocalMuClipPalette.current.primaryDark

val MuClipOnPrimary: Color
    @Composable @ReadOnlyComposable
    get() = LocalMuClipPalette.current.onPrimary

val MuClipTextPrimary: Color
    @Composable @ReadOnlyComposable
    get() = LocalMuClipPalette.current.textPrimary

val MuClipTextSecondary: Color
    @Composable @ReadOnlyComposable
    get() = LocalMuClipPalette.current.textSecondary

val MuClipGreen: Color
    @Composable @ReadOnlyComposable
    get() = LocalMuClipPalette.current.green

val MuClipRed: Color
    @Composable @ReadOnlyComposable
    get() = LocalMuClipPalette.current.red
