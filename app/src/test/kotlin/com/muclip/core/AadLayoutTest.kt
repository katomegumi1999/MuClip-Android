package com.muclip.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 文件分块 AAD 与 nonce 的二进制布局测试。
 *
 * 为什么这两个函数值得单独测：它们是**跨语言契约**的一部分 —— Kotlin 侧在这里拼
 * 57 字节 AAD，Rust 侧 `sync/file_transfer/` 用同一布局校验。任何一端的字段偏移、
 * 字节序、填充规则改错，症状都不是崩溃，而是「AEAD 认证失败」—— 文件传输直接
 * 全部失败，且错误信息指向 tag 不匹配，看不出是 AAD 拼错了。
 *
 * 这些用例打的是 Kotlin 回退实现（`MuClipNative.isLoaded == false` 时走的路径），
 * 在 host JVM 上可跑；native 版本按设计应当产出**完全相同**的字节。
 */
class AadLayoutTest {

    private val aadSize = NativeXChaCha20Poly1305Aead.AAD_SIZE
    private val fileIdLen = NativeXChaCha20Poly1305Aead.FILE_ID_AAD_LEN

    // ---------------------------------------------------------------------
    // 常量自洽性
    // ---------------------------------------------------------------------

    @Test
    fun `AAD size constant matches documented field layout`() {
        // 1(version) + 36(fileId) + 8(chunkIndex) + 4(chunkLength) + 8(totalFileSize)
        assertEquals(57, aadSize)
        assertEquals(57, 1 + fileIdLen + 8 + 4 + 8)
    }

    @Test
    fun `nonce and key sizes match XChaCha20-Poly1305 requirements`() {
        assertEquals(32, NativeXChaCha20Poly1305Aead.KEY_SIZE)
        assertEquals(24, NativeXChaCha20Poly1305Aead.NONCE_SIZE)
        assertEquals(16, NativeXChaCha20Poly1305Aead.TAG_SIZE)
        assertEquals(16, NativeXChaCha20Poly1305Aead.FILE_NONCE_SIZE)
        // 文件 nonce(16) + chunkIndex(8) 必须正好填满 24 字节 nonce
        assertEquals(
            NativeXChaCha20Poly1305Aead.NONCE_SIZE,
            NativeXChaCha20Poly1305Aead.FILE_NONCE_SIZE + 8
        )
    }

    // ---------------------------------------------------------------------
    // AAD 字段布局
    // ---------------------------------------------------------------------

    @Test
    fun `AAD encodes every field at its documented offset in big-endian`() {
        val fileId = "0123456789abcdef".toByteArray()
        val fileIdBytes = ByteArray(fileIdLen)
        val written = NativeXChaCha20Poly1305Aead.encodeFileIdInPlace(
            "0123456789abcdef", fileIdBytes
        )
        assertEquals(16, written)

        val aad = ByteArray(aadSize)
        NativeXChaCha20Poly1305Aead.buildAadInPlace(
            protocolVersion = 1,
            fileIdBytes = fileIdBytes,
            fileIdLength = written,
            chunkIndex = 0x0102030405060708L,
            chunkLength = 0x11223344,
            totalFileSize = 0x1122334455667788L,
            outAad = aad
        )

        assertEquals("version 位于 [0]", 1, aad[0].toInt())

        // fileId [1..37)，后 20 字节零填充
        assertArrayEquals(fileId, aad.copyOfRange(1, 17))
        for (i in 17 until 37) {
            assertEquals("fileId 尾部应为零填充 (offset $i)", 0, aad[i].toInt())
        }

        // chunkIndex [37..45) 大端
        assertEquals(0x01.toByte(), aad[37])
        assertEquals(0x08.toByte(), aad[44])

        // chunkLength [45..49) 大端
        assertEquals(0x11.toByte(), aad[45])
        assertEquals(0x44.toByte(), aad[48])

        // totalFileSize [49..57) 大端
        assertEquals(0x11.toByte(), aad[49])
        assertEquals(0x88.toByte(), aad[56])
    }

    @Test
    fun `fileId longer than 36 bytes is truncated not overflowed`() {
        val longId = "x".repeat(60)
        val fileIdBytes = ByteArray(fileIdLen)
        val written = NativeXChaCha20Poly1305Aead.encodeFileIdInPlace(longId, fileIdBytes)

        assertEquals(fileIdLen, written)
        // 不能越界写到 chunkIndex 区
        val aad = ByteArray(aadSize)
        NativeXChaCha20Poly1305Aead.buildAadInPlace(
            fileIdBytes = fileIdBytes,
            fileIdLength = written,
            chunkIndex = 7L,
            chunkLength = 1,
            totalFileSize = 1L,
            outAad = aad
        )
        assertEquals("fileId 之后紧跟 chunkIndex 高位", 0.toByte(), aad[37])
        assertEquals(7.toByte(), aad[44])
    }

    @Test
    fun `empty fileId yields all-zero fileId field`() {
        val fileIdBytes = ByteArray(fileIdLen)
        val written = NativeXChaCha20Poly1305Aead.encodeFileIdInPlace("", fileIdBytes)
        assertEquals(0, written)

        val aad = ByteArray(aadSize)
        NativeXChaCha20Poly1305Aead.buildAadInPlace(
            fileIdBytes = fileIdBytes,
            fileIdLength = written,
            chunkIndex = 0L,
            chunkLength = 0,
            totalFileSize = 0L,
            outAad = aad
        )
        for (i in 1 until 37) {
            assertEquals("空 fileId 应全零 (offset $i)", 0, aad[i].toInt())
        }
    }

    @Test
    fun `different chunkIndex produces different AAD`() {
        val fileIdBytes = ByteArray(fileIdLen)
        NativeXChaCha20Poly1305Aead.encodeFileIdInPlace("fid", fileIdBytes)

        val a = ByteArray(aadSize)
        val b = ByteArray(aadSize)
        NativeXChaCha20Poly1305Aead.buildAadInPlace(
            fileIdBytes = fileIdBytes, chunkIndex = 0L,
            chunkLength = 1024, totalFileSize = 4096L, outAad = a
        )
        NativeXChaCha20Poly1305Aead.buildAadInPlace(
            fileIdBytes = fileIdBytes, chunkIndex = 1L,
            chunkLength = 1024, totalFileSize = 4096L, outAad = b
        )
        assertNotEquals(a.toList(), b.toList())
    }

    @Test
    fun `different chunkLength or totalSize produces different AAD`() {
        val fileIdBytes = ByteArray(fileIdLen)
        NativeXChaCha20Poly1305Aead.encodeFileIdInPlace("fid", fileIdBytes)

        val base = ByteArray(aadSize)
        val otherLen = ByteArray(aadSize)
        val otherSize = ByteArray(aadSize)
        NativeXChaCha20Poly1305Aead.buildAadInPlace(
            fileIdBytes = fileIdBytes, chunkIndex = 3L,
            chunkLength = 1024, totalFileSize = 8192L, outAad = base
        )
        NativeXChaCha20Poly1305Aead.buildAadInPlace(
            fileIdBytes = fileIdBytes, chunkIndex = 3L,
            chunkLength = 1025, totalFileSize = 8192L, outAad = otherLen
        )
        NativeXChaCha20Poly1305Aead.buildAadInPlace(
            fileIdBytes = fileIdBytes, chunkIndex = 3L,
            chunkLength = 1024, totalFileSize = 8193L, outAad = otherSize
        )
        assertNotEquals(base.toList(), otherLen.toList())
        assertNotEquals(base.toList(), otherSize.toList())
    }

    @Test
    fun `totalFileSize above Int range survives without truncation`() {
        // 4 GiB 以上：验证 8 字节字段没被当成 Int 截断
        val over4GiB = 5_000_000_000L
        val fileIdBytes = ByteArray(fileIdLen)
        val aad = ByteArray(aadSize)
        NativeXChaCha20Poly1305Aead.buildAadInPlace(
            fileIdBytes = fileIdBytes, chunkIndex = 0L,
            chunkLength = 0, totalFileSize = over4GiB, outAad = aad
        )
        // 0x12A05F200 → 高 4 字节应为 0x00000001
        assertEquals(0.toByte(), aad[49])
        assertEquals(0.toByte(), aad[50])
        assertEquals(0.toByte(), aad[51])
        assertEquals(1.toByte(), aad[52])
    }

    // ---------------------------------------------------------------------
    // nonce 派生
    // ---------------------------------------------------------------------

    @Test
    fun `nonce is fileNonce followed by big-endian chunkIndex`() {
        val fileNonce = ByteArray(16) { (it + 1).toByte() }
        val out = ByteArray(24)
        NativeXChaCha20Poly1305Aead.deriveNonceInPlace(fileNonce, 0x0102030405060708L, out)

        assertArrayEquals(fileNonce, out.copyOfRange(0, 16))
        assertEquals(0x01.toByte(), out[16])
        assertEquals(0x08.toByte(), out[23])
    }

    @Test
    fun `nonce uniqueness across chunk indices`() {
        val fileNonce = ByteArray(16) { 0x5A }
        val seen = HashSet<String>()
        for (i in 0L until 64L) {
            val out = ByteArray(24)
            NativeXChaCha20Poly1305Aead.deriveNonceInPlace(fileNonce, i, out)
            assertTrue("chunkIndex=$i 的 nonce 发生重复", seen.add(out.joinToString(",")))
        }
    }

    @Test
    fun `encodeFileId writes zero-filled buffer even if reused`() {
        val buf = ByteArray(fileIdLen) { 0x7F }   // 脏缓冲区
        val written = NativeXChaCha20Poly1305Aead.encodeFileIdInPlace("ab", buf)
        assertEquals(2, written)
        assertEquals('a'.code.toByte(), buf[0])
        assertEquals('b'.code.toByte(), buf[1])
        for (i in 2 until fileIdLen) {
            assertEquals("复用缓冲区必须被清零 (offset $i)", 0, buf[i].toInt())
        }
    }
}
