package com.muclip.core

/**
 * Global callback interface for MuClip native P2P events:
 * - Clipboard payloads received from remote peers
 * - Incoming single-stream handshake pairing requests with random PIN
 * - Pairing successfully established
 */
interface MuClipSyncCallback {

    /**
     * Invoked when a remote peer sends clipboard content over encrypted TCP stream.
     */
    fun onClipboardReceived(
        deviceId: String,
        deviceName: String,
        content: String,
        contentType: String
    )

    /**
     * Invoked when a remote peer initiates a single-stream pairing handshake request.
     * Android displays a dialog/notification with the target peer's identity, asking
     * the local user to input the random PIN displayed on the requester's screen.
     */
    fun onPairRequestReceived(
        requestId: String,
        fromDeviceId: String,
        fromDeviceName: String,
        clientIp: String
    )

    /**
     * Invoked when a pairing handshake has successfully verified the random PIN
     * and derived the mutual E2EE session key.
     */
    fun onPairSuccess(
        fromDeviceId: String,
        fromDeviceName: String,
        clientIp: String,
        pin: String
    )

    /**
     * Invoked when a remote peer sends an unpair notification or rejects synchronization.
     */
    fun onPeerUnpaired(deviceId: String)

    /**
     * Invoked when the SAME peer keeps failing the PFS session handshake (3 in a row;
     * one successful sync resets the counter).
     *
     * This is a **hint only** — the pairing and its key are left untouched, because
     * "auth rejected" is deliberately unspecific: the peer may have unpaired us, its key
     * may differ (PIN changed / app reinstalled), or the endpoint may point at another device.
     * Show a tip and let the user decide whether to re-pair or delete the device.
     */
    fun onPeerAuthRejected(deviceId: String, streak: Int)

    /**
     * Invoked when a remote peer initiates a high-speed file transfer offer.
     */
    fun onFileOffer(
        transferId: String,
        fromDeviceId: String,
        fromDeviceName: String,
        fileName: String,
        fileSize: Long
    )

    /**
     * Invoked periodically during file transfer to report real-time speed and bytes transferred.
     */
    fun onFileProgress(
        transferId: String,
        bytesTransferred: Long,
        totalBytes: Long,
        speedBps: Long,
        isSend: Boolean
    )

    /**
     * Invoked when file transfer completes or fails.
     */
    fun onFileCompleted(
        transferId: String,
        filePath: String,
        isSend: Boolean,
        success: Boolean,
        errorMsg: String
    )

    /**
     * Invoked when an incoming file offer is accepted over TCP.
     *
     * Returning `true` transfers ownership of `socketFd` to the Kotlin receiver, which must
     * decrypt the stream and write it straight into a MediaStore-backed output stream —
     * this avoids the private staging file entirely. Return `false` to let the Rust receiver
     * handle it with the staging-file path.
     */
    fun onIncomingFileStream(
        transferId: String,
        fromDeviceId: String,
        fromDeviceName: String,
        fileName: String,
        fileSize: Long,
        chunkSize: Int,
        totalChunks: Long,
        streamSalt: ByteArray,
        socketFd: Int,
        peerKey: ByteArray
    ): Boolean = false
}
