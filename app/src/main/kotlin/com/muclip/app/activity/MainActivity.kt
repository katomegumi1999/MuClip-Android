package com.muclip.app.activity

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import com.muclip.app.MuClipApplication
import com.muclip.app.service.ClipboardSyncService
import com.muclip.app.ui.screens.MainAppScreen
import com.muclip.app.ui.theme.MuClipTheme
import com.muclip.app.viewmodel.ClipboardViewModel
import com.muclip.app.viewmodel.SyncViewModel

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    private val clipboardViewModel: ClipboardViewModel by viewModels()
    private val syncViewModel: SyncViewModel by viewModels()

    private var hasPromptedBatteryOpt = false

    private val requestNotificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            if (isGranted) {
                startForegroundServiceIfEnabled()
            }
            requestLegacyStoragePermissionIfNeeded()
        }

    /**
     * API 26~28 的运行时存储权限。
     *
     * Android 10（Q）之前没有分区存储，接收文件时 `AndroidStorageUtils` 会退回到
     * 直接写在公共 `Download/MuClip` 目录，这需要 WRITE_EXTERNAL_STORAGE。
     * 清单里已声明（带 maxSdkVersion="28"），但运行时不给授予的话
     * 文件接收会静默失败——这也是之前「收到的文件找不到」的根因之一。
     */
    private val requestStoragePermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            if (!isGranted) {
                Toast.makeText(
                    this,
                    "未授予存储权限：Android 9 及以下接收文件可能会失败，可在系统设置中补授",
                    Toast.LENGTH_LONG
                ).show()
            }
            requestIgnoreBatteryOptimizationsIfNeeded()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        // 全面屏手势/三键导航都要让内容铺到系统栏后面，玻璃底栏才压得住边。
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        // 0. Check native library load status
        if (!com.muclip.core.MuClipNative.isLoaded) {
            Toast.makeText(this, "警告: 本地核心库未能加载，请确认当前设备支持 arm64-v8a 架构", Toast.LENGTH_LONG).show()
        }

        // 1. Request notification permissions (Android 13+) & battery optimization exemption
        checkAndRequestPermissions()

        // 2. Start persistent background sync service if enabled (safely within Activity foreground context)
        startForegroundServiceIfEnabled()

        // 3. When staying on screen with window focus, auto-write remote clipboard push directly in foreground
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                com.muclip.app.data.SyncEventManager.pendingClipboardWrite.collect { pending ->
                    if (!pending.isNullOrEmpty()) {
                        clipboardViewModel.loadData()
                        if (hasWindowFocus()) {
                            val consumed = com.muclip.app.data.SyncEventManager.consumePendingClipboardWrite()
                            if (!consumed.isNullOrEmpty()) {
                                writeToSystemClipboard(consumed)
                            }
                        }
                    }
                }
            }
        }

        // 4. Listen for unpair events from remote peers
        lifecycleScope.launch {
            com.muclip.app.data.SyncEventManager.unpairedPeerFlow.collect { displayName ->
                Toast.makeText(this@MainActivity, "设备 $displayName 已解除配对", Toast.LENGTH_SHORT).show()
            }
        }

        // 4b. 配对认证连续被拒：只提示，不擅自解配（原因可能是解配/密钥不一致/端点串台）
        lifecycleScope.launch {
            com.muclip.app.data.SyncEventManager.authRejectedFlow.collect { event ->
                Toast.makeText(
                    this@MainActivity,
                    "与 ${event.deviceId} 的认证连续失败 ${event.streak} 次，建议重新配对",
                    Toast.LENGTH_LONG
                ).show()
            }
        }

        // 5. Set Jetpack Compose View
        setContent {
            // 明暗跟随系统：主题与系统栏图标同时切换，避免浅色界面配白色状态栏图标
            val darkTheme = isSystemInDarkTheme()
            MuClipTheme(darkTheme = darkTheme) {
                LaunchedEffect(darkTheme) { applySystemBarIconMode(darkTheme) }
                MainAppScreen(
                    clipboardViewModel = clipboardViewModel,
                    syncViewModel = syncViewModel
                )
            }
        }
    }

    /** 状态栏/导航栏图标颜色：浅色界面用深色图标，暗色界面用浅色图标。 */
    private fun applySystemBarIconMode(darkTheme: Boolean) {
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = !darkTheme
            isAppearanceLightNavigationBars = !darkTheme
        }
    }

    override fun onResume() {
        super.onResume()
        syncViewModel.refreshAccessibilityStatus(this)
        // Always reload SQLite history so all items received in background are displayed immediately
        clipboardViewModel.loadData()
        syncViewModel.triggerSilentForegroundScan()
        if (hasWindowFocus()) {
            if (!checkAndApplyPendingClipboardWrite()) {
                performAutoClipboardSync()
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            clipboardViewModel.loadData()
            if (!checkAndApplyPendingClipboardWrite()) {
                performAutoClipboardSync()
            }
        }
    }

    private fun checkAndApplyPendingClipboardWrite(): Boolean {
        if (!hasWindowFocus()) return false
        val pending = com.muclip.app.data.SyncEventManager.consumePendingClipboardWrite()
        if (!pending.isNullOrEmpty()) {
            writeToSystemClipboard(pending)
            return true
        }
        return false
    }

    private fun writeToSystemClipboard(text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        com.muclip.app.data.SyncEventManager.markRemoteReceived(text)
        val clip = ClipData.newPlainText("MuClip Sync", text)
        clipboard.setPrimaryClip(clip)
        val preview = if (text.length > 25) text.take(25) + "..." else text
        Toast.makeText(this, "已写入远程剪贴板: $preview", Toast.LENGTH_SHORT).show()
        clipboardViewModel.loadData()
    }

    private fun performAutoClipboardSync() {
        lifecycleScope.launch(Dispatchers.IO) {
            val count = syncViewModel.autoCheckAndSyncClipboard(this@MainActivity)
            if (count >= 0) {
                withContext(Dispatchers.Main) {
                    val msg = if (count > 0) {
                        "已自动读取并同步剪贴板至 $count 台设备"
                    } else {
                        "已自动记录剪贴板 (当前无在线设备)"
                    }
                    Toast.makeText(this@MainActivity, msg, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun checkAndRequestPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                requestNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                return
            }
        }
        requestLegacyStoragePermissionIfNeeded()
    }

    /**
     * API 26~28 才需要的存储权限；Q+ 走 MediaStore，无需申请。
     * 只申请一次，避免用户拒绝后反复弹窗。
     */
    private fun requestLegacyStoragePermissionIfNeeded() {
        val needsStorage = Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) != PackageManager.PERMISSION_GRANTED
        if (needsStorage) {
            requestStoragePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            return
        }
        requestIgnoreBatteryOptimizationsIfNeeded()
    }

    private fun requestIgnoreBatteryOptimizationsIfNeeded() {
        if (hasPromptedBatteryOpt) return
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
            if (pm != null && !pm.isIgnoringBatteryOptimizations(packageName)) {
                hasPromptedBatteryOpt = true
                val intent = android.content.Intent(
                    android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    android.net.Uri.parse("package:$packageName")
                )
                startActivity(intent)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun startForegroundServiceIfEnabled() {
        if (MuClipApplication.preferences.foregroundServiceEnabled) {
            try {
                ClipboardSyncService.start(this)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}
