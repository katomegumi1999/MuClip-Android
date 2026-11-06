package com.muclip.app.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.muclip.app.MuClipApplication
import com.muclip.app.data.SyncEventManager
import com.muclip.app.service.ClipboardSyncService
import com.muclip.core.IncomingPairRequest
import com.muclip.core.PairResult
import com.muclip.core.PeerInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.random.Random

data class PairingSessionState(
    val inProgress: Boolean = false,
    val targetPeer: PeerInfo? = null,
    val generatedPin: String = "",
    val error: String? = null,
    val success: Boolean = false
)

data class PairedDevice(
    val deviceId: String,
    val deviceName: String,
    val deviceType: String,
    val isOnline: Boolean,
    val ip: String,
    val syncPort: Int,
    val latencyMs: Int?,
    val isManual: Boolean = false
)

class SyncViewModel : ViewModel() {

    private val prefs = MuClipApplication.preferences

    private val _activePeers = MutableStateFlow<List<PeerInfo>>(emptyList())
    val activePeers: StateFlow<List<PeerInfo>> = _activePeers.asStateFlow()

    private val _pairedDeviceIds = MutableStateFlow<Set<String>>(emptySet())
    val pairedDeviceIds: StateFlow<Set<String>> = _pairedDeviceIds.asStateFlow()

    // Structured paired devices list (including offline devices)
    private val _pairedDevicesList = MutableStateFlow<List<PairedDevice>>(emptyList())
    val pairedDevicesList: StateFlow<List<PairedDevice>> = _pairedDevicesList.asStateFlow()

    // Active online / port-mapped peers that are not yet paired
    private val _unpairedDiscoveredPeers = MutableStateFlow<List<PeerInfo>>(emptyList())
    val unpairedDiscoveredPeers: StateFlow<List<PeerInfo>> = _unpairedDiscoveredPeers.asStateFlow()

    private val _pairingPin = MutableStateFlow("")
    val pairingPin: StateFlow<String> = _pairingPin.asStateFlow()

    private val _syncPort = MutableStateFlow(42424)
    val syncPort: StateFlow<Int> = _syncPort.asStateFlow()

    private val _customPeers = MutableStateFlow<Set<String>>(emptySet())
    val customPeers: StateFlow<Set<String>> = _customPeers.asStateFlow()

    private val _serviceEnabled = MutableStateFlow(true)
    val serviceEnabled: StateFlow<Boolean> = _serviceEnabled.asStateFlow()

    private val _autoReadClipboard = MutableStateFlow(true)
    val autoReadClipboard: StateFlow<Boolean> = _autoReadClipboard.asStateFlow()

    private val _autoCopyRemote = MutableStateFlow(true)
    val autoCopyRemote: StateFlow<Boolean> = _autoCopyRemote.asStateFlow()

    private val _aggressiveBackgroundClipboard = MutableStateFlow(false)
    val aggressiveBackgroundClipboard: StateFlow<Boolean> = _aggressiveBackgroundClipboard.asStateFlow()

    private val _autoDiscovery = MutableStateFlow(true)
    val autoDiscovery: StateFlow<Boolean> = _autoDiscovery.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private val _deviceName = MutableStateFlow("")
    val deviceName: StateFlow<String> = _deviceName.asStateFlow()

    // Test Peer RTT Latency in milliseconds (key = deviceId or ip:port)
    private val _peerLatencies = MutableStateFlow<Map<String, Int>>(emptyMap())
    val peerLatencies: StateFlow<Map<String, Int>> = _peerLatencies.asStateFlow()

    // Testing state (key = target deviceId or ip:port)
    private val _isTestingPeer = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    val isTestingPeer: StateFlow<Map<String, Boolean>> = _isTestingPeer.asStateFlow()

    // Active outbound pairing session state
    private val _outboundPairing = MutableStateFlow(PairingSessionState())
    val outboundPairing: StateFlow<PairingSessionState> = _outboundPairing.asStateFlow()

    // Inbound pairing request received from remote peer
    val incomingPairRequest: StateFlow<IncomingPairRequest?> = SyncEventManager.pendingPairRequest

    private val _isAccessibilityEnabled = MutableStateFlow(false)
    val isAccessibilityEnabled: StateFlow<Boolean> = _isAccessibilityEnabled.asStateFlow()

    fun refreshAccessibilityStatus(context: Context) {
        _isAccessibilityEnabled.value = com.muclip.app.service.MuClipAccessibilityService.isEnabled(context)
    }

    fun openAccessibilitySettings(context: Context) {
        com.muclip.app.service.MuClipAccessibilityService.openAccessibilitySettings(context)
    }

    /**
     * Checks and automatically broadcasts the current system clipboard.
     * Returns:
     *   > 0: successfully broadcasted to N peers
     *   == 0: saved locally (no peers online)
     *   == -1: empty clipboard or already synced (no-op)
     */
    fun autoCheckAndSyncClipboard(context: Context): Int {
        if (!prefs.autoReadClipboard) return -1
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager ?: return -1
        val clip = clipboard.primaryClip
        if (clip != null && clip.itemCount > 0) {
            val text = clip.getItemAt(0).text?.toString()
            if (!text.isNullOrBlank()) {
                val trimmed = text.trim()
                // 如果此时剪贴板内容和软件剪贴板第一条数据完全一致，则不再写入和推送同步
                if (MuClipApplication.isCoreInitialized()) {
                    try {
                        val latest = MuClipApplication.coreOrNull()?.queryHistory(1)?.firstOrNull()
                        if (latest != null && latest.content.trim() == trimmed) {
                            SyncEventManager.markRemoteReceived(trimmed)
                            return -1
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
                if (SyncEventManager.shouldBroadcast(trimmed)) {
                    MuClipApplication.coreOrNull()?.addHistory(trimmed)
                    SyncEventManager.emitHistoryChanged()
                    return MuClipApplication.sync?.broadcastClipboard(trimmed, "text") ?: 0
                }
            }
        }
        return -1
    }

    init {
        _syncPort.value = prefs.syncPort
        val currentPin = prefs.pairingPin
        _pairingPin.value = if (currentPin.length == 64) "已信任 (长期密钥)" else currentPin
        _pairedDeviceIds.value = prefs.getPairedDevices()
        _customPeers.value = prefs.getCustomPeers()
        _serviceEnabled.value = prefs.foregroundServiceEnabled
        _autoReadClipboard.value = prefs.autoReadClipboard
        _autoCopyRemote.value = prefs.autoCopyRemote
        _aggressiveBackgroundClipboard.value = prefs.aggressiveBackgroundClipboard
        _autoDiscovery.value = prefs.autoDiscovery
        _deviceName.value = prefs.deviceName

        val initialPeers = MuClipApplication.sync?.getActivePeers() ?: emptyList()
        _activePeers.value = initialPeers
        updateDeviceLists(initialPeers, _pairedDeviceIds.value)
        startPeerPolling()
        listenForPairEvents()
    }

    private fun updateDeviceLists(peers: List<PeerInfo>, pairedIds: Set<String>) {
        val latencies = _peerLatencies.value
        val customSet = _customPeers.value
        val effectivePairedIds = pairedIds.toMutableSet()
        peers.forEach { p ->
            if (p.isPaired && !p.deviceId.startsWith("manual-")) {
                effectivePairedIds.add(p.deviceId)
            }
        }

        val list = effectivePairedIds.map { id ->
            val onlinePeer = peers.find { it.deviceId == id }
            if (onlinePeer != null) {
                val savedName = prefs.getDeviceName(id)
                val savedType = prefs.getDeviceType(id)
                val incomingTypeValid = onlinePeer.deviceType.isNotBlank() &&
                    onlinePeer.deviceType != "Remote" &&
                    onlinePeer.deviceType != "Paired" &&
                    onlinePeer.deviceType != "Manual"
                val effectiveType = if (incomingTypeValid) onlinePeer.deviceType else savedType
                val effectiveName = if (onlinePeer.deviceName.isNotBlank() && onlinePeer.deviceName != id) {
                    onlinePeer.deviceName
                } else {
                    savedName
                }
                if (
                    !onlinePeer.deviceId.startsWith("manual-") &&
                    (!pairedIds.contains(id) || savedName != effectiveName || (incomingTypeValid && savedType != effectiveType))
                ) {
                    prefs.setDevicePairedInfo(id, effectiveName, effectiveType, true)
                }
                if (prefs.isValidHexKey64(onlinePeer.fingerprint) && prefs.getDevicePeerKey(id) != onlinePeer.fingerprint.trim()) {
                    prefs.setDevicePeerKey(id, onlinePeer.fingerprint.trim())
                }
                val prevEp = prefs.getDeviceLastEndpoint(id)
                if (onlinePeer.ip.isNotBlank() && onlinePeer.syncPort > 0) {
                    prefs.setDeviceLastEndpoint(id, onlinePeer.ip, onlinePeer.syncPort)
                }
                if (!onlinePeer.isPaired || (onlinePeer.ip.isNotBlank() && (prevEp?.first != onlinePeer.ip || prevEp.second != onlinePeer.syncPort))) {
                    MuClipApplication.sync?.addPairedDevice(
                        prefs.buildPairedDeviceSpec(id, onlinePeer.ip, onlinePeer.syncPort, effectiveName, effectiveType, onlinePeer.fingerprint)
                    )
                }
                val epKey = "${onlinePeer.ip}:${onlinePeer.syncPort}"
                val isManual = onlinePeer.isManual || customSet.any { it.equals(epKey, ignoreCase = true) }
                PairedDevice(
                    deviceId = id,
                    deviceName = effectiveName,
                    deviceType = effectiveType,
                    isOnline = onlinePeer.isOnline,
                    ip = onlinePeer.ip,
                    syncPort = onlinePeer.syncPort,
                    latencyMs = latencies[id] ?: latencies[epKey],
                    isManual = isManual
                )
            } else {
                val lastEp = prefs.getDeviceLastEndpoint(id)
                val epKey = if (lastEp != null) "${lastEp.first}:${lastEp.second}" else ""
                val isManual = epKey.isNotEmpty() && customSet.any { it.equals(epKey, ignoreCase = true) }
                PairedDevice(
                    deviceId = id,
                    deviceName = prefs.getDeviceName(id),
                    deviceType = prefs.getDeviceType(id),
                    isOnline = false,
                    ip = lastEp?.first ?: "",
                    syncPort = lastEp?.second ?: 0,
                    latencyMs = null,
                    isManual = isManual
                )
            }
        }
        _pairedDevicesList.value = list

        val pairedEndpoints = list.mapNotNull { dev ->
            if (dev.ip.isNotEmpty() && dev.syncPort > 0) "${dev.ip}:${dev.syncPort}".lowercase() else null
        }.toSet()

        _unpairedDiscoveredPeers.value = peers.filter { peer ->
            if (effectivePairedIds.contains(peer.deviceId)) {
                false
            } else if (peer.deviceId.startsWith("manual-")) {
                val ep = "${peer.ip}:${peer.syncPort}".lowercase()
                !pairedEndpoints.contains(ep)
            } else {
                true
            }
        }
    }

    private fun startPeerPolling() {
        viewModelScope.launch(Dispatchers.IO) {
            while (isActive) {
                val peers = MuClipApplication.sync?.getActivePeers() ?: emptyList()
                val paired = prefs.getPairedDevices()
                withContext(Dispatchers.Main) {
                    _activePeers.value = peers
                    _pairedDeviceIds.value = paired
                    updateDeviceLists(peers, paired)
                }
                delay(2500)
            }
        }
    }

    private fun listenForPairEvents() {
        viewModelScope.launch {
            SyncEventManager.pairSuccessFlow.collect { event ->
                if (event.pin.isNotBlank()) {
                    _pairingPin.value = if (event.pin.trim().length == 64) "已信任 (长期密钥)" else event.pin.trim()
                }
                refreshPeers()
            }
        }
        viewModelScope.launch {
            SyncEventManager.unpairedPeerFlow.collect { _ ->
                refreshPeers()
            }
        }
    }

    // --- Discovery & Manual Scan ---

    fun triggerScan() {
        viewModelScope.launch {
            _isScanning.value = true
            withContext(Dispatchers.IO) {
                MuClipApplication.sync?.triggerScan()
                delay(600)
                val peers = MuClipApplication.sync?.getActivePeers() ?: emptyList()
                val paired = prefs.getPairedDevices()
                withContext(Dispatchers.Main) {
                    _activePeers.value = peers
                    _pairedDeviceIds.value = paired
                    updateDeviceLists(peers, paired)
                }
            }
            delay(1000)
            _isScanning.value = false
        }
    }

    fun triggerSilentForegroundScan() {
        if (!_autoDiscovery.value) return
        viewModelScope.launch(Dispatchers.IO) {
            MuClipApplication.sync?.triggerScan()
            delay(800)
            val peers = MuClipApplication.sync?.getActivePeers() ?: emptyList()
            val paired = prefs.getPairedDevices()
            withContext(Dispatchers.Main) {
                _activePeers.value = peers
                _pairedDeviceIds.value = paired
                updateDeviceLists(peers, paired)
            }
        }
    }

    fun toggleAutoDiscovery(enabled: Boolean) {
        prefs.autoDiscovery = enabled
        _autoDiscovery.value = enabled
        MuClipApplication.sync?.setAutoDiscovery(enabled)
    }

    // --- One-Way Random PIN Handshake Pairing ---

    /**
     * Initiates pairing with a peer. Generates a random 6-digit PIN to show to local user,
     * while sending the challenge to the remote peer over single-stream TCP (supporting IP:Port and domain:Port).
     */
    fun startPairingWithPeer(peer: PeerInfo) {
        val pin = "%06d".format(Random.nextInt(100000, 999999))
        _outboundPairing.value = PairingSessionState(
            inProgress = true,
            targetPeer = peer,
            generatedPin = pin,
            error = null,
            success = false
        )

        viewModelScope.launch {
            val peerAddr = "${peer.ip}:${peer.syncPort}"
            val result: PairResult = withContext(Dispatchers.IO) {
                MuClipApplication.sync?.requestPair(peerAddr, pin, timeoutSecs = 60)
                    ?: PairResult(false, -1, "", "", "同步引擎不可用")
            }

            if (result.success) {
                // Record paired device in preferences & engine
                val devId = result.responderDeviceId.ifEmpty { peer.deviceId }
                val devName = result.responderDeviceName.ifEmpty { peer.deviceName }
                val freshPeers = withContext(Dispatchers.IO) {
                    MuClipApplication.sync?.getActivePeers() ?: emptyList()
                }
                val resolvedPeer = freshPeers.find { it.deviceId == devId }
                val devType = resolvedPeer?.deviceType?.takeIf { it.isNotEmpty() && it != "Manual" }
                    ?: peer.deviceType.takeIf { it.isNotEmpty() && it != "Manual" }
                    ?: "Windows"
                val peerKeyHex = result.longTermKey?.trim() ?: ""
                if (prefs.isValidHexKey64(peerKeyHex)) {
                    prefs.setDevicePeerKey(devId, peerKeyHex)
                }
                _pairingPin.value = "已信任 (长期密钥)"

                // Persist device pairing metadata, peer key, and last endpoint into preferences and native sync engine
                prefs.setDevicePairedInfo(devId, devName, devType, true)
                prefs.setDeviceLastEndpoint(devId, peer.ip, peer.syncPort)
                MuClipApplication.sync?.addPairedDevice(
                    prefs.buildPairedDeviceSpec(devId, peer.ip, peer.syncPort, devName, devType, peerKeyHex)
                )
                if (peer.isManual || resolvedPeer?.isManual == true) {
                    prefs.addCustomPeer(peerAddr)
                    _customPeers.value = prefs.getCustomPeers()
                }
                val newPaired = prefs.getPairedDevices()
                _activePeers.value = freshPeers
                _pairedDeviceIds.value = newPaired

                // Emit pair success event so both sides display the paired device immediately
                SyncEventManager.emitPairSuccess(
                    com.muclip.app.data.PairSuccessEvent(devId, devName, peer.ip, peerKeyHex)
                )

                updateDeviceLists(freshPeers, newPaired)

                _outboundPairing.value = _outboundPairing.value.copy(
                    inProgress = false,
                    success = true,
                    error = null
                )
            } else {
                val errMsg = when (result.errorCode) {
                    1 -> "对方拒绝了配对请求"
                    2 -> "对方连续 3 次输错 PIN 码，本次配对已作废，请重新生成配对码"
                    3 -> "配对超时，对方未在 60 秒内确认"
                    else -> result.errorMessage ?: "配对失败，请检查网络或映射端口"
                }
                _outboundPairing.value = _outboundPairing.value.copy(
                    inProgress = false,
                    success = false,
                    error = errMsg
                )
            }
        }
    }

    fun dismissOutboundPairing() {
        _outboundPairing.value = PairingSessionState(inProgress = false)
    }

    /**
     * Responds to an incoming pairing request initiated by a remote peer.
     * @param pin The 6-digit PIN displayed on the requester's device screen
     */
    fun respondToPairRequest(requestId: String, pin: String, accept: Boolean, onResult: (Boolean, String?) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val code = MuClipApplication.sync?.respondPair(requestId, pin, accept) ?: -1
            withContext(Dispatchers.Main) {
                when (code) {
                    0 -> {
                        SyncEventManager.clearPairRequest()
                        if (accept) {
                            _pairingPin.value = if (prefs.pairingPin.length == 64) "已信任 (长期密钥)" else prefs.pairingPin
                            refreshPeers()
                        }
                        onResult(true, null)
                    }
                    2 -> {
                        // Still has remaining attempts (up to 3 total) -> keep dialog open!
                        onResult(false, "PIN 码错误，请核对后重试（连续输错 3 次将作废）")
                    }
                    3 -> {
                        SyncEventManager.clearPairRequest()
                        onResult(false, "PIN 码连续输错 3 次，本次配对已作废，请让对方重新发起配对")
                    }
                    1 -> {
                        SyncEventManager.clearPairRequest()
                        onResult(false, "配对请求已过期或超时")
                    }
                    else -> {
                        SyncEventManager.clearPairRequest()
                        onResult(false, "配对响应失败")
                    }
                }
            }
        }
    }

    fun unpairDevice(deviceId: String) {
        prefs.setDevicePaired(deviceId, false)
        val newPaired = prefs.getPairedDevices()
        _pairedDeviceIds.value = newPaired
        MuClipApplication.sync?.removePairedDevice(deviceId)
        refreshPeers()
    }

    fun refreshPeers() {
        val peers = MuClipApplication.sync?.getActivePeers() ?: emptyList()
        val paired = prefs.getPairedDevices()
        _activePeers.value = peers
        _pairedDeviceIds.value = paired
        updateDeviceLists(peers, paired)
    }

    // --- Test Paired Peer Connection ---

    fun testPairedDeviceConnection(pairedDev: PairedDevice, onResult: ((Boolean, Int) -> Unit)? = null) {
        if (pairedDev.ip.isEmpty() || pairedDev.syncPort <= 0) {
            onResult?.invoke(false, -1)
            return
        }
        val key = pairedDev.deviceId.ifEmpty { "${pairedDev.ip}:${pairedDev.syncPort}" }
        _isTestingPeer.value = _isTestingPeer.value + (key to true)

        viewModelScope.launch {
            val peerAddr = "${pairedDev.ip}:${pairedDev.syncPort}"
            val latency = withContext(Dispatchers.IO) {
                MuClipApplication.sync?.testPeer(peerAddr) ?: -1
            }

            _isTestingPeer.value = _isTestingPeer.value + (key to false)
            _peerLatencies.value = _peerLatencies.value + (key to latency)
            refreshPeers()
            onResult?.invoke(latency >= 0, latency)
        }
    }

    fun testPeerConnection(peer: PeerInfo, onResult: ((Boolean, Int) -> Unit)? = null) {
        val key = peer.deviceId.ifEmpty { "${peer.ip}:${peer.syncPort}" }
        _isTestingPeer.value = _isTestingPeer.value + (key to true)

        viewModelScope.launch {
            val peerAddr = "${peer.ip}:${peer.syncPort}"
            val latency = withContext(Dispatchers.IO) {
                MuClipApplication.sync?.testPeer(peerAddr) ?: -1
            }

            _isTestingPeer.value = _isTestingPeer.value + (key to false)
            _peerLatencies.value = _peerLatencies.value + (key to latency)
            refreshPeers()
            onResult?.invoke(latency >= 0, latency)
        }
    }

    // --- Preferences & Settings ---

    fun updateDeviceName(newName: String) {
        val sanitized = newName.trim()
        if (sanitized.isNotEmpty()) {
            prefs.deviceName = sanitized
            _deviceName.value = sanitized
            MuClipApplication.instance.initSyncEngine()
            refreshPeers()
        }
    }

    fun updatePairingPin(newPin: String) {
        val sanitized = newPin.trim()
        if (sanitized.isEmpty() || sanitized == "已信任 (长期密钥)") return
        prefs.pairingPin = sanitized
        _pairingPin.value = if (sanitized.length == 64) "已信任 (长期密钥)" else sanitized
        MuClipApplication.sync?.setPairingPin(sanitized)
    }

    fun updateSyncPort(newPort: Int) {
        if (newPort in 1024..65535) {
            prefs.syncPort = newPort
            _syncPort.value = newPort
            MuClipApplication.instance.initSyncEngine()
            refreshPeers()
        }
    }

    fun toggleForegroundService(context: Context, enable: Boolean) {
        prefs.foregroundServiceEnabled = enable
        _serviceEnabled.value = enable
        if (enable) {
            ClipboardSyncService.start(context)
        } else {
            ClipboardSyncService.stop(context)
        }
    }

    fun toggleAutoReadClipboard(enable: Boolean) {
        prefs.autoReadClipboard = enable
        _autoReadClipboard.value = enable
    }

    fun toggleAutoCopyRemote(enable: Boolean) {
        prefs.autoCopyRemote = enable
        _autoCopyRemote.value = enable
    }

    fun toggleAggressiveBackgroundClipboard(enable: Boolean) {
        prefs.aggressiveBackgroundClipboard = enable
        _aggressiveBackgroundClipboard.value = enable
    }

    /**
     * Adds a static / port-mapped node (supporting IP:Port, pure IP with default 42424, or FRP/DDNS domain:port).
     * Immediately registers the node in native PeerManager so it appears in PeerDevices right away,
     * and refreshes again when the background TCP MSG_HEARTBEAT probe completes.
     */
    fun addCustomPeer(endpoint: String) {
        val normalized = prefs.addCustomPeer(endpoint)
        if (normalized.isNotEmpty()) {
            _customPeers.value = prefs.getCustomPeers()
            MuClipApplication.sync?.addManualPeer(normalized)
            // Refresh immediately so the placeholder PeerInfo ("映射节点 (host:port)", isManual = true) appears at once
            refreshPeers()
            // Refresh again after async TCP MSG_HEARTBEAT probe completes to upgrade to real device info
            viewModelScope.launch(Dispatchers.IO) {
                delay(400)
                val peers1 = MuClipApplication.sync?.getActivePeers() ?: emptyList()
                val paired1 = prefs.getPairedDevices()
                withContext(Dispatchers.Main) {
                    _activePeers.value = peers1
                    _pairedDeviceIds.value = paired1
                    updateDeviceLists(peers1, paired1)
                }
                delay(900)
                val peers2 = MuClipApplication.sync?.getActivePeers() ?: emptyList()
                val paired2 = prefs.getPairedDevices()
                withContext(Dispatchers.Main) {
                    _activePeers.value = peers2
                    _pairedDeviceIds.value = paired2
                    updateDeviceLists(peers2, paired2)
                }
            }
        }
    }

    /**
     * Removes a static / port-mapped node by endpoint ("host:port" or "host") or resolved deviceId,
     * synchronously removing it from PeerDevices.
     */
    fun removeCustomPeer(endpoint: String) {
        val sanitized = endpoint.trim()
        if (sanitized.isEmpty()) return
        val normalized = prefs.normalizeEndpoint(sanitized)
        prefs.removeCustomPeer(sanitized)
        _customPeers.value = prefs.getCustomPeers()
        MuClipApplication.sync?.removeManualPeer(sanitized)
        if (normalized.isNotEmpty() && normalized != sanitized) {
            MuClipApplication.sync?.removeManualPeer(normalized)
        }
        refreshPeers()
    }

    /**
     * Removes a manual port-mapped PeerInfo directly from the PeerDevices list.
     */
    fun removeManualPeerNode(peer: PeerInfo) {
        val ep = "${peer.ip}:${peer.syncPort}"
        prefs.removeCustomPeer(ep)
        prefs.removeCustomPeer(peer.ip)
        _customPeers.value = prefs.getCustomPeers()
        MuClipApplication.sync?.removeManualPeer(ep)
        MuClipApplication.sync?.removeManualPeer(peer.deviceId)
        refreshPeers()
    }
}
