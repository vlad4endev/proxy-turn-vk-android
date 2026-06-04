package com.wdtt.client

/**
 * ╔══════════════════════════════════════════════════╗
 * ║  МЕНЯЕШЬ ТОЛЬКО ЗДЕСЬ — один раз перед сборкой  ║
 * ╚══════════════════════════════════════════════════╝
 */
object HardcodedConfig {

    // ── VPS ─────────────────────────────────────────────────────────────────
    const val SSH_IP         = "185.x.x.x"
    const val SSH_LOGIN      = "root"
    const val SSH_PASSWORD   = "пароль_vps"
    const val SSH_PORT       = "22"

    // ── Параметры туннеля ────────────────────────────────────────────────────
    const val SERVER_HOST    = "185.x.x.x"   // тот же IP что и SSH_IP
    const val SERVER_PORT    = 56000
    const val TUNNEL_PASSWORD = "пароль_туннеля"

    // ── Telegram бот (опционально) ───────────────────────────────────────────
    const val ADMIN_ID  = ""
    const val BOT_TOKEN = ""
}
