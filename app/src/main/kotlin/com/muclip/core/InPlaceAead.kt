package com.muclip.core

import java.security.GeneralSecurityException
import javax.crypto.AEADBadTagException

/**
 * Zero-allocation in-place AEAD (XChaCha20-Poly1305) abstraction.
 *
 * Buffer layout after [encryptInPlace] (returns `plaintextLength + 16`):
 * ┌──────────────────────────────┬────────────┐
 * │          ciphertext          │  Poly1305  │
 * │       plaintextLength        │    16 B    │
 * └──────────────────────────────┴────────────┘
 */
interface InPlaceAead {
    fun encryptInPlace(
        buffer: ByteArray,
        plaintextLength: Int,
        nonce: ByteArray,
        aad: ByteArray
    ): Int

    fun decryptInPlace(
        buffer: ByteArray,
        ciphertextAndTagLength: Int,
        nonce: ByteArray,
        aad: ByteArray
    ): Int
}

/**
 * Native Rust-backed XChaCha20-Poly1305 [InPlaceAead] implementation.
 * Uses JNI critical array access so encryption and decryption mutate [buffer] directly in-place
 * without allocating any temporary `ByteArray` on the JVM heap.
 */
class NativeXChaCha20Poly1305Aead(
    private val key: ByteArray
) : InPlaceAead {

    init {
        require(key.size == KEY_SIZE) { "XChaCha20-Poly1305 key must be $KEY_SIZE bytes" }
    }

    override fun encryptInPlace(
        buffer: ByteArray,
        plaintextLength: Int,
        nonce: ByteArray,
        aad: ByteArray
    ): Int {
        require(plaintextLength >= 0 && plaintextLength + TAG_SIZE <= buffer.size) {
            "Invalid plaintextLength ($plaintextLength) for buffer size (${buffer.size})"
        }
        require(nonce.size == NONCE_SIZE) {
            "XChaCha20 nonce must be $NONCE_SIZE bytes"
        }

        val encryptedLength = MuClipNative.nativeEncryptInPlace(
            key = key,
            buffer = buffer,
            plaintextLength = plaintextLength,
            nonce = nonce,
            aad = aad
        )
        if (encryptedLength != plaintextLength + TAG_SIZE) {
            throw GeneralSecurityException("In-place XChaCha20-Poly1305 encryption failed (code=$encryptedLength)")
        }
        return encryptedLength
    }

    override fun decryptInPlace(
        buffer: ByteArray,
        ciphertextAndTagLength: Int,
        nonce: ByteArray,
        aad: ByteArray
    ): Int {
        if (ciphertextAndTagLength < TAG_SIZE || ciphertextAndTagLength > buffer.size) {
            throw AEADBadTagException("Invalid ciphertextAndTagLength ($ciphertextAndTagLength) for buffer size (${buffer.size})")
        }
        require(nonce.size == NONCE_SIZE) {
            "XChaCha20 nonce must be $NONCE_SIZE bytes"
        }

        val decryptedLength = MuClipNative.nativeDecryptInPlace(
            key = key,
            buffer = buffer,
            ciphertextAndTagLength = ciphertextAndTagLength,
            nonce = nonce,
            aad = aad
        )
        if (decryptedLength < 0 || decryptedLength != ciphertextAndTagLength - TAG_SIZE) {
            throw AEADBadTagException("XChaCha20-Poly1305 authentication tag mismatch")
        }
        return decryptedLength
    }

    companion object {
        const val KEY_SIZE = 32
        const val NONCE_SIZE = 24
        const val FILE_NONCE_SIZE = 16
        const val TAG_SIZE = 16
        const val FILE_ID_AAD_LEN = 36
        const val AAD_SIZE = 1 + FILE_ID_AAD_LEN + 8 + 4 + 8 // 57 bytes
        const val PROTOCOL_VERSION: Byte = 1

        /**
         * Derives a unique 24-byte XChaCha20 nonce from [fileNonce] (16 bytes) and [chunkIndex]
         * directly into the pre-allocated [outNonce] (24 bytes) without creating any temporary ByteArray.
         *
         * Layout: `[fileNonce: 16B][chunkIndex: 8B big-endian]`
         */
        fun deriveNonceInPlace(
            fileNonce: ByteArray,
            chunkIndex: Long,
            outNonce: ByteArray
        ) {
            if (MuClipNative.isLoaded) {
                val rc = MuClipNative.nativeDeriveChunkNonce(fileNonce, chunkIndex, outNonce)
                if (rc == 0) return
            }
            System.arraycopy(fileNonce, 0, outNonce, 0, FILE_NONCE_SIZE)
            writeLongBe(outNonce, 16, chunkIndex)
        }

        /**
         * Encodes an ASCII/UTF-8 [fileId] into the fixed 36-byte [outFileIdBytes] buffer once before the loop.
         */
        fun encodeFileIdInPlace(fileId: String, outFileIdBytes: ByteArray): Int {
            outFileIdBytes.fill(0)
            val len = minOf(fileId.length, FILE_ID_AAD_LEN)
            for (i in 0 until len) {
                outFileIdBytes[i] = fileId[i].code.toByte()
            }
            return len
        }

        /**
         * Builds the fixed 57-byte binary AAD directly into [outAad] in-place with zero heap allocation.
         *
         * Binary structure:
         * - `[0]`      protocolVersion (1B)
         * - `[1..37]`  fileId (36B zero-padded)
         * - `[37..45]` chunkIndex (8B big-endian)
         * - `[45..49]` chunkLength (4B big-endian, plaintext length)
         * - `[49..57]` totalFileSize (8B big-endian)
         */
        fun buildAadInPlace(
            protocolVersion: Byte = PROTOCOL_VERSION,
            fileIdBytes: ByteArray,
            fileIdLength: Int = fileIdBytes.size,
            chunkIndex: Long,
            chunkLength: Int,
            totalFileSize: Long,
            outAad: ByteArray
        ) {
            if (MuClipNative.isLoaded && protocolVersion == PROTOCOL_VERSION && fileIdLength == fileIdBytes.size) {
                val rc = MuClipNative.nativeBuildChunkAad(
                    fileIdBytes = fileIdBytes,
                    chunkIndex = chunkIndex,
                    chunkLength = chunkLength,
                    totalFileSize = totalFileSize,
                    outAad = outAad
                )
                if (rc == 0) return
            }
            outAad[0] = protocolVersion
            for (i in 0 until FILE_ID_AAD_LEN) {
                outAad[1 + i] = if (i < fileIdLength) fileIdBytes[i] else 0
            }
            writeLongBe(outAad, 37, chunkIndex)
            writeIntBe(outAad, 45, chunkLength)
            writeLongBe(outAad, 49, totalFileSize)
        }

        private fun writeIntBe(buf: ByteArray, offset: Int, value: Int) {
            buf[offset] = (value ushr 24).toByte()
            buf[offset + 1] = (value ushr 16).toByte()
            buf[offset + 2] = (value ushr 8).toByte()
            buf[offset + 3] = value.toByte()
        }

        private fun writeLongBe(buf: ByteArray, offset: Int, value: Long) {
            buf[offset] = (value ushr 56).toByte()
            buf[offset + 1] = (value ushr 48).toByte()
            buf[offset + 2] = (value ushr 40).toByte()
            buf[offset + 3] = (value ushr 32).toByte()
            buf[offset + 4] = (value ushr 24).toByte()
            buf[offset + 5] = (value ushr 16).toByte()
            buf[offset + 6] = (value ushr 8).toByte()
            buf[offset + 7] = value.toByte()
        }
    }
}
