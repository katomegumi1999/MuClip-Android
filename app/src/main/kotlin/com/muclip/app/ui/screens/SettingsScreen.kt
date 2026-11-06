package com.muclip.app.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.Color
import com.muclip.app.MuClipApplication
import com.muclip.app.ui.components.LocalMuBottomInset
import com.muclip.app.ui.theme.*
import com.muclip.app.viewmodel.ClipboardViewModel
import com.muclip.app.viewmodel.SyncViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun SettingsScreen(
    syncViewModel: SyncViewModel,
    clipboardViewModel: ClipboardViewModel
) {
    val context = LocalContext.current
    val serviceEnabled by syncViewModel.serviceEnabled.collectAsState()
    val autoReadClipboard by syncViewModel.autoReadClipboard.collectAsState()
    val autoCopyRemote by syncViewModel.autoCopyRemote.collectAsState()
    val currentPort by syncViewModel.syncPort.collectAsState()
    val deviceName by syncViewModel.deviceName.collectAsState()

    val aggressiveBackgroundClipboard by syncViewModel.aggressiveBackgroundClipboard.collectAsState()
    val isAccessibilityEnabled by syncViewModel.isAccessibilityEnabled.collectAsState()

    var showEditNameDialog by remember { mutableStateOf(false) }
    var editNameText by remember { mutableStateOf("") }
    var showPortDialog by remember { mutableStateOf(false) }
    var editPortText by remember { mutableStateOf("") }
    var showClearHistoryDialog by remember { mutableStateOf(false) }

    // ---- 本地库加密状态 & .mubc 备份 ----
    val scope = rememberCoroutineScope()
    var backupBusy by remember { mutableStateOf(false) }
    var showExportPwdDialog by remember { mutableStateOf(false) }
    var exportPwd by remember { mutableStateOf("") }
    var pendingImportUri by remember { mutableStateOf<Uri?>(null) }
    var showImportDialog by remember { mutableStateOf(false) }
    var importPwd by remember { mutableStateOf("") }
    var importReplaceAll by remember { mutableStateOf(false) }
    // 加密状态在进程生命周期内不会变，读一次即可
    val dbEncrypted = remember {
        runCatching {
            MuClipApplication.coreOrNull()?.isEncrypted() ?: false
        }.getOrDefault(false)
    }

    fun coreOrNull(): com.muclip.core.MuClipCore? =
        if (MuClipApplication.isCoreInitialized()) MuClipApplication.core else null

    // 导出：先弹口令框，确认后再拉起「保存到……」系统面板
    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri: Uri? ->
        val target = uri
        val pwd = exportPwd
        exportPwd = ""
        val coreRef = coreOrNull()
        if (target == null || coreRef == null) return@rememberLauncherForActivityResult
        backupBusy = true
        scope.launch {
            val msg = withContext(Dispatchers.IO) {
                try {
                    // Rust 侧只认文件路径，先导到私有缓存再转存到 SAF 选中的位置
                    val tmp = File(context.cacheDir, "muclip_export_tmp.mubc")
                    if (tmp.exists()) tmp.delete()
                    val summary = coreRef.exportBackup(tmp.absolutePath, pwd)
                    context.contentResolver.openOutputStream(target)?.use { out ->
                        tmp.inputStream().use { it.copyTo(out) }
                    } ?: throw IllegalStateException("无法写入所选位置")
                    val sizeKb = (tmp.length() / 1024).coerceAtLeast(1)
                    tmp.delete()
                    "已导出 ${sizeKb}KB：历史 ${summary.historyCount} 条 / 短语 ${summary.phraseCount} 条"
                } catch (e: Throwable) {
                    e.message ?: "导出失败"
                }
            }
            backupBusy = false
            Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
        }
    }

    // 导入：选中文件后弹口令 + 是否覆盖
    val importPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            pendingImportUri = uri
            importPwd = ""
            importReplaceAll = false
            showImportDialog = true
        }
    }

    fun runImport(uri: Uri, pwd: String, replaceAll: Boolean) {
        val coreRef = coreOrNull() ?: run {
            Toast.makeText(context, "核心未初始化，无法导入", Toast.LENGTH_SHORT).show()
            return
        }
        backupBusy = true
        scope.launch {
            val msg = withContext(Dispatchers.IO) {
                try {
                    val tmp = File(context.cacheDir, "muclip_import_tmp.mubc")
                    if (tmp.exists()) tmp.delete()
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        tmp.outputStream().use { input.copyTo(it) }
                    } ?: throw IllegalStateException("无法读取所选文件")
                    val summary = coreRef.importBackup(tmp.absolutePath, pwd, replaceAll)
                    tmp.delete()
                    "已导入：历史 ${summary.historyCount} 条 / 短语 ${summary.phraseCount} 条"
                } catch (e: Throwable) {
                    e.message ?: "导入失败"
                }
            }
            backupBusy = false
            Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
            clipboardViewModel.loadData()
        }
    }

    LaunchedEffect(Unit) {
        syncViewModel.refreshAccessibilityStatus(context)
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
        // 1. Service & Basic Sync Automation
        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .border(1.dp, MuClipCardBorder, RoundedCornerShape(16.dp)),
                colors = CardDefaults.cardColors(containerColor = MuClipCard)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "前台服务与同步基础",
                        color = MuClipTextPrimary,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(14.dp))

                    // Foreground Service Switch
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("常驻通知栏前台服务", color = MuClipTextPrimary, fontSize = 14.sp)
                            Text("保持后台接收电脑推送与 P2P 加密连接", color = MuClipTextSecondary, fontSize = 12.sp)
                        }
                        Switch(
                            checked = serviceEnabled,
                            onCheckedChange = { syncViewModel.toggleForegroundService(context, it) },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = MuClipBackground,
                                checkedTrackColor = MuClipPrimary
                            )
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                    HorizontalDivider(color = MuClipCardBorder, thickness = 0.5.dp)
                    Spacer(modifier = Modifier.height(12.dp))

                    // Automatic Clipboard Reading Switch
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("自动监听并同步本地剪贴板", color = MuClipTextPrimary, fontSize = 14.sp)
                            Text("打开应用或前台复制时，自动广播给已配对电脑", color = MuClipTextSecondary, fontSize = 12.sp)
                        }
                        Switch(
                            checked = autoReadClipboard,
                            onCheckedChange = { syncViewModel.toggleAutoReadClipboard(it) },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = MuClipBackground,
                                checkedTrackColor = MuClipPrimary
                            )
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                    HorizontalDivider(color = MuClipCardBorder, thickness = 0.5.dp)
                    Spacer(modifier = Modifier.height(12.dp))

                    // Auto Copy Remote Content Switch
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("接收远程内容自动复制", color = MuClipTextPrimary, fontSize = 14.sp)
                            Text("收到电脑端推送时自动准备并写入剪贴板", color = MuClipTextSecondary, fontSize = 12.sp)
                        }
                        Switch(
                            checked = autoCopyRemote,
                            onCheckedChange = { syncViewModel.toggleAutoCopyRemote(it) },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = MuClipBackground,
                                checkedTrackColor = MuClipPrimary
                            )
                        )
                    }
                }
            }
        }

        // 2. Android Clipboard Policy: Gentle (Default) vs Aggressive
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
                            Icon(Icons.Default.ContentPaste, contentDescription = null, tint = MuClipPrimary, modifier = Modifier.size(22.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Android 剪贴板写入策略",
                                color = MuClipTextPrimary,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = if (!aggressiveBackgroundClipboard) MuClipGreen.copy(alpha = 0.15f) else MuClipPrimary.copy(alpha = 0.15f)
                        ) {
                            Text(
                                text = if (!aggressiveBackgroundClipboard) "默认温和模式 (推荐)" else "激进后台模式",
                                color = if (!aggressiveBackgroundClipboard) MuClipGreen else MuClipPrimary,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = if (!aggressiveBackgroundClipboard) {
                            "【当前为温和模式】：Android 10+ 出于隐私规范，普通应用在后台无法直接写入系统剪贴板。收到电脑推送时，MuClip 将实时保存在本地历史并显示通知栏提示；当您切换打开应用或停留在界面时，最新内容将毫秒级全自动写入系统剪贴板，平稳且不打扰日常使用。"
                        } else {
                            "【当前为激进模式】：在后台收到电脑推送时，将强制拉起半透明微型窗口抢占瞬时焦点强行写入系统剪贴板。注意：若正在全屏游戏或视频，可能会产生轻微焦点打断。"
                        },
                        color = MuClipTextSecondary,
                        fontSize = 12.sp,
                        lineHeight = 18.sp
                    )

                    Spacer(modifier = Modifier.height(14.dp))
                    HorizontalDivider(color = MuClipCardBorder, thickness = 0.5.dp)
                    Spacer(modifier = Modifier.height(14.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("后台激进弹窗强写剪贴板", color = MuClipTextPrimary, fontSize = 14.sp)
                            Text("默认关闭；仅在无法前台打开软件但必须后台即时写入时开启", color = MuClipTextSecondary, fontSize = 12.sp)
                        }
                        Switch(
                            checked = aggressiveBackgroundClipboard,
                            onCheckedChange = { syncViewModel.toggleAggressiveBackgroundClipboard(it) },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = MuClipBackground,
                                checkedTrackColor = MuClipPrimary
                            )
                        )
                    }
                }
            }
        }

        // 3. Accessibility Service (Advanced / Optional Background Auto-Read)
        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .border(
                        1.dp,
                        if (isAccessibilityEnabled) MuClipGreen.copy(alpha = 0.5f) else MuClipCardBorder,
                        RoundedCornerShape(16.dp)
                    ),
                colors = CardDefaults.cardColors(containerColor = MuClipCard)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.Accessibility,
                                contentDescription = null,
                                tint = if (isAccessibilityEnabled) MuClipGreen else MuClipTextSecondary,
                                modifier = Modifier.size(22.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "高级：系统无障碍后台监听 (可选)",
                                color = MuClipTextPrimary,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = if (isAccessibilityEnabled) MuClipGreen.copy(alpha = 0.15f) else MuClipSurface
                        ) {
                            Text(
                                text = if (isAccessibilityEnabled) "已开启无障碍" else "未开启 (默认方案)",
                                color = if (isAccessibilityEnabled) MuClipGreen else MuClipTextSecondary,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = if (isAccessibilityEnabled) {
                            "已激活系统级无障碍监听服务。在任何第三方 App（如微信、小红书、浏览器等）复制文字时，服务将在后台全自动读取并同步推送到已配对电脑，无需切回 MuClip。"
                        } else {
                            "属于可选高级特性。未开启时，只要您切回 MuClip 即可全自动同步。若您希望在其他任何应用复制文字时后台静默同步到电脑，可点击下方按钮在系统设置中启用 MuClip 的无障碍权限。"
                        },
                        color = MuClipTextSecondary,
                        fontSize = 12.sp,
                        lineHeight = 18.sp
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    OutlinedButton(
                        onClick = { syncViewModel.openAccessibilitySettings(context) },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = if (isAccessibilityEnabled) MuClipGreen else MuClipPrimary
                        )
                    ) {
                        Icon(
                            if (isAccessibilityEnabled) Icons.Default.Settings else Icons.Default.Accessibility,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (isAccessibilityEnabled) "管理系统无障碍设置" else "前往开启系统无障碍服务 (可选)",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }

        // 2. Local Device Configuration Section
        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .border(1.dp, MuClipCardBorder, RoundedCornerShape(16.dp)),
                colors = CardDefaults.cardColors(containerColor = MuClipCard)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "本机设备配置",
                        color = MuClipTextPrimary,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(14.dp))

                    // Device Name Row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("设备名称", color = MuClipTextPrimary, fontSize = 14.sp)
                            Text(deviceName, color = MuClipPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        }
                        OutlinedButton(
                            onClick = {
                                editNameText = deviceName
                                showEditNameDialog = true
                            },
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = MuClipPrimary)
                        ) {
                            Text("修改", fontSize = 12.sp)
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                    HorizontalDivider(color = MuClipCardBorder, thickness = 0.5.dp)
                    Spacer(modifier = Modifier.height(12.dp))

                    // Device ID Row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("设备 ID", color = MuClipTextPrimary, fontSize = 14.sp)
                            Text(MuClipApplication.preferences.deviceId, color = MuClipTextSecondary, fontSize = 12.sp)
                        }
                        IconButton(onClick = {
                            val cb = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            cb.setPrimaryClip(ClipData.newPlainText("Device ID", MuClipApplication.preferences.deviceId))
                            Toast.makeText(context, "设备 ID 已复制", Toast.LENGTH_SHORT).show()
                        }) {
                            Icon(Icons.Default.ContentCopy, contentDescription = "Copy", tint = MuClipTextSecondary, modifier = Modifier.size(18.dp))
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                    HorizontalDivider(color = MuClipCardBorder, thickness = 0.5.dp)
                    Spacer(modifier = Modifier.height(12.dp))

                    // Listen Port Row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("TCP 同步监听端口", color = MuClipTextPrimary, fontSize = 14.sp)
                            Text("当前: $currentPort (可用于内网/公网映射)", color = MuClipTextSecondary, fontSize = 12.sp)
                        }
                        OutlinedButton(
                            onClick = {
                                editPortText = currentPort.toString()
                                showPortDialog = true
                            },
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = MuClipPrimary)
                        ) {
                            Text("修改", fontSize = 12.sp)
                        }
                    }
                }
            }
        }

        // 3. Local Database Encryption & Encrypted Backup (.mubc)
        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .border(1.dp, MuClipCardBorder, RoundedCornerShape(16.dp)),
                colors = CardDefaults.cardColors(containerColor = MuClipCard)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "加密备份 (.mubc)",
                        color = MuClipTextPrimary,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = if (dbEncrypted) "本地库: SQLCipher 加密生效" else "本地库: 明文（native 库为明文构建）",
                        color = if (dbEncrypted) MuClipPrimary else MuClipTextSecondary,
                        fontSize = 12.sp
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "备份文件先压缩再加密，口令与本地库密钥相互独立，拷到别处没口令打不开。",
                        color = MuClipTextSecondary,
                        fontSize = 12.sp
                    )

                    Spacer(modifier = Modifier.height(14.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                if (backupBusy) return@OutlinedButton
                                exportPwd = ""
                                showExportPwdDialog = true
                            },
                            enabled = !backupBusy,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = MuClipPrimary)
                        ) {
                            Text("导出备份", fontSize = 12.sp)
                        }
                        OutlinedButton(
                            onClick = {
                                if (backupBusy) return@OutlinedButton
                                importPicker.launch(arrayOf("*/*"))
                            },
                            enabled = !backupBusy,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = MuClipPrimary)
                        ) {
                            Text("从备份还原", fontSize = 12.sp)
                        }
                    }

                    if (backupBusy) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = MuClipPrimary
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("处理中…", color = MuClipTextSecondary, fontSize = 12.sp)
                        }
                    }
                }
            }
        }

        // 4. Storage & Maintenance Section
        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .border(1.dp, MuClipCardBorder, RoundedCornerShape(16.dp)),
                colors = CardDefaults.cardColors(containerColor = MuClipCard)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "存储与数据维护",
                        color = MuClipTextPrimary,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(14.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("清理 7 天前旧记录", color = MuClipTextPrimary, fontSize = 14.sp)
                            Text("释放 SQLite 数据库空间", color = MuClipTextSecondary, fontSize = 12.sp)
                        }
                        OutlinedButton(
                            onClick = {
                                val count = MuClipApplication.coreOrNull()?.cleanupHistory(7) ?: 0
                                Toast.makeText(context, "已清理 $count 条过期记录", Toast.LENGTH_SHORT).show()
                                clipboardViewModel.loadData()
                            },
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = MuClipPrimary)
                        ) {
                            Text("立即清理", fontSize = 12.sp)
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                    HorizontalDivider(color = MuClipCardBorder, thickness = 0.5.dp)
                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("清空全部剪贴板历史", color = MuClipRed, fontSize = 14.sp)
                            Text("此操作不可恢复", color = MuClipTextSecondary, fontSize = 12.sp)
                        }
                        Button(
                            onClick = { showClearHistoryDialog = true },
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = MuClipRed, contentColor = Color.White)
                        ) {
                            Text("清空历史", fontSize = 12.sp)
                        }
                    }
                }
            }
        }

        // 5. About & Core Engine Status
        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .border(1.dp, MuClipCardBorder, RoundedCornerShape(16.dp)),
                colors = CardDefaults.cardColors(containerColor = MuClipCard)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "关于 MuClip",
                        color = MuClipTextPrimary,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Text("版本: v0.1.0", color = MuClipTextSecondary, fontSize = 13.sp)
                    Text("Rust 核心: 0.1.0 (Zero-Copy P2P E2EE)", color = MuClipTextSecondary, fontSize = 13.sp)
                    Text("内存池: 64MB Off-Heap Direct Buffer Pool", color = MuClipTextSecondary, fontSize = 13.sp)
                    Text("网络发现: Multi-Interface UDP & mDNS Beacon", color = MuClipTextSecondary, fontSize = 13.sp)
                }
            }
        }
    }

    // 修改设备名称
    if (showEditNameDialog) {
        EditDeviceNameDialog(
            value = editNameText,
            onValueChange = { editNameText = it },
            onConfirm = {
                syncViewModel.updateDeviceName(editNameText)
                showEditNameDialog = false
            },
            onDismiss = { showEditNameDialog = false }
        )
    }

    // 修改监听端口（校验在调用方，对话框只负责收集文本）
    if (showPortDialog) {
        EditListenPortDialog(
            value = editPortText,
            onValueChange = { editPortText = it.filter { c -> c.isDigit() } },
            onConfirm = { portText ->
                val port = portText.toIntOrNull()
                if (port != null && port in 1024..65535) {
                    syncViewModel.updateSyncPort(port)
                    showPortDialog = false
                } else {
                    Toast.makeText(context, "端口必须在 1024~65535 范围内", Toast.LENGTH_SHORT).show()
                }
            },
            onDismiss = { showPortDialog = false }
        )
    }

    // 导出备份：先设口令
    if (showExportPwdDialog) {
        ExportBackupPasswordDialog(
            password = exportPwd,
            onPasswordChange = { exportPwd = it },
            onConfirm = {
                if (exportPwd.length < 6) {
                    Toast.makeText(context, "口令至少 6 位", Toast.LENGTH_SHORT).show()
                } else {
                    showExportPwdDialog = false
                    val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmm", java.util.Locale.US)
                        .format(java.util.Date())
                    exportLauncher.launch("muclip-$stamp.mubc")
                }
            },
            onDismiss = { showExportPwdDialog = false }
        )
    }

    // 从备份还原：口令 + 合并/覆盖
    if (showImportDialog) {
        ImportBackupDialog(
            password = importPwd,
            onPasswordChange = { importPwd = it },
            replaceAll = importReplaceAll,
            onReplaceAllChange = { importReplaceAll = it },
            onConfirm = {
                val uri = pendingImportUri
                if (uri == null || importPwd.isBlank()) {
                    Toast.makeText(context, "请输入备份口令", Toast.LENGTH_SHORT).show()
                } else {
                    showImportDialog = false
                    pendingImportUri = null
                    runImport(uri, importPwd, importReplaceAll)
                }
            },
            onDismiss = {
                showImportDialog = false
                pendingImportUri = null
            }
        )
    }

    // 清空全部历史
    if (showClearHistoryDialog) {
        ClearHistoryConfirmDialog(
            onConfirm = {
                clipboardViewModel.clearAllHistory()
                showClearHistoryDialog = false
                Toast.makeText(context, "已清空剪贴板历史", Toast.LENGTH_SHORT).show()
            },
            onDismiss = { showClearHistoryDialog = false }
        )
    }
}
