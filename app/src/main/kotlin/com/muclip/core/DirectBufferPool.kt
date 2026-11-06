package com.muclip.core

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets

/**
 * A leased off-heap direct buffer rented from Rust's [NativeBufferPool].
 * Implements [AutoCloseable] to support Kotlin's `use` construct.
 *
 * All operations on this buffer are 100% zero-copy, operating directly
 * on off-heap memory with Little-Endian binary wire format and zero JSON overhead.
 */
class LeasedBuffer(
    val leaseId: Long,
    val capacity: Long,
    val rawPtr: Long,
    val byteBuffer: ByteBuffer
) : AutoCloseable {

    private var closed = false

    init {
        byteBuffer.order(ByteOrder.LITTLE_ENDIAN)
        byteBuffer.clear()
    }

    /**
     * Writes a UTF-8 string directly into this off-heap buffer without creating intermediate byte arrays.
     * Sets buffer position to 0 and limit to written bytes count.
     */
    fun writeUtf8(str: String): Int {
        checkNotClosed()
        byteBuffer.clear()
        byteBuffer.order(ByteOrder.LITTLE_ENDIAN)
        val encoder = StandardCharsets.UTF_8.newEncoder()
        val inBuf = java.nio.CharBuffer.wrap(str)
        encoder.encode(inBuf, byteBuffer, true)
        val written = byteBuffer.position()
        byteBuffer.flip()
        return written
    }

    /**
     * Reads UTF-8 string of [length] bytes directly from the buffer's current position.
     */
    fun readUtf8(length: Int): String {
        checkNotClosed()
        if (length <= 0) return ""
        val bytes = ByteArray(length)
        byteBuffer.get(bytes, 0, length)
        return String(bytes, StandardCharsets.UTF_8)
    }

    /**
     * Prepares the buffer cursor at position 0 with limit [writtenBytes] in Little-Endian order
     * for reading binary wire format written by Rust.
     */
    fun rewindForRead(writtenBytes: Int) {
        checkNotClosed()
        byteBuffer.order(ByteOrder.LITTLE_ENDIAN)
        byteBuffer.position(0)
        byteBuffer.limit(writtenBytes.coerceIn(0, byteBuffer.capacity()))
    }

    fun readByte(): Byte {
        checkNotClosed()
        return byteBuffer.get()
    }

    fun readBoolean(): Boolean {
        return readByte().toInt() != 0
    }

    fun readInt(): Int {
        checkNotClosed()
        return byteBuffer.int
    }

    fun readLong(): Long {
        checkNotClosed()
        return byteBuffer.long
    }

    /**
     * Reads a length-prefixed UTF-8 string (`[i32 byte_len][utf8_bytes]`) from the binary cursor.
     */
    fun readString(): String {
        val len = readInt()
        if (len <= 0) return ""
        return readUtf8(len)
    }

    /**
     * Reads an optional length-prefixed UTF-8 string (`[i32 byte_len (-1 if null)][utf8_bytes]`).
     */
    fun readNullableString(): String? {
        val len = readInt()
        if (len < 0) return null
        if (len == 0) return ""
        return readUtf8(len)
    }

    private fun checkNotClosed() {
        if (closed) {
            throw IllegalStateException("Buffer lease $leaseId has already been closed and returned to pool.")
        }
    }

    override fun close() {
        if (!closed) {
            closed = true
            DirectBufferPool.release(leaseId)
        }
    }
}

/**
 * Off-heap Native DirectByteBuffer Pool Manager.
 * Facilitates high-throughput, low-latency zero-copy binary memory leasing between Kotlin and Rust.
 */
object DirectBufferPool {

    /**
     * Rents a direct buffer with at least [minCapacity] bytes.
     * The returned [LeasedBuffer] MUST be closed to return memory back to the native pool.
     */
    fun acquire(minCapacity: Long = 65536): LeasedBuffer {
        val outInfo = LongArray(3) // [0] = leaseId, [1] = capacity, [2] = rawPtr
        val directBuf = MuClipNative.nativeAcquireBuffer(minCapacity, outInfo)
            ?: throw OutOfMemoryError("Failed to acquire off-heap direct buffer of size $minCapacity")

        val leaseId = outInfo[0]
        val capacity = outInfo[1]
        val rawPtr = outInfo[2]

        return LeasedBuffer(leaseId, capacity, rawPtr, directBuf)
    }

    /**
     * Releases a leased buffer back to the native pool by its lease ID.
     */
    fun release(leaseId: Long): Boolean {
        return MuClipNative.nativeReleaseBuffer(leaseId)
    }

    /**
     * Executes [block] with a rented off-heap direct buffer, automatically recycling the buffer upon completion.
     */
    inline fun <R> useLeased(minCapacity: Long = 65536, block: (LeasedBuffer) -> R): R {
        val leased = acquire(minCapacity)
        return try {
            block(leased)
        } finally {
            leased.close()
        }
    }

    /**
     * Executes a native binary writer against a pooled off-heap buffer, automatically resizing and retrying
     * if the payload exceeds [initialCapacity] (indicated by `written < -1`, where `-written` is the required size).
     */
    inline fun <R> useBinaryQuery(
        initialCapacity: Long = 65536,
        defaultResult: R,
        nativeWriter: (rawPtr: Long, capacity: Int) -> Int,
        decoder: (LeasedBuffer) -> R
    ): R {
        useLeased(initialCapacity) { buf ->
            val written = nativeWriter(buf.rawPtr, buf.capacity.toInt())
            if (written > 0) {
                buf.rewindForRead(written)
                return decoder(buf)
            }
            if (written >= -1) {
                return defaultResult
            }
            // Buffer capacity was too small; `written` is `-required_bytes`
            val requiredCapacity = (-written).toLong()
            useLeased(requiredCapacity) { largerBuf ->
                val retryWritten = nativeWriter(largerBuf.rawPtr, largerBuf.capacity.toInt())
                if (retryWritten > 0) {
                    largerBuf.rewindForRead(retryWritten)
                    return decoder(largerBuf)
                }
                return defaultResult
            }
        }
    }
}
