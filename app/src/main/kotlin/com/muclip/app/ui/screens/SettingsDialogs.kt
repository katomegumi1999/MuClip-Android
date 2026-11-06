package com.muclip.app.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.muclip.app.ui.theme.MuClipCard
import com.muclip.app.ui.theme.MuClipBackground
import com.muclip.app.ui.theme.MuClipOnPrimary
import com.muclip.app.ui.theme.MuClipPrimary
import com.muclip.app.ui.theme.MuClipRed
import com.muclip.app.ui.theme.MuClipTextPrimary
import com.muclip.app.ui.theme.MuClipTextSecondary

/**
 * 设置页的五个对话框。
 *
 * 从 889 行的 `SettingsScreen` 里搬出来。这些对话框本来就能靠参数完全驱动，
 * 跟设置项主体没有共享状态，放在一起只是让主文件更难读。
 *
 * 约定：**对话框只负责展示与收集输入**，校验和副作用（写配置、导出、清空）
 * 都通过 lambda 交回调用方，这样每个对话框都能独立预览。
 */

/** 修改设备名称。 */
@Composable
internal fun EditDeviceNameDialog(
    value: String,
    onValueChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("修改设备名称", color = MuClipTextPrimary) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(containerColor = MuClipPrimary, contentColor = MuClipOnPrimary)
            ) {
                Text("保存")
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

/** 修改 TCP 监听端口。校验（1024~65535）在调用方做。 */
@Composable
internal fun EditListenPortDialog(
    value: String,
    onValueChange: (String) -> Unit,
    onConfirm: (portText: String) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("修改 TCP 监听端口", color = MuClipTextPrimary) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(value) },
                colors = ButtonDefaults.buttonColors(containerColor = MuClipPrimary, contentColor = MuClipOnPrimary)
            ) {
                Text("保存并重启引擎")
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

/** 导出备份前设置加密口令。长度校验在调用方做。 */
@Composable
internal fun ExportBackupPasswordDialog(
    password: String,
    onPasswordChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("设置备份口令", color = MuClipTextPrimary) },
        text = {
            Column {
                Text(
                    text = "口令用于加密备份文件，与本地库密钥无关。忘记口令将无法恢复该备份。",
                    color = MuClipTextSecondary,
                    fontSize = 12.sp
                )
                Spacer(modifier = Modifier.height(10.dp))
                OutlinedTextField(
                    value = password,
                    onValueChange = onPasswordChange,
                    singleLine = true,
                    placeholder = { Text("至少 6 位，建议更长", fontSize = 12.sp) },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(containerColor = MuClipPrimary, contentColor = MuClipOnPrimary)
            ) {
                Text("选择保存位置")
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

/** 从备份还原：口令 + 合并/覆盖模式。 */
@Composable
internal fun ImportBackupDialog(
    password: String,
    onPasswordChange: (String) -> Unit,
    replaceAll: Boolean,
    onReplaceAllChange: (Boolean) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("从备份还原", color = MuClipTextPrimary) },
        text = {
            Column {
                Text(
                    text = if (replaceAll)
                        "将清空本地现有历史与短语，完全覆盖为备份内容。"
                    else
                        "将备份内容合并到本地，重复条目自动跳过。",
                    color = if (replaceAll) MuClipRed else MuClipTextSecondary,
                    fontSize = 12.sp
                )
                Spacer(modifier = Modifier.height(10.dp))
                OutlinedTextField(
                    value = password,
                    onValueChange = onPasswordChange,
                    singleLine = true,
                    placeholder = { Text("备份口令", fontSize = 12.sp) },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Checkbox(
                        checked = replaceAll,
                        onCheckedChange = onReplaceAllChange
                    )
                    Text("完全覆盖（先清空本地数据）", color = MuClipTextPrimary, fontSize = 12.sp)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (replaceAll) MuClipRed else MuClipPrimary,
                    contentColor = if (replaceAll) Color.White else MuClipBackground
                )
            ) {
                Text(if (replaceAll) "覆盖导入" else "合并导入")
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

/** 清空全部剪贴板历史前的确认。 */
@Composable
internal fun ClearHistoryConfirmDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("清空全部剪贴板历史？", color = MuClipTextPrimary) },
        text = { Text("此操作将永久删除本地记录的所有历史内容。", color = MuClipTextSecondary) },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(containerColor = MuClipRed, contentColor = Color.White)
            ) {
                Text("确认清空")
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
