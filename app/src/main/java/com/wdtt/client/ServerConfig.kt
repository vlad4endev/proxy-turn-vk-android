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
