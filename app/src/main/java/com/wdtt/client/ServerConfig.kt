package com.wdtt.client

/**
 * Меняешь только здесь — один раз перед сборкой APK.
 * Значения берёшь из вывода install.sh на своём VPS.
 */
object ServerConfig {
    const val HOST     = "192.145.30.132"       // ← IP твоего VPS
    const val PORT     = 56000              // ← не менять
    const val PASSWORD = "tunnel2026"  // ← из install.sh --password
    // OBF_KEY = HKDF-SHA256(ikm=PASSWORD, salt="WDTT-WRAP-v1", info="rtp-obfs/chacha20poly1305")
    // Автоматически совпадает с ключом сервера при -password PASSWORD.
    const val OBF_KEY = "012ae585326c1b41b0ae0278e1aa409063949562cc5645444a35deba4419224c"

    const val VLESS_URI = "vless://e3731d3b-5bac-42df-8299-c10bf105e4c5@178.208.87.245:8880/?type=ws&encryption=none&path=%2Fskyflow&host=skyflow.sky-flow.site&security=none#WS%20Protokol"

    // WireGuard конфиг клиента. Ключи должны совпадать с /etc/wdtt/wg-keys.dat на VPS.
    // Сгенерированы setup-server.sh — при деплое нового сервера ключи обновляются автоматически.
    const val WG_CONFIG = """
[Interface]
PrivateKey = cFKlz2G2UU3j03vwe2jugrIhJTFjLmu1MSZ41Lpg024=
Address = 10.66.66.2/32
DNS = 1.1.1.1
MTU = 1280

[Peer]
PublicKey = 3TnSzSokv43REGxreadx6RL/YPwt/1hR+MaOUxRyTxs=
AllowedIPs = 0.0.0.0/0
Endpoint = 127.0.0.1:9000
PersistentKeepalive = 25
"""

    fun isConfigured(): Boolean {
        return HOST.isNotBlank() &&
            HOST != "your.server.host" &&
            PORT > 0 &&
            PASSWORD.isNotBlank()
    }

    fun defaultPeer(): String {
        return "$HOST:$PORT"
    }
}
