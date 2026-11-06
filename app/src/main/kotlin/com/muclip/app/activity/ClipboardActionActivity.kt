package com.muclip.app.activity

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.widget.Toast
import com.muclip.app.MuClipApplication

/**
 * Transparent Trampoline Activity.
 *
 * Android 10+ restricts background apps from reading/writing the system clipboard.
 * This activity is launched via PendingIntent with a completely invisible/translucent theme.
 * As soon as it gains window focus (`onWindowFocusChanged(true)`), it performs the clipboard
 * transaction synchronously and immediately calls `finish()`.
 */
class ClipboardActionActivity : Activity() {

    companion object {
        const val ACTION_READ_AND_SYNC = "com.muclip.app.ACTION_READ_AND_SYNC"
        const val ACTION_WRITE_CLIPBOARD = "com.muclip.app.ACTION_WRITE_CLIPBOARD"
        const val EXTRA_CLIP_CONTENT = "extra_clip_content"
        const val EXTRA_SOURCE_DEVICE = "extra_source_device"
    }

    private var actionHandled = false

    private fun disableTransitions() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, 0, 0)
            overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        disableTransitions()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && !actionHandled) {
            actionHandled = true
            handleClipboardIntent()
            finish()
            disableTransitions()
        }
    }

    private fun handleClipboardIntent() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            ?: return

        when (intent?.action) {
            ACTION_READ_AND_SYNC -> {
                val clip = clipboard.primaryClip
                if (clip != null && clip.itemCount > 0) {
                    val text = clip.getItemAt(0).text?.toString()
                    if (!text.isNullOrBlank()) {
                        val trimmed = text.trim()
                        if (com.muclip.app.data.SyncEventManager.shouldBroadcast(trimmed)) {
                            // 1. Save to local core history and notify UI immediately
                            MuClipApplication.coreOrNull()?.addHistory(trimmed)
                            com.muclip.app.data.SyncEventManager.emitHistoryChanged()

                            // 2. Broadcast via P2P LAN E2EE sync engine to Windows / trusted peers in parallel
                            val sentCount = MuClipApplication.sync?.broadcastClipboard(trimmed, "text") ?: 0

                            val msg = if (sentCount > 0) {
                                "已自动同步剪贴板至 $sentCount 台设备"
                            } else {
                                "已记录剪贴板 (当前无在线配对设备)"
                            }
                            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }

            ACTION_WRITE_CLIPBOARD -> {
                val content = intent.getStringExtra(EXTRA_CLIP_CONTENT)
                val source = intent.getStringExtra(EXTRA_SOURCE_DEVICE) ?: "远程设备"
                if (!content.isNullOrEmpty()) {
                    val newClip = ClipData.newPlainText("MuClip Sync", content)
                    clipboard.setPrimaryClip(newClip)
                    Toast.makeText(this, "已复制来自 $source 的内容", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}
