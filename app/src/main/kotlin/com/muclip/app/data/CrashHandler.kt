package com.muclip.app.data

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.Process
import android.provider.MediaStore
import android.util.Log
import androidx.annotation.RequiresApi
import java.io.File
import java.io.FileOutputStream
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 全局未捕获异常崩溃记录器。
 *
 * 专门针对冷启动闪退进行全生命周期捕获，将详细的崩溃堆栈、设备环境、
 * ABI 架构与 JNI 状态即时固化保存至公共「下载/MuClip」目录以及私有存储中。
 */
object CrashHandler {
    private const val TAG = "MuClipCrashHandler"
    private var defaultHandler: Thread.UncaughtExceptionHandler? = null
    private var appContext: Context? = null
    private var isInstalled = false

    @Synchronized
    fun install(context: Context) {
        appContext = context.applicationContext ?: context
        if (!isInstalled) {
            defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
                try {
                    handleCrash(thread, throwable)
                } catch (e: Throwable) {
                    Log.e(TAG, "CrashHandler 内部执行出错", e)
                } finally {
                    defaultHandler?.uncaughtException(thread, throwable)
                        ?: Process.killProcess(Process.myPid())
                }
            }
            isInstalled = true
            Log.i(TAG, "CrashHandler 已成功挂载全局异常监听")
        }
    }

    @RequiresApi(Build.VERSION_CODES.BAKLAVA)
    private fun handleCrash(thread: Thread, throwable: Throwable) {
        val now = Date()
        val timeFormatted = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault()).format(now)
        val fileTimeTag = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(now)

        val sw = StringWriter()
        val pw = PrintWriter(sw)
        throwable.printStackTrace(pw)
        val stackTrace = sw.toString()

        val runtime = Runtime.getRuntime()
        val maxMemoryMb = runtime.maxMemory() / (1024 * 1024)
        val totalMemoryMb = runtime.totalMemory() / (1024 * 1024)
        val freeMemoryMb = runtime.freeMemory() / (1024 * 1024)

        val logContent = buildString {
            appendLine("=================================================================")
            appendLine("                MuClip Android 启动崩溃日志 (Crash Report)       ")
            appendLine("=================================================================")
            appendLine("崩溃发生时间    : $timeFormatted")
            appendLine("设备品牌型号    : ${Build.MANUFACTURER} ${Build.MODEL} (${Build.PRODUCT})")
            appendLine("设备主板硬件    : ${Build.BOARD} / ${Build.HARDWARE}")
            appendLine("系统版本        : Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("设备支持的 ABI  : ${Build.SUPPORTED_ABIS.joinToString(", ")}")
            appendLine("运行内存状态    : 已用 ${totalMemoryMb - freeMemoryMb}MB / 总分配 ${totalMemoryMb}MB / 最大上限 ${maxMemoryMb}MB")
            appendLine("崩溃发生的线程  : ${thread.name} (ID: ${thread.threadId()})")
            appendLine("Rust JNI 加载状态: ${com.muclip.core.MuClipNative.isLoaded}")
            appendLine("核心数据库初始化: ${com.muclip.app.MuClipApplication.isCoreInitialized()}")
            appendLine("-----------------------------------------------------------------")
            appendLine("异常类型 (Class): ${throwable.javaClass.name}")
            appendLine("异常详情 (Msg)  : ${throwable.message}")
            appendLine("----------------- 完整堆栈追踪 (Full StackTrace) ----------------")
            appendLine(stackTrace)
            appendLine("=================================================================")
        }

        Log.e(TAG, logContent)

        val ctx = appContext ?: return

        // 仅保存到公共「下载/MuClip」目录
        val latestLogName = "muclip_crash_latest.txt"
        val timestampedLogName = "muclip_crash_$fileTimeTag.txt"

        saveToPublicDownload(ctx, latestLogName, logContent)
        saveToPublicDownload(ctx, timestampedLogName, logContent)
    }

    /**
     * 保存至系统「下载/MuClip」公共目录
     */
    private fun saveToPublicDownload(context: Context, fileName: String, content: String) {
        // Android 10 (Q) 及以上通过 MediaStore 写入 Download/MuClip
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val resolver = context.contentResolver
                val contentValues = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                    put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/MuClip")
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }

                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
                if (uri != null) {
                    resolver.openOutputStream(uri)?.use { outputStream ->
                        outputStream.write(content.toByteArray(Charsets.UTF_8))
                        outputStream.flush()
                    }
                    contentValues.clear()
                    contentValues.put(MediaStore.Downloads.IS_PENDING, 0)
                    resolver.update(uri, contentValues, null, null)
                    Log.i(TAG, "成功通过 MediaStore 将崩溃日志写入 Download/MuClip/$fileName")
                    return
                }
            } catch (e: Throwable) {
                Log.e(TAG, "MediaStore 写入 Download/MuClip 失败: ${e.message}")
            }
        }

        // 直接 POSIX 文件系统写入兜底 (Android 9 或部分兼容 ROM)
        try {
            val publicDownload = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val muclipDir = File(publicDownload, "MuClip").apply { mkdirs() }
            val targetFile = File(muclipDir, fileName)
            targetFile.writeText(content)
            Log.i(TAG, "成功通过 File API 将崩溃日志写入 ${targetFile.absolutePath}")
        } catch (e: Throwable) {
            Log.e(TAG, "POSIX 直接写入 Download/MuClip 失败: ${e.message}")
        }
    }
}
