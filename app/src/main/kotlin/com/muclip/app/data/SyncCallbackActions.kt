package com.muclip.app.data

import com.muclip.app.MuClipApplication
import com.muclip.core.IncomingPairRequest

/**
 * [com.muclip.core.MuClipSyncCallback] 各事件里「业务逻辑」的唯一实现。
 *
 * ## 为什么需要这个类
 *
 * `MuClipSyncCallback` 有**两个注册者**，这是结构上必须的：
 * - [MuClipApplication.initSyncEngine] 注册兜底版本 —— 前台服务还没起来时，
 *   配对、落库、事件总线也要能用；
 * - [com.muclip.app.service.ClipboardSyncService.registerNativeSyncCallbacks] 注册
 *   完整版本 —— 额外负责通知、状态文案、文件直写 MediaStore。
 *
 * 两份适配器各自实现 8 个方法，但其中真正**写库、改配对状态、推事件总线**的部分
 * 是完全一样的。这段逻辑以前在两个文件里各抄一份：改一处忘一处不会编译报错，
 * 只会让「有前台服务」和「没前台服务」两种状态下行为不一致。而这条路径是 Rust 侧
 * 按方法名字符串反射进来的（`env.call_method(&callback, "onXxx", ...)`），
 * R8 和编译器都发现不了遗漏。
 *
 * 所以约定：**业务逻辑只写在这里**，两份适配器只保留各自差异
 * （通知、界面状态、socket 直传）。新增事件或改业务规则时只改这个文件。
 */
object SyncCallbackActions {

    /**
     * 内部心跳包的内容类型。远端用它探测连通性，不应写库、不应提示用户。
     * 公开给适配器用于「先挡 ping 再改本地状态」的场景。
     */
    const val CONTENT_TYPE_PING = "system/ping"

    /**
     * 收到远端剪贴板：写历史库 + 通知事件总线。
     *
     * @return false 表示这条内容应当被忽略（[CONTENT_TYPE_PING]），
     *         调用方不要再做通知 / 拉起界面等后续动作。
     */
    fun onClipboardReceived(
        deviceId: String,
        deviceName: String,
        content: String,
        contentType: String
    ): Boolean {
        if (contentType == CONTENT_TYPE_PING) return false

        SyncEventManager.markRemoteReceived(content)
        MuClipApplication.coreOrNull()?.addHistory(content)
        SyncEventManager.emitHistoryChanged()
        SyncEventManager.emitClipboardReceived(
            ReceivedClipboardEvent(deviceId, deviceName, content, contentType)
        )
        return true
    }

    /**
     * 收到配对请求：推事件总线，并回传请求对象供调用方弹通知。
     */
    fun onPairRequestReceived(
        requestId: String,
        fromDeviceId: String,
        fromDeviceName: String,
        clientIp: String
    ): IncomingPairRequest {
        val request = IncomingPairRequest(requestId, fromDeviceId, fromDeviceName, clientIp)
        SyncEventManager.emitPairRequest(request)
        return request
    }

    /**
     * 配对成功：落盘设备元信息、对端长期密钥、末次端点，并把设备加进引擎白名单。
     */
    fun onPairSuccess(
        fromDeviceId: String,
        fromDeviceName: String,
        clientIp: String,
        pin: String
    ) {
        val prefs = MuClipApplication.preferences
        val sync = MuClipApplication.sync
        val existingPeer = sync?.getActivePeers()?.find { it.deviceId == fromDeviceId }

        val peerType = existingPeer?.deviceType?.takeIf { it.isNotBlank() && it != "Manual" } ?: "Windows"
        prefs.setDevicePairedInfo(fromDeviceId, fromDeviceName, peerType, true)
        if (prefs.isValidHexKey64(pin)) {
            prefs.setDevicePeerKey(fromDeviceId, pin.trim())
        }

        val peerIp = existingPeer?.ip?.takeIf { it.isNotBlank() } ?: clientIp
        val peerPort = existingPeer?.syncPort?.takeIf { it > 0 } ?: prefs.syncPort
        if (peerIp.isNotBlank() && peerPort > 0) {
            prefs.setDeviceLastEndpoint(fromDeviceId, peerIp, peerPort)
        }
        sync?.addPairedDevice(
            prefs.buildPairedDeviceSpec(fromDeviceId, peerIp, peerPort, fromDeviceName, peerType, pin)
        )

        SyncEventManager.emitPairSuccess(
            PairSuccessEvent(fromDeviceId, fromDeviceName, clientIp, pin)
        )
    }

    /**
     * 对端解除配对。
     *
     * @return 用于展示的设备名（形如 `小明 (muclip-android-abcd1234)`）。
     */
    fun onPeerUnpaired(deviceId: String): String {
        val prefs = MuClipApplication.preferences
        val devName = prefs.getDeviceName(deviceId)
        val displayName = if (devName.isNotBlank() && devName != deviceId) {
            "$devName ($deviceId)"
        } else {
            deviceId
        }
        prefs.setDevicePaired(deviceId, false)

        // 注意：这里**绝对不能**调 sync?.removePairedDevice(deviceId)。
        // 解除是对端发起的，muclip-core 服务端已把该设备从白名单移除；
        // 本地再调一次会把「解除配对」通知发回对端，形成无限回声循环。
        SyncEventManager.emitPeerUnpaired(displayName)
        return displayName
    }

    /**
     * 配对认证连续被拒：只推一条事件让界面提示，**不动**配对状态与密钥。
     *
     * 这个信号来自 Rust 侧对 `SyncRejected` 的计数（连续 3 次、成功即清零）。它可能是
     * 对端解了我们、两边密钥不一致（改过 PIN / 对方重装），也可能是端点串到了另一台设备。
     * core 刻意不细分原因（对未认证方说「你没配对」等于泄露配对状态），所以这里也不能
     * 擅自解配——把选择权交给用户。
     */
    fun onPeerAuthRejected(deviceId: String, streak: Int): String {
        val prefs = MuClipApplication.preferences
        val devName = prefs.getDeviceName(deviceId)
        val displayName = if (devName.isNotBlank() && devName != deviceId) {
            "$devName ($deviceId)"
        } else {
            deviceId
        }
        SyncEventManager.emitPeerAuthRejected(displayName, streak)
        return displayName
    }

    /**
     * 收到文件传输提议：先建一条占位任务，后续进度事件填充。
     */
    fun onFileOffer(
        transferId: String,
        fromDeviceName: String,
        fileName: String,
        fileSize: Long
    ) {
        SyncEventManager.addOrUpdateFileTask(
            FileTransferTask(
                transferId = transferId,
                fileName = fileName,
                filePath = "",
                fileSize = fileSize,
                peerName = fromDeviceName,
                isSend = false
            )
        )
    }

    /** 文件传输进度。 */
    fun onFileProgress(
        transferId: String,
        bytesTransferred: Long,
        totalBytes: Long,
        speedBps: Long,
        isSend: Boolean
    ) {
        SyncEventManager.updateFileProgress(transferId, bytesTransferred, totalBytes, speedBps, isSend)
    }

    /** 文件传输完成。 */
    fun onFileCompleted(
        transferId: String,
        filePath: String,
        isSend: Boolean,
        success: Boolean,
        errorMsg: String
    ) {
        SyncEventManager.updateFileCompleted(transferId, filePath, isSend, success, errorMsg)
    }
}
