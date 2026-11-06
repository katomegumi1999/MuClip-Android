package com.muclip.app.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow
import com.kyant.shapes.Capsule
import com.muclip.app.ui.theme.LocalMuClipPalette
import dev.chrisbanes.haze.HazeDefaults
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
import kotlin.math.roundToInt

/**
 * 悬浮液态玻璃底栏几何规范（Stadium Capsule Dock）。
 * 水滴直径放大至 62.dp，高度 74.dp，无多余弹性拉伸变形，形体纯净饱满。
 */
object LiquidGlassBar {
    val Height = 74.dp
    val DropletHeight = 62.dp
    val SideMargin = 14.dp
    val BottomMargin = 20.dp
    val Radius = 37.dp
}

/** 列表底部需要额外让出的高度（已包含系统导航条 inset）。 */
val LocalMuBottomInset = compositionLocalOf { 0.dp }

data class GlassNavItem(val label: String, val icon: ImageVector)

@Composable
fun LiquidGlassNavBar(
    items: List<GlassNavItem>,
    selectedIndex: Int,
    modifier: Modifier = Modifier,
    backdrop: Backdrop? = null,
    hazeState: HazeState? = null,
    continuousPosition: Float = selectedIndex.toFloat(),
    verticalScrollProgress: Float = 0f,
    onDragPosition: ((Float) -> Unit)? = null,
    onSelect: (Int) -> Unit
) {
    if (items.isEmpty()) return
    val palette = LocalMuClipPalette.current
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current
    val barShape = remember { RoundedCornerShape(percent = 50) }

    // 1. 底栏静止底座：纯粹宁静的高斯模糊磨砂底栏（Gaussian Blur），无任何多余透镜折射扭曲
    val baseDockModifier = if (backdrop != null) {
        Modifier.drawBackdrop(
            backdrop = backdrop,
            shape = { Capsule() },
            effects = {
                vibrancy()
                blur(28f.dp.toPx())
            },
            highlight = {
                Highlight.Default.copy(
                    alpha = if (palette.isDark) 0.14f else 0.24f
                )
            },
            shadow = {
                Shadow.Default.copy(
                    radius = 16f.dp,
                    alpha = if (palette.isDark) 0.25f else 0.12f
                )
            },
            innerShadow = {
                InnerShadow.Default.copy(
                    radius = 2f.dp,
                    alpha = 0.08f
                )
            },
            onDrawSurface = {
                drawRect(if (palette.isDark) Color(0x7514202E) else Color(0xB8FFFFFF))
            }
        )
    } else if (hazeState != null) {
        Modifier
            .shadow(
                elevation = 14.dp,
                shape = barShape,
                clip = false,
                ambientColor = palette.glassShadow,
                spotColor = palette.glassShadow
            )
            .clip(barShape)
            .hazeEffect(
                state = hazeState,
                style = HazeDefaults.style(
                    backgroundColor = Color.Transparent,
                    tint = HazeTint(
                        if (palette.isDark) Color(0x7A14202E) else Color(0xC0FFFFFF)
                    ),
                    blurRadius = 28.dp,
                    noiseFactor = 0.03f
                )
            )
            .border(
                width = 1.dp,
                color = if (palette.isDark) Color(0x22FFFFFF) else Color(0x18000000),
                shape = barShape
            )
    } else {
        Modifier
            .shadow(
                elevation = 14.dp,
                shape = barShape,
                clip = false,
                ambientColor = palette.glassShadow,
                spotColor = palette.glassShadow
            )
            .clip(barShape)
            .background(
                if (palette.isDark) Color(0x85162230) else Color(0xD8FFFFFF)
            )
            .border(
                width = 1.dp,
                color = if (palette.isDark) Color(0x22FFFFFF) else Color(0x18000000),
                shape = barShape
            )
    }

    // 2. 整个底栏水滴液态折射层：在上下滑动页面列表时显现，呈现与水滴滑块完全一致的晶莹透镜折射与色散特效
    val dockDropletModifier = if (backdrop != null) {
        Modifier
            .graphicsLayer {
                alpha = verticalScrollProgress.coerceIn(0f, 1f)
            }
            .drawBackdrop(
                backdrop = backdrop,
                shape = { Capsule() },
                effects = {
                    vibrancy()
                    lens(
                        refractionHeight = 16f.dp.toPx(),
                        refractionAmount = 26f.dp.toPx(),
                        depthEffect = true,
                        chromaticAberration = true
                    )
                },
                highlight = {
                    Highlight.Default.copy(
                        alpha = if (palette.isDark) 0.55f else 0.75f
                    )
                },
                shadow = {
                    Shadow.Default.copy(
                        radius = 24f.dp,
                        alpha = if (palette.isDark) 0.40f else 0.20f
                    )
                },
                innerShadow = {
                    InnerShadow.Default.copy(
                        radius = 5f.dp,
                        alpha = 0.24f
                    )
                },
                onDrawSurface = {
                    // 整个底栏呈现水滴滑动时的纯透晶莹微反光，绝无杂色
                    drawRect(
                        if (palette.isDark) Color.White.copy(alpha = 0.16f)
                        else Color.White.copy(alpha = 0.50f)
                    )
                }
            )
    } else if (hazeState != null) {
        Modifier
            .graphicsLayer {
                alpha = verticalScrollProgress.coerceIn(0f, 1f)
            }
            .shadow(
                elevation = 24.dp,
                shape = barShape,
                clip = false,
                ambientColor = palette.glassShadow,
                spotColor = palette.glassShadow
            )
            .clip(barShape)
            .hazeEffect(
                state = hazeState,
                style = HazeDefaults.style(
                    backgroundColor = Color.Transparent,
                    tint = HazeTint(
                        if (palette.isDark) Color.White.copy(alpha = 0.22f)
                        else Color.White.copy(alpha = 0.60f)
                    ),
                    blurRadius = 16.dp,
                    noiseFactor = 0.03f
                )
            )
            .border(
                width = 1.dp,
                color = if (palette.isDark) Color.White.copy(alpha = 0.55f) else Color.White.copy(alpha = 0.85f),
                shape = barShape
            )
    } else {
        Modifier
            .graphicsLayer {
                alpha = verticalScrollProgress.coerceIn(0f, 1f)
            }
            .shadow(
                elevation = 24.dp,
                shape = barShape,
                clip = false,
                ambientColor = palette.glassShadow,
                spotColor = palette.glassShadow
            )
            .clip(barShape)
            .background(
                if (palette.isDark) Color.White.copy(alpha = 0.22f)
                else Color.White.copy(alpha = 0.65f)
            )
            .border(
                width = 1.dp,
                color = if (palette.isDark) Color.White.copy(alpha = 0.55f) else Color.White.copy(alpha = 0.85f),
                shape = barShape
            )
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = LiquidGlassBar.SideMargin)
            .systemGestureExclusion() // 排除系统手势区域，彻底防止直接滑动底栏误退到桌面
            .then(baseDockModifier)
    ) {
        // 上下滑动页面时：全底栏水滴液态折射层平滑浮现并折射背部内容
        if (verticalScrollProgress > 0.001f) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .then(dockDropletModifier)
            )
        }
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .height(LiquidGlassBar.Height)
        ) {
            val tabWidthPx = constraints.maxWidth.toFloat() / items.size
            val tabWidthDp = with(density) { tabWidthPx.toDp() }

            // 2. 底栏直接点按与横向滑动控制（Scrubber & Drag）
            var isDraggingBar by remember { mutableStateOf(false) }
            var dragPosition by remember { mutableFloatStateOf(selectedIndex.toFloat()) }

            // 实时有效位置：底栏拖动时优先跟随手指，否则跟随页面滑动进度
            val effectivePosition = if (isDraggingBar) dragPosition else continuousPosition
            val clampedPos = effectivePosition.coerceIn(0f, (items.size - 1).toFloat())

            // 3. 水滴横向滑动胶囊：仅在横向拖动底栏或翻页切换过程中显现，静止时平滑淡出至完全透明（alpha = 0f）
            // 静止时当前选中的 button 背景与底栏高斯模糊完全融为一体，绝无绿色色块或多余玻璃泡泡遮挡
            val offsetFromTarget = kotlin.math.abs(clampedPos - clampedPos.roundToInt())
            val isSlidingHorizontal = isDraggingBar || offsetFromTarget > 0.02f
            val dropletAlpha by animateFloatAsState(
                targetValue = if (isSlidingHorizontal) 1f else 0f,
                animationSpec = tween(durationMillis = 200),
                label = "dropletAlpha"
            )

            if (dropletAlpha > 0.001f) {
                val dropletModifier = if (backdrop != null) {
                    Modifier
                        .width(tabWidthDp)
                        .height(LiquidGlassBar.DropletHeight)
                        .graphicsLayer {
                            translationX = clampedPos * tabWidthPx
                            alpha = dropletAlpha
                        }
                        .padding(horizontal = 2.5.dp)
                        .drawBackdrop(
                            backdrop = backdrop,
                            shape = { Capsule() },
                            effects = {
                                vibrancy()
                                lens(
                                    refractionHeight = 12f.dp.toPx(),
                                    refractionAmount = 18f.dp.toPx(),
                                    depthEffect = true,
                                    chromaticAberration = true
                                )
                            },
                            highlight = {
                                Highlight.Default.copy(
                                    alpha = if (palette.isDark) 0.35f else 0.50f
                                )
                            },
                            shadow = {
                                Shadow.Default.copy(
                                    radius = 12f.dp,
                                    alpha = if (palette.isDark) 0.30f else 0.12f
                                )
                            },
                            innerShadow = {
                                InnerShadow.Default.copy(
                                    radius = 4f.dp,
                                    alpha = if (palette.isDark) 0.18f else 0.10f
                                )
                            },
                            onDrawSurface = {
                                // 保持纯透晶莹的水滴微反光，绝无任何绿色或杂色色块
                                drawRect(
                                    if (palette.isDark) Color.White.copy(alpha = 0.12f)
                                    else Color.White.copy(alpha = 0.45f)
                                )
                            }
                        )
                } else {
                    Modifier
                        .width(tabWidthDp)
                        .height(LiquidGlassBar.DropletHeight)
                        .graphicsLayer {
                            translationX = clampedPos * tabWidthPx
                            alpha = dropletAlpha
                        }
                        .padding(horizontal = 2.5.dp)
                        .shadow(
                            elevation = 6.dp,
                            shape = barShape,
                            clip = false,
                            ambientColor = palette.glassShadow,
                            spotColor = palette.glassShadow
                        )
                        .clip(barShape)
                        .background(
                            if (palette.isDark) Color.White.copy(alpha = 0.12f)
                            else Color.White.copy(alpha = 0.55f)
                        )
                        .border(
                            width = 1.dp,
                            color = if (palette.isDark) Color.White.copy(alpha = 0.25f)
                            else Color.White.copy(alpha = 0.65f),
                            shape = barShape
                        )
                }

                // 水滴胶囊层（仅在横向滑动切换中显示）
                Box(
                    modifier = dropletModifier.align(Alignment.CenterStart)
                )
            }

            // 4. 按钮列表与手势捕获：点按即刻切换，横向滑动时 1:1 跟随手指，松手平滑吸附
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(items.size, tabWidthPx) {
                        val touchSlop = viewConfiguration.touchSlop
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            val startX = down.position.x
                            var dragging = false
                            var lastHapticIndex = (startX / tabWidthPx).toInt().coerceIn(0, items.size - 1)

                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull() ?: break
                                if (!change.pressed) {
                                    change.consume()
                                    if (dragging) {
                                        val finalTarget = dragPosition.roundToInt().coerceIn(0, items.size - 1)
                                        isDraggingBar = false
                                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                        onSelect(finalTarget)
                                    } else {
                                        val tapIndex = (startX / tabWidthPx).toInt().coerceIn(0, items.size - 1)
                                        isDraggingBar = false
                                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                        onSelect(tapIndex)
                                    }
                                    break
                                }

                                val currentX = change.position.x
                                val delta = kotlin.math.abs(currentX - startX)
                                if (!dragging && delta > touchSlop) {
                                    dragging = true
                                    isDraggingBar = true
                                    down.consume()
                                }

                                if (dragging) {
                                    change.consume()
                                    val pos = (currentX / tabWidthPx).coerceIn(0f, (items.size - 1).toFloat())
                                    dragPosition = pos
                                    onDragPosition?.invoke(pos)

                                    val intIndex = pos.roundToInt().coerceIn(0, items.size - 1)
                                    if (intIndex != lastHapticIndex) {
                                        lastHapticIndex = intIndex
                                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    }
                                }
                            }
                        }
                    }
            ) {
                Row(modifier = Modifier.fillMaxSize()) {
                    items.forEachIndexed { index, item ->
                        val distance = kotlin.math.abs(clampedPos - index)
                        val activeRatio = (1f - distance).coerceIn(0f, 1f)

                        val targetScale = 1.0f + 0.08f * activeRatio
                        // 选中与未选中按钮通过图标与文字平滑过渡主题色
                        val iconTint = lerp(
                            start = palette.textSecondary,
                            stop = palette.primary,
                            fraction = activeRatio
                        )
                        val labelTint = lerp(
                            start = palette.textSecondary.copy(alpha = 0.70f),
                            stop = palette.primary,
                            fraction = activeRatio
                        )

                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight(),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center,
                                modifier = Modifier.graphicsLayer {
                                    scaleX = targetScale
                                    scaleY = targetScale
                                }
                            ) {
                                Icon(
                                    imageVector = item.icon,
                                    contentDescription = item.label,
                                    tint = iconTint,
                                    modifier = Modifier.size(24.dp)
                                )
                                Spacer(Modifier.height(3.dp))
                                Text(
                                    text = item.label,
                                    color = labelTint,
                                    fontSize = 11.5.sp,
                                    fontWeight = if (activeRatio >= 0.5f) FontWeight.SemiBold else FontWeight.Normal,
                                    maxLines = 1,
                                    letterSpacing = (-0.2).sp
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
