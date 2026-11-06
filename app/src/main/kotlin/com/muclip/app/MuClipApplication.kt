package com.muclip.app

import android.app.Activity
import android.app.Application
import android.content.Context
import android.net.wifi.WifiManager
import android.os.Bundle
import com.muclip.app.data.LocalDbKey
import com.muclip.app.data.PairingPreferences
import com.muclip.core.MuClipCore
import com.muclip.core.MuClipSync
import java.io.File

class MuClipApplication : Application() {

    companion object {
        private const val TAG = "MuClipApp"

        lateinit var instance: MuClipApplication
            private set

        lateinit var core: MuClipCore
            private set

        fun isCoreInitialized(): Boolean = ::core.isInitialized

        /**
         * 安全的核心库入口。
         *
         * 本地库打开失败时 [core] 会一直保持未初始化，直接解引用会抛
         * `UninitializedPropertyAccessException`（每次剪贴板变化都会崩一次）。
         * 所有调用点一律经由这里访问，用 `?.` 处理 null。
         */
        fun coreOrNull(): MuClipCore? = if (::core.isInitialized) core else null

        var sync: MuClipSync? = null
            private set

        lateinit var preferences: PairingPreferences
            private set
    }

    private var multicastLock: WifiManager.MulticastLock? = null

    /** 处于 started 状态的 Activity 计数，用于判断界面是否可见。 */
    private var startedActivityCount = 0

    /**
     * mDNS 组播锁跟着「界面可见性」走。
     *
     * 原来的写法是在 `onCreate()` 里 `acquire()`、只在 `onTerminate()` 里 `release()`。
     * 但 `onTerminate()` 在真机上**永远不会被调用**（系统直接 kill 进程），
     * 等于从安装后第一次启动起就永久持有 MulticastLock：Wi-Fi 固件无法进入
     * 组播省电状态，这是后台耗电的主要来源之一。
     *
     * 后台发现本来就是 [com.muclip.app.service.ClipboardSyncService] 的职责
     * （它自己持有一把独立 tag 的组播锁），所以界面不可见时这里没必要占着。
     */
    private fun registerMulticastLockLifecycle() {
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                startedActivityCount++
                if (startedActivityCount == 1) acquireMulticastLock()
            }

            override fun onActivityStopped(activity: Activity) {
                startedActivityCount--
                if (startedActivityCount <= 0) {
                    startedActivityCount = 0
                    releaseMulticastLock()
                }
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }

    private fun acquireMulticastLock() {
        try {
            if (multicastLock == null) {
                val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                multicastLock = wifi?.createMulticastLock("MuClipMDNSLock")?.apply {
                    setReferenceCounted(false)
                }
            }
            if (multicastLock?.isHeld == false) {
                multicastLock?.acquire()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun releaseMulticastLock() {
        try {
            multicastLock?.let {
                if (it.isHeld) it.release()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        com.muclip.app.data.CrashHandler.install(base)
    }

    override fun onCreate() {
        super.onCreate()
        com.muclip.app.data.CrashHandler.install(this)
        instance = this

        // 1. Initialize Native MuClip Core Database safely
        initCoreDatabase()

        preferences = PairingPreferences(this)

        // 2. 组播锁按「界面是否可见」持有，详见 [registerMulticastLockLifecycle]
        registerMulticastLockLifecycle()

        // 3. Start P2P LAN Sync Engine (if native library was loaded)
        if (com.muclip.core.MuClipNative.isLoaded) {
            initSyncEngine()
        }
    }

    /**
     * 以加密模式初始化本地库。
     *
     * 旧明文库不做任何保留：软件目前自用，没有需要抢救的历史数据，第一次跑加密版时
     * 直接删掉重建。唯一保留的护栏是 [nativeSupportsEncryption] —— 不能在明知
     * 打不开加密库的情况下去删文件，否则就是白丢数据还起不来。
     */
    private fun initCoreDatabase() {
        val dbFile = File(filesDir, "muclip.db")

        // 1) 先确认 native 库到底支不支持加密。用 nativeIsEncrypted(0) 做探针：
        //    句柄 0 返回 -1 是正常的「句柄无效」，只有符号不存在才会抛 UnsatisfiedLinkError。
        //    这一步必须排在删库之前。
        if (!nativeSupportsEncryption()) {
            android.util.Log.w(
                TAG,
                "native 库为明文构建（缺 nativeIsEncrypted 导出），本地库不加密。设置页会如实显示明文。"
            )
            openPlain(dbFile)
            return
        }

        // 2) 没有历史密钥 = 第一次以加密模式启动，磁盘上的库必然是打不开的明文旧库，
        //    删掉重建。必须在 getOrCreate() 之前判断，否则密钥一落地就再也分不出首不首次。
        if (!LocalDbKey.exists(this)) {
            android.util.Log.i(TAG, "首次启用加密，清理旧明文库")
            removeDatabaseFiles(dbFile)
        }

        val dbKey = LocalDbKey.getOrCreate(this)
        try {
            core = MuClipCore.openWithKey(dbFile.absolutePath, dbKey)
        } catch (e: Throwable) {
            // 密钥记录在但库打不开（文件损坏等）。这里不删也不改名，把状态留给日志，
            // 由使用者决定是否清数据 —— 自动删只会把「能救回来」变成「救不回来」。
            android.util.Log.e(TAG, "加密库打开失败，本地数据不可用: ${e.message}")
            e.printStackTrace()
        }
    }

    /** 删掉主库文件及可能残留的 journal/wal/shm。默认 journal 模式只会留 -journal。 */
    private fun removeDatabaseFiles(dbFile: File) {
        val parent = dbFile.parentFile ?: return
        for (suffix in listOf("", "-journal", "-wal", "-shm")) {
            val f = File(parent, dbFile.name + suffix)
            if (f.exists() && !f.delete()) {
                android.util.Log.w(TAG, "删除旧库文件失败: ${f.name}")
            }
        }
    }

    private fun nativeSupportsEncryption(): Boolean {
        if (!com.muclip.core.MuClipNative.isLoaded) return false
        return try {
            com.muclip.core.MuClipNative.nativeIsEncrypted(0L)
            true
        } catch (e: UnsatisfiedLinkError) {
            false
        }
    }

    private fun openPlain(dbFile: File) {
        try {
            core = MuClipCore.open(dbFile.absolutePath)
        } catch (e: Throwable) {
            e.printStackTrace()
        }
    }

    fun initSyncEngine() {
        try {
            sync?.close()
            val engine = MuClipSync.start(
                deviceId = preferences.deviceId,
                deviceName = preferences.deviceName,
                deviceType = "Android",
                syncPort = preferences.syncPort,
                pairingPin = preferences.pairingPin
            )
            // Load any static / port-mapped remote peers
            preferences.getCustomPeers().forEach { endpoint ->
                engine.addManualPeer(endpoint)
            }
            // Load paired devices into discovery table & keyring (with saved IP/port & peerKey)
            preferences.getPairedDevices().forEach { devId ->
                engine.addPairedDevice(preferences.buildPairedDeviceSpec(devId))
            }
            // Apply auto-discovery configuration
            engine.setAutoDiscovery(preferences.autoDiscovery)

            // 注册兜底回调：让配对 / 落库 / 事件总线在前台服务启动前就能工作。
            // 业务逻辑全在 SyncCallbackActions 里，这里只做转发——服务启动后会用
            // ClipboardSyncService 里那份「带通知」的适配器替换掉本回调。
            engine.registerCallback(object : com.muclip.core.MuClipSyncCallback {
                override fun onClipboardReceived(
                    deviceId: String,
                    deviceName: String,
                    content: String,
                    contentType: String
                ) {
                    com.muclip.app.data.SyncCallbackActions
                        .onClipboardReceived(deviceId, deviceName, content, contentType)
                }

                override fun onPairRequestReceived(
                    requestId: String,
                    fromDeviceId: String,
                    fromDeviceName: String,
                    clientIp: String
                ) {
                    com.muclip.app.data.SyncCallbackActions
                        .onPairRequestReceived(requestId, fromDeviceId, fromDeviceName, clientIp)
                }

                override fun onPairSuccess(
                    fromDeviceId: String,
                    fromDeviceName: String,
                    clientIp: String,
                    pin: String
                ) {
                    com.muclip.app.data.SyncCallbackActions
                        .onPairSuccess(fromDeviceId, fromDeviceName, clientIp, pin)
                }

                override fun onPeerUnpaired(deviceId: String) {
                    com.muclip.app.data.SyncCallbackActions.onPeerUnpaired(deviceId)
                }

                override fun onPeerAuthRejected(deviceId: String, streak: Int) {
                    com.muclip.app.data.SyncCallbackActions
                        .onPeerAuthRejected(deviceId, streak)
                }

                override fun onFileOffer(
                    transferId: String,
                    fromDeviceId: String,
                    fromDeviceName: String,
                    fileName: String,
                    fileSize: Long
                ) {
                    com.muclip.app.data.SyncCallbackActions
                        .onFileOffer(transferId, fromDeviceName, fileName, fileSize)
                }

                override fun onFileProgress(
                    transferId: String,
                    bytesTransferred: Long,
                    totalBytes: Long,
                    speedBps: Long,
                    isSend: Boolean
                ) {
                    com.muclip.app.data.SyncCallbackActions
                        .onFileProgress(transferId, bytesTransferred, totalBytes, speedBps, isSend)
                }

                override fun onFileCompleted(
                    transferId: String,
                    filePath: String,
                    isSend: Boolean,
                    success: Boolean,
                    errorMsg: String
                ) {
                    com.muclip.app.data.SyncCallbackActions
                        .onFileCompleted(transferId, filePath, isSend, success, errorMsg)
                }
            })

            sync = engine

            // If ForegroundService is already running, let it re-attach its full notification-enabled callback
            com.muclip.app.service.ClipboardSyncService.rebindNativeCallbacksIfRunning()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * 仅在模拟器 / 可调试环境中会被调用，真机上进程是被直接 kill 的。
     * 所以这里不是资源释放的正确位置——锁的生命周期见 [registerMulticastLockLifecycle]。
     */
    override fun onTerminate() {
        super.onTerminate()
        try {
            releaseMulticastLock()
            sync?.close()
            core.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
