package com.muclip.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.media.MediaScannerConnection
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import com.muclip.app.MuClipApplication
import com.muclip.app.R
import com.muclip.app.activity.ClipboardActionActivity
import com.muclip.app.activity.MainActivity
import com.muclip.app.data.AndroidStorageUtils
import com.muclip.app.data.SyncCallbackActions
import com.muclip.app.data.SyncEventManager
import com.muclip.core.IncomingPairRequest
import com.muclip.core.MuClipSyncCallback
import com.muclip.core.NativeXChaCha20Poly1305Aead
import com.muclip.core.StreamingFileTransfer
import java.io.FileInputStream
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

/**
 * Robust Foreground Service maintaining LAN mDNS discovery, P2P sync,
 * and automatic clipboard change reading & broadcasting.
 */
class ClipboardSyncService : Service() {

    companion object {
        private const val TAG = "MuClipSyncService"

        /**
         * 服务控制指令。通知渠道与通知 ID 见 [ClipboardNotifier]——
         * 那些属于通知层，跟服务的生命周期无关。
         */
        const val ACTION_RESTORE_NOTIFICATION = "com.muclip.app.ACTION_RESTORE_NOTIFICATION"
        const val ACTION_EXIT_APP = "com.muclip.app.ACTION_EXIT_APP"

        @Volatile
        private var activeInstance: ClipboardSyncService? = null

        fun rebindNativeCallbacksIfRunning() {
            activeInstance?.registerNativeSyncCallbacks()
        }

        fun start(context: Context) {
            try {
                val intent = Intent(context, ClipboardSyncService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        fun stop(context: Context) {
            try {
                activeInstance?.isStopping = true
                val intent = Intent(context, ClipboardSyncService::class.java)
                context.stopService(intent)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    /**
     * 通知构建器。渠道、通知 ID、各种提醒的排版全在 [ClipboardNotifier] 里，
     * 本类只负责在正确时机调用它。
     */
    private val notifier by lazy { ClipboardNotifier(this) }

    /** socket 直传接收器，见 [IncomingFileReceiver]。 */
    private val incomingFileReceiver by lazy {
        IncomingFileReceiver(this, serviceScope) { path -> notifier.showFileReceived(path) }
    }

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)
    @Volatile
    private var isStopping = false
    private var multicastLock: WifiManager.MulticastLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var wakeLock: android.os.PowerManager.WakeLock? = null
    private var clipboardManager: ClipboardManager? = null

    // Track last seen clipboard content to avoid redundant reads & echoes
    private var lastLocalClipboardText: String? = null
    private var lastRemoteSyncedText: String? = null
    private var lastSyncStatusText: String = "正在监听剪贴板与局域网节点..."

    private val clipChangedListener = ClipboardManager.OnPrimaryClipChangedListener {
        onClipboardChanged()
    }

    private fun acquireKeepAliveLocks() {
        try {
            val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            if (multicastLock == null) {
                multicastLock = wifi?.createMulticastLock("MuClipServiceMulticastLock")?.apply {
                    setReferenceCounted(false)
                }
            }
            if (multicastLock?.isHeld == false) {
                multicastLock?.acquire()
            }

            if (wifiLock == null) {
                // 原来用 WIFI_MODE_FULL_LOW_LATENCY：它会向 Wi-Fi 固件申请低延迟通道，
                // 射频长期维持高功耗状态，对剪贴板同步这种「小包、低频」的流量完全是浪费。
                // FULL_HIGH_PERF 同样能阻止 Wi-Fi 进入 PSM 休眠，但不需要额外 QoS 通道。
                @Suppress("DEPRECATION")
                val lockMode = WifiManager.WIFI_MODE_FULL_HIGH_PERF
                wifiLock = wifi?.createWifiLock(lockMode, "MuClip:SyncServiceWifiLock")?.apply {
                    setReferenceCounted(false)
                }
            }
            if (wifiLock?.isHeld == false) {
                wifiLock?.acquire()
            }

            val pm = applicationContext.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
            if (wakeLock == null) {
                wakeLock = pm?.newWakeLock(
                    android.os.PowerManager.PARTIAL_WAKE_LOCK,
                    "MuClip:SyncServiceWakeLock"
                )?.apply {
                    setReferenceCounted(false)
                }
            }
            if (wakeLock?.isHeld == false) {
                wakeLock?.acquire()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun ensureForegroundNotification() {
        if (isStopping) return
        try {
            val notification = notifier.buildPersistent(lastSyncStatusText)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                try {
                    startForeground(
                        ClipboardNotifier.NOTIFICATION_ID_SERVICE,
                        notification,
                        android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or
                            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                    )
                } catch (_: Exception) {
                    startForeground(
                        ClipboardNotifier.NOTIFICATION_ID_SERVICE,
                        notification,
                        android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                    )
                }
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    ClipboardNotifier.NOTIFICATION_ID_SERVICE,
                    notification,
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                )
            } else {
                startForeground(ClipboardNotifier.NOTIFICATION_ID_SERVICE, notification)
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            manager?.notify(ClipboardNotifier.NOTIFICATION_ID_SERVICE, notification)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun performExitApp() {
        isStopping = true
        serviceJob.cancel()
        try {
            MuClipApplication.sync?.close()
        } catch (_: Exception) {}
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            manager?.cancelAll()
        } catch (_: Exception) {}
        try {
            val am = getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
            am?.appTasks?.forEach { task ->
                try {
                    task.finishAndRemoveTask()
                } catch (_: Exception) {}
            }
        } catch (_: Exception) {}
        stopSelf()
        kotlin.system.exitProcess(0)
    }

    override fun onCreate() {
        super.onCreate()
        isStopping = false
        activeInstance = this

        // 1. Acquire WakeLock, WifiLock & MulticastLock to prevent background CPU freeze and Wi-Fi PSM packet drops
        acquireKeepAliveLocks()

        // 2. Setup Notification Channels & Persistent Foreground Notification
        notifier.ensureChannels()
        ensureForegroundNotification()

        // 3. Register Clipboard Listener for Automatic Clipboard Sync
        try {
            clipboardManager = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            clipboardManager?.addPrimaryClipChangedListener(clipChangedListener)
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // 4. Configure File Save Directory & Register Native Callbacks
        try {
            val publicDownloadDir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                "MuClip"
            )
            val saveDir = if (publicDownloadDir.exists() || publicDownloadDir.mkdirs()) {
                publicDownloadDir
            } else {
                File(cacheDir, "received_staging").apply { mkdirs() }
            }
            MuClipApplication.sync?.setFileSaveDir(saveDir.absolutePath)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        registerNativeSyncCallbacks()

        // 5. Periodic status refresh & background check loop
        startBackgroundMonitorLoop()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_EXIT_APP) {
            performExitApp()
            return START_NOT_STICKY
        }
        isStopping = false
        activeInstance = this
        ensureForegroundNotification()
        acquireKeepAliveLocks()
        registerNativeSyncCallbacks()
        if (intent?.action != ACTION_RESTORE_NOTIFICATION) {
            MuClipApplication.sync?.triggerScan()
        }
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        if (!isStopping && MuClipApplication.preferences.foregroundServiceEnabled) {
            ensureForegroundNotification()
        }
    }

    /**
     * Android 15+ 对有时间额度限制的前台服务类型（dataSync / mediaProcessing 等）在额度
     * 耗尽时会回调此处；不实现、不停止会被系统按 ANR 强杀。
     *
     * 本服务已改用 connectedDevice | specialUse（均无时间限制），正常情况下不会走到这里。
     * 保留实现是兜底：万一系统仍判定超时，宁可自己优雅退出，也不要变成崩溃。
     */
    @androidx.annotation.RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
    override fun onTimeout(startId: Int) {
        handleForegroundTimeout()
    }

    /** API 36+ 的重载，额外告知触发的 FGS 类型。 */
    @androidx.annotation.RequiresApi(Build.VERSION_CODES.BAKLAVA)
    override fun onTimeout(startId: Int, fgsType: Int) {
        android.util.Log.w(TAG, "前台服务超时，类型=$fgsType")
        handleForegroundTimeout()
    }

    private fun handleForegroundTimeout() {
        android.util.Log.w(TAG, "前台服务触达系统时间上限，主动停止以避免被强杀")
        isStopping = true
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
        } catch (_: Exception) {}
        stopSelf()
    }

    private fun updateServiceNotification() {
        if (isStopping) return
        try {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.notify(
                ClipboardNotifier.NOTIFICATION_ID_SERVICE,
                notifier.buildPersistent(lastSyncStatusText)
            )
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Triggered automatically whenever system clipboard changes.
     */
    private fun onClipboardChanged() {
        if (!MuClipApplication.preferences.autoReadClipboard) {
            return
        }

        try {
            val clip = clipboardManager?.primaryClip
            if (clip != null && clip.itemCount > 0) {
                val text = clip.getItemAt(0).text?.toString()
                if (!text.isNullOrBlank()) {
                    processAndBroadcastText(text)
                    return
                }
            }
        } catch (e: Exception) {
            // In Android 10+ background, primaryClip can return null or throw SecurityException
        }

        // Fallback: If in background and restricted, invoke transparent trampoline to read directly
        try {
            val trampolineIntent = Intent(this, ClipboardActionActivity::class.java).apply {
                action = ClipboardActionActivity.ACTION_READ_AND_SYNC
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            startActivity(trampolineIntent)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun processAndBroadcastText(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return

        // Drop loopback echo if this is what we just received from remote
        if (trimmed == lastRemoteSyncedText) {
            return
        }

        // Avoid re-processing the exact same local clipboard text
        if (trimmed == lastLocalClipboardText) {
            return
        }
        lastLocalClipboardText = trimmed

        serviceScope.launch(Dispatchers.IO) {
            // 1. Save to local core database and notify UI immediately
            MuClipApplication.coreOrNull()?.addHistory(trimmed)
            SyncEventManager.emitHistoryChanged()

            // 2. Broadcast via P2P LAN engine in parallel
            val sentCount = MuClipApplication.sync?.broadcastClipboard(trimmed, "text") ?: 0

            val preview = if (trimmed.length > 20) trimmed.take(20) + "..." else trimmed
            lastSyncStatusText = if (sentCount > 0) {
                "已自动同步至 $sentCount 台设备: $preview"
            } else {
                "已记录剪贴板: $preview"
            }

            serviceScope.launch(Dispatchers.Main) {
                updateServiceNotification()
            }
        }
    }

    /**
     * 注册「带通知」的完整回调。
     *
     * 与 [MuClipApplication] 里那份兜底适配器的区别只在于：本适配器额外刷新前台
     * 通知、更新状态栏文案、接管 socket 直写 MediaStore。**业务逻辑一律走
     * [SyncCallbackActions]**，不要在适配器里复制一份——两处逻辑分叉时编译器不会报错，
     * 只会表现为「有前台服务」和「没前台服务」时行为不一致。
     */
    private fun registerNativeSyncCallbacks() {
        MuClipApplication.sync?.registerCallback(object : MuClipSyncCallback {
            override fun onClipboardReceived(
                deviceId: String,
                deviceName: String,
                content: String,
                contentType: String
            ) {
                // 心跳包必须先挡掉，否则会污染下面两个「去重标记」。
                if (contentType == SyncCallbackActions.CONTENT_TYPE_PING) return

                val trimmed = content.trim()
                lastRemoteSyncedText = trimmed
                lastLocalClipboardText = trimmed

                SyncCallbackActions.onClipboardReceived(deviceId, deviceName, content, contentType)

                val preview = trimmed.replace("\n", " ").take(28)
                lastSyncStatusText = "收到来自 $deviceName: $preview"
                serviceScope.launch(Dispatchers.Main) {
                    updateServiceNotification()
                }

                // 后台收到内容时只在 DB 落库 + 通过事件总线登记待写剪贴板；
                // 只有用户显式开了「激进后台写剪贴板」才拉透明 Activity 去写。
                if (MuClipApplication.preferences.autoCopyRemote && MuClipApplication.preferences.aggressiveBackgroundClipboard) {
                    serviceScope.launch(Dispatchers.Main) {
                        if (!MuClipAccessibilityService.launchWriteActivityWithBalExemption(content, deviceName)) {
                            try {
                                val writeIntent = Intent(this@ClipboardSyncService, ClipboardActionActivity::class.java).apply {
                                    action = ClipboardActionActivity.ACTION_WRITE_CLIPBOARD
                                    putExtra(ClipboardActionActivity.EXTRA_CLIP_CONTENT, content)
                                    putExtra(ClipboardActionActivity.EXTRA_SOURCE_DEVICE, deviceName)
                                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION
                                }
                                startActivity(writeIntent)
                            } catch (e: Exception) {
                                e.printStackTrace()
                            }
                        }
                    }
                }

                // 横幅提醒：点进去会打开 MainActivity，由它把内容写进剪贴板
                notifier.showIncomingClipboardAlert(deviceName, content)
            }

            override fun onPairRequestReceived(
                requestId: String,
                fromDeviceId: String,
                fromDeviceName: String,
                clientIp: String
            ) {
                val request = SyncCallbackActions
                    .onPairRequestReceived(requestId, fromDeviceId, fromDeviceName, clientIp)
                notifier.showPairRequest(request)
            }

            override fun onPairSuccess(
                fromDeviceId: String,
                fromDeviceName: String,
                clientIp: String,
                pin: String
            ) {
                SyncCallbackActions.onPairSuccess(fromDeviceId, fromDeviceName, clientIp, pin)
                notifier.showPairSuccess(fromDeviceName)
            }

            override fun onPeerUnpaired(deviceId: String) {
                SyncCallbackActions.onPeerUnpaired(deviceId)
            }

            override fun onPeerAuthRejected(deviceId: String, streak: Int) {
                SyncCallbackActions.onPeerAuthRejected(deviceId, streak)
            }

            override fun onFileOffer(
                transferId: String,
                fromDeviceId: String,
                fromDeviceName: String,
                fileName: String,
                fileSize: Long
            ) {
                SyncCallbackActions.onFileOffer(transferId, fromDeviceName, fileName, fileSize)
            }

            override fun onFileProgress(
                transferId: String,
                bytesTransferred: Long,
                totalBytes: Long,
                speedBps: Long,
                isSend: Boolean
            ) {
                SyncCallbackActions
                    .onFileProgress(transferId, bytesTransferred, totalBytes, speedBps, isSend)
            }

            override fun onFileCompleted(
                transferId: String,
                filePath: String,
                isSend: Boolean,
                success: Boolean,
                errorMsg: String
            ) {
                // 直传路径（onIncomingFileStream 接管）已经写进公共 Download 目录，
                // filePath 就是最终位置，不需要再做拷贝。
                SyncCallbackActions.onFileCompleted(transferId, filePath, isSend, success, errorMsg)
                if (!isSend && success && filePath.isNotEmpty()) {
                    notifier.showFileReceived(filePath)
                }
            }

            override fun onIncomingFileStream(
                transferId: String,
                fromDeviceId: String,
                fromDeviceName: String,
                fileName: String,
                fileSize: Long,
                chunkSize: Int,
                totalChunks: Long,
                streamSalt: ByteArray,
                socketFd: Int,
                peerKey: ByteArray
            ): Boolean {
                // 具体实现（fd 接管、MediaStore pending 提交/回滚）见 IncomingFileReceiver
                val sync = MuClipApplication.sync ?: return false
                return incomingFileReceiver.handle(
                    sync = sync,
                    transferId = transferId,
                    fileName = fileName,
                    fileSize = fileSize,
                    chunkSize = chunkSize,
                    totalChunks = totalChunks,
                    streamSalt = streamSalt,
                    socketFd = socketFd,
                    peerKey = peerKey
                )
            }
        })
    }

    private fun startBackgroundMonitorLoop() {
        serviceScope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(10000)
                if (isStopping) break
                acquireKeepAliveLocks()
                // Ensure Rust sync engine is still running
                if (MuClipApplication.sync == null && com.muclip.core.MuClipNative.isLoaded) {
                    MuClipApplication.instance.initSyncEngine()
                    registerNativeSyncCallbacks()
                }
                // Persist any peers auto-paired via Trusted Introducer gossip while in background
                try {
                    val prefs = MuClipApplication.preferences
                    val savedPaired = prefs.getPairedDevices()
                    if (savedPaired.isNotEmpty()) {
                        val activePeers = MuClipApplication.sync?.getActivePeers() ?: emptyList()
                        for (peer in activePeers) {
                            if (peer.isPaired && peer.deviceId.isNotBlank() && !peer.deviceId.startsWith("manual-")) {
                                if (!savedPaired.contains(peer.deviceId)) {
                                    val devType = if (peer.deviceType.isNotBlank() && peer.deviceType != "Remote") peer.deviceType else "Windows"
                                    val devName = if (peer.deviceName.isNotBlank()) peer.deviceName else peer.deviceId
                                    prefs.setDevicePairedInfo(peer.deviceId, devName, devType, true)
                                }
                                if (prefs.isValidHexKey64(peer.fingerprint) && prefs.getDevicePeerKey(peer.deviceId) != peer.fingerprint.trim()) {
                                    prefs.setDevicePeerKey(peer.deviceId, peer.fingerprint.trim())
                                }
                                if (peer.ip.isNotBlank() && peer.syncPort > 0) {
                                    prefs.setDeviceLastEndpoint(peer.deviceId, peer.ip, peer.syncPort)
                                }
                            }
                        }
                    }
                } catch (_: Exception) {}
                // Ensure persistent notification is restored if swiped away
                if (!isStopping && MuClipApplication.preferences.foregroundServiceEnabled) {
                    try {
                        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                        val isShowing = manager?.activeNotifications?.any { it.id == ClipboardNotifier.NOTIFICATION_ID_SERVICE } == true
                        if (!isShowing) {
                            launch(Dispatchers.Main) {
                                ensureForegroundNotification()
                            }
                        }
                    } catch (_: Exception) {}
                }
            }
        }
    }

    override fun onDestroy() {
        if (activeInstance === this) {
            activeInstance = null
        }
        super.onDestroy()
        serviceJob.cancel()
        try {
            clipboardManager?.removePrimaryClipChangedListener(clipChangedListener)
            multicastLock?.let {
                if (it.isHeld) it.release()
            }
            wifiLock?.let {
                if (it.isHeld) it.release()
            }
            wakeLock?.let {
                if (it.isHeld) it.release()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
