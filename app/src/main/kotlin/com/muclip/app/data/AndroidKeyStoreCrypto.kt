package com.muclip.app.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 硬件安全芯片 (Android KeyStore) AES-256-GCM 加解密工具类。
 * 密钥受 TEE / StrongBox 隔离保护，密钥材料永不出硬件芯片。
 */
object AndroidKeyStoreCrypto {
    private const val KEY_ALIAS = "MuClip_Secure_Storage_Key_v1"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val IV_SIZE = 12
    private const val TAG_SIZE_BITS = 128

    /**
     * 进程内的 [SecretKey] 句柄缓存。
     *
     * 原来每次加解密都要重新走一遍
     * `KeyStore.getInstance(...).load(null)` → `containsAlias` → `getEntry`：
     * 这是 2~3 次 keystore2 守护进程的 binder 往返，之后才轮到 TEE 里真正做
     * AES-GCM。而这条路径在冷启动的主线程上会被走好几次
     * （数据库密钥、设备长期密钥、每个已配对设备的对端密钥），单次 TEE 操作在
     * 部分机型上是 10~30ms 量级。
     *
     * 为什么可以缓存：这里缓存的只是**句柄**（`KeyStoreSecretKey` 内部只有别名），
     * 密钥材料始终留在 TEE 里，不会被复制到用户态；且本项目没有任何删除该别名的
     * 代码路径，别名一旦创建，直到卸载 / 恢复出厂才可能消失。
     */
    @Volatile
    private var keyCache: SecretKey? = null

    @Synchronized
    private fun getOrCreateKey(): SecretKey {
        keyCache?.let { return it }

        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val key = if (!keyStore.containsAlias(KEY_ALIAS)) {
            val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
            val spec = KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
            keyGenerator.init(spec)
            keyGenerator.generateKey()
        } else {
            (keyStore.getEntry(KEY_ALIAS, null) as KeyStore.SecretKeyEntry).secretKey
        }

        keyCache = key
        return key
    }

    @Synchronized
    private fun clearCachedKey() {
        keyCache = null
    }

    /**
     * 用当前密钥执行一次加解密；失败时丢弃缓存句柄、重新取一次再试。
     *
     * 极少数 ROM 会在 TEE 重置或系统吊销密钥后让已持有的句柄失效，此时重新
     * `getEntry` 能拿到有效句柄。重试仍失败就把**第一次的异常**抛出去，
     * 交给 [encrypt] / [decrypt] 原有的降级逻辑，行为与优化前一致。
     */
    private inline fun <T> withKeyRetrying(block: (SecretKey) -> T): T {
        return try {
            block(getOrCreateKey())
        } catch (first: Exception) {
            clearCachedKey()
            try {
                block(getOrCreateKey())
            } catch (_: Exception) {
                throw first
            }
        }
    }

    /**
     * 使用硬件 AES-256-GCM 加密明文，返回 Base64 编码的密文 ([12B IV] + [密文 + 16B 认证Tag])
     */
    fun encrypt(plainText: String): String {
        if (plainText.isEmpty()) return ""
        return try {
            withKeyRetrying { key ->
                val cipher = Cipher.getInstance(TRANSFORMATION)
                cipher.init(Cipher.ENCRYPT_MODE, key)
                val iv = cipher.iv
                val cipherBytes = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
                val combined = ByteArray(iv.size + cipherBytes.size)
                System.arraycopy(iv, 0, combined, 0, iv.size)
                System.arraycopy(cipherBytes, 0, combined, iv.size, cipherBytes.size)
                Base64.encodeToString(combined, Base64.NO_WRAP)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            // 降级使用基础 Base64（如低版本测试沙盒环境）
            Base64.encodeToString(plainText.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        }
    }

    /**
     * 解密 Base64 密文，自动校验 GCM 认证标签与还原明文
     */
    fun decrypt(encryptedBase64: String): String? {
        if (encryptedBase64.isEmpty()) return null

        val combined = try {
            Base64.decode(encryptedBase64, Base64.NO_WRAP)
        } catch (_: Exception) {
            null
        }
        if (combined == null || combined.size <= IV_SIZE) return null

        return try {
            withKeyRetrying { key ->
                val iv = ByteArray(IV_SIZE)
                val cipherText = ByteArray(combined.size - IV_SIZE)
                System.arraycopy(combined, 0, iv, 0, IV_SIZE)
                System.arraycopy(combined, IV_SIZE, cipherText, 0, cipherText.size)

                val cipher = Cipher.getInstance(TRANSFORMATION)
                cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_SIZE_BITS, iv))
                String(cipher.doFinal(cipherText), Charsets.UTF_8)
            }
        } catch (e: Exception) {
            // 降级尝试明文 Base64 解码（兼容历史上 TEE 不可用时写入的明文数据）
            try {
                String(Base64.decode(encryptedBase64, Base64.NO_WRAP), Charsets.UTF_8)
            } catch (_: Exception) {
                null
            }
        }
    }
}
