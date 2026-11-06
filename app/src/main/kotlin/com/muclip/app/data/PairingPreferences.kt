package com.muclip.app.data

import android.content.Context
import android.content.SharedPreferences

/**
 * Manages device pairing states, trusted peers list, and sync preferences.
 */
class PairingPreferences(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("muclip_preferences", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_TRUSTED_DEVICES = "trusted_devices"
        private const val KEY_CUSTOM_PEERS = "custom_peers"
        private const val KEY_SYNC_PORT = "sync_port"
        private const val KEY_DEVICE_NAME = "device_name"
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_FOREGROUND_SERVICE = "foreground_service_enabled"
        private const val KEY_AUTO_COPY_REMOTE = "auto_copy_remote"
        private const val KEY_AUTO_READ_CLIPBOARD = "auto_read_clipboard"
        private const val KEY_AUTO_DISCOVERY = "auto_discovery"
        private const val KEY_PAIRED_DEVICES = "paired_devices"
        private const val KEY_LONG_TERM_KEY_ENC = "long_term_key_enc"
        private const val KEY_PAIRING_PIN_ENC = "pairing_pin_enc"
        private const val DEFAULT_PIN = "000000"
        private const val DEFAULT_PORT = 42424

        /**
         * [longTermKey] 的进程内缓存。
         *
         * 每次读取都要走 KeyStore（TEE / StrongBox）做一次 AES-GCM 解密，单次 5~20ms；
         * 而这个属性在 [com.muclip.app.MuClipApplication.onCreate] 主线程的
         * initSyncEngine() 里被调用，也在每个配对 / 连接 / 状态刷新流程里反复调用。
         * 设备主密钥在单个进程生命周期内不会变化，缓存后只有首次付这笔开销。
         */
        @Volatile
        private var cachedLongTermKey: String? = null

        /**
         * deviceId → 明文对端密钥。
         *
         * [getDevicePeerKey] 每次都要走 TEE 解密，而它在冷启动路径上会被**逐个已配对
         * 设备**调用一次（`initSyncEngine` 遍历 `getPairedDevices()` 调
         * `buildPairedDeviceSpec` → `getDevicePeerKey`）。设备越多启动越慢。
         * 对端密钥只在配对时写入，进程内不会变，因此可以安全缓存；
         * 解除配对时由 [setDevicePaired] 逐条失效。
         */
        private val peerKeyCache = java.util.concurrent.ConcurrentHashMap<String, String>()
    }

    /**
     * 安全的核心库入口。核心初始化失败（如加密数据库打不开）时返回 null，
     * 避免 `UninitializedPropertyAccessException` 把每次调用都变成一次崩溃。
     */
    private fun coreOrNull(): com.muclip.core.MuClipCore? =
        com.muclip.app.MuClipApplication.coreOrNull()

    var syncPort: Int
        get() = prefs.getInt(KEY_SYNC_PORT, DEFAULT_PORT)
        set(value) = prefs.edit().putInt(KEY_SYNC_PORT, value).apply()

    fun isValidHexKey64(value: String?): Boolean {
        val clean = value?.trim() ?: return false
        if (clean.length != 64) return false
        return clean.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }
    }

    private fun generateRandomLongTermKeyHex(): String {
        val bytes = ByteArray(32)
        java.security.SecureRandom().nextBytes(bytes)
        val sb = StringBuilder(64)
        for (b in bytes) {
            sb.append(String.format("%02x", b))
        }
        return sb.toString()
    }

    var longTermKey: String?
        get() {
            cachedLongTermKey?.let { return it }
            val resolved = resolveLongTermKey()
            if (resolved != null) {
                cachedLongTermKey = resolved
            }
            return resolved
        }
        set(value) {
            val clean = value?.trim()
            if (!isValidHexKey64(clean)) return
            val encrypted = try {
                AndroidKeyStoreCrypto.encrypt(clean!!)
            } catch (e: Throwable) {
                e.printStackTrace()
                return
            }
            // 用 apply()：commit() 会同步 fsync 磁盘，而这里大多在主线程被调用。
            // apply() 会同步更新内存快照，同进程后续读取立刻可见，语义上等价。
            prefs.edit().putString(KEY_LONG_TERM_KEY_ENC, encrypted).apply()
            coreOrNull()?.setConfig("long_term_key", encrypted)
            cachedLongTermKey = clean
        }

    /**
     * 真正去存储里找主密钥。三级回退：SQLite(config) → SharedPreferences → 首次生成。
     * 只在 [longTermKey] 缓存未命中时被调用一次。
     */
    @Synchronized
    private fun resolveLongTermKey(): String? {
        try {
            val encrypted = coreOrNull()?.getConfig("long_term_key")
            if (!encrypted.isNullOrBlank()) {
                val decrypted = AndroidKeyStoreCrypto.decrypt(encrypted)
                if (isValidHexKey64(decrypted)) {
                    return decrypted!!.trim()
                }
            }
        } catch (_: Throwable) {}

        try {
            val fallbackEnc = prefs.getString(KEY_LONG_TERM_KEY_ENC, null)
            if (!fallbackEnc.isNullOrBlank()) {
                val decrypted = AndroidKeyStoreCrypto.decrypt(fallbackEnc)
                if (isValidHexKey64(decrypted)) {
                    return decrypted!!.trim()
                }
            }
        } catch (_: Throwable) {}

        // 首次初始化：生成永久 256 位设备主密钥并落盘
        val generated = generateRandomLongTermKeyHex()
        try {
            val encrypted = AndroidKeyStoreCrypto.encrypt(generated)
            prefs.edit().putString(KEY_LONG_TERM_KEY_ENC, encrypted).apply()
            coreOrNull()?.setConfig("long_term_key", encrypted)
        } catch (_: Throwable) {}
        return generated
    }

    var pairingPin: String
        get() {
            // 1. 优先读取本机永久 256 位设备主密钥 (Long-Term Key)
            val ltk = longTermKey
            if (!ltk.isNullOrBlank()) {
                return ltk
            }
            return DEFAULT_PIN
        }
        set(value) {
            val clean = value.trim()
            if (isValidHexKey64(clean)) {
                longTermKey = clean
            } else if (clean.length == 6) {
                try {
                    val encrypted = AndroidKeyStoreCrypto.encrypt(clean)
                    prefs.edit().putString(KEY_PAIRING_PIN_ENC, encrypted).apply()
                    coreOrNull()?.setConfig("pairing_pin", encrypted)
                } catch (e: Throwable) {
                    e.printStackTrace()
                }
            }
        }

    var foregroundServiceEnabled: Boolean
        get() = prefs.getBoolean(KEY_FOREGROUND_SERVICE, true)
        set(value) = prefs.edit().putBoolean(KEY_FOREGROUND_SERVICE, value).apply()

    var autoCopyRemote: Boolean
        get() = prefs.getBoolean(KEY_AUTO_COPY_REMOTE, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_COPY_REMOTE, value).apply()

    var autoReadClipboard: Boolean
        get() = prefs.getBoolean(KEY_AUTO_READ_CLIPBOARD, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_READ_CLIPBOARD, value).apply()

    var autoDiscovery: Boolean
        get() = prefs.getBoolean(KEY_AUTO_DISCOVERY, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_DISCOVERY, value).apply()

    @Synchronized
    fun getPairedDevices(): Set<String> {
        val raw = prefs.getStringSet(KEY_PAIRED_DEVICES, null)
        if (!raw.isNullOrEmpty()) {
            return HashSet(raw)
        }
        // SQLite 备份兜底：SharedPreferences 被清空或并发写坏时用
        try {
            val backup = coreOrNull()?.getConfig("paired_devices_backup")
            if (!backup.isNullOrBlank()) {
                val restored = backup.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
                if (restored.isNotEmpty()) {
                    prefs.edit().putStringSet(KEY_PAIRED_DEVICES, HashSet(restored)).apply()
                    return HashSet(restored)
                }
            }
        } catch (_: Exception) {}
        return emptySet()
    }

    @Synchronized
    fun setDevicePaired(deviceId: String, paired: Boolean) {
        if (deviceId.isBlank()) return
        val current = HashSet(getPairedDevices())
        val changed = if (paired) {
            current.add(deviceId)
        } else {
            current.remove(deviceId)
        }
        if (!paired) {
            peerKeyCache.remove(deviceId)
            prefs.edit()
                .remove("dev_name_$deviceId")
                .remove("dev_type_$deviceId")
                .remove("dev_ip_$deviceId")
                .remove("dev_port_$deviceId")
                .remove("dev_key_enc_$deviceId")
                .putStringSet(KEY_PAIRED_DEVICES, HashSet(current))
                .apply()
            try {
                coreOrNull()?.setConfig("peer_key_$deviceId", "")
            } catch (_: Exception) {}
        } else if (changed || !prefs.contains(KEY_PAIRED_DEVICES)) {
            prefs.edit()
                .putStringSet(KEY_PAIRED_DEVICES, HashSet(current))
                .apply()
        }
        try {
            coreOrNull()?.setConfig("paired_devices_backup", current.joinToString(","))
        } catch (_: Exception) {}
    }

    @Synchronized
    fun setDevicePairedInfo(deviceId: String, name: String, type: String, paired: Boolean) {
        if (deviceId.isBlank()) return
        setDevicePaired(deviceId, paired)
        if (paired) {
            val editor = prefs.edit()
            if (name.isNotBlank() && name != deviceId) {
                editor.putString("dev_name_$deviceId", name)
            } else if (!prefs.contains("dev_name_$deviceId") && name.isNotBlank()) {
                editor.putString("dev_name_$deviceId", name)
            }
            if (type.isNotBlank() && type != "Remote" && type != "Paired" && type != "Manual" && type != "未知设备") {
                editor.putString("dev_type_$deviceId", type)
            } else if (!prefs.contains("dev_type_$deviceId") && type.isNotBlank()) {
                editor.putString("dev_type_$deviceId", type)
            }
            editor.apply()
        }
    }

    @Synchronized
    fun setDevicePeerKey(deviceId: String, peerKeyHex: String) {
        val cleanId = deviceId.trim()
        val cleanKey = peerKeyHex.trim()
        if (cleanId.isEmpty() || !isValidHexKey64(cleanKey)) return
        try {
            val encrypted = AndroidKeyStoreCrypto.encrypt(cleanKey)
            prefs.edit().putString("dev_key_enc_$cleanId", encrypted).apply()
            coreOrNull()?.setConfig("peer_key_$cleanId", encrypted)
            peerKeyCache[cleanId] = cleanKey
        } catch (e: Throwable) {
            e.printStackTrace()
        }
    }

    @Synchronized
    fun getDevicePeerKey(deviceId: String): String {
        val cleanId = deviceId.trim()
        if (cleanId.isEmpty()) return ""

        // 命中内存缓存就不碰 TEE
        peerKeyCache[cleanId]?.let { return it }

        try {
            val enc = prefs.getString("dev_key_enc_$cleanId", null)
            if (!enc.isNullOrBlank()) {
                val dec = AndroidKeyStoreCrypto.decrypt(enc)
                if (isValidHexKey64(dec)) {
                    val key = dec!!.trim()
                    peerKeyCache[cleanId] = key
                    return key
                }
            }
        } catch (_: Throwable) {}
        try {
            val enc = coreOrNull()?.getConfig("peer_key_$cleanId")
            if (!enc.isNullOrBlank()) {
                val dec = AndroidKeyStoreCrypto.decrypt(enc)
                if (isValidHexKey64(dec)) {
                    prefs.edit().putString("dev_key_enc_$cleanId", enc).apply()
                    val key = dec!!.trim()
                    peerKeyCache[cleanId] = key
                    return key
                }
            }
        } catch (_: Throwable) {}
        return ""
    }

    @Synchronized
    fun buildPairedDeviceSpec(
        deviceId: String,
        ip: String = "",
        port: Int = 0,
        name: String = "",
        type: String = "",
        peerKey: String = ""
    ): String {
        val cleanId = deviceId.trim()
        val lastEp = getDeviceLastEndpoint(cleanId)
        val resolvedIp = ip.trim().ifEmpty { lastEp?.first ?: "" }
        val resolvedPort = if (port > 0) port else (lastEp?.second ?: syncPort)
        val resolvedName = (name.trim().ifEmpty { getDeviceName(cleanId) }).replace('|', ' ')
        val resolvedType = (type.trim().ifEmpty { getDeviceType(cleanId) }).replace('|', ' ')
        val resolvedKey = if (isValidHexKey64(peerKey)) peerKey.trim() else getDevicePeerKey(cleanId)
        return if (resolvedIp.isEmpty() && resolvedKey.isEmpty()) {
            cleanId
        } else {
            "$cleanId|$resolvedIp|$resolvedPort|$resolvedName|$resolvedType|$resolvedKey"
        }
    }

    @Synchronized
    fun setDeviceLastEndpoint(deviceId: String, ip: String, port: Int) {
        if (ip.isNotBlank() && port > 0) {
            val prevIp = prefs.getString("dev_ip_$deviceId", null)
            val prevPort = prefs.getInt("dev_port_$deviceId", 0)
            if (prevIp != ip || prevPort != port) {
                prefs.edit().putString("dev_ip_$deviceId", ip).putInt("dev_port_$deviceId", port).apply()
            }
        }
    }

    @Synchronized
    fun getDeviceLastEndpoint(deviceId: String): Pair<String, Int>? {
        val ip = prefs.getString("dev_ip_$deviceId", null) ?: return null
        val port = prefs.getInt("dev_port_$deviceId", 0)
        return if (ip.isNotBlank() && port > 0) Pair(ip, port) else null
    }

    @Synchronized
    fun getDeviceName(deviceId: String): String {
        return prefs.getString("dev_name_$deviceId", null) ?: deviceId
    }

    @Synchronized
    fun getDeviceType(deviceId: String): String {
        return prefs.getString("dev_type_$deviceId", null) ?: "未知设备"
    }

    var aggressiveBackgroundClipboard: Boolean
        get() = prefs.getBoolean("aggressive_background_clipboard", false)
        set(value) = prefs.edit().putBoolean("aggressive_background_clipboard", value).apply()

    var deviceName: String
        get() = prefs.getString(KEY_DEVICE_NAME, android.os.Build.MODEL) ?: android.os.Build.MODEL
        set(value) = prefs.edit().putString(KEY_DEVICE_NAME, value).apply()

    var deviceId: String
        get() {
            var id = prefs.getString(KEY_DEVICE_ID, null)
            if (id == null) {
                id = "muclip-android-" + java.util.UUID.randomUUID().toString().substring(0, 8)
                prefs.edit().putString(KEY_DEVICE_ID, id).apply()
            }
            return id
        }
        set(value) = prefs.edit().putString(KEY_DEVICE_ID, value).apply()

    fun getTrustedDevices(): Set<String> {
        return prefs.getStringSet(KEY_TRUSTED_DEVICES, emptySet()) ?: emptySet()
    }

    fun isDeviceTrusted(deviceId: String): Boolean {
        return getTrustedDevices().contains(deviceId)
    }

    fun setDeviceTrusted(deviceId: String, trusted: Boolean) {
        val current = getTrustedDevices().toMutableSet()
        if (trusted) {
            current.add(deviceId)
        } else {
            current.remove(deviceId)
        }
        prefs.edit().putStringSet(KEY_TRUSTED_DEVICES, current).apply()
    }

    fun normalizeEndpoint(endpoint: String): String {
        val trimmed = endpoint.trim()
        if (trimmed.isEmpty()) return ""
        if (trimmed.startsWith("[")) {
            val endBracket = trimmed.indexOf(']')
            if (endBracket > 1) {
                val host = trimmed.substring(1, endBracket).trim()
                val after = trimmed.substring(endBracket + 1).trim()
                val port = if (after.startsWith(":")) {
                    after.substring(1).toIntOrNull()?.takeIf { it in 1..65535 } ?: DEFAULT_PORT
                } else {
                    DEFAULT_PORT
                }
                return "$host:$port"
            }
        }
        val colonIdx = trimmed.lastIndexOf(':')
        if (colonIdx > 0 && colonIdx < trimmed.length - 1) {
            val host = trimmed.substring(0, colonIdx).trim()
            val port = trimmed.substring(colonIdx + 1).trim().toIntOrNull()
            if (host.isNotEmpty() && port != null && port in 1..65535) {
                return "$host:$port"
            }
        }
        return "$trimmed:$DEFAULT_PORT"
    }

    fun getCustomPeers(): Set<String> {
        val raw = prefs.getStringSet(KEY_CUSTOM_PEERS, emptySet()) ?: emptySet()
        return raw.mapNotNull { ep -> normalizeEndpoint(ep).takeIf { it.isNotEmpty() } }.toSet()
    }

    fun addCustomPeer(endpoint: String): String {
        val normalized = normalizeEndpoint(endpoint)
        if (normalized.isNotEmpty()) {
            val current = getCustomPeers().toMutableSet()
            current.add(normalized)
            prefs.edit().putStringSet(KEY_CUSTOM_PEERS, current).apply()
        }
        return normalized
    }

    fun removeCustomPeer(endpoint: String) {
        val trimmed = endpoint.trim()
        val normalized = normalizeEndpoint(trimmed)
        val current = getCustomPeers().toMutableSet()
        current.removeAll {
            it.equals(trimmed, ignoreCase = true) ||
                it.equals(normalized, ignoreCase = true) ||
                it.substringBeforeLast(':').equals(trimmed, ignoreCase = true)
        }
        prefs.edit().putStringSet(KEY_CUSTOM_PEERS, current).apply()
    }
}
