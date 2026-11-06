package com.muclip.core

import java.nio.ByteBuffer

/**
 * Raw JNI bindings for `muclip_core` native shared library.
 * Direct off-heap pointers and DirectByteBuffers are utilized for 100% zero-copy performance.
 */
object MuClipNative {

    @Volatile
    var isLoaded: Boolean = false
        private set

    init {
        try {
            System.loadLibrary("muclip_core")
            isLoaded = true
        } catch (e: UnsatisfiedLinkError) {
            isLoaded = false
            // Fallback for non-standard library paths or tests
            System.err.println("[MuClipNative] Failed to load 'muclip_core' from java.library.path: ${e.message}")
        } catch (e: Exception) {
            isLoaded = false
            System.err.println("[MuClipNative] Unexpected error loading 'muclip_core': ${e.message}")
        }
    }

    // -------------------------------------------------------------------------
    // Native Off-Heap Buffer Pool
    // -------------------------------------------------------------------------

    @JvmStatic
    external fun nativeAcquireBuffer(minCapacity: Long, outInfo: LongArray): ByteBuffer?

    @JvmStatic
    external fun nativeReleaseBuffer(leaseId: Long): Boolean

    @JvmStatic
    external fun nativeWrapDirectBuffer(rawPtr: Long, capacity: Long): ByteBuffer?

    // -------------------------------------------------------------------------
    // Core Engine Lifecycle
    // -------------------------------------------------------------------------

    @JvmStatic
    external fun nativeInit(dbPath: String): Long

    /**
     * 打开（或首次创建）本地库。keyHex 为 64 位十六进制原始密钥，null 表示明文。
     *
     * 注意：这需要 native 库以 `sqlcipher` feature 构建；明文版构建会忽略密钥并如实
     * 通过 [nativeIsEncrypted] 报告 0，不会假装加密成功。
     */
    @JvmStatic
    external fun nativeInitWithKey(dbPath: String, keyHex: String?): Long

    @JvmStatic
    external fun nativeInitInMemory(): Long

    @JvmStatic
    external fun nativeClose(handle: Long)

    // -------------------------------------------------------------------------
    // 本地库加密状态 & 加密备份 (.mubc)
    // -------------------------------------------------------------------------

    /** 1 = 加密真实生效, 0 = 明文, -1 = 句柄无效 */
    @JvmStatic
    external fun nativeIsEncrypted(handle: Long): Int

    /**
     * 导出加密备份。返回 JSON 信封：
     * 成功 `{"ok":true,"data":{...}}`，失败 `{"ok":false,"error":"..."}`。
     */
    @JvmStatic
    external fun nativeExportDb(handle: Long, exportPath: String, password: String): String?

    /**
     * 导入加密备份。replaceAll 为 true 时完全覆盖，false 时合并。
     * 返回格式同 [nativeExportDb]。
     */
    @JvmStatic
    external fun nativeImportDb(
        handle: Long,
        importPath: String,
        password: String,
        replaceAll: Boolean
    ): String?

    // -------------------------------------------------------------------------
    // Zero-Copy Clipboard History Operations
    // -------------------------------------------------------------------------

    @JvmStatic
    external fun nativeAddHistoryZeroCopy(handle: Long, bufferPtr: Long, length: Int): Long

    @JvmStatic
    external fun nativeQueryHistoryZeroCopy(handle: Long, limit: Int, outBufferPtr: Long, outCapacity: Int): Int

    @JvmStatic
    external fun nativeSearchHistoryZeroCopy(
        handle: Long,
        queryPtr: Long,
        queryLen: Int,
        limit: Int,
        outBufferPtr: Long,
        outCapacity: Int
    ): Int

    @JvmStatic
    external fun nativeDeleteHistory(handle: Long, id: Long): Int

    @JvmStatic
    external fun nativeClearHistory(handle: Long): Int

    @JvmStatic
    external fun nativeCleanupHistory(handle: Long, days: Int): Int

    @JvmStatic
    external fun nativeSetConfig(handle: Long, key: String, value: String): Int

    @JvmStatic
    external fun nativeGetConfig(handle: Long, key: String): String?

    // -------------------------------------------------------------------------
    // Zero-Copy Phrases Operations
    // -------------------------------------------------------------------------

    @JvmStatic
    external fun nativeAddPhraseZeroCopy(handle: Long, bufferPtr: Long, length: Int): Long

    @JvmStatic
    external fun nativeQueryPhrasesZeroCopy(handle: Long, outBufferPtr: Long, outCapacity: Int): Int

    @JvmStatic
    external fun nativeDeletePhrase(handle: Long, id: Long): Int

    @JvmStatic
    external fun nativeClearPhrases(handle: Long): Int

    // -------------------------------------------------------------------------
    // Zero-Copy Content Formatting & Analysis
    // -------------------------------------------------------------------------

    @JvmStatic
    external fun nativeFormatPreviewZeroCopy(
        textPtr: Long,
        textLen: Int,
        maxChars: Int,
        outBufferPtr: Long,
        outCapacity: Int
    ): Int

    @JvmStatic
    external fun nativeAnalyzeContentZeroCopy(
        textPtr: Long,
        textLen: Int,
        outBufferPtr: Long,
        outCapacity: Int
    ): Int

    // -------------------------------------------------------------------------
    // Zero-Copy E2EE Crypto Operations (ChaCha20-Poly1305 + HKDF)
    // -------------------------------------------------------------------------

    @JvmStatic
    external fun nativeDeriveKey(pin: String, outKeyPtr: Long): Int

    @JvmStatic
    external fun nativeEncryptZeroCopy(
        keyPtr: Long,
        noncePtr: Long,
        inPtr: Long,
        inLen: Int,
        outPtr: Long,
        outCap: Int
    ): Int

    @JvmStatic
    external fun nativeDecryptZeroCopy(
        keyPtr: Long,
        noncePtr: Long,
        inPtr: Long,
        inLen: Int,
        outPtr: Long,
        outCap: Int
    ): Int

    // -------------------------------------------------------------------------
    // In-Place Streaming AEAD Crypto Operations (XChaCha20-Poly1305)
    // -------------------------------------------------------------------------

    @JvmStatic
    external fun nativeEncryptInPlace(
        key: ByteArray,
        buffer: ByteArray,
        plaintextLength: Int,
        nonce: ByteArray,
        aad: ByteArray
    ): Int

    @JvmStatic
    external fun nativeDecryptInPlace(
        key: ByteArray,
        buffer: ByteArray,
        ciphertextAndTagLength: Int,
        nonce: ByteArray,
        aad: ByteArray
    ): Int

    @JvmStatic
    external fun nativeDeriveChunkNonce(
        fileNonce: ByteArray,
        chunkIndex: Long,
        outNonce: ByteArray
    ): Int

    @JvmStatic
    external fun nativeBuildChunkAad(
        fileIdBytes: ByteArray,
        chunkIndex: Long,
        chunkLength: Int,
        totalFileSize: Long,
        outAad: ByteArray
    ): Int

    // -------------------------------------------------------------------------
    // LAN Sync & mDNS P2P Engine
    // -------------------------------------------------------------------------

    @JvmStatic
    external fun nativeStartSync(
        deviceId: String,
        deviceName: String,
        deviceType: String,
        syncPort: Int,
        pairingPin: String
    ): Long

    @JvmStatic
    external fun nativeStopSync(syncHandle: Long)

    @JvmStatic
    external fun nativeBroadcastClipboardZeroCopy(
        syncHandle: Long,
        bufferPtr: Long,
        length: Int,
        contentType: String
    ): Int

    @JvmStatic
    external fun nativeGetActivePeersZeroCopy(
        syncHandle: Long,
        outBufferPtr: Long,
        outCapacity: Int
    ): Int

    @JvmStatic
    external fun nativeGetActivePeersJson(
        syncHandle: Long,
        outBufferPtr: Long,
        outCapacity: Int
    ): Int

    @JvmStatic
    external fun nativeGetAllPeersZeroCopy(
        syncHandle: Long,
        outBufferPtr: Long,
        outCapacity: Int
    ): Int

    @JvmStatic
    external fun nativeSetPairingPin(syncHandle: Long, pairingPin: String): Int

    @JvmStatic
    external fun nativeAddManualPeer(syncHandle: Long, peerAddr: String): Int

    @JvmStatic
    external fun nativeRemoveManualPeer(syncHandle: Long, peerAddr: String): Int

    @JvmStatic
    external fun nativeGetManualPeersZeroCopy(
        syncHandle: Long,
        outBufferPtr: Long,
        outCapacity: Int
    ): Int

    @JvmStatic
    external fun nativeGetManualPeersJson(
        syncHandle: Long,
        outBufferPtr: Long,
        outCapacity: Int
    ): Int

    // -------------------------------------------------------------------------
    // Selective Scan & Discovery Controls
    // -------------------------------------------------------------------------

    @JvmStatic
    external fun nativeSetAutoDiscovery(syncHandle: Long, enabled: Boolean): Int

    @JvmStatic
    external fun nativeIsAutoDiscovery(syncHandle: Long): Boolean

    @JvmStatic
    external fun nativeTriggerScan(syncHandle: Long): Int

    // -------------------------------------------------------------------------
    // Paired Devices Management
    // -------------------------------------------------------------------------

    @JvmStatic
    external fun nativeAddPairedDevice(syncHandle: Long, deviceId: String): Int

    @JvmStatic
    external fun nativeRemovePairedDevice(syncHandle: Long, deviceId: String): Int

    // -------------------------------------------------------------------------
    // Single-Stream Random PIN Handshake Pairing (One-Way)
    // -------------------------------------------------------------------------

    @JvmStatic
    external fun nativeRequestPairZeroCopy(
        syncHandle: Long,
        peerAddr: String,
        pin: String,
        timeoutSecs: Int,
        outBufferPtr: Long,
        outCapacity: Int
    ): Int

    @JvmStatic
    external fun nativeRespondPair(
        syncHandle: Long,
        requestId: String,
        pin: String,
        accept: Boolean
    ): Int

    // -------------------------------------------------------------------------
    // Test Paired Peer Connection
    // -------------------------------------------------------------------------

    @JvmStatic
    external fun nativeTestPeer(syncHandle: Long, peerAddr: String): Int

    // -------------------------------------------------------------------------
    // Event Callbacks Registration
    // -------------------------------------------------------------------------

    @JvmStatic
    external fun nativeRegisterSyncCallback(syncHandle: Long, callback: MuClipSyncCallback): Int

    // -------------------------------------------------------------------------
    // High-Speed Encrypted File Transfer
    // -------------------------------------------------------------------------

    @JvmStatic
    external fun nativeSendFile(
        syncHandle: Long,
        peerAddr: String,
        filePath: String,
        displayName: String
    ): String

    @JvmStatic
    external fun nativeCancelFileTransfer(syncHandle: Long, transferId: String): Boolean

    @JvmStatic
    external fun nativeSetFileSaveDir(syncHandle: Long, saveDir: String): Int

    @JvmStatic
    external fun nativeGetBulkSessionKey(syncHandle: Long, outKey: ByteArray): Int

    @JvmStatic
    external fun nativeDeriveBulkKey(peerKey: ByteArray, streamSalt: ByteArray, outBulkKey: ByteArray): Int

    @JvmStatic
    external fun nativeEncodeFileOfferPacket(
        syncHandle: Long,
        transferId: String,
        fileName: String,
        fileSize: Long,
        chunkSize: Int,
        totalChunks: Long,
        streamSalt: ByteArray,
        outPacket: ByteArray
    ): Int

    @JvmStatic
    external fun nativeDecodeFileResponsePacket(
        syncHandle: Long,
        packet: ByteArray,
        packetLen: Int,
        transferId: String
    ): Int

    @JvmStatic
    external fun nativeEncodeFileResponsePacket(
        syncHandle: Long,
        peerKey: ByteArray?,
        transferId: String,
        accepted: Boolean,
        reason: String,
        outPacket: ByteArray
    ): Int

    @JvmStatic
    external fun nativeEncodeFileFinishPacket(
        syncHandle: Long,
        transferId: String,
        totalChunks: Long,
        totalBytes: Long,
        outPacket: ByteArray
    ): Int

    @JvmStatic
    external fun nativeDecodeFileFinishPacket(
        syncHandle: Long,
        peerKey: ByteArray?,
        packet: ByteArray,
        packetLen: Int,
        transferId: String,
        expectedChunks: Long,
        expectedBytes: Long
    ): Int
}
