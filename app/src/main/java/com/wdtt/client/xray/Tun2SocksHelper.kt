package com.wdtt.client.xray

import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log
import com.wdtt.client.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Создаёт TUN-интерфейс через [VpnService.Builder] и запускает hev-socks5-tunnel
 * ([libhev-socks5-tunnel.so] через JNI), направляя весь трафик устройства в SOCKS5-прокси Xray.
 *
 * Жизненный цикл:
 *   1. TunnelService (VpnService) уже запущен → [TunnelService.instance] != null
 *   2. [start] → VpnService.Builder.establish() → TUN fd → TProxyStartService(config, fd)
 *   3. [stop]  → TProxyStopService() → закрываем fd
 */
class Tun2SocksHelper(private val vpnService: VpnService) {

    @Volatile private var tunFd: ParcelFileDescriptor? = null
    @Volatile private var running = false
    @Volatile private var nativeLibLoaded = false

    companion object {
        private const val TAG = "Tun2Socks"
        private const val TUN_ADDRESS = "10.1.0.2"
        private const val TUN_PREFIX = 30
        private const val TUN_MTU = 1500

        @JvmStatic
        private external fun TProxyStartService(configPath: String, fd: Int)

        @JvmStatic
        private external fun TProxyStopService()
    }

    init {
        tryLoadNativeLibrary()
    }

    private fun tryLoadNativeLibrary() {
        try {
            System.loadLibrary("hev-socks5-tunnel")
            nativeLibLoaded = true
            Log.d(TAG, "libhev-socks5-tunnel loaded successfully")
        } catch (e: UnsatisfiedLinkError) {
            Log.w(TAG, "Failed to load libhev-socks5-tunnel: ${e.message}")
            nativeLibLoaded = false
        }
    }

    /**
     * Создаёт TUN через VpnService.Builder и запускает hev-socks5-tunnel.
     * Должен вызываться из IO-корутины.
     */
    suspend fun start(socksHost: String, socksPort: Int) = withContext(Dispatchers.IO) {
        stop()

        if (!nativeLibLoaded) {
            throw IllegalStateException(
                "libhev-socks5-tunnel.so не загружена — пересоберите APK (scripts/build-native-speed.sh)"
            )
        }

        if (VpnService.prepare(vpnService) != null) {
            throw IllegalStateException("VPN-разрешение не выдано")
        }

        // ── 1. TUN через VpnService.Builder ──────────────────────────────
        val builder = vpnService.Builder()
        builder.setSession("SKYFLOW Speed")
        builder.addAddress(TUN_ADDRESS, TUN_PREFIX)
        builder.addRoute("0.0.0.0", 0)
        builder.addDnsServer("1.1.1.1")
        builder.addDnsServer("8.8.8.8")
        builder.setMtu(TUN_MTU)
        builder.setBlocking(false)

        // Исключения приложений (та же логика что в WireGuardHelper)
        val store = SettingsStore(vpnService)
        val savedExcluded = store.excludedApps.first()
        val userSelected = savedExcluded.split(",").filter { it.isNotEmpty() }.toSet()

        val excluded = mutableSetOf(
            vpnService.packageName,
            "com.vkontakte.android",
            "com.vk.calls"
        )
        excluded.addAll(userSelected)

        val pm = vpnService.packageManager
        excluded
            .filter { pkg -> runCatching { pm.getPackageInfo(pkg, 0); true }.getOrDefault(false) }
            .forEach { builder.addDisallowedApplication(it) }

        tunFd = builder.establish()
            ?: throw IllegalStateException("VPN establish() вернул null — VPN-разрешение отозвано?")

        Log.i(TAG, "TUN создан, fd=${tunFd!!.fd}")

        // ── 2. Конфиг hev-socks5-tunnel ──────────────────────────────────
        val configFile = File(vpnService.filesDir, "hev-socks5-tunnel.yml")
        configFile.writeText(
            """
tunnel:
  mtu: $TUN_MTU
  ipv4: $TUN_ADDRESS

socks5:
  port: $socksPort
  address: $socksHost
  udp: 'udp'

misc:
  task-stack-size: 20480
  connect-timeout: 5000
  tcp-read-write-timeout: 60000
  log-level: warning
            """.trimIndent()
        )

        // ── 3. Запуск hev-socks5-tunnel через JNI ───────────────────────
        try {
            TProxyStartService(configFile.absolutePath, tunFd!!.fd)
            running = true
            Log.i(TAG, "hev-socks5-tunnel запущен")
        } catch (e: UnsatisfiedLinkError) {
            tunFd?.close()
            tunFd = null
            throw IllegalStateException(
                "libhev-socks5-tunnel.so не найден — пересоберите APK (scripts/build-native-speed.sh)",
                e
            )
        } catch (e: Exception) {
            tunFd?.close()
            tunFd = null
            throw IllegalStateException("Не удалось запустить hev-socks5-tunnel: ${e.message}", e)
        }
    }

    /** Останавливает hev-socks5-tunnel и закрывает TUN-дескриптор. */
    suspend fun stop() = withContext(Dispatchers.IO) {
        if (running) {
            try {
                TProxyStopService()
            } catch (e: Exception) {
                Log.w(TAG, "TProxyStopService: ${e.message}")
            }
            running = false
            Log.i(TAG, "hev-socks5-tunnel остановлен")
        }
        val fd = tunFd
        tunFd = null
        try {
            fd?.close()
        } catch (_: Exception) {
        }
    }

    fun isRunning(): Boolean = running && tunFd != null
}
