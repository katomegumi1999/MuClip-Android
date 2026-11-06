package com.muclip.app.data

import com.muclip.core.IncomingPairRequest
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

data class ReceivedClipboardEvent(
    val deviceId: String,
    val deviceName: String,
    val content: String,
    val contentType: String
)

/** 配对认证连续被拒的提示载荷：只提示，配对与密钥都不动。 */
data class AuthRejectedEvent(
    val deviceId: String,
    val streak: Int
)

data class PairSuccessEvent(
    val fromDeviceId: String,
    val fromDeviceName: String,
    val clientIp: String,
    val pin: String
)

object SyncEventManager {

    // Incoming pairing handshake request waiting for local confirmation
    private val _pendingPairRequest = MutableStateFlow<IncomingPairRequest?>(null)
    val pendingPairRequest = _pendingPairRequest.asStateFlow()

    // Event when pairing succeeds
    private val _pairSuccessFlow = MutableSharedFlow<PairSuccessEvent>(extraBufferCapacity = 10)
    val pairSuccessFlow = _pairSuccessFlow.asSharedFlow()

    // Event when remote clipboard content is received
    private val _clipboardReceivedFlow = MutableSharedFlow<ReceivedClipboardEvent>(extraBufferCapacity = 10)
    val clipboardReceivedFlow = _clipboardReceivedFlow.asSharedFlow()

    // Event when local or remote clipboard history changes
    private val _historyChangedFlow = MutableSharedFlow<Unit>(extraBufferCapacity = 10)
    val historyChangedFlow = _historyChangedFlow.asSharedFlow()

    // Global deduplication and echo-loop prevention
    @Volatile
    var lastSyncedText: String? = null

    @Volatile
    var lastReceivedRemoteText: String? = null

    /**
     * Checks if the given clipboard text should be broadcasted to peers.
     * Prevents loopback echoes (from remote sync) and redundant broadcasts of the same local text.
     */
    @Synchronized
    fun shouldBroadcast(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return false
        if (trimmed == lastSyncedText) return false
        if (trimmed == lastReceivedRemoteText) return false
        lastSyncedText = trimmed
        return true
    }

    @Synchronized
    fun markRemoteReceived(text: String) {
        val trimmed = text.trim()
        lastReceivedRemoteText = trimmed
        lastSyncedText = trimmed
    }

    // Pending content to be written to system clipboard when app is in foreground
    private val _pendingClipboardWrite = MutableStateFlow<String?>(null)
    val pendingClipboardWrite = _pendingClipboardWrite.asStateFlow()

    // Event when a remote peer unpairs this device
    private val _unpairedPeerFlow = MutableSharedFlow<String>(extraBufferCapacity = 10)
    val unpairedPeerFlow = _unpairedPeerFlow.asSharedFlow()

    // Event when the same peer keeps failing the session handshake (hint only)
    private val _authRejectedFlow = MutableSharedFlow<AuthRejectedEvent>(extraBufferCapacity = 10)
    val authRejectedFlow = _authRejectedFlow.asSharedFlow()

    fun scheduleForegroundClipboardWrite(content: String) {
        _pendingClipboardWrite.value = content
    }

    fun consumePendingClipboardWrite(): String? {
        val current = _pendingClipboardWrite.value
        _pendingClipboardWrite.value = null
        return current
    }

    fun emitPeerUnpaired(deviceId: String) {
        _unpairedPeerFlow.tryEmit(deviceId)
    }

    fun emitPeerAuthRejected(deviceId: String, streak: Int) {
        _authRejectedFlow.tryEmit(AuthRejectedEvent(deviceId, streak))
    }

    fun emitPairRequest(request: IncomingPairRequest) {
        _pendingPairRequest.value = request
    }

    fun clearPairRequest() {
        _pendingPairRequest.value = null
    }

    fun emitPairSuccess(event: PairSuccessEvent) {
        _pairSuccessFlow.tryEmit(event)
    }

    fun emitClipboardReceived(event: ReceivedClipboardEvent) {
        markRemoteReceived(event.content)
        scheduleForegroundClipboardWrite(event.content)
        _clipboardReceivedFlow.tryEmit(event)
    }

    fun emitHistoryChanged() {
        _historyChangedFlow.tryEmit(Unit)
    }

    // -------------------------------------------------------------------------
    // High-Speed Encrypted File Transfer Tasks
    // -------------------------------------------------------------------------

    private val _fileTasksFlow = MutableStateFlow<List<FileTransferTask>>(emptyList())
    val fileTasksFlow = _fileTasksFlow.asStateFlow()

    @Synchronized
    fun addOrUpdateFileTask(task: FileTransferTask) {
        val current = _fileTasksFlow.value.toMutableList()
        val index = current.indexOfFirst { it.transferId == task.transferId }
        if (index >= 0) {
            val old = current[index]
            current[index] = old.copy(
                fileName = if (task.fileName.isNotEmpty()) task.fileName else old.fileName,
                filePath = if (task.filePath.isNotEmpty()) task.filePath else old.filePath,
                fileSize = if (task.fileSize > 0) task.fileSize else old.fileSize,
                peerName = if (task.peerName.isNotEmpty()) task.peerName else old.peerName,
                isSend = task.isSend,
                bytesTransferred = if (old.isCompleted && old.isSuccess && task.fileSize > 0) task.fileSize else old.bytesTransferred
            )
        } else {
            current.add(0, task)
        }
        _fileTasksFlow.value = current
    }

    @Synchronized
    fun updateFileProgress(transferId: String, transferred: Long, total: Long, speedBps: Long, isSend: Boolean) {
        val current = _fileTasksFlow.value.toMutableList()
        val index = current.indexOfFirst { it.transferId == transferId }
        if (index >= 0) {
            val old = current[index]
            current[index] = old.copy(
                bytesTransferred = transferred,
                fileSize = if (total > 0) total else old.fileSize,
                speedBps = speedBps,
                isSend = isSend
            )
        } else {
            current.add(
                0,
                FileTransferTask(
                    transferId = transferId,
                    fileName = "传输中...",
                    filePath = "",
                    fileSize = total,
                    bytesTransferred = transferred,
                    speedBps = speedBps,
                    isSend = isSend
                )
            )
        }
        _fileTasksFlow.value = current
    }

    @Synchronized
    fun updateFileCompleted(transferId: String, filePath: String, isSend: Boolean, success: Boolean, errorMsg: String) {
        val current = _fileTasksFlow.value.toMutableList()
        val index = current.indexOfFirst { it.transferId == transferId }
        if (index >= 0) {
            val old = current[index]
            current[index] = old.copy(
                filePath = if (filePath.isNotEmpty()) filePath else old.filePath,
                isCompleted = true,
                isSuccess = success,
                errorMsg = if (errorMsg.isNotEmpty()) errorMsg else null,
                bytesTransferred = if (success) old.fileSize else old.bytesTransferred,
                speedBps = 0
            )
        } else {
            current.add(
                0,
                FileTransferTask(
                    transferId = transferId,
                    fileName = filePath.substringAfterLast('/').ifEmpty { "文件传输" },
                    filePath = filePath,
                    fileSize = 0L,
                    isSend = isSend,
                    isCompleted = true,
                    isSuccess = success,
                    errorMsg = if (errorMsg.isNotEmpty()) errorMsg else null
                )
            )
        }
        _fileTasksFlow.value = current
    }
}

data class FileTransferTask(
    val transferId: String,
    val fileName: String,
    val filePath: String,
    val fileSize: Long,
    val peerName: String = "配对设备",
    val bytesTransferred: Long = 0,
    val speedBps: Long = 0,
    val isSend: Boolean,
    val isCompleted: Boolean = false,
    val isSuccess: Boolean = false,
    val errorMsg: String? = null,
    val timestamp: Long = System.currentTimeMillis()
) {
    val progress: Float
        get() = if (fileSize > 0) (bytesTransferred.toFloat() / fileSize).coerceIn(0f, 1f) else 0f

    val speedFormatted: String
        get() {
            val speedMb = speedBps / (1024.0 * 1024.0)
            return if (speedMb >= 1.0) "%.1f MB/s".format(speedMb) else "${speedBps / 1024} KB/s"
        }

    val sizeFormatted: String
        get() {
            val mb = fileSize / (1024.0 * 1024.0)
            return if (mb >= 1.0) "%.1f MB".format(mb) else "${fileSize / 1024} KB"
        }
}
