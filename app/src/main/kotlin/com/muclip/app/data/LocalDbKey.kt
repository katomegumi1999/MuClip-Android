package com.muclip.app.data

import android.content.Context
import java.security.SecureRandom

/**
 * 本地 SQLite 数据库的加密密钥（SQLCipher raw key）。
 *
 * 为什么不能直接拿用户口令当 SQLCipher 的 `PRAGMA key`：
 * SQLCipher 对普通口令会跑 25 万轮 PBKDF2，每次打开库都要重新算；
 * 而 raw key 形式 `PRAGMA key = "x'<64位hex>'"` 跳过 KDF，打开几乎是瞬时的。
 *
 * 密钥材料本体交给 [AndroidKeyStoreCrypto]（AES-256-GCM，密钥在 TEE 里），
 * SharedPreferences 里只存密文。这样即使有人拿到了 /data/data 目录，
 * 把 muclip.db 和 SharedPreferences 一起拷走也解不开 —— GCM 密钥取不出来。
 *
 * 需要说明的边界：这是「设备被单独拷走」的防护。设备已经解锁、攻击者能跑
 * 我们自己的进程时，SQLCipher 救不了，因为密钥就在同一个 TEE 里。
 */
object LocalDbKey {

    private const val PREFS_NAME = "muclip_local_db_key"
    private const val PREF_KEY = "db_key_v1"
    private const val KEY_HEX_LENGTH = 64

    /**
     * 判断本机是否已经生成过库密钥。
     *
     * 升级路径依赖它：false 表示这是首次启用加密，磁盘上的 muclip.db 一定是
     * 明文旧库，必须让位（见 [MuClipApplication]），否则加密打开会失败。
     */
    fun exists(context: Context): Boolean {
        val stored = prefs(context).getString(PREF_KEY, null)
        return !stored.isNullOrBlank()
    }

    /**
     * 读取已有密钥；不存在则生成一份并持久化。
     *
     * 如果存了密文却解不出来（KeyStore 被清空、抗回滚触发等），会重新生成。
     * 那种情况下旧库打不开是必然结果，调用方按「旧库作废」处理。
     */
    @Synchronized
    fun getOrCreate(context: Context): String {
        val prefs = prefs(context)
        val stored = prefs.getString(PREF_KEY, null)
        if (!stored.isNullOrBlank()) {
            val decrypted = AndroidKeyStoreCrypto.decrypt(stored)
            if (isValidRawKeyHex(decrypted)) {
                return decrypted!!
            }
            // 解不出来就当没有，往下走重新生成
        }
        val fresh = generateRawKeyHex()
        prefs.edit().putString(PREF_KEY, AndroidKeyStoreCrypto.encrypt(fresh)).apply()
        return fresh
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * SQLCipher raw key 是 32 字节，十六进制即 64 个字符。
     * 必须全为十六进制，否则 Rust 侧会把它当口令走 PBKDF2，行为和预期完全不同。
     */
    private fun isValidRawKeyHex(value: String?): Boolean {
        if (value == null || value.length != KEY_HEX_LENGTH) return false
        return value.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }
    }

    private fun generateRawKeyHex(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        val sb = StringBuilder(KEY_HEX_LENGTH)
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            sb.append(HEX_DIGITS[v ushr 4])
            sb.append(HEX_DIGITS[v and 0x0F])
        }
        return sb.toString()
    }

    private val HEX_DIGITS = "0123456789abcdef".toCharArray()
}
