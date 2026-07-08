package com.wdtt.client

import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.util.Log
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config
import com.wireguard.config.Interface
import com.wireguard.config.Peer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream

class WireGuardHelper(context: Context) {
    private val appContext = context.applicationContext
    private val backend = (appContext as WdttApplication).getBackend(context)

    companion object {
        private val wgMutex = Mutex()
        private var sharedTunnel: WgTunnel? = null

        const val SPEED_WG_PORT = 51820

        // MTU интерфейса WireGuard. Ниже 1280, т.к. внутренний WG-пакет ещё раз
        // оборачивается rtpopus (AEAD+RTP ~40Б) + TURN ChannelData + UDP/IP до
        // выхода в реальную сеть; на LTE с path-MTU ~1400 прежние 1280 давали
        // фрагментацию/потери на bulk-трафике («подключено, но не грузит»).
        const val WG_MTU = 1240

        /**
         * Endpoint для скоростного режима: IP из [peer] (settingsStore.peer),
         * DTLS-порт отбрасывается, WG-порт всегда [SPEED_WG_PORT].
         * Пример: "192.145.30.132:56000" → "192.145.30.132:51820"
         */
        fun speedEndpointFromPeer(peer: String): String {
            val host = peer.trim().substringBefore(":").ifBlank { ServerConfig.HOST }
            return "$host:$SPEED_WG_PORT"
        }

        /** WireGuard-конфиг для SPEED: endpoint из settingsStore.peer → IP:51820. */
        fun buildConfigForSpeedMode(peer: String): String =
            buildConfigWithEndpoint(speedEndpointFromPeer(peer))

        /** Собирает WireGuard-конфиг с заданным endpoint (для скоростного режима — VPS напрямую). */
        fun buildConfigWithEndpoint(endpoint: String): String {
            val base = ensureWireGuardMtu(ServerConfig.WG_CONFIG.trim())
            return base.lines().joinToString("\n") { line ->
                if (line.trimStart().startsWith("Endpoint", ignoreCase = true)) {
                    "Endpoint = $endpoint"
                } else {
                    line
                }
            }.trimEnd()
        }

        /** Подставляет или заменяет MTU в секции [Interface] (TURN/DTLS накладные расходы). */
        fun ensureWireGuardMtu(config: String, mtu: Int = WG_MTU): String {
            val mtuLine = "MTU = $mtu"
            val lines = config.lines().toMutableList()
            val interfaceIdx = lines.indexOfFirst { it.trim().equals("[Interface]", ignoreCase = true) }
            if (interfaceIdx < 0) return config

            val peerIdx = lines.indexOfFirst { it.trim().equals("[Peer]", ignoreCase = true) }
            val sectionEnd = if (peerIdx >= 0) peerIdx else lines.size

            for (i in interfaceIdx + 1 until sectionEnd) {
                if (lines[i].trim().startsWith("MTU", ignoreCase = true)) {
                    lines[i] = mtuLine
                    return lines.joinToString("\n").trimEnd()
                }
            }

            val insertAt = (interfaceIdx + 1 until sectionEnd)
                .lastOrNull { lines[it].trim().isNotEmpty() && !lines[it].trim().startsWith("[") }
                ?.plus(1) ?: (interfaceIdx + 1)
            lines.add(insertAt, mtuLine)
            return lines.joinToString("\n").trimEnd()
        }
    }

    class WgTunnel : Tunnel {
        override fun getName() = "wdtt"
        override fun onStateChange(newState: Tunnel.State) {}
    }

    suspend fun startTunnel(configString: String) = wgMutex.withLock {
        startTunnelLocked(configString)
    }

    private suspend fun startTunnelLocked(configString: String) = withContext(Dispatchers.IO) {
        try {
            if (VpnService.prepare(appContext) != null) {
                throw IllegalStateException("VPN-разрешение не выдано")
            }

            ensureGoBackendServiceStarted()

            sharedTunnel?.let { existingTunnel ->
                try {
                    backend.setState(existingTunnel, Tunnel.State.DOWN, null)
                } catch (e: Exception) {
                    Log.w("WG", "Failed to stop previous tunnel before restart: ${e.readableMessage()}")
                }
                sharedTunnel = null
                delay(150)
            }

            val normalizedConfig = ensureWireGuardMtu(configString)
            val parsedConfig = Config.parse(ByteArrayInputStream(normalizedConfig.toByteArray(Charsets.UTF_8)))

            // 1. Пакеты, которые всегда исключаются (наше приложение, ВК)
            // 2. Получаю настройки пользователя
            // Список исключений — общий для обоих вариантов конфига (IPv6 / IPv4-only).
            val settingsStore = SettingsStore(appContext)
            val savedExcluded = settingsStore.excludedApps.first()

            val userSelected = savedExcluded.split(",").filter { it.isNotEmpty() }.toSet()

            // В обоих режимах (ЧС и БС) мы технически используем Blacklist (Checked = Excluded),
            // так как пользователю удобнее логика "снимите галочку, чтобы приложение пошло в туннель".
            // Разница только в описании и начальном состоянии списка (пустой/полный).
            val excluded = mutableSetOf(appContext.packageName, "com.vkontakte.android", "com.vk.calls")
            excluded.addAll(userSelected)
            val installedExcluded = excluded.filter { it.isInstalledPackage() }.toSet()

            // Собирает финальный WireGuard-конфиг.
            //   includeIpv6=true  — добавляет ULA v6-адрес (fd00::3/128) + маршрут ::/0,
            //                       чтобы IPv6 не утекал мимо VPN на dual-stack сетях.
            //   includeIpv6=false — только IPv4: fallback для устройств/сетей, где
            //                       WG-бэкенд отклоняет добавление v6-адреса на tun
            //                       («Cannot set address») и весь старт WG падает.
            fun buildFinalConfig(includeIpv6: Boolean): Config {
                val addresses = parsedConfig.`interface`.addresses.map { it.toString() }
                    .filter { includeIpv6 || !it.contains(":") }
                    .toMutableList()
                if (includeIpv6 && addresses.none { it.contains(":") }) {
                    addresses.add("fd00::3/128")
                }

                val builder = Interface.Builder()
                    .parseAddresses(addresses.joinToString(", "))

                val dnsServers = parsedConfig.`interface`.dnsServers
                if (dnsServers.isNotEmpty()) {
                    builder.parseDnsServers(dnsServers.joinToString(", ") { it.hostAddress ?: "" })
                } else {
                    // Динамический конфиг без строки DNS + маршрут 0.0.0.0/0 в туннель
                    // = резолвинг уходит в туннель в никуда (DNS-чёрная дыра). Дефолтим
                    // на публичные резолверы, чтобы имена всегда резолвились.
                    builder.parseDnsServers("1.1.1.1, 8.8.8.8")
                }
                if (parsedConfig.`interface`.listenPort.isPresent) {
                    builder.parseListenPort(parsedConfig.`interface`.listenPort.get().toString())
                }
                builder.parseMtu(WG_MTU.toString())
                builder.parsePrivateKey(parsedConfig.`interface`.keyPair.privateKey.toBase64())
                if (installedExcluded.isNotEmpty()) {
                    builder.excludeApplications(installedExcluded)
                }

                val peerBuilder = Peer.Builder()
                val firstPeer = parsedConfig.peers.firstOrNull()
                    ?: throw IllegalStateException("WireGuard config has no peer")
                firstPeer.let { peer ->
                    peerBuilder.parsePublicKey(peer.publicKey.toBase64())
                    if (peer.preSharedKey.isPresent) peerBuilder.parsePreSharedKey(peer.preSharedKey.get().toBase64())
                    if (peer.endpoint.isPresent) peerBuilder.parseEndpoint(peer.endpoint.get().toString())
                    if (peer.persistentKeepalive.isPresent) peerBuilder.parsePersistentKeepalive(peer.persistentKeepalive.get().toString())
                }
                // Весь трафик в туннель; ::/0 добавляем только когда есть v6-адрес.
                peerBuilder.parseAllowedIPs(if (includeIpv6) "0.0.0.0/0, ::/0" else "0.0.0.0/0")

                return Config.Builder()
                    .setInterface(builder.build())
                    .addPeer(peerBuilder.build())
                    .build()
            }

            val nextTunnel = WgTunnel()
            // Сначала пробуем поднять туннель с IPv6 (защита от v6-утечки); если
            // устройство/сеть отклоняет v6-адрес — откатываемся на IPv4-only, чтобы
            // подключение всё равно состоялось.
            setTunnelUp(nextTunnel, listOf(buildFinalConfig(true), buildFinalConfig(false)))
            sharedTunnel = nextTunnel
            Log.d("WG", "WireGuard tunnel started successfully")
        } catch (e: Exception) {
            val detailed = "WireGuard start failed: ${e.readableMessage()}; ${configString.describeWireGuardConfig()}"
            Log.e("WG", detailed)
            e.printStackTrace()
            throw IllegalStateException(detailed, e)
        }
    }

    suspend fun reloadTunnel() = wgMutex.withLock {
        withContext(Dispatchers.IO) {
            val currentTunnel = sharedTunnel ?: return@withContext
            try {
                val configFlow = TunnelManager.config.first() ?: return@withContext
                backend.setState(currentTunnel, Tunnel.State.DOWN, null)
                sharedTunnel = null
                delay(150)
                startTunnelLocked(configFlow)
                Log.d("WG", "WireGuard tunnel reloaded for new exceptions")
            } catch (e: Exception) {
                Log.e("WG", "Failed to reload WireGuard: ${e.readableMessage()}")
            }
        }
    }

    suspend fun isTunnelUp(): Boolean = wgMutex.withLock {
        val current = sharedTunnel ?: return false
        return try {
            backend.getState(current) == Tunnel.State.UP
        } catch (e: Exception) {
            false
        }
    }

    suspend fun stopTunnel() = wgMutex.withLock {
        withContext(Dispatchers.IO) {
            try {
                sharedTunnel?.let {
                    backend.setState(it, Tunnel.State.DOWN, null)
                    sharedTunnel = null
                    Log.d("WG", "WireGuard tunnel stopped")
                }
            } catch (e: Exception) {
                Log.e("WG", "Failed to stop WireGuard: ${e.readableMessage()}")
            }
        }
    }

    private suspend fun ensureGoBackendServiceStarted() {
        withContext(Dispatchers.Main) {
            runCatching {
                val intent = Intent(appContext, GoBackend.VpnService::class.java)
                appContext.startService(intent)
            }.onFailure {
                Log.w("WG", "GoBackend service warmup failed: ${it.readableMessage()}")
            }
        }
        delay(300)
    }

    /**
     * Поднимает туннель, пробуя конфиги [configs] по порядку. Первый (обычно с
     * IPv6) получает одну попытку — если он падает детерминированно (например
     * «Cannot set address» на устройстве без v6), быстро переходим к следующему.
     * Последний конфиг (IPv4-only fallback) получает 3 попытки на транзиентные
     * сбои запуска сервиса.
     */
    private suspend fun setTunnelUp(nextTunnel: WgTunnel, configs: List<Config>) {
        var lastError: Exception? = null
        configs.forEachIndexed { idx, config ->
            val isLast = idx == configs.lastIndex
            val attempts = if (isLast) 3 else 1
            repeat(attempts) { attempt ->
                try {
                    backend.setState(nextTunnel, Tunnel.State.UP, config)
                    if (idx > 0) Log.w("WG", "WireGuard поднят на fallback-конфиге #$idx (IPv4-only)")
                    return
                } catch (e: Exception) {
                    lastError = e
                    Log.w("WG", "WireGuard UP конфиг#$idx попытка ${attempt + 1}/$attempts: ${e.readableMessage()}")
                    runCatching { backend.setState(nextTunnel, Tunnel.State.DOWN, null) }
                    ensureGoBackendServiceStarted()
                    delay(250L * (attempt + 1))
                }
            }
        }
        throw lastError ?: IllegalStateException("WireGuard UP failed")
    }

    private fun Throwable.readableMessage(): String {
        val text = message ?: localizedMessage
        return if (text.isNullOrBlank()) this::class.java.simpleName else "${this::class.java.simpleName}: $text"
    }

    private fun String.isInstalledPackage(): Boolean {
        return runCatching {
            appContext.packageManager.getPackageInfo(this, 0)
            true
        }.getOrDefault(false)
    }

    private fun String.describeWireGuardConfig(): String {
        val lines = lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        val hasInterface = lines.any { it.equals("[Interface]", ignoreCase = true) }
        val hasPeer = lines.any { it.equals("[Peer]", ignoreCase = true) }
        val hasPrivateKey = lines.any { it.startsWith("PrivateKey", ignoreCase = true) }
        val hasPublicKey = lines.any { it.startsWith("PublicKey", ignoreCase = true) }
        val hasAddress = lines.any { it.startsWith("Address", ignoreCase = true) }
        val endpoint = lines.firstOrNull { it.startsWith("Endpoint", ignoreCase = true) }
            ?.substringAfter("=", "")
            ?.trim()
            ?.take(80)
            ?: "none"
        return "config lines=${lines.size}, interface=$hasInterface, peer=$hasPeer, privateKey=$hasPrivateKey, publicKey=$hasPublicKey, address=$hasAddress, endpoint=$endpoint"
    }
}
