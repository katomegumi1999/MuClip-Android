package com.muclip.app.ui.screens

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.muclip.app.ui.components.LocalMuBottomInset
import com.muclip.app.ui.theme.*
import com.muclip.app.viewmodel.FileTransferViewModel
import com.muclip.app.viewmodel.PairedDevice
import com.muclip.app.viewmodel.SyncViewModel

@Composable
fun FilesScreen(
    fileViewModel: FileTransferViewModel,
    syncViewModel: SyncViewModel
) {
    val context = LocalContext.current
    val pairedDevices by syncViewModel.pairedDevicesList.collectAsState()
    val fileTasks by fileViewModel.fileTasks.collectAsState()

    val onlineDevices = remember(pairedDevices) {
        pairedDevices.filter { it.isOnline && it.ip.isNotEmpty() }
    }

    var selectedDevice by remember { mutableStateOf<PairedDevice?>(null) }

    LaunchedEffect(onlineDevices) {
        if (selectedDevice == null || !onlineDevices.contains(selectedDevice)) {
            selectedDevice = onlineDevices.firstOrNull()
        }
    }

    // Android Native SAF (Storage Access Framework) System Document Picker Launcher
    val safPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri> ->
        if (uris.isNotEmpty()) {
            val target = selectedDevice
            if (target != null) {
                val peerAddr = "${target.ip}:${target.syncPort}|${target.deviceId}"
                uris.forEach { uri ->
                    try {
                        context.contentResolver.takePersistableUriPermission(
                            uri,
                            android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                        )
                    } catch (_: Exception) {}

                    fileViewModel.sendFile(context, peerAddr, target.deviceName, uri) { started, error ->
                        if (!started) {
                            Toast.makeText(context, error ?: "发送失败", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                Toast.makeText(context, "已启动 ${uris.size} 个文件的极速传输", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(context, "请先选择在线的配对设备", Toast.LENGTH_SHORT).show()
            }
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MuClipBackground),
        contentPadding = PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = 16.dp,
            bottom = 16.dp + LocalMuBottomInset.current
        ),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // 1. Target Peer Device Selection
        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .border(1.dp, MuClipCardBorder, RoundedCornerShape(16.dp)),
                colors = CardDefaults.cardColors(containerColor = MuClipCard)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Devices, contentDescription = null, tint = MuClipPrimary, modifier = Modifier.size(22.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "目标传输设备",
                                color = MuClipTextPrimary,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = if (onlineDevices.isNotEmpty()) MuClipGreen.copy(alpha = 0.15f) else MuClipSurface
                        ) {
                            Text(
                                text = if (onlineDevices.isNotEmpty()) "${onlineDevices.size} 台设备就绪" else "暂无在线配对设备",
                                color = if (onlineDevices.isNotEmpty()) MuClipGreen else MuClipTextSecondary,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    if (onlineDevices.isEmpty()) {
                        Text(
                            text = "未检测到在线的已配对设备。请确保电脑端或对方手机处于同一局域网并已完成配对。",
                            color = MuClipTextSecondary,
                            fontSize = 12.sp,
                            lineHeight = 16.sp
                        )
                    } else {
                        onlineDevices.forEach { dev ->
                            val isChosen = selectedDevice?.deviceId == dev.deviceId
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = if (isChosen) MuClipPrimary.copy(alpha = 0.15f) else MuClipSurface,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp)
                                    .border(
                                        1.dp,
                                        if (isChosen) MuClipPrimary else MuClipCardBorder,
                                        RoundedCornerShape(10.dp)
                                    )
                                    .clickable { selectedDevice = dev }
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(12.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            imageVector = if (dev.deviceType.contains("Windows", ignoreCase = true)) Icons.Default.Computer else Icons.Default.PhoneAndroid,
                                            contentDescription = null,
                                            tint = if (isChosen) MuClipPrimary else MuClipTextSecondary,
                                            modifier = Modifier.size(24.dp)
                                        )
                                        Spacer(modifier = Modifier.width(10.dp))
                                        Column {
                                            Text(
                                                text = dev.deviceName.ifEmpty { "设备 (${dev.deviceId.take(6)})" },
                                                color = MuClipTextPrimary,
                                                fontSize = 14.sp,
                                                fontWeight = FontWeight.SemiBold
                                            )
                                            Text(
                                                text = "${dev.ip}:${dev.syncPort}",
                                                color = MuClipTextSecondary,
                                                fontSize = 11.sp
                                            )
                                        }
                                    }

                                    RadioButton(
                                        selected = isChosen,
                                        onClick = { selectedDevice = dev },
                                        colors = RadioButtonDefaults.colors(
                                            selectedColor = MuClipPrimary,
                                            unselectedColor = MuClipTextSecondary
                                        )
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // 2. Big Send File Action Card
        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .border(1.5.dp, MuClipPrimary.copy(alpha = 0.8f), RoundedCornerShape(16.dp))
                    .clickable(enabled = onlineDevices.isNotEmpty()) {
                        safPickerLauncher.launch(arrayOf("*/*"))
                    },
                colors = CardDefaults.cardColors(containerColor = MuClipSurface)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = MuClipPrimary.copy(alpha = 0.2f),
                        modifier = Modifier.size(56.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.Default.UploadFile,
                                contentDescription = null,
                                tint = if (onlineDevices.isNotEmpty()) MuClipPrimary else MuClipTextSecondary,
                                modifier = Modifier.size(32.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))
                    Text(
                        text = if (onlineDevices.isNotEmpty()) "选择文件并极速发送 (系统 SAF)" else "请先连接配对设备",
                        color = if (onlineDevices.isNotEmpty()) MuClipTextPrimary else MuClipTextSecondary,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "支持原生 SAF 文档选择、支持多选批量发送，局域网跑满物理线速",
                        color = MuClipTextSecondary,
                        fontSize = 12.sp,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }

        // 3. File Transfer Tasks List Header
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.SwapVert, contentDescription = null, tint = MuClipPrimary, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "传输记录 (${fileTasks.size})",
                        color = MuClipTextPrimary,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                if (fileTasks.isNotEmpty()) {
                    Text(
                        text = "自动保存到「下载」目录",
                        color = MuClipTextSecondary,
                        fontSize = 11.sp
                    )
                }
            }
        }

        // 4. File Transfer Task Cards
        if (fileTasks.isEmpty()) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MuClipSurface),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(Icons.Default.FolderOpen, contentDescription = null, tint = MuClipTextSecondary, modifier = Modifier.size(36.dp))
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("暂无传输记录", color = MuClipTextSecondary, fontSize = 14.sp)
                    }
                }
            }
        } else {
            items(fileTasks, key = { it.transferId }) { task ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .border(
                            1.dp,
                            if (!task.isCompleted) MuClipPrimary.copy(alpha = 0.6f) else MuClipCardBorder,
                            RoundedCornerShape(14.dp)
                        ),
                    colors = CardDefaults.cardColors(containerColor = MuClipCard)
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                modifier = Modifier.weight(1f),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Surface(
                                    shape = RoundedCornerShape(10.dp),
                                    color = if (task.isSend) MuClipPrimary.copy(alpha = 0.15f) else MuClipGreen.copy(alpha = 0.15f),
                                    modifier = Modifier.size(40.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            imageVector = if (task.isSend) Icons.Default.ArrowUpward else Icons.Default.ArrowDownward,
                                            contentDescription = null,
                                            tint = if (task.isSend) MuClipPrimary else MuClipGreen,
                                            modifier = Modifier.size(22.dp)
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.width(12.dp))

                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = task.fileName,
                                        color = MuClipTextPrimary,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = if (task.isSend) "发送至「${task.peerName}」· ${task.sizeFormatted}" else "来自「${task.peerName}」· ${task.sizeFormatted}",
                                        color = MuClipTextSecondary,
                                        fontSize = 11.sp
                                    )
                                }
                            }

                            // Status Tag
                            if (task.isCompleted) {
                                if (task.isSuccess) {
                                    Surface(
                                        shape = RoundedCornerShape(6.dp),
                                        color = MuClipGreen.copy(alpha = 0.15f)
                                    ) {
                                        Text(
                                            text = "已完成",
                                            color = MuClipGreen,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                        )
                                    }
                                } else {
                                    Surface(
                                        shape = RoundedCornerShape(6.dp),
                                        color = MuClipRed.copy(alpha = 0.15f)
                                    ) {
                                        Text(
                                            text = "失败",
                                            color = MuClipRed,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                        )
                                    }
                                }
                            } else {
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = MuClipPrimary.copy(alpha = 0.15f)
                                ) {
                                    Text(
                                        text = task.speedFormatted,
                                        color = MuClipPrimary,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            }
                        }

                        // Real-time Progress Bar
                        if (!task.isCompleted) {
                            Spacer(modifier = Modifier.height(10.dp))
                            LinearProgressIndicator(
                                progress = { task.progress },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(6.dp)
                                    .clip(RoundedCornerShape(3.dp)),
                                color = MuClipPrimary,
                                trackColor = MuClipSurface
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "${(task.progress * 100).toInt()}% (${task.bytesTransferred / (1024 * 1024)}MB / ${task.fileSize / (1024 * 1024)}MB)",
                                    color = MuClipTextSecondary,
                                    fontSize = 11.sp
                                )

                                Text(
                                    text = "取消传输",
                                    color = MuClipRed,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.clickable { fileViewModel.cancelTransfer(task.transferId) }
                                )
                            }
                        } else {
                            if (task.errorMsg != null) {
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(text = "错误: ${task.errorMsg}", color = MuClipRed, fontSize = 11.sp)
                            } else if (!task.isSend && task.filePath.isNotEmpty()) {
                                Spacer(modifier = Modifier.height(8.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.End
                                ) {
                                    OutlinedButton(
                                        onClick = { fileViewModel.openFile(context, task.filePath) },
                                        shape = RoundedCornerShape(8.dp),
                                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MuClipPrimary)
                                    ) {
                                        Icon(Icons.Default.FileOpen, contentDescription = null, modifier = Modifier.size(14.dp))
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("打开文件", fontSize = 12.sp)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
