package com.wdtt.client

/**
 * Меняешь только здесь — один раз перед сборкой APK.
 * Значения берёшь из вывода install.sh на своём VPS.
 */
object ServerConfig {
    const val HOST     = "192.145.30.132"       // ← IP твоего VPS
    const val PORT     = 56000              // ← не менять
    const val PASSWORD = "tunnel2026"  // ← из install.sh --password

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
