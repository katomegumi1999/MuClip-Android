package com.muclip.app.service

import android.content.Context
import android.os.ParcelFileDescriptor
import com.muclip.app.data.AndroidStorageUtils
import com.muclip.app.data.SyncEventManager
import com.muclip.core.MuClipSync
import com.muclip.core.NativeXChaCha20Poly1305Aead
import com.muclip.core.StreamingFileTransfer
import java.io.BufferedOutputStream
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 接收端「socket 直传」路径：把 Rust 交过来的原始 socket fd 直接解密写进 MediaStore
 * 的 pending 流，全程不落 staging 私有文件，所以收完不需要再拷一次。
 *
 * 从 [ClipboardSyncService] 的 JNI 回调里拆出来，原因有两个：
 * 1. 这段逻辑有 80 行、全是 fd / 流的生命周期处理，跟 Service 的生命周期没关系，
 *    塞在回调匿名对象里根本没法单独看；
 * 2. 它同时涉及「Rust 侧 fd 归属约定」和「MediaStore pending 条目提交 / 回滚」两套
 *    容易出错的契约，独立成类之后可以在一个地方读懂全部约束。
 */
internal class IncomingFileReceiver(
    private val context: Context,
    private val scope: CoroutineScope,
    private val onFileReceived: (filePath: String) -> Unit
) {

    /**
     * 接管 [socketFd] 并异步接收。
     *
     * @return `true` 表示已经接管该 fd 并由本类负责关闭；`false` 表示交还给 Rust 自带的
     *         staging 文件路径（结果一样，只是慢一点）。
     *
     * **返回 false 时绝对不能关 [socketFd]** —— Rust 侧 `server.rs` 在收到 false 后会
     * 自己回收 `raw_fd`，提前关掉会让它拿到一个已关闭的 fd。
     */
    fun handle(
        sync: MuClipSync,
        transferId: String,
        fileName: String,
        fileSize: Long,
        chunkSize: Int,
        totalChunks: Long,
        streamSalt: ByteArray,
        socketFd: Int,
        peerKey: ByteArray
    ): Boolean {
        val pending = AndroidStorageUtils.createPendingDownloadStream(context, fileName)
            ?: return false
        val (pendingUri, mediaOut) = pending

        scope.launch(Dispatchers.IO) {
            val cancelFlag = StreamingFileTransfer.registerCancelFlag(transferId)
            var ok = false
            var displayPath = ""
            var pfd: ParcelFileDescriptor? = null
            val bufferedFileOut = BufferedOutputStream(mediaOut, 1024 * 1024)
            try {
                // 立刻接管 fd：后续任何失败都不能把它留在悬空状态
                pfd = ParcelFileDescriptor.adoptFd(socketFd)
                val sockIn = FileInputStream(pfd.fileDescriptor)
                val sockOut = FileOutputStream(pfd.fileDescriptor)

                val derivedBulkKey = ByteArray(NativeXChaCha20Poly1305Aead.KEY_SIZE)
                if (sync.deriveBulkKey(peerKey, streamSalt, derivedBulkKey) != 0) {
                    throw IOException("无法推导文件传输会话密钥")
                }

                StreamingFileTransfer.receiveToStream(
                    sync = sync,
                    aead = NativeXChaCha20Poly1305Aead(derivedBulkKey),
                    transferId = transferId,
                    expectedFileSize = fileSize,
                    chunkSize = chunkSize,
                    expectedChunkCount = totalChunks,
                    fileNonce = streamSalt,
                    sockIn = sockIn,
                    sockOut = sockOut,
                    fileOut = bufferedFileOut,
                    cancelFlag = cancelFlag,
                    peerKey = peerKey,
                    onProgress = { done, total, speed ->
                        SyncEventManager.updateFileProgress(transferId, done, total, speed, false)
                    }
                )
                ok = true
                displayPath = "Download/MuClip/$fileName"
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                StreamingFileTransfer.unregisterCancelFlag(transferId)
                try { bufferedFileOut.close() } catch (_: Exception) {}
                try { mediaOut.close() } catch (_: Exception) {}
                try { pfd?.close() } catch (_: Exception) {}
            }

            if (ok) {
                AndroidStorageUtils.finalizePendingDownload(context, pendingUri)
            } else {
                // 把半截条目丢掉，别让用户看到一个截断的文件
                AndroidStorageUtils.abortPendingDownload(context, pendingUri)
            }

            SyncEventManager.updateFileCompleted(
                transferId, displayPath, false, ok,
                if (ok) "" else "接收文件失败"
            )
            if (ok) onFileReceived(displayPath)
        }
        return true
    }
}
