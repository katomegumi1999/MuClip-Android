package com.muclip.app.viewmodel

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.muclip.app.MuClipApplication
import com.muclip.app.data.AndroidStorageUtils
import com.muclip.app.data.FileTransferTask
import com.muclip.app.data.SyncEventManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class FileTransferViewModel : ViewModel() {

    val fileTasks: StateFlow<List<FileTransferTask>> = SyncEventManager.fileTasksFlow

    /**
     * Sends a selected SAF `content://` Uri directly via Rust `sender.rs` (`MuClipSync.sendFile`)
     * by detaching the native file descriptor (`fd:<raw_fd>`), achieving zero disk caching
     * and zero key derivation in the Kotlin layer.
     */
    fun sendFile(
        context: Context,
        peerAddr: String,
        peerName: String,
        uri: Uri,
        onStarted: (Boolean, String?) -> Unit
    ) {
        val appContext = context.applicationContext
        viewModelScope.launch {
            val startResult = withContext(Dispatchers.IO) {
                try {
                    val sync = MuClipApplication.sync
                        ?: return@withContext Triple(false, "同步引擎未启动", "")

                    val (fileName, fileSize) = AndroidStorageUtils.getFileMetaFromUri(appContext, uri)

                    // Open read-only ParcelFileDescriptor from SAF and detach raw FD for Rust sender.rs
                    val pfd = appContext.contentResolver.openFileDescriptor(uri, "r")
                        ?: return@withContext Triple(false, "无法通过系统 SAF 读取所选文件", "")
                    val detachedFd = pfd.detachFd()

                    val transferId = sync.sendFile(peerAddr, "fd:$detachedFd", fileName)
                    if (transferId.isBlank()) {
                        return@withContext Triple(false, "无法启动文件传输，请确认已完成设备配对", "")
                    }

                    SyncEventManager.addOrUpdateFileTask(
                        FileTransferTask(
                            transferId = transferId,
                            fileName = fileName,
                            filePath = uri.toString(),
                            fileSize = fileSize,
                            peerName = peerName,
                            isSend = true
                        )
                    )

                    Triple(true, null, transferId)
                } catch (e: Exception) {
                    Triple(false, e.message ?: "发送文件发生异常", "")
                }
            }

            onStarted(startResult.first, startResult.second)
        }
    }

    fun cancelTransfer(transferId: String) {
        com.muclip.core.StreamingFileTransfer.cancelTransfer(transferId)
        MuClipApplication.sync?.cancelFileTransfer(transferId)
    }

    /**
     * Attempts to open the received file using Android standard VIEW intent.
     */
    fun openFile(context: Context, filePath: String) {
        try {
            var file = File(filePath)
            if (!file.exists()) {
                // Try resolving relative path under standard Download directory
                val publicDownload = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                val candidate1 = File(publicDownload, filePath.removePrefix("Download/"))
                val candidate2 = File(publicDownload, "MuClip/" + filePath.substringAfterLast('/'))
                if (candidate1.exists()) {
                    file = candidate1
                } else if (candidate2.exists()) {
                    file = candidate2
                } else {
                    Toast.makeText(context, "文件已保存至公共「下载/MuClip」目录", Toast.LENGTH_SHORT).show()
                    return
                }
            }

            val uri: Uri = try {
                FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            } catch (e: Exception) {
                // 不要回退到 Uri.fromFile：targetSdk 24+ 把它交给 startActivity 会抛
                // FileUriExposedException，等于把「拿不到 content:// URI」升级成一次崩溃。
                // 走到这里说明文件不在 FileProvider 声明的路径内（见 res/xml/file_paths.xml），
                // 直接告诉用户文件实际在哪。
                Toast.makeText(
                    context,
                    "无法打开该文件，请到「下载/MuClip」目录手动查看",
                    Toast.LENGTH_LONG
                ).show()
                return
            }

            // 1. 优先调用安卓系统 ContentResolver 自动识别该 Uri 的真实 MIME 类型
            // 2. 若返回空或未识别，自动通过系统 MimeTypeMap 提取
            val mimeType = context.contentResolver.getType(uri)
                ?.takeIf { it.isNotBlank() && it != "application/octet-stream" }
                ?: AndroidStorageUtils.getMimeType(file.name)

            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mimeType)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            try {
                val chooser = Intent.createChooser(intent, "打开方式: ${file.name}").apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(chooser)
            } catch (e: Exception) {
                // 如果特定类型没有匹配的应用，回退到泛型选择器
                val fallbackIntent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "*/*")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                val fallbackChooser = Intent.createChooser(fallbackIntent, "打开方式: ${file.name}").apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(fallbackChooser)
            }
        } catch (e: Exception) {
            Toast.makeText(context, "未找到可打开此文件的应用: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
}
