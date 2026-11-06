package com.muclip.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.AEADBadTagException
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * [StreamingFileTransfer] 的接收侧单测。
 *
 * 这些测试打的是**生产环境真正跑的那条路径**：`receiveChunksInto`（由
 * `receiveToStream` 调用，用于把对端文件直接解密写进 MediaStore）。
 * 协议层（回应包 / FileFinish / "OK"）依赖 [MuClipSync] 的 native 实现，
 * 在 host JVM 上跑不了，因此不在覆盖范围内。
 */
class StreamingFileTransferTest {

    /**
     * Pure-JVM reference InPlaceAead used in host unit tests when Android arm64 JNI is not loaded on Windows host.
     * Mutates `buffer[0 until plaintextLength + 16]` in-place and authenticates `(nonce, aad, ciphertext)`
     * via HMAC-SHA256 truncated to 16B.
     */
    private class TestInPlaceAead(private val key: ByteArray) : InPlaceAead {
        override fun encryptInPlace(
            buffer: ByteArray,
            plaintextLength: Int,
            nonce: ByteArray,
            aad: ByteArray
        ): Int {
            // Stream-XOR in place
            for (i in 0 until plaintextLength) {
                val mask = (key[i % key.size].toInt() xor nonce[i % nonce.size].toInt() xor (i and 0xFF)).toByte()
                buffer[i] = (buffer[i].toInt() xor mask.toInt()).toByte()
            }
            val tag = computeTag(buffer, plaintextLength, nonce, aad)
            System.arraycopy(tag, 0, buffer, plaintextLength, 16)
            return plaintextLength + 16
        }

        override fun decryptInPlace(
            buffer: ByteArray,
            ciphertextAndTagLength: Int,
            nonce: ByteArray,
            aad: ByteArray
        ): Int {
            if (ciphertextAndTagLength < 16) throw AEADBadTagException("Too short")
            val plaintextLength = ciphertextAndTagLength - 16
            val expectedTag = computeTag(buffer, plaintextLength, nonce, aad)
            for (i in 0 until 16) {
                if (buffer[plaintextLength + i] != expectedTag[i]) {
                    throw AEADBadTagException("Poly1305 tag mismatch")
                }
            }
            for (i in 0 until plaintextLength) {
                val mask = (key[i % key.size].toInt() xor nonce[i % nonce.size].toInt() xor (i and 0xFF)).toByte()
                buffer[i] = (buffer[i].toInt() xor mask.toInt()).toByte()
            }
            return plaintextLength
        }

        private fun computeTag(buffer: ByteArray, ciphertextLen: Int, nonce: ByteArray, aad: ByteArray): ByteArray {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(key, "HmacSHA256"))
            mac.update(nonce)
            mac.update(aad)
            mac.update(buffer, 0, ciphertextLen)
            return mac.doFinal()
        }
    }

    /** 按发送端逻辑把 [plain] 切成定长块并原地加密，得到完整线上字节流。 */
    private fun encodeWire(
        aead: InPlaceAead,
        transferId: String,
        plain: ByteArray,
        fileNonce: ByteArray
    ): ByteArray {
        val fileIdBytes = ByteArray(36)
        NativeXChaCha20Poly1305Aead.encodeFileIdInPlace(transferId, fileIdBytes)

        val wireOut = ByteArrayOutputStream()
        val sendBuffer = ByteArray(StreamingFileTransfer.CHUNK_SIZE + StreamingFileTransfer.TAG_SIZE)
        val nonce = ByteArray(24)
        val aad = ByteArray(57)
        val input = ByteArrayInputStream(plain)
        var chunkIndex = 0L
        while (true) {
            val length = input.read(sendBuffer, 0, StreamingFileTransfer.CHUNK_SIZE)
            if (length < 0) break
            NativeXChaCha20Poly1305Aead.deriveNonceInPlace(fileNonce, chunkIndex, nonce)
            NativeXChaCha20Poly1305Aead.buildAadInPlace(
                fileIdBytes = fileIdBytes,
                chunkIndex = chunkIndex,
                chunkLength = length,
                totalFileSize = plain.size.toLong(),
                outAad = aad
            )
            val encryptedLength = aead.encryptInPlace(sendBuffer, length, nonce, aad)
            assertEquals(length + 16, encryptedLength)
            wireOut.write(sendBuffer, 0, encryptedLength)
            chunkIndex++
        }
        return wireOut.toByteArray()
    }

    /** 调一次接收主循环，返回写出的明文。 */
    private fun receive(
        aead: InPlaceAead,
        transferId: String,
        fileSize: Long,
        chunkCount: Long,
        fileNonce: ByteArray,
        wire: ByteArray,
        cancelFlag: AtomicBoolean? = AtomicBoolean(false)
    ): ByteArray {
        val out = ByteArrayOutputStream()
        StreamingFileTransfer.receiveChunksInto(
            aead = aead,
            transferId = transferId,
            expectedFileSize = fileSize,
            chunkSize = StreamingFileTransfer.CHUNK_SIZE,
            expectedChunkCount = chunkCount,
            fileNonce = fileNonce,
            sockIn = ByteArrayInputStream(wire),
            fileOut = out,
            cancelFlag = cancelFlag,
            buffer = ByteArray(StreamingFileTransfer.CHUNK_SIZE + StreamingFileTransfer.TAG_SIZE),
            onProgress = { _, _, _ -> }
        )
        return out.toByteArray()
    }

    @Test
    fun testDeterministicNonceUniqueness() {
        val fileNonce = ByteArray(16) { (it + 1).toByte() }
        val nonce0 = ByteArray(24)
        val nonce1 = ByteArray(24)
        val nonce2 = ByteArray(24)

        NativeXChaCha20Poly1305Aead.deriveNonceInPlace(fileNonce, 0L, nonce0)
        NativeXChaCha20Poly1305Aead.deriveNonceInPlace(fileNonce, 1L, nonce1)
        NativeXChaCha20Poly1305Aead.deriveNonceInPlace(fileNonce, 2L, nonce2)

        assertFalse(nonce0.contentEquals(nonce1))
        assertFalse(nonce1.contentEquals(nonce2))
        assertFalse(nonce0.contentEquals(nonce2))
        assertEquals(0.toByte(), nonce0[23])
        assertEquals(1.toByte(), nonce1[23])
        assertEquals(2.toByte(), nonce2[23])
    }

    @Test
    fun testChunkAadBinaryLayout() {
        val fileIdBytes = ByteArray(36)
        NativeXChaCha20Poly1305Aead.encodeFileIdInPlace("file_abc_123", fileIdBytes)
        val aad0 = ByteArray(57)
        val aad1 = ByteArray(57)

        NativeXChaCha20Poly1305Aead.buildAadInPlace(
            fileIdBytes = fileIdBytes,
            chunkIndex = 0L,
            chunkLength = 131072,
            totalFileSize = 300000L,
            outAad = aad0
        )
        NativeXChaCha20Poly1305Aead.buildAadInPlace(
            fileIdBytes = fileIdBytes,
            chunkIndex = 1L,
            chunkLength = 131072,
            totalFileSize = 300000L,
            outAad = aad1
        )

        assertEquals(1.toByte(), aad0[0])
        assertFalse(aad0.contentEquals(aad1))
    }

    /**
     * 2 个整块 + 1 个残块（300 KiB → 128K + 128K + 44K），逐字节还原。
     */
    @Test
    fun testStreamingRoundtripAcrossFullAndPartialChunks() {
        val key = ByteArray(32) { (it * 7 + 3).toByte() }
        val aead = TestInPlaceAead(key)
        val fileNonce = ByteArray(16) { (it + 0x20).toByte() }
        val transferId = "transfer_test_001"

        val original = ByteArray(300 * 1024) { ((it * 31) and 0xFF).toByte() }
        val fileSize = original.size.toLong()
        val totalChunks = (fileSize + StreamingFileTransfer.CHUNK_SIZE - 1) / StreamingFileTransfer.CHUNK_SIZE
        assertEquals(3L, totalChunks)

        val wire = encodeWire(aead, transferId, original, fileNonce)
        // 每块都多 16 字节认证标签
        assertEquals(original.size + 3 * 16, wire.size)

        val received = receive(aead, transferId, fileSize, totalChunks, fileNonce, wire)
        assertArrayEquals(original, received)
    }

    /**
     * 篡改任意一个字节都必须认证失败——错误必须抛出，不能静默写坏文件。
     */
    @Test
    fun testTamperedChunkFailsAuthentication() {
        val key = ByteArray(32) { (it + 11).toByte() }
        val aead = TestInPlaceAead(key)
        val fileNonce = ByteArray(16) { (it + 5).toByte() }
        val transferId = "transfer_tamper_002"

        val original = ByteArray(150 * 1024) { (it and 0xFF).toByte() }
        val wire = encodeWire(aead, transferId, original, fileNonce)
        // 破坏第二个分块里的一个字节
        wire[wire.size - 20] = (wire[wire.size - 20].toInt() xor 0xFF).toByte()

        var failed = false
        try {
            receive(aead, transferId, original.size.toLong(), 2L, fileNonce, wire)
        } catch (_: Exception) {
            failed = true
        }
        assertTrue("被篡改的流必须抛异常", failed)
    }

    /**
     * 对端声明的分块数与实际不符时，必须在校验阶段拒绝（防止提前收尾截断文件）。
     */
    @Test
    fun testChunkCountMismatchIsRejected() {
        val key = ByteArray(32) { (it * 3 + 1).toByte() }
        val aead = TestInPlaceAead(key)
        val fileNonce = ByteArray(16) { (it + 9).toByte() }
        val transferId = "transfer_mismatch_003"

        val original = ByteArray(200 * 1024) { (it and 0x7F).toByte() }
        val wire = encodeWire(aead, transferId, original, fileNonce)

        var failed = false
        try {
            // 谎报比实际多的分块数
            receive(aead, transferId, original.size.toLong(), 99L, fileNonce, wire)
        } catch (_: Exception) {
            failed = true
        }
        assertTrue("分块数不符必须抛异常", failed)
    }

    /**
     * 取消标志必须在下一块开始前生效。
     */
    @Test
    fun testCancellationAbortsTransfer() {
        val key = ByteArray(32) { (it + 2).toByte() }
        val aead = TestInPlaceAead(key)
        val fileNonce = ByteArray(16) { (it + 3).toByte() }
        val transferId = "transfer_cancel_004"

        val original = ByteArray(300 * 1024) { (it and 0xFF).toByte() }
        val wire = encodeWire(aead, transferId, original, fileNonce)

        var failed = false
        try {
            receive(aead, transferId, original.size.toLong(), 3L, fileNonce, wire, AtomicBoolean(true))
        } catch (_: Exception) {
            failed = true
        }
        assertTrue("取消后必须抛异常", failed)
    }

    /**
     * 空文件（0 字节）走通且不写任何内容。
     */
    @Test
    fun testZeroLengthFile() {
        val key = ByteArray(32) { (it + 4).toByte() }
        val aead = TestInPlaceAead(key)
        val fileNonce = ByteArray(16) { (it + 6).toByte() }

        val received = receive(aead, "transfer_empty_005", 0L, 0L, fileNonce, ByteArray(0))
        assertEquals(0, received.size)
    }
}
