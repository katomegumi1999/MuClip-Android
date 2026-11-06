package com.muclip.core

import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Streaming, in-place AEAD encrypted file transfer engine for Android/Kotlin.
 *
 * 关键不变量：
 * - 定长 `ByteArray(CHUNK_SIZE + TAG_SIZE)` 只在传输循环外分配一次
 * - 定长 `nonce`（24B）与 `aad`（57B）缓冲区每轮原地覆写
 * - 循环内零 `ByteArray(...)` / `copyOf(...)` / `toByteArray()` / `readBytes()`
 * - 直接从 socket / SAF `InputStream` 流式处理，不落 staging 缓存文件
 * - 使用 XChaCha20-Poly1305，每块派生唯一确定性 nonce + 57 字节上下文 AAD
 * - 接收端解密后直接写目标流，完整性要求三者同时成立：
 *   `receivedChunkCount == expectedChunkCount`、`receivedBytes == expectedFileSize`、
 *   `lastChunkVerified`（最后一块通过 AEAD 认证），以及带认证的 `FileFinish` 控制帧
 */
object StreamingFileTransfer {

    const val CHUNK_SIZE: Int = 128 * 1024
    const val TAG_SIZE: Int = 16

    private const val NONCE_SIZE: Int = NativeXChaCha20Poly1305Aead.NONCE_SIZE
    private const val AAD_SIZE: Int = NativeXChaCha20Poly1305Aead.AAD_SIZE
    private const val FILE_ID_AAD_LEN: Int = NativeXChaCha20Poly1305Aead.FILE_ID_AAD_LEN
    private const val HEADER_SIZE: Int = 44

    private val cancelFlags = ConcurrentHashMap<String, AtomicBoolean>()

    fun registerCancelFlag(transferId: String): AtomicBoolean {
        val flag = AtomicBoolean(false)
        cancelFlags[transferId] = flag
        return flag
    }

    fun unregisterCancelFlag(transferId: String) {
        cancelFlags.remove(transferId)
    }

    fun cancelTransfer(transferId: String): Boolean {
        val flag = cancelFlags[transferId]
        if (flag != null) {
            flag.set(true)
            return true
        }
        return false
    }

    /**
     * 接收对端传来的文件，解密后直接写入 [fileOut]——通常是 MediaStore 指向公共
     * Download 目录的输出流。
     *
     * 与已删除的「staging 临时文件 + 原子改名」路径相比，这里解密后直接写最终位置，
     * 省掉一次整体拷贝。纯数据搬运在 [receiveChunksInto] 里，协议层
     * （回应包 / FileFinish / "OK"）留在本方法。
     */
    fun receiveToStream(
        sync: MuClipSync,
        aead: InPlaceAead,
        transferId: String,
        expectedFileSize: Long,
        chunkSize: Int,
        expectedChunkCount: Long,
        fileNonce: ByteArray,
        sockIn: InputStream,
        sockOut: OutputStream,
        fileOut: OutputStream,
        cancelFlag: AtomicBoolean? = null,
        peerKey: ByteArray? = null,
        onProgress: (bytesTransferred: Long, totalBytes: Long, speedBps: Long) -> Unit
    ) {
        // 固定缓冲全链路只分配一次，控制帧读写复用它
        val buffer = ByteArray(CHUNK_SIZE + TAG_SIZE)

        // 1. 先回「接受」响应，对端收到后才开始推流
        val respLen = sync.encodeFileResponsePacket(
            transferId = transferId,
            accepted = true,
            reason = "",
            outPacket = buffer,
            peerKey = peerKey
        )
        if (respLen <= 0) {
            throw IOException("编码文件接收响应失败 ($respLen)")
        }
        sockOut.write(buffer, 0, respLen)
        sockOut.flush()

        // 2. 数据通道：读 → AEAD 认证 → 原地解密 → 写目标流（含完整性校验）
        receiveChunksInto(
            aead = aead,
            transferId = transferId,
            expectedFileSize = expectedFileSize,
            chunkSize = chunkSize,
            expectedChunkCount = expectedChunkCount,
            fileNonce = fileNonce,
            sockIn = sockIn,
            fileOut = fileOut,
            cancelFlag = cancelFlag,
            buffer = buffer,
            onProgress = onProgress
        )

        // 3. 校验对端发来的带认证 FileFinish 控制帧
        val effectiveExpectedChunks = resolveExpectedChunks(
            expectedFileSize = expectedFileSize,
            effectiveChunkSize = resolveChunkSize(chunkSize),
            expectedChunkCount = expectedChunkCount
        )
        val finishPacketLen = readControlPacket(sockIn, buffer)
        val finishValid = sync.decodeFileFinishPacket(
            packet = buffer,
            packetLen = finishPacketLen,
            transferId = transferId,
            expectedChunks = effectiveExpectedChunks,
            expectedBytes = expectedFileSize,
            peerKey = peerKey
        )
        if (finishValid != 1) {
            throw SecurityException("文件结束控制帧校验失败 ($finishValid)")
        }

        // 4. 回 2 字节 "OK" 告诉对端可以收尾
        buffer[0] = 'O'.code.toByte()
        buffer[1] = 'K'.code.toByte()
        sockOut.write(buffer, 0, 2)
        sockOut.flush()
    }

    /** 接收结果，供调用方核对，也方便单测断言。 */
    internal data class ReceiveOutcome(val chunkCount: Long, val totalBytes: Long)

    /**
     * 分块接收主循环：读定长密文 → AEAD 认证 → 原地解密 → 写 [fileOut]。
     *
     * 只做数据搬运与完整性校验，**不碰任何协议控制帧**（回应包 / FileFinish / "OK"），
     * 因此可以脱离 [MuClipSync] 直接单测——实时传输这条路径以前没有任何测试覆盖
     * （以前那批测试测的是已删除的 staging 文件版本）。
     *
     * 关键不变量：全程复用调用方传入的 [buffer]，循环内零 `ByteArray(...)` 分配。
     *
     * @throws SecurityException 某个分块认证失败，或最后一个分块未通过认证
     * @throws IOException 传输被取消、流提前结束，或分块计数 / 字节数与声明不符
     */
    internal fun receiveChunksInto(
        aead: InPlaceAead,
        transferId: String,
        expectedFileSize: Long,
        chunkSize: Int,
        expectedChunkCount: Long,
        fileNonce: ByteArray,
        sockIn: InputStream,
        fileOut: OutputStream,
        cancelFlag: AtomicBoolean?,
        buffer: ByteArray,
        onProgress: (bytesTransferred: Long, totalBytes: Long, speedBps: Long) -> Unit
    ): ReceiveOutcome {
        require(buffer.size >= CHUNK_SIZE + TAG_SIZE) {
            "接收缓冲区过小: ${buffer.size} < ${CHUNK_SIZE + TAG_SIZE}"
        }

        val effectiveChunkSize = resolveChunkSize(chunkSize)
        val effectiveExpectedChunks =
            resolveExpectedChunks(expectedFileSize, effectiveChunkSize, expectedChunkCount)

        val nonce = ByteArray(NONCE_SIZE)
        val aad = ByteArray(AAD_SIZE)
        val fileIdBytes = ByteArray(FILE_ID_AAD_LEN)
        NativeXChaCha20Poly1305Aead.encodeFileIdInPlace(transferId, fileIdBytes)

        var receivedChunkCount = 0L
        var receivedBytes = 0L
        var lastChunkVerified = (expectedFileSize == 0L && effectiveExpectedChunks == 0L)

        var lastSpeedTimeNs = System.nanoTime()
        var bytesSinceLast = 0L

        while (receivedBytes < expectedFileSize) {
            if (cancelFlag?.get() == true) {
                throw IOException("传输已由用户取消")
            }

            val remaining = expectedFileSize - receivedBytes
            val expectedPlainLen = if (remaining < effectiveChunkSize.toLong()) {
                remaining.toInt()
            } else {
                effectiveChunkSize
            }
            val expectedEncryptedLen = expectedPlainLen + TAG_SIZE

            readFully(sockIn, buffer, 0, expectedEncryptedLen)

            NativeXChaCha20Poly1305Aead.deriveNonceInPlace(
                fileNonce = fileNonce,
                chunkIndex = receivedChunkCount,
                outNonce = nonce
            )
            NativeXChaCha20Poly1305Aead.buildAadInPlace(
                fileIdBytes = fileIdBytes,
                chunkIndex = receivedChunkCount,
                chunkLength = expectedPlainLen,
                totalFileSize = expectedFileSize,
                outAad = aad
            )

            val decryptedLen = aead.decryptInPlace(
                buffer = buffer,
                ciphertextAndTagLength = expectedEncryptedLen,
                nonce = nonce,
                aad = aad
            )
            if (decryptedLen != expectedPlainLen) {
                throw SecurityException("分块 $receivedChunkCount 认证或长度校验失败")
            }

            fileOut.write(buffer, 0, decryptedLen)

            receivedBytes += decryptedLen
            bytesSinceLast += decryptedLen
            receivedChunkCount++

            if (receivedChunkCount == effectiveExpectedChunks && receivedBytes == expectedFileSize) {
                lastChunkVerified = true
            }

            val nowNs = System.nanoTime()
            val elapsedNs = nowNs - lastSpeedTimeNs
            if (elapsedNs >= 250_000_000L || receivedBytes == expectedFileSize) {
                val speedBps = if (elapsedNs > 0L) {
                    (bytesSinceLast * 1_000_000_000L) / elapsedNs
                } else {
                    0L
                }
                onProgress(receivedBytes, expectedFileSize, speedBps)
                lastSpeedTimeNs = nowNs
                bytesSinceLast = 0L
            }
        }
        fileOut.flush()

        // 严格完整性校验：分块数、字节数、最后一块认证，三者必须同时成立
        if (receivedChunkCount != effectiveExpectedChunks) {
            throw IOException(
                "文件完整性校验失败: receivedChunkCount ($receivedChunkCount) != expectedChunkCount ($effectiveExpectedChunks)"
            )
        }
        if (receivedBytes != expectedFileSize) {
            throw IOException(
                "文件完整性校验失败: receivedBytes ($receivedBytes) != expectedFileSize ($expectedFileSize)"
            )
        }
        if (!lastChunkVerified) {
            throw SecurityException("文件完整性校验失败: 最后一个分块未通过 AEAD 认证")
        }

        return ReceiveOutcome(receivedChunkCount, receivedBytes)
    }

    /** 把对端声明的分块大小规整到合法区间。 */
    private fun resolveChunkSize(chunkSize: Int): Int =
        if (chunkSize in 1..CHUNK_SIZE) chunkSize else CHUNK_SIZE

    /** 推导期望分块数：优先用对端声明的值，否则按文件大小算。 */
    private fun resolveExpectedChunks(
        expectedFileSize: Long,
        effectiveChunkSize: Int,
        expectedChunkCount: Long
    ): Long = when {
        expectedChunkCount > 0L -> expectedChunkCount
        expectedFileSize == 0L -> 0L
        else -> (expectedFileSize + effectiveChunkSize.toLong() - 1L) / effectiveChunkSize.toLong()
    }

    /**
     * Reads exactly [length] bytes into `buffer[offset until offset + length]`,
     * or throws [EOFException] if the stream ends prematurely.
     */
    private fun readFully(input: InputStream, buffer: ByteArray, offset: Int, length: Int) {
        var readSoFar = 0
        while (readSoFar < length) {
            val n = input.read(buffer, offset + readSoFar, length - readSoFar)
            if (n < 0) {
                throw EOFException("连接意外中断 (已读取 $readSoFar / $length 字节)")
            }
            readSoFar += n
        }
    }

    /**
     * Reads a framed MuClip control packet (`44-byte SyncHeader + payload`) into [buffer]
     * without allocating any temporary `ByteArray`.
     */
    private fun readControlPacket(input: InputStream, buffer: ByteArray): Int {
        readFully(input, buffer, 0, HEADER_SIZE)
        // SyncHeader payload_len is u32 big-endian at bytes [40..44]
        val payloadLen = ((buffer[40].toInt() and 0xFF) shl 24) or
            ((buffer[41].toInt() and 0xFF) shl 16) or
            ((buffer[42].toInt() and 0xFF) shl 8) or
            (buffer[43].toInt() and 0xFF)
        if (payloadLen < 0 || HEADER_SIZE + payloadLen > buffer.size) {
            throw IOException("控制帧长度无效: $payloadLen")
        }
        readFully(input, buffer, HEADER_SIZE, payloadLen)
        return HEADER_SIZE + payloadLen
    }
}
