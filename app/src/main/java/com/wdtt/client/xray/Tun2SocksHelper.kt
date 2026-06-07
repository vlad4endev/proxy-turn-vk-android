package com.wdtt.client.xray

import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log
import com.wdtt.client.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

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
    // AtomicBoolean для видимости между нативным потоком и Kotlin-корутинами
    private val _running = AtomicBoolean(false)
    // Нативный поток, в котором живёт блокирующий вызов TProxyStartService.
    // TProxyStartService запускает внутренний event loop и возвращается только
    // после вызова TProxyStopService — его НЕЛЬЗЯ вызывать из корутины напрямую.
    @Volatile private var proxyThread: Thread? = null
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

        // ── 3. Запуск hev-socks5-tunnel через JNI в отдельном потоке ────
        //
        // ВАЖНО: TProxyStartService — блокирующий JNI-вызов. Он запускает
        // нативный event loop hev-socks5-tunnel и возвращается ТОЛЬКО после
        // вызова TProxyStopService(). Вызывать его из корутины нельзя —
        // suspend fun start() зависнет навечно и не установит VPN_READY.
        //
        // Решение: запускаем в daemon-потоке, устанавливаем _running = true
        // сразу после старта потока (не после возврата TProxyStartService).
        val configPath  = configFile.absolutePath
        val fdInt       = tunFd!!.fd
        proxyThread = Thread {
            Log.i(TAG, "hev-socks5-tunnel thread started")
            try {
                TProxyStartService(configPath, fdInt)
            } catch (e: UnsatisfiedLinkError) {
                Log.e(TAG, "libhev-socks5-tunnel.so не найден: ${e.message}")
            } catch (e: Exception) {
                Log.e(TAG, "TProxyStartService exception: ${e.message}")
            } finally {
                _running.set(false)
                Log.i(TAG, "hev-socks5-tunnel thread exited")
            }
        }.also {
            it.isDaemon = true
            it.name = "hev-tproxy"
            it.start()
        }

        // Небольшая пауза — убедиться, что поток не упал мгновенно
        // (например, если fd некорректен). Поток остаётся живым — всё ок.
        Thread.sleep(200)
        if (!proxyThread!!.isAlive) {
            tunFd?.close()
            tunFd = null
            throw IllegalStateException(
                "hev-socks5-tunnel завершился немедленно — libhev-socks5-tunnel.so не загружен или некорректный fd"
            )
        }

        _running.set(true)
        Log.i(TAG, "hev-socks5-tunnel запущен (thread=${proxyThread?.name})")
    }

    /** Останавливает hev-socks5-tunnel и закрывает TUN-дескриптор. */
    suspend fun stop() = withContext(Dispatchers.IO) {
        if (_running.getAndSet(false)) {
            // Сигнализируем нативному event loop завершиться
            try {
                TProxyStopService()
            } catch (e: Exception) {
                Log.w(TAG, "TProxyStopService: ${e.message}")
            }
            // Ждём завершения нативного потока (max 3 сек)
            try {
                proxyThread?.join(3_000)
            } catch (_: InterruptedException) {}
            proxyThread = null
            Log.i(TAG, "hev-socks5-tunnel остановлен")
        }
        val fd = tunFd
        tunFd = null
        try {
            fd?.close()
        } catch (_: Exception) {}
    }

    fun isRunning(): Boolean = _running.get() && tunFd != null && (proxyThread?.isAlive == true)
}
