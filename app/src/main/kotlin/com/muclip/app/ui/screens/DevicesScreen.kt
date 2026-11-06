package com.muclip.app.ui.screens

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.repeatOnLifecycle
import com.muclip.app.ui.components.LocalMuBottomInset
import com.muclip.app.ui.theme.*
import com.muclip.app.viewmodel.SyncViewModel
import com.muclip.core.PeerInfo

@Composable
fun DevicesScreen(viewModel: SyncViewModel) {
    val context = LocalContext.current
    val pairedDevices by viewModel.pairedDevicesList.collectAsState()
    val unpairedPeers by viewModel.unpairedDiscoveredPeers.collectAsState()
    val isScanning by viewModel.isScanning.collectAsState()
    val autoDiscovery by viewModel.autoDiscovery.collectAsState()
    val customPeers by viewModel.customPeers.collectAsState()
    val isTestingPeer by viewModel.isTestingPeer.collectAsState()
    val outboundPairing by viewModel.outboundPairing.collectAsState()
    val incomingPairRequest by viewModel.incomingPairRequest.collectAsState()

    var showManualAddDialog by remember { mutableStateOf(false) }
    var manualEndpointText by remember { mutableStateOf("") }
    var incomingPinInput by remember { mutableStateOf("") }
    var incomingPinError by remember { mutableStateOf<String?>(null) }
    var unpairTargetDevice by remember { mutableStateOf<com.muclip.app.viewmodel.PairedDevice?>(null) }

    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner, autoDiscovery) {
        if (!autoDiscovery) return@LaunchedEffect
        lifecycleOwner.lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.RESUMED) {
            viewModel.triggerSilentForegroundScan()
        }
    }

    // 收到对方发起的配对请求：填 6 位 PIN
    if (incomingPairRequest != null) {
        val req = incomingPairRequest!!
        IncomingPairRequestDialog(
            request = req,
            pinInput = incomingPinInput,
            onPinInputChange = { input ->
                if (input.length <= 6) incomingPinInput = input.filter { c -> c.isDigit() }
            },
            pinError = incomingPinError,
            onConfirm = { pin ->
                if (pin.length != 6) {
                    incomingPinError = "请输入完整的 6 位数字 PIN"
                } else {
                    viewModel.respondToPairRequest(req.requestId, pin, accept = true) { success, err ->
                        if (success) {
                            Toast.makeText(context, "配对成功！", Toast.LENGTH_SHORT).show()
                            incomingPinInput = ""
                            incomingPinError = null
                        } else {
                            if (viewModel.incomingPairRequest.value == null) {
                                Toast.makeText(context, err ?: "配对失败", Toast.LENGTH_LONG).show()
                                incomingPinInput = ""
                                incomingPinError = null
                            } else {
                                incomingPinError = err ?: "配对失败"
                            }
                        }
                    }
                }
            },
            onReject = {
                viewModel.respondToPairRequest(req.requestId, "", accept = false) { _, _ -> }
                incomingPinInput = ""
                incomingPinError = null
            }
        )
    }

    // 主动发起配对：展示 PIN 等待对方输入
    if (outboundPairing.inProgress || outboundPairing.success || outboundPairing.error != null) {
        OutboundPairingDialog(
            state = outboundPairing,
            onDismiss = { viewModel.dismissOutboundPairing() }
        )
    }

    // 解除配对二次确认
    unpairTargetDevice?.let { dev ->
        UnpairConfirmDialog(
            device = dev,
            onConfirm = {
                viewModel.unpairDevice(dev.deviceId)
                unpairTargetDevice = null
                Toast.makeText(context, "已解除与设备配对", Toast.LENGTH_SHORT).show()
            },
            onDismiss = { unpairTargetDevice = null }
        )
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
        // 1. Scan & Discovery Control Panel
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
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "局域网设备发现与扫描",
                                color = MuClipTextPrimary,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = if (autoDiscovery) "自动持续发现中" else "仅手动扫描发现",
                                color = MuClipTextSecondary,
                                fontSize = 12.sp
                            )
                        }

                        Button(
                            onClick = { viewModel.triggerScan() },
                            enabled = !isScanning,
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MuClipPrimary,
                                contentColor = MuClipOnPrimary
                            )
                        ) {
                            if (isScanning) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                    color = MuClipBackground
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("扫描中...", fontSize = 13.sp)
                            } else {
                                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("立即扫描", fontSize = 13.sp)
                            }
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
                        Column(modifier = Modifier.weight(1f)) {
                            Text("自动广播自发现 (mDNS / UDP)", color = MuClipTextPrimary, fontSize = 14.sp)
                            Text("开启后周期性向局域网广播设备信息", color = MuClipTextSecondary, fontSize = 12.sp)
                        }
                        Switch(
                            checked = autoDiscovery,
                            onCheckedChange = { viewModel.toggleAutoDiscovery(it) },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = MuClipBackground,
                                checkedTrackColor = MuClipPrimary
                            )
                        )
                    }
                }
            }
        }

        // 2. Paired Devices Section (Dedicated List)
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Link, contentDescription = null, tint = MuClipPrimary, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "已配对设备 (${pairedDevices.size})",
                        color = MuClipTextPrimary,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MuClipSurface
                ) {
                    val onlineCount = pairedDevices.count { it.isOnline }
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(if (onlineCount > 0) MuClipGreen else MuClipTextSecondary)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (onlineCount > 0) "$onlineCount 台在线" else "全部离线",
                            color = MuClipTextSecondary,
                            fontSize = 12.sp
                        )
                    }
                }
            }
        }

        if (pairedDevices.isEmpty()) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MuClipSurface),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(Icons.Default.LinkOff, contentDescription = null, tint = MuClipTextSecondary, modifier = Modifier.size(36.dp))
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("暂无已配对设备", color = MuClipTextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text("可在下方「待配对新设备」列表中点击「单向PIN配对」完成授权建立安全通道", color = MuClipTextSecondary, fontSize = 12.sp, textAlign = TextAlign.Center)
                    }
                }
            }
        } else {
            items(pairedDevices) { dev ->
                val peerKey = dev.deviceId.ifEmpty { "${dev.ip}:${dev.syncPort}" }
                val isTesting = isTestingPeer[peerKey] == true

                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .border(
                            1.dp,
                            if (dev.isOnline) MuClipPrimary.copy(alpha = 0.6f) else MuClipCardBorder,
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
                                Icon(
                                    imageVector = if (dev.deviceType.contains("Windows", ignoreCase = true) || dev.deviceType.contains("PC", ignoreCase = true)) Icons.Default.Computer else Icons.Default.PhoneAndroid,
                                    contentDescription = null,
                                    tint = if (dev.isOnline) MuClipPrimary else MuClipTextSecondary,
                                    modifier = Modifier.size(26.dp)
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Column {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = dev.deviceName.ifEmpty { "未命名设备 (${dev.deviceId.take(8)})" },
                                            color = MuClipTextPrimary,
                                            fontSize = 15.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                        if (dev.isManual) {
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Surface(
                                                shape = RoundedCornerShape(6.dp),
                                                color = MuClipPrimary.copy(alpha = 0.15f),
                                                modifier = Modifier.border(0.5.dp, MuClipPrimary.copy(alpha = 0.5f), RoundedCornerShape(6.dp))
                                            ) {
                                                Text(
                                                    text = "端口映射",
                                                    color = MuClipPrimary,
                                                    fontSize = 10.sp,
                                                    fontWeight = FontWeight.SemiBold,
                                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                )
                                            }
                                        }
                                    }
                                    Text(
                                        text = if (dev.ip.isNotEmpty()) {
                                            "${dev.ip}:${dev.syncPort} · ${dev.deviceType}"
                                        } else {
                                            "离线 · 历史配对"
                                        },
                                        color = MuClipTextSecondary,
                                        fontSize = 12.sp
                                    )
                                }
                            }

                            // Online / Offline Status Badge
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = if (dev.isOnline) MuClipGreen.copy(alpha = 0.15f) else MuClipSurface,
                                modifier = Modifier.border(
                                    1.dp,
                                    if (dev.isOnline) MuClipGreen.copy(alpha = 0.4f) else MuClipCardBorder,
                                    RoundedCornerShape(8.dp)
                                )
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(7.dp)
                                            .clip(RoundedCornerShape(3.5.dp))
                                            .background(if (dev.isOnline) MuClipGreen else MuClipTextSecondary)
                                    )
                                    Spacer(modifier = Modifier.width(5.dp))
                                    Text(
                                        text = if (dev.isOnline) "在线" else "离线",
                                        color = if (dev.isOnline) MuClipGreen else MuClipTextSecondary,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                            }
                        }

                        // Latency Display
                        if (dev.isOnline && dev.latencyMs != null) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (dev.latencyMs >= 0) {
                                    Icon(Icons.Default.Check, contentDescription = null, tint = MuClipGreen, modifier = Modifier.size(14.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("端到端握手正常 · 延迟: ", color = MuClipTextSecondary, fontSize = 12.sp)
                                    Text("${dev.latencyMs} ms", color = MuClipGreen, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                } else {
                                    Icon(Icons.Default.ErrorOutline, contentDescription = null, tint = MuClipRed, modifier = Modifier.size(14.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("延迟测试超时或对方未应答", color = MuClipRed, fontSize = 12.sp)
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))
                        HorizontalDivider(color = MuClipCardBorder, thickness = 0.5.dp)
                        Spacer(modifier = Modifier.height(10.dp))

                        // Action Buttons: Ping latency & Unpair
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "ID: ${dev.deviceId.take(12)}...",
                                color = MuClipTextSecondary.copy(alpha = 0.7f),
                                fontSize = 11.sp
                            )

                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (dev.ip.isNotEmpty()) {
                                    OutlinedButton(
                                        onClick = {
                                            viewModel.testPairedDeviceConnection(dev) { ok, lat ->
                                                val msg = if (ok) "测试成功！延迟: ${lat}ms" else "测试失败：无法连接或对方未响应"
                                                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                                            }
                                        },
                                        enabled = !isTesting,
                                        shape = RoundedCornerShape(8.dp),
                                        modifier = Modifier.padding(end = 8.dp),
                                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MuClipPrimary)
                                    ) {
                                        if (isTesting) {
                                            CircularProgressIndicator(modifier = Modifier.size(13.dp), strokeWidth = 2.dp, color = MuClipPrimary)
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text("测试中", fontSize = 12.sp)
                                        } else {
                                            Icon(Icons.Default.Speed, contentDescription = null, modifier = Modifier.size(14.dp))
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text("测试连接", fontSize = 12.sp)
                                        }
                                    }
                                }

                                TextButton(
                                    onClick = { unpairTargetDevice = dev }
                                ) {
                                    Icon(Icons.Default.DeleteOutline, contentDescription = null, tint = MuClipRed, modifier = Modifier.size(14.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("解除配对", color = MuClipRed, fontSize = 12.sp)
                                }
                            }
                        }
                    }
                }
            }
        }

        // 3. Discovered Unpaired & Port-Mapped Peers Section
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.WifiTethering, contentDescription = null, tint = MuClipPrimary, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "待配对新设备 (${unpairedPeers.size})",
                        color = MuClipTextPrimary,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(
                        onClick = { showManualAddDialog = true },
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MuClipPrimary)
                    ) {
                        Icon(Icons.Default.AddLink, contentDescription = null, modifier = Modifier.size(15.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("+ 添加映射节点", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }

        if (unpairedPeers.isEmpty()) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MuClipSurface),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(18.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("当前没有待配对的局域网或映射设备", color = MuClipTextSecondary, fontSize = 13.sp)
                        Text(
                            "同一 Wi-Fi 设备将自动发现，跨网段/FRP/DDNS 设备可点击右上角「+ 添加映射节点」立即加入列表并配对",
                            color = MuClipTextSecondary.copy(alpha = 0.7f),
                            fontSize = 11.sp,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        } else {
            items(unpairedPeers, key = { "${it.deviceId}_${it.ip}:${it.syncPort}" }) { peer ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .border(
                            1.dp,
                            if (peer.isManual) MuClipPrimary.copy(alpha = 0.5f) else MuClipCardBorder,
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
                                Icon(
                                    imageVector = when {
                                        peer.deviceType.contains("Windows", ignoreCase = true) || peer.deviceType.contains("PC", ignoreCase = true) -> Icons.Default.Computer
                                        peer.deviceType.equals("Manual", ignoreCase = true) -> Icons.Default.Router
                                        else -> Icons.Default.PhoneAndroid
                                    },
                                    contentDescription = null,
                                    tint = if (peer.isManual) MuClipPrimary else MuClipTextSecondary,
                                    modifier = Modifier.size(24.dp)
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Column {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = peer.deviceName,
                                            color = MuClipTextPrimary,
                                            fontSize = 15.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                        if (peer.isManual) {
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Surface(
                                                shape = RoundedCornerShape(6.dp),
                                                color = MuClipPrimary.copy(alpha = 0.15f),
                                                modifier = Modifier.border(0.5.dp, MuClipPrimary.copy(alpha = 0.5f), RoundedCornerShape(6.dp))
                                            ) {
                                                Text(
                                                    text = "端口映射",
                                                    color = MuClipPrimary,
                                                    fontSize = 10.sp,
                                                    fontWeight = FontWeight.SemiBold,
                                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                )
                                            }
                                        }
                                    }
                                    Text(
                                        text = if (peer.isManual && peer.deviceId.startsWith("manual-")) {
                                            "${peer.ip}:${peer.syncPort} · 外部映射节点 (可直接配对)"
                                        } else {
                                            "${peer.ip}:${peer.syncPort} · ${peer.deviceType}"
                                        },
                                        color = MuClipTextSecondary,
                                        fontSize = 12.sp
                                    )
                                }
                            }

                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Button(
                                    onClick = { viewModel.startPairingWithPeer(peer) },
                                    shape = RoundedCornerShape(8.dp),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = MuClipPrimary, contentColor = MuClipOnPrimary)
                                ) {
                                    Icon(Icons.Default.VpnKey, contentDescription = null, modifier = Modifier.size(14.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("配对", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                }

                                if (peer.isManual) {
                                    Spacer(modifier = Modifier.width(6.dp))
                                    IconButton(
                                        onClick = {
                                            viewModel.removeManualPeerNode(peer)
                                            Toast.makeText(context, "已删除映射节点 ${peer.ip}:${peer.syncPort}", Toast.LENGTH_SHORT).show()
                                        },
                                        modifier = Modifier.size(34.dp)
                                    ) {
                                        Icon(
                                            Icons.Default.DeleteOutline,
                                            contentDescription = "删除映射节点",
                                            tint = MuClipRed,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // 4. Static / Custom Port Mapping Section (FRP / Tailscale / DDNS)
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
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "静态端口映射节点 (${customPeers.size})",
                                color = MuClipTextPrimary,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "支持 IP:Port、纯 IP (默认 42424) 及 FRP / DDNS 域名:端口",
                                color = MuClipTextSecondary,
                                fontSize = 12.sp
                            )
                        }

                        Button(
                            onClick = { showManualAddDialog = true },
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = MuClipPrimary, contentColor = MuClipOnPrimary)
                        ) {
                            Text("+ 添加节点", fontSize = 12.sp)
                        }
                    }

                    if (customPeers.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(10.dp))
                        customPeers.forEach { endpoint ->
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = MuClipSurface,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp)
                                    .border(1.dp, MuClipCardBorder, RoundedCornerShape(8.dp))
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp, vertical = 8.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(text = endpoint, color = MuClipPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                                        Text(
                                            text = "TCP 双向探测 (MSG_HEARTBEAT) 与剪贴板同步已启用",
                                            color = MuClipTextSecondary,
                                            fontSize = 11.sp
                                        )
                                    }
                                    IconButton(
                                        onClick = {
                                            viewModel.removeCustomPeer(endpoint)
                                            Toast.makeText(context, "已移除映射节点 $endpoint", Toast.LENGTH_SHORT).show()
                                        },
                                        modifier = Modifier.size(28.dp)
                                    ) {
                                        Icon(Icons.Default.Close, contentDescription = "Delete", tint = MuClipRed, modifier = Modifier.size(16.dp))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // 添加端口映射 / 静态节点
    if (showManualAddDialog) {
        AddCustomPeerDialog(
            endpointText = manualEndpointText,
            onEndpointChange = { manualEndpointText = it },
            onConfirm = { endpoint ->
                if (endpoint.isNotEmpty()) {
                    viewModel.addCustomPeer(endpoint)
                    manualEndpointText = ""
                    showManualAddDialog = false
                    Toast.makeText(context, "已添加映射节点，已加入待配对列表并探测中...", Toast.LENGTH_SHORT).show()
                }
            },
            onDismiss = { showManualAddDialog = false }
        )
    }
}
