package com.muclip.core

import org.json.JSONObject

data class HistoryItem(
    val id: Long,
    val content: String,
    val contentType: String,
    val charCount: Int,
    val lineCount: Int,
    val preview: String,
    val createdAt: String,
    val isPinned: Boolean,
    val sourceApp: String?,
    val byteCount: Int = 0
)

data class PhraseItem(
    val id: Long,
    val content: String,
    val label: String?,
    val shortcut: String?,
    val usageCount: Int,
    val createdAt: String,
    val lastUsedAt: String?,
    val contentType: String = "plain_text",
    val charCount: Int = 0,
    val lineCount: Int = 1,
    val byteCount: Int = 0,
    val preview: String = ""
)

data class ContentAnalysis(
    val contentType: String,
    val charCount: Int,
    val lineCount: Int,
    val preview: String,
    val byteCount: Int = 0
)

/**
 * `.mubc` 备份里各类数据的条目数，导出/导入都返回这一份统计。
 */
data class BackupSummary(
    val historyCount: Long,
    val phraseCount: Long,
    val ruleCount: Long,
    val templateCount: Long,
    val configCount: Long
)

/**
 * 备份操作失败。message 直接来自 Rust 侧，可能是「口令错误」「文件不存在」等。
 */
class BackupException(message: String) : Exception(message)

/**
 * Idiomatic Kotlin interface for MuClip Core backed by JNI + DirectByteBuffer zero-copy binary wire architecture.
 */
class MuClipCore private constructor(private var handle: Long) : AutoCloseable {

    companion object {
        fun open(dbPath: String): MuClipCore {
            val h = MuClipNative.nativeInit(dbPath)
            if (h == 0L) {
                throw IllegalStateException("Failed to open MuClip database at $dbPath")
            }
            return MuClipCore(h)
        }

        /**
         * 以加密模式打开本地库。keyHex 必须是 64 位十六进制（32 字节）。
         *
         * 若库是用其它密钥创建的，Rust 侧会直接返回 Err（不会改名、不会覆盖），
         * 这里抛 [IllegalStateException]，调用方应把旧库当作不可恢复处理。
         */
        fun openWithKey(dbPath: String, keyHex: String): MuClipCore {
            val h = MuClipNative.nativeInitWithKey(dbPath, keyHex)
            if (h == 0L) {
                throw IllegalStateException("Failed to open encrypted MuClip database at $dbPath")
            }
            return MuClipCore(h)
        }

        fun openInMemory(): MuClipCore {
            val h = MuClipNative.nativeInitInMemory()
            if (h == 0L) {
                throw IllegalStateException("Failed to initialize in-memory MuClip database")
            }
            return MuClipCore(h)
        }

        /**
         * 解析 Rust 侧统一的备份 JSON 信封。失败一律抛 [BackupException]。
         */
        private fun parseBackupEnvelope(json: String?): BackupSummary {
            if (json.isNullOrBlank()) {
                throw BackupException("核心未返回结果（native 库可能未加载）")
            }
            val root = try {
                JSONObject(json)
            } catch (e: Exception) {
                throw BackupException("无法解析核心返回的数据: ${e.message}")
            }
            if (!root.optBoolean("ok", false)) {
                val err = root.optString("error").ifBlank { "未知错误" }
                throw BackupException(err)
            }
            val data = root.optJSONObject("data") ?: JSONObject()
            return BackupSummary(
                historyCount = data.optLong("historyCount", 0L),
                phraseCount = data.optLong("phraseCount", 0L),
                ruleCount = data.optLong("ruleCount", 0L),
                templateCount = data.optLong("templateCount", 0L),
                configCount = data.optLong("configCount", 0L)
            )
        }
    }

    private fun checkHandle() {
        if (handle == 0L) {
            throw IllegalStateException("MuClipCore has already been closed.")
        }
    }

    /**
     * Adds a new clipboard history item using 100% zero-copy pooled off-heap memory.
     */
    fun addHistory(text: String): Long {
        checkHandle()
        return DirectBufferPool.useLeased { buffer ->
            val len = buffer.writeUtf8(text)
            MuClipNative.nativeAddHistoryZeroCopy(handle, buffer.rawPtr, len)
        }
    }

    /**
     * Queries recent history items via direct Little-Endian binary wire format with zero JSON overhead.
     */
    fun queryHistory(limit: Int = 100): List<HistoryItem> {
        checkHandle()
        return DirectBufferPool.useBinaryQuery(
            defaultResult = emptyList(),
            nativeWriter = { rawPtr, cap ->
                MuClipNative.nativeQueryHistoryZeroCopy(handle, limit, rawPtr, cap)
            },
            decoder = { buf -> readHistoryBinary(buf) }
        )
    }

    /**
     * Searches history items matching query via direct Little-Endian binary wire format.
     */
    fun searchHistory(query: String, limit: Int = 50): List<HistoryItem> {
        checkHandle()
        return DirectBufferPool.useLeased { queryBuf ->
            val queryLen = queryBuf.writeUtf8(query)
            DirectBufferPool.useBinaryQuery(
                defaultResult = emptyList(),
                nativeWriter = { rawPtr, cap ->
                    MuClipNative.nativeSearchHistoryZeroCopy(
                        handle,
                        queryBuf.rawPtr,
                        queryLen,
                        limit,
                        rawPtr,
                        cap
                    )
                },
                decoder = { outBuf -> readHistoryBinary(outBuf) }
            )
        }
    }

    fun deleteHistory(id: Long): Boolean {
        checkHandle()
        return MuClipNative.nativeDeleteHistory(handle, id) == 0
    }

    fun clearHistory(): Boolean {
        checkHandle()
        return MuClipNative.nativeClearHistory(handle) == 0
    }

    fun cleanupHistory(days: Int): Int {
        checkHandle()
        return MuClipNative.nativeCleanupHistory(handle, days)
    }

    fun addPhrase(text: String): Long {
        checkHandle()
        return DirectBufferPool.useLeased { buffer ->
            val len = buffer.writeUtf8(text)
            MuClipNative.nativeAddPhraseZeroCopy(handle, buffer.rawPtr, len)
        }
    }

    /**
     * Queries saved phrases via direct Little-Endian binary wire format with zero JSON overhead.
     */
    fun queryPhrases(): List<PhraseItem> {
        checkHandle()
        return DirectBufferPool.useBinaryQuery(
            defaultResult = emptyList(),
            nativeWriter = { rawPtr, cap ->
                MuClipNative.nativeQueryPhrasesZeroCopy(handle, rawPtr, cap)
            },
            decoder = { buf -> readPhrasesBinary(buf) }
        )
    }

    fun deletePhrase(id: Long): Boolean {
        checkHandle()
        return MuClipNative.nativeDeletePhrase(handle, id) == 0
    }

    fun clearPhrases(): Boolean {
        checkHandle()
        return MuClipNative.nativeClearPhrases(handle) == 0
    }

    fun formatPreview(text: String, maxChars: Int = 100): String {
        return DirectBufferPool.useLeased { inBuf ->
            val inLen = inBuf.writeUtf8(text)
            DirectBufferPool.useLeased { outBuf ->
                val written = MuClipNative.nativeFormatPreviewZeroCopy(
                    inBuf.rawPtr,
                    inLen,
                    maxChars,
                    outBuf.rawPtr,
                    outBuf.capacity.toInt()
                )
                if (written > 0) {
                    outBuf.rewindForRead(written)
                    outBuf.readUtf8(written)
                } else {
                    ""
                }
            }
        }
    }

    fun analyzeContent(text: String): ContentAnalysis {
        return DirectBufferPool.useLeased { inBuf ->
            val inLen = inBuf.writeUtf8(text)
            DirectBufferPool.useBinaryQuery(
                defaultResult = ContentAnalysis(
                    contentType = "plain_text",
                    charCount = text.length,
                    lineCount = 1,
                    preview = text,
                    byteCount = inLen
                ),
                nativeWriter = { rawPtr, cap ->
                    MuClipNative.nativeAnalyzeContentZeroCopy(inBuf.rawPtr, inLen, rawPtr, cap)
                },
                decoder = { outBuf ->
                    val charCount = outBuf.readInt()
                    val lineCount = outBuf.readInt()
                    val byteCount = outBuf.readInt()
                    val contentType = outBuf.readString()
                    val preview = outBuf.readString()
                    ContentAnalysis(
                        contentType = contentType.ifEmpty { "plain_text" },
                        charCount = charCount,
                        lineCount = lineCount,
                        preview = preview,
                        byteCount = byteCount
                    )
                }
            )
        }
    }

    private fun readHistoryBinary(buf: LeasedBuffer): List<HistoryItem> {
        val count = buf.readInt()
        if (count <= 0) return emptyList()
        val list = ArrayList<HistoryItem>(count)
        for (i in 0 until count) {
            val id = buf.readLong()
            val charCount = buf.readInt()
            val lineCount = buf.readInt()
            val byteCount = buf.readInt()
            val content = buf.readString()
            val contentType = buf.readString()
            val preview = buf.readString()
            val createdAt = buf.readString()
            val lastUsedAt = buf.readString()
            list.add(
                HistoryItem(
                    id = id,
                    content = content,
                    contentType = contentType.ifEmpty { "plain_text" },
                    charCount = charCount,
                    lineCount = lineCount,
                    preview = preview,
                    createdAt = createdAt,
                    isPinned = false,
                    sourceApp = lastUsedAt.ifEmpty { null },
                    byteCount = byteCount
                )
            )
        }
        return list
    }

    private fun readPhrasesBinary(buf: LeasedBuffer): List<PhraseItem> {
        val count = buf.readInt()
        if (count <= 0) return emptyList()
        val list = ArrayList<PhraseItem>(count)
        for (i in 0 until count) {
            val id = buf.readLong()
            val sortOrder = buf.readLong()
            val charCount = buf.readInt()
            val lineCount = buf.readInt()
            val byteCount = buf.readInt()
            val content = buf.readString()
            val contentType = buf.readString()
            val preview = buf.readString()
            val createdAt = buf.readString()
            val lastUsedAt = buf.readString()
            list.add(
                PhraseItem(
                    id = id,
                    content = content,
                    label = null,
                    shortcut = null,
                    usageCount = sortOrder.toInt(),
                    createdAt = createdAt,
                    lastUsedAt = lastUsedAt.ifEmpty { null },
                    contentType = contentType.ifEmpty { "plain_text" },
                    charCount = charCount,
                    lineCount = lineCount,
                    byteCount = byteCount,
                    preview = preview
                )
            )
        }
        return list
    }

    /**
     * Sets a persistent configuration key-value pair in SQLite secure_config table.
     */
    fun setConfig(key: String, value: String): Boolean {
        checkHandle()
        return MuClipNative.nativeSetConfig(handle, key, value) == 0
    }

    /**
     * Gets a persistent configuration value by key from SQLite secure_config table.
     */
    fun getConfig(key: String): String? {
        checkHandle()
        return MuClipNative.nativeGetConfig(handle, key)
    }

    /**
     * 本地库当前是否真的走了加密。
     *
     * 三态：true 加密生效 / false 明文 / 句柄无效时抛异常。
     * 这是「实际生效状态」，不是「请求了加密」——明文版 native 库会如实返回 false。
     */
    fun isEncrypted(): Boolean {
        checkHandle()
        return when (MuClipNative.nativeIsEncrypted(handle)) {
            1 -> true
            0 -> false
            else -> throw IllegalStateException("无法读取加密状态（引擎句柄无效）")
        }
    }

    /**
     * 导出加密备份到 [path]（.mubc）。
     *
     * 载荷先 deflate 压缩再用 XChaCha20-Poly1305 加密，口令与本地库密钥完全分离——
     * 备份文件拷贝到别处，没有口令就打不开。
     */
    fun exportBackup(path: String, password: String): BackupSummary {
        checkHandle()
        return parseBackupEnvelope(MuClipNative.nativeExportDb(handle, path, password))
    }

    /**
     * 从 [path] 导入加密备份。
     *
     * [replaceAll] 为 true 时清空本地数据后完全覆盖，false 时按内容去重合并。
     */
    fun importBackup(path: String, password: String, replaceAll: Boolean = false): BackupSummary {
        checkHandle()
        return parseBackupEnvelope(MuClipNative.nativeImportDb(handle, path, password, replaceAll))
    }

    override fun close() {
        if (handle != 0L) {
            MuClipNative.nativeClose(handle)
            handle = 0L
        }
    }
}
