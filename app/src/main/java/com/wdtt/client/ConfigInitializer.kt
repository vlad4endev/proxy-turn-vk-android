package com.wdtt.client

import android.content.Context
import kotlinx.coroutines.flow.first

object ConfigInitializer {

    suspend fun applyIfNeeded(context: Context) {
        val store = SettingsStore(context)

        // SSH данные для деплоя (если вдруг понадобится с телефона)
        val currentIp = store.deployIp.first()
        if (currentIp.isBlank() && HardcodedConfig.SSH_IP != "185.x.x.x") {
            store.saveDeploy(
                ip = HardcodedConfig.SSH_IP,
                login = HardcodedConfig.SSH_LOGIN,
                pass = HardcodedConfig.SSH_PASSWORD,
                sshPort = HardcodedConfig.SSH_PORT
            )
        }

        // Адрес сервера (peer) — IP:port
        val currentPeer = store.peer.first()
        val serverHost = when {
            HardcodedConfig.SERVER_HOST != "185.x.x.x" -> HardcodedConfig.SERVER_HOST
            ServerConfig.isConfigured() -> ServerConfig.HOST
            else -> null
        }
        val serverPort = when {
            HardcodedConfig.SERVER_HOST != "185.x.x.x" -> HardcodedConfig.SERVER_PORT
            ServerConfig.isConfigured() -> ServerConfig.PORT
            else -> null
        }
        if (serverHost != null && serverPort != null &&
            (currentPeer.isBlank() || ServerConfig.isConfigured())
        ) {
            store.save(
                peer = "$serverHost:$serverPort",
                vkHashes = store.vkHashes.first(),
                secondaryVkHash = store.secondaryVkHash.first(),
                workersPerHash = 18,
                protocol = "udp",
                listenPort = store.listenPort.first()
            )
        }

        // Пароль туннеля
        val currentPass = store.connectionPassword.first()
        val tunnelPassword = when {
            HardcodedConfig.TUNNEL_PASSWORD != "пароль_туннеля" -> HardcodedConfig.TUNNEL_PASSWORD
            ServerConfig.isConfigured() -> ServerConfig.PASSWORD
            else -> null
        }
        if (tunnelPassword != null && (currentPass.isBlank() || ServerConfig.isConfigured())) {
            store.saveConnectionPassword(tunnelPassword)
            store.saveDeploySecrets(
                mainPass = tunnelPassword,
                adminId = HardcodedConfig.ADMIN_ID,
                botToken = HardcodedConfig.BOT_TOKEN,
                sshPort = HardcodedConfig.SSH_PORT
            )
        }
    }
}
