package com.muclip.app.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.muclip.app.ui.theme.MuClipCard
import com.muclip.app.ui.theme.MuClipCardBorder
import com.muclip.app.ui.theme.MuClipBackground
import com.muclip.app.ui.theme.MuClipOnPrimary
import com.muclip.app.ui.theme.MuClipGreen
import com.muclip.app.ui.theme.MuClipPrimary
import com.muclip.app.ui.theme.MuClipRed
import com.muclip.app.ui.theme.MuClipSurface
import com.muclip.app.ui.theme.MuClipTextPrimary
import com.muclip.app.ui.theme.MuClipTextSecondary
import com.muclip.app.viewmodel.PairedDevice
import com.muclip.app.viewmodel.PairingSessionState
import com.muclip.core.IncomingPairRequest

/**
 * 设备页的四个对话框。
 *
 * 从 894 行的 `DevicesScreen` 里搬出来——它们本来就能靠显式参数完全驱动，
 * 挤在巨大的 LazyColumn 前面只是让人找不到列表在哪儿。
 *
 * 约定：这些 Composable **不直接持有 ViewModel**，状态由调用方 hoist、
 * 结果通过 lambda 回传，方便单独预览和测试。
 */

/** 收到别人发来的配对请求：填 6 位 PIN 决定接受还是拒绝。 */
@Composable
internal fun IncomingPairRequestDialog(
    request: IncomingPairRequest,
    pinInput: String,
    onPinInputChange: (String) -> Unit,
    pinError: String?,
    onConfirm: (pin: String) -> Unit,
    onReject: () -> Unit
) {
    val context = LocalContext.current
    // 用原生 ClipboardManager 而不是已弃用的 LocalClipboardManager：
    // 新版 Compose Clipboard API 是 suspend 的，而这两处都是同步点击回调，
    // 为一次复制/粘贴引入协程作用域不划算。onClick 发生在前台且有焦点，
    // 不受 Android 10+ 后台剪贴板读取限制影响。
    val clipboardManager = remember(context) {
        context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    }
    val haptics = LocalHapticFeedback.current

    AlertDialog(
        onDismissRequest = { /* 必须显式接受或拒绝，不允许点外部关掉 */ },
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Security, contentDescription = null, tint = MuClipPrimary)
                Spacer(modifier = Modifier.width(8.dp))
                Text("收到配对请求", color = MuClipTextPrimary, fontWeight = FontWeight.Bold)
            }
        },
        text = {
            Column {
                Text(
                    text = "设备「${request.fromDeviceName}」(${request.clientIp}) 正在请求与此设备配对。",
                    color = MuClipTextSecondary,
                    fontSize = 14.sp
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "请输入对方屏幕上显示的 6 位随机 PIN 码：",
                    color = MuClipTextPrimary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = pinInput,
                    onValueChange = onPinInputChange,
                    placeholder = { Text("6位数字 PIN", color = MuClipTextSecondary) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    trailingIcon = {
                        IconButton(onClick = {
                            val clipText = clipboardManager.primaryClip
                                ?.takeIf { it.itemCount > 0 }
                                ?.getItemAt(0)
                                ?.coerceToText(context)
                                ?.toString()
                                ?.trim()
                                ?.filter { it.isDigit() }
                            if (!clipText.isNullOrEmpty()) {
                                onPinInputChange(clipText.take(6))
                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                Toast.makeText(context, "已粘贴剪贴板 PIN 码", Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(context, "剪贴板中未发现数字 PIN 码", Toast.LENGTH_SHORT).show()
                            }
                        }) {
                            Icon(
                                Icons.Default.ContentPaste,
                                contentDescription = "粘贴剪贴板 PIN 码",
                                tint = MuClipPrimary
                            )
                        }
                    },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MuClipPrimary,
                        unfocusedBorderColor = MuClipCardBorder,
                        focusedTextColor = MuClipTextPrimary,
                        unfocusedTextColor = MuClipTextPrimary
                    )
                )
                if (pinError != null) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(text = pinError, color = MuClipRed, fontSize = 12.sp)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(pinInput) },
                colors = ButtonDefaults.buttonColors(containerColor = MuClipPrimary, contentColor = MuClipOnPrimary)
            ) {
                Text("确认配对")
            }
        },
        dismissButton = {
            TextButton(onClick = onReject) {
                Text("拒绝", color = MuClipRed)
            }
        },
        containerColor = MuClipCard
    )
}

/** 主动发起配对：展示 6 位 PIN 并等待对端输入。 */
@Composable
internal fun OutboundPairingDialog(
    state: PairingSessionState,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    // 用原生 ClipboardManager 而不是已弃用的 LocalClipboardManager：
    // 新版 Compose Clipboard API 是 suspend 的，而这两处都是同步点击回调，
    // 为一次复制/粘贴引入协程作用域不划算。onClick 发生在前台且有焦点，
    // 不受 Android 10+ 后台剪贴板读取限制影响。
    val clipboardManager = remember(context) {
        context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    }
    val haptics = LocalHapticFeedback.current

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = if (state.success) "配对成功" else "正在发起配对",
                color = MuClipTextPrimary,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                if (state.success) {
                    Icon(
                        Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = MuClipGreen,
                        modifier = Modifier.size(48.dp)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        "已与「${state.targetPeer?.deviceName ?: "目标设备"}」建立端到端加密连接！",
                        color = MuClipTextPrimary,
                        textAlign = TextAlign.Center
                    )
                } else if (state.error != null) {
                    Icon(
                        Icons.Default.Error,
                        contentDescription = null,
                        tint = MuClipRed,
                        modifier = Modifier.size(48.dp)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(state.error, color = MuClipRed, textAlign = TextAlign.Center)
                } else {
                    Text(
                        text = "请在对方设备「${state.targetPeer?.deviceName ?: ""}」的弹窗中输入下方 6 位 PIN 码：",
                        color = MuClipTextSecondary,
                        fontSize = 13.sp,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(16.dp))

                    // 大号高对比 PIN 显示，点击即可直接复制到剪贴板
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MuClipSurface,
                        modifier = Modifier
                            .border(1.5.dp, MuClipPrimary, RoundedCornerShape(12.dp))
                            .clickable {
                                if (state.generatedPin.isNotEmpty()) {
                                    clipboardManager.setPrimaryClip(
                                        ClipData.newPlainText("MuClip 配对 PIN", state.generatedPin)
                                    )
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    Toast.makeText(context, "已复制配对 PIN 码: ${state.generatedPin}", Toast.LENGTH_SHORT).show()
                                }
                            }
                            .padding(horizontal = 20.dp, vertical = 12.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Text(
                                text = state.generatedPin.chunked(3).joinToString(" "),
                                color = MuClipPrimary,
                                fontSize = 32.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace,
                                letterSpacing = 4.sp
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Icon(
                                Icons.Default.ContentCopy,
                                contentDescription = "点击复制 PIN 码",
                                tint = MuClipPrimary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = "点击上方 PIN 码数字直接复制",
                            color = MuClipTextSecondary,
                            fontSize = 12.sp
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth(),
                        color = MuClipPrimary,
                        trackColor = MuClipSurface
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("等待对方输入确认中（60秒超时）...", color = MuClipTextSecondary, fontSize = 12.sp)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.buttonColors(containerColor = MuClipPrimary, contentColor = MuClipOnPrimary)
            ) {
                Text(if (state.success || state.error != null) "完成" else "取消")
            }
        },
        containerColor = MuClipCard
    )
}

/** 解除配对前的二次确认。 */
@Composable
internal fun UnpairConfirmDialog(
    device: PairedDevice,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Warning, contentDescription = null, tint = MuClipRed)
                Spacer(modifier = Modifier.width(8.dp))
                Text("解除设备配对", color = MuClipTextPrimary, fontWeight = FontWeight.Bold)
            }
        },
        text = {
            Column {
                Text(
                    text = "确定要解除与设备「${device.deviceName.ifEmpty { device.deviceId.take(8) }}」的配对吗？",
                    color = MuClipTextPrimary,
                    fontSize = 14.sp
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "解除后，双方将无法再互相同步剪贴板，单向同步将被核心安全机制阻断。如需重新同步，需重新发起 PIN 码配对。",
                    color = MuClipTextSecondary,
                    fontSize = 12.sp,
                    lineHeight = 16.sp
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(containerColor = MuClipRed, contentColor = Color.White)
            ) {
                Text("确认解除")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消", color = MuClipTextSecondary)
            }
        },
        containerColor = MuClipCard
    )
}

/** 添加端口映射 / 静态节点（FRP、DDNS、Tailscale 等）。 */
@Composable
internal fun AddCustomPeerDialog(
    endpointText: String,
    onEndpointChange: (String) -> Unit,
    onConfirm: (endpoint: String) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加端口映射节点", color = MuClipTextPrimary, fontWeight = FontWeight.Bold) },
        text = {
            Column {
                Text(
                    "输入远程节点的 IP:Port、纯 IP（默认 42424）或 FRP / DDNS 域名:端口。添加后将立即显示在「待配对新设备」列表中并自动探测对端设备信息：",
                    color = MuClipTextSecondary,
                    fontSize = 13.sp,
                    lineHeight = 18.sp
                )
                Spacer(modifier = Modifier.height(10.dp))
                OutlinedTextField(
                    value = endpointText,
                    onValueChange = onEndpointChange,
                    placeholder = {
                        Text(
                            "例如 frp.example.com:55555 或 192.168.1.100:42424",
                            color = MuClipTextSecondary,
                            fontSize = 12.sp
                        )
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MuClipPrimary,
                        unfocusedBorderColor = MuClipCardBorder,
                        focusedTextColor = MuClipTextPrimary,
                        unfocusedTextColor = MuClipTextPrimary
                    )
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(endpointText.trim()) },
                colors = ButtonDefaults.buttonColors(containerColor = MuClipPrimary, contentColor = MuClipOnPrimary)
            ) {
                Text("添加")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消", color = MuClipTextSecondary)
            }
        },
        containerColor = MuClipCard
    )
}
