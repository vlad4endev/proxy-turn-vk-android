package com.wdtt.client

/**
 * Меняешь только здесь — один раз перед сборкой APK.
 * Значения берёшь из вывода install.sh на своём VPS.
 */
object ServerConfig {
    const val HOST     = "192.145.30.132"       // ← IP твоего VPS
    const val PORT     = 56000              // ← не менять
    const val PASSWORD = "tunnel2026"  // ← из install.sh --password
    const val OBF_KEY = "55c51601b576de791d58cd0ed8110f3833d106e40a16d10cfe09f53c0de5754a"

    const val WG_CONFIG = """
[Interface]
PrivateKey = WDa5z/k0Z4CR+92guVwyGQ41+9VqImg07HsNK4EunEo=
Address = 10.0.0.2/32
DNS = 1.1.1.1
MTU = 1280

[Peer]
PublicKey = WPfiQbTLCgE+GJkKaMbtQvw6WVXMWb/w0yKzSw9rbUM=
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
