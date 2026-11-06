package com.muclip.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.muclip.app.MuClipApplication
import com.muclip.app.R
import com.muclip.app.activity.ClipboardActionActivity
import com.muclip.app.activity.MainActivity
import com.muclip.core.IncomingPairRequest
import java.io.File

/**
 * MuClip 的全部通知构建逻辑。
 *
 * 从 [ClipboardSyncService] 里拆出来是纯机械动作：这些方法只依赖 `Context` +
 * 一份状态文案，跟 Service 的生命周期、锁、协程作用域都没关系。原来挤在
 * 900 行的 Service 里，改一条通知文案要在一堆生命周期代码里翻。
 *
 * [alertCounter] 是这里唯一可变状态，用于让同类提醒轮流占 10 个通知位，
 * 避免新提醒把旧提醒顶掉。
 */
internal class ClipboardNotifier(private val context: Context) {

    companion object {
        const val CHANNEL_ID_SERVICE = "muclip_sync_service_channel"
        const val CHANNEL_ID_ALERT = "muclip_sync_alert_channel"
        const val CHANNEL_ID_PAIRING = "muclip_pairing_channel"
        const val CHANNEL_ID_FILE = "muclip_file_channel"

        const val NOTIFICATION_ID_SERVICE = 1001
        const val NOTIFICATION_ID_ALERT_BASE = 2000
        const val NOTIFICATION_ID_PAIRING_BASE = 3000
        const val NOTIFICATION_ID_FILE_BASE = 4000
    }

    private var alertCounter = 0

    /** 幂等：重复调用只是覆盖同名渠道。 */
    fun ensureChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val serviceChannel = NotificationChannel(
            CHANNEL_ID_SERVICE,
            context.getString(R.string.sync_service_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = context.getString(R.string.sync_service_channel_desc)
            setShowBadge(false)
        }
        manager.createNotificationChannel(serviceChannel)

        val alertChannel = NotificationChannel(
            CHANNEL_ID_ALERT,
            context.getString(R.string.sync_alert_channel_name),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = context.getString(R.string.sync_alert_channel_desc)
            enableVibration(true)
            setShowBadge(true)
        }
        manager.createNotificationChannel(alertChannel)

        val pairingChannel = NotificationChannel(
            CHANNEL_ID_PAIRING,
            context.getString(R.string.pairing_alert_channel_name),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = context.getString(R.string.pairing_alert_channel_desc)
            enableVibration(true)
            setShowBadge(true)
        }
        manager.createNotificationChannel(pairingChannel)

        val fileChannel = NotificationChannel(
            CHANNEL_ID_FILE,
            "文件传输通知",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "接收与发送文件的状态通知"
            enableVibration(true)
            setShowBadge(true)
        }
        manager.createNotificationChannel(fileChannel)
    }

    /**
     * 常驻前台通知。
     *
     * @param statusText 当前同步状态文案，由 Service 维护（例如「收到来自 XX: ...」）。
     */
    fun buildPersistent(statusText: String): Notification {
        // 透明 Activity：点「立即同步」直接把本机剪贴板推出去
        val syncIntent = Intent(context, ClipboardActionActivity::class.java).apply {
            action = ClipboardActionActivity.ACTION_READ_AND_SYNC
        }
        val syncPendingIntent = PendingIntent.getActivity(
            context,
            1,
            syncIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val openAppIntent = Intent(context, MainActivity::class.java)
        val openAppPendingIntent = PendingIntent.getActivity(
            context,
            2,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val exitIntent = Intent(context, ClipboardSyncService::class.java).apply {
            action = ClipboardSyncService.ACTION_EXIT_APP
        }
        val exitPendingIntent = PendingIntent.getService(
            context,
            3,
            exitIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // 被划掉时自我恢复（Android 13/14+ 允许用户划掉前台通知）
        val restoreIntent = Intent(context, ClipboardSyncService::class.java).apply {
            action = ClipboardSyncService.ACTION_RESTORE_NOTIFICATION
        }
        val restorePendingIntent = PendingIntent.getService(
            context,
            4,
            restoreIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val autoText = if (MuClipApplication.preferences.autoReadClipboard) {
            "自动读取剪贴板已开启"
        } else {
            "自动读取已关闭"
        }

        return NotificationCompat.Builder(context, CHANNEL_ID_SERVICE)
            .setSmallIcon(R.drawable.ic_stat_sync)
            .setContentTitle(context.getString(R.string.notification_title))
            .setContentText("$autoText · $statusText")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(openAppPendingIntent)
            .setDeleteIntent(restorePendingIntent)
            .addAction(
                R.drawable.ic_stat_sync,
                context.getString(R.string.action_sync_now),
                syncPendingIntent
            )
            .addAction(
                R.drawable.ic_stat_sync,
                context.getString(R.string.action_open_app),
                openAppPendingIntent
            )
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                context.getString(R.string.action_exit_app),
                exitPendingIntent
            )
            .build()
    }

    fun showFileReceived(filePath: String) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val fileName = filePath.substringAfterLast('/').substringAfterLast(File.separator)

        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val openPendingIntent = PendingIntent.getActivity(
            context,
            NOTIFICATION_ID_FILE_BASE,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID_FILE)
            .setSmallIcon(R.drawable.ic_stat_sync)
            .setContentTitle("已接收文件: $fileName")
            .setContentText("文件已成功保存至「下载/MuClip」公共目录")
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(openPendingIntent)
            .build()

        manager.notify(NOTIFICATION_ID_FILE_BASE + (alertCounter++ % 10), notification)
    }

    fun showIncomingClipboardAlert(sourceDevice: String, content: String) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val preview = if (content.length > 60) content.take(60) + "..." else content

        val copyIntent = Intent(context, ClipboardActionActivity::class.java).apply {
            action = ClipboardActionActivity.ACTION_WRITE_CLIPBOARD
            putExtra(ClipboardActionActivity.EXTRA_CLIP_CONTENT, content)
            putExtra(ClipboardActionActivity.EXTRA_SOURCE_DEVICE, sourceDevice)
        }
        val copyPendingIntent = PendingIntent.getActivity(
            context,
            alertCounter + 10,
            copyIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID_ALERT)
            .setSmallIcon(R.drawable.ic_stat_sync)
            .setContentTitle("来自「$sourceDevice」的剪贴板内容")
            .setContentText(preview)
            .setStyle(NotificationCompat.BigTextStyle().bigText(content))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .addAction(R.drawable.ic_stat_sync, "复制到剪贴板", copyPendingIntent)
            .build()

        manager.notify(NOTIFICATION_ID_ALERT_BASE + (alertCounter++ % 10), notification)
    }

    fun showPairRequest(request: IncomingPairRequest) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val openPendingIntent = PendingIntent.getActivity(
            context,
            99,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID_PAIRING)
            .setSmallIcon(R.drawable.ic_stat_sync)
            .setContentTitle("收到配对请求: ${request.fromDeviceName}")
            .setContentText("对方请求与此设备配对，请在应用中输入对方显示的随机 PIN 码。")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(openPendingIntent)
            .addAction(R.drawable.ic_stat_sync, context.getString(R.string.action_pair_now), openPendingIntent)
            .build()

        manager.notify(NOTIFICATION_ID_PAIRING_BASE, notification)
    }

    fun showPairSuccess(deviceName: String) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notification = NotificationCompat.Builder(context, CHANNEL_ID_PAIRING)
            .setSmallIcon(R.drawable.ic_stat_sync)
            .setContentTitle("配对成功")
            .setContentText("已成功与「$deviceName」建立端到端加密配对关系！")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        manager.notify(NOTIFICATION_ID_PAIRING_BASE + 1, notification)
    }
}
