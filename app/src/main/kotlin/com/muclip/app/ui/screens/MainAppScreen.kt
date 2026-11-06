package com.muclip.app.ui.screens

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.FolderShared
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.muclip.app.ui.components.GlassNavItem
import com.muclip.app.ui.components.LiquidGlassBar
import com.muclip.app.ui.components.LiquidGlassNavBar
import com.muclip.app.ui.components.LocalMuBottomInset
import com.muclip.app.ui.theme.*
import com.muclip.app.viewmodel.ClipboardViewModel
import com.muclip.app.viewmodel.FileTransferViewModel
import com.muclip.app.viewmodel.SyncViewModel
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

enum class MainTab(val title: String, val icon: ImageVector) {
    CLIPBOARD("剪贴板", Icons.Default.ContentPaste),
    PHRASES("常用语", Icons.AutoMirrored.Filled.Chat),
    FILES("文件传输", Icons.Default.FolderShared),
    DEVICES("配对设备", Icons.Default.Devices),
    SETTINGS("设置", Icons.Default.Settings)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainAppScreen(
    clipboardViewModel: ClipboardViewModel = viewModel(),
    syncViewModel: SyncViewModel = viewModel(),
    fileViewModel: FileTransferViewModel = viewModel()
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val hazeState = remember { HazeState() }
    val backdrop = rememberLayerBackdrop()
    val pagerState = rememberPagerState(
        initialPage = 0,
        pageCount = { MainTab.entries.size }
    )
    val selectedIndex = pagerState.currentPage
    val selectedTab = MainTab.entries[selectedIndex.coerceIn(0, MainTab.entries.lastIndex)]
    val continuousPosition by remember {
        derivedStateOf {
            pagerState.currentPage + pagerState.currentPageOffsetFraction
        }
    }

    // 监听列表上下滑动：滚动时底栏背景实时转为水滴滑动时的纯透晶莹水光与透镜折射效果
    val verticalScrollProgress = remember { Animatable(0f) }
    var scrollJob by remember { mutableStateOf<Job?>(null) }
    var pagerDragJob by remember { mutableStateOf<Job?>(null) }
    val nestedScrollConnection = remember {
        object : NestedScrollConnection {
            private fun onScrollDetected(dy: Float) {
                if (kotlin.math.abs(dy) > 0.2f) {
                    scrollJob?.cancel()
                    scrollJob = coroutineScope.launch {
                        verticalScrollProgress.snapTo(1f)
                        delay(350) // 滑动过程中保持水滴形态，手松开/惯性结束后平滑过渡回高斯模糊底栏
                        verticalScrollProgress.animateTo(
                            targetValue = 0f,
                            animationSpec = tween(durationMillis = 400, easing = FastOutSlowInEasing)
                        )
                    }
                }
            }

            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                onScrollDetected(available.y)
                return Offset.Zero
            }

            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource
            ): Offset {
                onScrollDetected(consumed.y + available.y)
                return Offset.Zero
            }
        }
    }

    // 防误触：在非首页时，系统返回手势或边缘滑动优先返回剪贴板首页，避免直接退回桌面
    BackHandler(enabled = pagerState.currentPage != 0) {
        coroutineScope.launch {
            pagerState.animateScrollToPage(0)
        }
    }

    // 底栏浮在内容之上，列表要靠这块留白才能在滚到底时完整露出来。
    val navBarInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val bottomReserve = LiquidGlassBar.Height + LiquidGlassBar.BottomMargin + navBarInset + 12.dp

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MuClipBackground)
    ) {
        Scaffold(
            containerColor = Color.Transparent,
            // 内容要铺到系统栏下面（edge-to-edge），inset 由我们自己分配。
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            topBar = {
                TopAppBar(
                    title = {
                        Row(
                            modifier = Modifier.padding(start = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "小沐剪贴板",
                                color = MuClipPrimary,
                                fontSize = 21.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = (-0.5).sp
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = selectedTab.title,
                                color = MuClipTextSecondary,
                                fontSize = 15.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    },
                    actions = {
                        // 手动读一次系统剪贴板并广播（强制同步）
                        IconButton(onClick = {
                            val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
                            val clip = clipboard?.primaryClip
                            if (clip != null && clip.itemCount > 0) {
                                val text = clip.getItemAt(0).text?.toString()
                                if (!text.isNullOrBlank()) {
                                    val trimmed = text.trim()
                                    com.muclip.app.data.SyncEventManager.lastSyncedText = trimmed
                                    clipboardViewModel.addHistory(trimmed)
                                    clipboardViewModel.broadcastToPeersAsync(trimmed) { sent ->
                                        Toast.makeText(context, if (sent > 0) "已强制同步至 $sent 台设备" else "已记录剪贴板 (当前无在线设备)", Toast.LENGTH_SHORT).show()
                                    }
                                } else {
                                    Toast.makeText(context, "当前剪贴板为空", Toast.LENGTH_SHORT).show()
                                }
                            } else {
                                Toast.makeText(context, "剪贴板为空", Toast.LENGTH_SHORT).show()
                            }
                        }) {
                            Icon(
                                Icons.Default.Sync,
                                contentDescription = "Force Re-sync Current Clipboard",
                                tint = MuClipPrimary
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Transparent,
                        scrolledContainerColor = Color.Transparent
                    )
                )
            }
        ) { innerPadding ->
            CompositionLocalProvider(LocalMuBottomInset provides bottomReserve) {
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier
                        .fillMaxSize()
                        .nestedScroll(nestedScrollConnection)
                        .layerBackdrop(backdrop)
                        .hazeSource(hazeState)
                        .padding(top = innerPadding.calculateTopPadding()),
                    beyondViewportPageCount = 1
                ) { page ->
                    when (MainTab.entries[page]) {
                        MainTab.CLIPBOARD -> HistoryScreen(clipboardViewModel)
                        MainTab.PHRASES -> PhrasesScreen(clipboardViewModel)
                        MainTab.FILES -> FilesScreen(fileViewModel, syncViewModel)
                        MainTab.DEVICES -> DevicesScreen(syncViewModel)
                        MainTab.SETTINGS -> SettingsScreen(syncViewModel, clipboardViewModel)
                    }
                }
            }
        }

        LiquidGlassNavBar(
            items = MainTab.entries.map { GlassNavItem(it.title, it.icon) },
            selectedIndex = selectedIndex,
            backdrop = backdrop,
            hazeState = hazeState,
            continuousPosition = continuousPosition,
            verticalScrollProgress = verticalScrollProgress.value,
            onDragPosition = { pos ->
                pagerDragJob?.cancel()
                pagerDragJob = coroutineScope.launch {
                    val targetPage = pos.roundToInt().coerceIn(0, MainTab.entries.size - 1)
                    val fraction = (pos - targetPage).coerceIn(-0.5f, 0.5f)
                    pagerState.scrollToPage(targetPage, fraction)
                }
            },
            onSelect = { target ->
                pagerDragJob?.cancel()
                pagerDragJob = null
                coroutineScope.launch {
                    pagerState.animateScrollToPage(target)
                }
            },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = LiquidGlassBar.BottomMargin + navBarInset)
        )
    }
}
