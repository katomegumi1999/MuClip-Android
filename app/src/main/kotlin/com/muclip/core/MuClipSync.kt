package com.muclip.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class PeerInfo(
    val deviceId: String,
    val deviceName: String,
    val deviceType: String,
    val ip: String,
    val syncPort: Int,
    val lastSeenMs: Long,
    val isOnline: Boolean,
    val fingerprint: String = "",
    val isPaired: Boolean = false,
    val isManual: Boolean = false
)

data class PairResult(
    val success: Boolean,
    val errorCode: Int,
    val responderDeviceId: String,
    val responderDeviceName: String,
    val errorMessage: String?,
    val longTermKey: String? = null
)

data class IncomingPairRequest(
    val requestId: String,
    val fromDeviceId: String,
    val fromDeviceName: String,
    val clientIp: String
)

/**
 * High-performance P2P LAN Cross-Device Sync Engine for Android/Kotlin.
 * Features:
 * - mDNS / UDP Multicast Local Device Discovery with Selective / Manual Scan
 * - Single-Stream Random PIN Handshake Pairing (One-Way Confirmation)
 * - ChaCha20-Poly1305 + HKDF-SHA256 End-to-End Encryption (E2EE)
 * - Anti-Loopback Broadcast Suppression
 * - Zero-Copy Direct Binary Memory Transmission (No JSON overhead)
 */
class MuClipSync private constructor(private var syncHandle: Long) : AutoCloseable {

    companion object {
        fun start(
            deviceId: String,
            deviceName: String,
            deviceType: String = "Android",
            syncPort: Int = 0,
            pairingPin: String = ""
        ): MuClipSync {
            val handle = MuClipNative.nativeStartSync(
                deviceId = deviceId,
                deviceName = deviceName,
                deviceType = deviceType,
                syncPort = syncPort,
                pairingPin = pairingPin
            )
            if (handle == 0L) {
                throw IllegalStateException("Failed to start MuClipSync engine.")
            }
            return MuClipSync(handle)
        }
    }

    private fun checkHandle() {
        if (syncHandle == 0L) {
            throw IllegalStateException("MuClipSync engine has already been stopped.")
        }
    }

    /**
     * Broadcasts a clipboard item to all discovered LAN peers using zero-copy leased memory.
     * Returns the number of peers successfully reached.
     */
    fun broadcastClipboard(text: String, contentType: String = "text"): Int {
        checkHandle()
        return DirectBufferPool.useLeased { buffer ->
            val len = buffer.writeUtf8(text)
            MuClipNative.nativeBroadcastClipboardZeroCopy(
                syncHandle,
                buffer.rawPtr,
                len,
                contentType
            )
        }
    }

    /**
     * Retrieves all active online peers discovered via mDNS and manual port mappings
     * using direct Little-Endian binary wire format in off-heap memory.
     */
    fun getActivePeers(): List<PeerInfo> {
        checkHandle()
        return DirectBufferPool.useBinaryQuery(
            defaultResult = emptyList(),
            nativeWriter = { rawPtr, cap ->
                MuClipNative.nativeGetActivePeersZeroCopy(syncHandle, rawPtr, cap)
            },
            decoder = { buf -> readPeersBinary(buf) }
        )
    }

    /**
     * Retrieves all known peers (including offline and manual port-mapped peers)
     * using direct Little-Endian binary wire format in off-heap memory.
     */
    fun getAllPeers(): List<PeerInfo> {
        checkHandle()
        return DirectBufferPool.useBinaryQuery(
            defaultResult = emptyList(),
            nativeWriter = { rawPtr, cap ->
                MuClipNative.nativeGetAllPeersZeroCopy(syncHandle, rawPtr, cap)
            },
            decoder = { buf -> readPeersBinary(buf) }
        )
    }

    /**
     * Updates the pairing PIN / shared passphrase across runtime.
     */
    fun setPairingPin(pin: String): Boolean {
        checkHandle()
        return MuClipNative.nativeSetPairingPin(syncHandle, pin) == 0
    }

    /**
     * Adds a static / port-mapped remote peer endpoint (e.g. "192.168.1.100:42424" or "myfrp.host.com:55555").
     */
    fun addManualPeer(endpoint: String): Boolean {
        checkHandle()
        return MuClipNative.nativeAddManualPeer(syncHandle, endpoint) == 0
    }

    /**
     * Removes a static / port-mapped remote peer endpoint.
     */
    fun removeManualPeer(endpoint: String): Boolean {
        checkHandle()
        return MuClipNative.nativeRemoveManualPeer(syncHandle, endpoint) == 0
    }

    /**
     * Retrieves all configured static / port-mapped remote peer endpoints via binary wire format.
     */
    fun getManualPeers(): List<String> {
        checkHandle()
        return DirectBufferPool.useBinaryQuery(
            defaultResult = emptyList(),
            nativeWriter = { rawPtr, cap ->
                MuClipNative.nativeGetManualPeersZeroCopy(syncHandle, rawPtr, cap)
            },
            decoder = { buf ->
                val count = buf.readInt()
                if (count <= 0) emptyList()
                else List(count) { buf.readString() }
            }
        )
    }

    /**
     * Toggles automatic periodic background discovery broadcasting.
     */
    fun setAutoDiscovery(enabled: Boolean): Boolean {
        checkHandle()
        return MuClipNative.nativeSetAutoDiscovery(syncHandle, enabled) == 0
    }

    /**
     * Checks if automatic periodic discovery is active.
     */
    fun isAutoDiscovery(): Boolean {
        checkHandle()
        return MuClipNative.nativeIsAutoDiscovery(syncHandle)
    }

    /**
     * Immediately triggers a single multi-interface discovery broadcast scan across the LAN.
     */
    fun triggerScan(): Boolean {
        checkHandle()
        return MuClipNative.nativeTriggerScan(syncHandle) == 0
    }

    /**
     * Marks a device ID as paired in the discovery table.
     */
    fun addPairedDevice(deviceId: String): Boolean {
        checkHandle()
        return MuClipNative.nativeAddPairedDevice(syncHandle, deviceId) == 0
    }

    /**
     * Removes a device ID from the paired list in the discovery table.
     */
    fun removePairedDevice(deviceId: String): Boolean {
        checkHandle()
        return MuClipNative.nativeRemovePairedDevice(syncHandle, deviceId) == 0
    }

    /**
     * Initiates a single-stream TCP handshake pairing with a remote peer using a random PIN.
     * Suspends asynchronously until the peer accepts/rejects or timeout occurs, decoding the result
     * directly from pooled binary memory.
     */
    suspend fun requestPair(
        peerAddr: String,
        pin: String,
        timeoutSecs: Int = 60
    ): PairResult = withContext(Dispatchers.IO) {
        checkHandle()
        DirectBufferPool.useBinaryQuery(
            initialCapacity = 4096,
            defaultResult = PairResult(
                success = false,
                errorCode = -1,
                responderDeviceId = "",
                responderDeviceName = "",
                errorMessage = "Failed to execute native pair handshake",
                longTermKey = null
            ),
            nativeWriter = { rawPtr, cap ->
                MuClipNative.nativeRequestPairZeroCopy(
                    syncHandle,
                    peerAddr,
                    pin,
                    timeoutSecs,
                    rawPtr,
                    cap
                )
            },
            decoder = { buf ->
                val success = buf.readBoolean()
                val errorCode = buf.readInt()
                val responderDeviceId = buf.readString()
                val responderDeviceName = buf.readString()
                val errorMessage = buf.readNullableString()
                val longTermKey = buf.readNullableString()
                PairResult(
                    success = success,
                    errorCode = errorCode,
                    responderDeviceId = responderDeviceId,
                    responderDeviceName = responderDeviceName,
                    errorMessage = errorMessage,
                    longTermKey = longTermKey
                )
            }
        )
    }

    /**
     * Responds to an incoming single-stream pairing handshake request.
     * @param accept true to accept with the PIN entered by local user, false to reject
     * @return 0 = success, 1 = request expired/not found, 2 = wrong PIN, -1 = error
     */
    fun respondPair(requestId: String, pin: String, accept: Boolean): Int {
        checkHandle()
        return MuClipNative.nativeRespondPair(syncHandle, requestId, pin, accept)
    }

    /**
     * Tests connectivity and end-to-end encryption validity with a peer.
     * @return Round-trip latency in milliseconds if successful, or -1 on failure
     */
    suspend fun testPeer(peerAddr: String): Int = withContext(Dispatchers.IO) {
        checkHandle()
        MuClipNative.nativeTestPeer(syncHandle, peerAddr)
    }

    /**
     * Registers native event listener for clipboard sync, pairing requests, and pairing success.
     */
    fun registerCallback(callback: MuClipSyncCallback): Boolean {
        checkHandle()
        return MuClipNative.nativeRegisterSyncCallback(syncHandle, callback) == 0
    }

    /**
     * Initiates a high-speed encrypted file transfer to a peer.
     * @param displayName original file display name (e.g. document.pdf), avoiding Android /proc/self/fd numeric fallback
     * @return transferId on success, or empty string on error
     */
    fun sendFile(peerAddr: String, filePath: String, displayName: String = ""): String {
        checkHandle()
        return MuClipNative.nativeSendFile(syncHandle, peerAddr, filePath, displayName)
    }

    /**
     * Cancels an active file transfer.
     */
    fun cancelFileTransfer(transferId: String): Boolean {
        checkHandle()
        return MuClipNative.nativeCancelFileTransfer(syncHandle, transferId)
    }

    /**
     * Configures the destination directory for incoming files.
     */
    fun setFileSaveDir(saveDir: String): Boolean {
        checkHandle()
        return MuClipNative.nativeSetFileSaveDir(syncHandle, saveDir) == 0
    }

    /**
     * Copies the 32-byte bulk file transfer session key into the pre-allocated `outKey` buffer.
     */
    fun getBulkSessionKey(outKey: ByteArray): Boolean {
        checkHandle()
        return MuClipNative.nativeGetBulkSessionKey(syncHandle, outKey) == 0
    }

    fun encodeFileOfferPacket(
        transferId: String,
        fileName: String,
        fileSize: Long,
        chunkSize: Int,
        totalChunks: Long,
        streamSalt: ByteArray,
        outPacket: ByteArray
    ): Int {
        checkHandle()
        return MuClipNative.nativeEncodeFileOfferPacket(
            syncHandle,
            transferId,
            fileName,
            fileSize,
            chunkSize,
            totalChunks,
            streamSalt,
            outPacket
        )
    }

    fun decodeFileResponsePacket(packet: ByteArray, packetLen: Int, transferId: String): Int {
        checkHandle()
        return MuClipNative.nativeDecodeFileResponsePacket(syncHandle, packet, packetLen, transferId)
    }

    fun deriveBulkKey(peerKey: ByteArray, streamSalt: ByteArray, outBulkKey: ByteArray): Int {
        return MuClipNative.nativeDeriveBulkKey(peerKey, streamSalt, outBulkKey)
    }

    fun encodeFileResponsePacket(
        transferId: String,
        accepted: Boolean,
        reason: String,
        outPacket: ByteArray,
        peerKey: ByteArray? = null
    ): Int {
        checkHandle()
        return MuClipNative.nativeEncodeFileResponsePacket(syncHandle, peerKey, transferId, accepted, reason, outPacket)
    }

    fun encodeFileFinishPacket(
        transferId: String,
        totalChunks: Long,
        totalBytes: Long,
        outPacket: ByteArray
    ): Int {
        checkHandle()
        return MuClipNative.nativeEncodeFileFinishPacket(syncHandle, transferId, totalChunks, totalBytes, outPacket)
    }

    fun decodeFileFinishPacket(
        packet: ByteArray,
        packetLen: Int,
        transferId: String,
        expectedChunks: Long,
        expectedBytes: Long,
        peerKey: ByteArray? = null
    ): Int {
        checkHandle()
        return MuClipNative.nativeDecodeFileFinishPacket(
            syncHandle,
            peerKey,
            packet,
            packetLen,
            transferId,
            expectedChunks,
            expectedBytes
        )
    }

    private fun readPeersBinary(buf: LeasedBuffer): List<PeerInfo> {
        val count = buf.readInt()
        if (count <= 0) return emptyList()
        val list = ArrayList<PeerInfo>(count)
        for (i in 0 until count) {
            val syncPort = buf.readInt()
            val lastSeenMs = buf.readLong()
            val isOnline = buf.readBoolean()
            val isPaired = buf.readBoolean()
            val isManual = buf.readBoolean()
            val deviceId = buf.readString()
            val deviceName = buf.readString()
            val deviceType = buf.readString()
            val ip = buf.readString()
            val fingerprint = buf.readString()
            list.add(
                PeerInfo(
                    deviceId = deviceId,
                    deviceName = deviceName,
                    deviceType = deviceType,
                    ip = ip,
                    syncPort = syncPort,
                    lastSeenMs = lastSeenMs,
                    isOnline = isOnline,
                    fingerprint = fingerprint,
                    isPaired = isPaired,
                    isManual = isManual
                )
            )
        }
        return list
    }

    override fun close() {
        if (syncHandle != 0L) {
            MuClipNative.nativeStopSync(syncHandle)
            syncHandle = 0L
        }
    }
}
