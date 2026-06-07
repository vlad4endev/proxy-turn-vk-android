package com.wdtt.client

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Меняешь только здесь — один раз перед сборкой APK.
 *
 * Для одного сервера на всех пользователей:
 *   HOST     = IP твоего VPS
 *   PASSWORD = пароль, с которым запущен wdtt-server (-password ...)
 *
 * OBF_KEY и WG_CONFIG.PublicKey вычисляются/берутся из сервера —
 * вручную менять не нужно.
 */
object ServerConfig {
    const val HOST     = "192.145.30.132"  // ← IP VPS
    const val PORT     = 56000             // ← не менять
    const val PASSWORD = "tunnel2026"      // ← пароль сервера (-password)

    /**
     * WRAP-ключ должен совпадать с OBF_KEY в конфиге free-turn-proxy на VPS.
     * Смотреть: docker inspect free-turn-proxy → Env → OBF_KEY
     *
     * Текущее значение взято напрямую из docker inspect (env OBF_KEY).
     * При смене ключа на сервере — обновить здесь и пересобрать APK.
     */
    const val OBF_KEY = "55c51601b576de791d58cd0ed8110f3833d106e40a16d10cfe09f53c0de5754a"

    const val VLESS_URI = "vless://e3731d3b-5bac-42df-8299-c10bf105e4c5@178.208.87.245:8880/?type=ws&encryption=none&path=%2Fskyflow&host=skyflow.sky-flow.site&security=none#WS%20Protokol"

    const val WG_CONFIG = """
[Interface]
PrivateKey = cFKlz2G2UU3j03vwe2jugrIhJTFjLmu1MSZ41Lpg024=
Address = 10.0.0.3/32
DNS = 1.1.1.1
MTU = 1280

[Peer]
PublicKey = WPfiQbTLCgE+GJkKaMbtQvw6WVXMWb/w0yKzSw9rbUM=
AllowedIPs = 0.0.0.0/0
Endpoint = 127.0.0.1:9000
PersistentKeepalive = 25
"""

    fun isConfigured(): Boolean =
        HOST.isNotBlank() && HOST != "your.server.host" && PORT > 0 && PASSWORD.isNotBlank()

    fun defaultPeer(): String = "$HOST:$PORT"

    // ── HKDF-SHA256 (RFC 5869) ────────────────────────────────────────────────
    // Повторяет deriveWrapKey() из server.go — изменять не нужно.
    private fun deriveObfKey(password: String): String {
        val salt = "WDTT-WRAP-v1".toByteArray(Charsets.UTF_8)
        val ikm  = password.toByteArray(Charsets.UTF_8)
        val info = "rtp-obfs/chacha20poly1305".toByteArray(Charsets.UTF_8)

        // Extract: PRK = HMAC-SHA256(salt, IKM)
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(salt, "HmacSHA256"))
        val prk = mac.doFinal(ikm)

        // Expand: OKM = HMAC-SHA256(PRK, info || 0x01)  (один блок = 32 байта)
        mac.init(SecretKeySpec(prk, "HmacSHA256"))
        mac.update(info)
        mac.update(0x01.toByte())
        val okm = mac.doFinal()

        return okm.joinToString("") { "%02x".format(it) }
    }
}
