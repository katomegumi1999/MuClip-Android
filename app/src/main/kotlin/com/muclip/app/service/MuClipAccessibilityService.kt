package com.muclip.app.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import com.muclip.app.MuClipApplication
import com.muclip.app.activity.ClipboardActionActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Accessibility Service for truly automatic background clipboard reading and sync.
 *
 * Android 10+ (API 29+) prevents background services from directly reading system clipboard
 * and forbids standard background services from launching activities (BAL restrictions).
 *
 * An AccessibilityService is an official Android system mechanism that:
 * 1. Remains active and running in the background.
 * 2. Has explicit exemption from Background Activity Launch restrictions (BAL exemption).
 * 3. Can detect global user interactions (clicks, copy/paste selections, window transitions)
 *    and immediately sync clipboard changes to paired devices.
 */
class MuClipAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        var isServiceRunning: Boolean = false
            private set

        @Volatile
        var activeInstance: MuClipAccessibilityService? = null
            private set

        /**
         * Launches ClipboardActionActivity using AccessibilityService's permanent BAL exemption.
         */
        fun launchWriteActivityWithBalExemption(text: String, sourceDevice: String): Boolean {
            val svc = activeInstance ?: return false
            return try {
                val writeIntent = Intent(svc, ClipboardActionActivity::class.java).apply {
                    action = ClipboardActionActivity.ACTION_WRITE_CLIPBOARD
                    putExtra(ClipboardActionActivity.EXTRA_CLIP_CONTENT, text)
                    putExtra(ClipboardActionActivity.EXTRA_SOURCE_DEVICE, sourceDevice)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION
                }
                svc.startActivity(writeIntent)
                true
            } catch (_: Exception) {
                false
            }
        }

        /**
         * Checks whether the user has enabled MuClip's Accessibility Service in Android Settings.
         */
        fun isEnabled(context: Context): Boolean {
            val expectedServiceName = "${context.packageName}/${MuClipAccessibilityService::class.java.name}"
            val enabledServices = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false

            return enabledServices.split(":").any { it.equals(expectedServiceName, ignoreCase = true) }
        }

        /**
         * Opens the system Accessibility settings screen so the user can easily toggle the service.
         */
        fun openAccessibilitySettings(context: Context) {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
        }
    }

    private val serviceJob = Job()
    private val serviceScope = CoroutineScope(Dispatchers.Main + serviceJob)
    private var clipboardManager: ClipboardManager? = null
    private var lastLocalText: String? = null
    private var lastCheckTimestamp: Long = 0

    private val clipListener = ClipboardManager.OnPrimaryClipChangedListener {
        checkAndSyncClipboard("ClipChangedListener")
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        isServiceRunning = true
        activeInstance = this

        try {
            clipboardManager = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            clipboardManager?.addPrimaryClipChangedListener(clipListener)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!MuClipApplication.preferences.autoReadClipboard) {
            return
        }

        // Throttle rapid consecutive events (debounce 400ms)
        val now = SystemClock.uptimeMillis()
        if (now - lastCheckTimestamp < 400) {
            return
        }
        lastCheckTimestamp = now

        when (event?.eventType) {
            AccessibilityEvent.TYPE_VIEW_CLICKED,
            AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                checkAndSyncClipboard("AccessibilityEvent_${event.eventType}")
            }
        }
    }

    override fun onInterrupt() {
        // Called when the system wants to interrupt feedback
    }

    override fun onDestroy() {
        if (activeInstance === this) {
            activeInstance = null
        }
        super.onDestroy()
        isServiceRunning = false
        serviceJob.cancel()
        try {
            clipboardManager?.removePrimaryClipChangedListener(clipListener)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Attempts to read clipboard directly. If Android 10+ restrictions return null or fail,
     * launches the transparent ClipboardActionActivity using Accessibility's BAL exemption.
     */
    private fun checkAndSyncClipboard(triggerSource: String) {
        if (!MuClipApplication.preferences.autoReadClipboard) return

        try {
            val clip = clipboardManager?.primaryClip
            if (clip != null && clip.itemCount > 0) {
                val text = clip.getItemAt(0).text?.toString()?.trim()
                if (!text.isNullOrEmpty() && com.muclip.app.data.SyncEventManager.shouldBroadcast(text)) {
                    broadcastClipboardText(text)
                    return
                }
            }
        } catch (e: Exception) {
            // In case of restriction
        }

        // Fallback with BAL exemption: Launch transparent trampoline activity to read with focus
        try {
            val trampolineIntent = Intent(this, ClipboardActionActivity::class.java).apply {
                action = ClipboardActionActivity.ACTION_READ_AND_SYNC
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION
            }
            startActivity(trampolineIntent)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun broadcastClipboardText(text: String) {
        serviceScope.launch(Dispatchers.IO) {
            MuClipApplication.coreOrNull()?.addHistory(text)
            MuClipApplication.sync?.broadcastClipboard(text, "text")
        }
    }
}
