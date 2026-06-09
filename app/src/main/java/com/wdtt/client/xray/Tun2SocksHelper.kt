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

        // Required by libhev-socks5-tunnel.so RegisterNatives (()[J descriptor).
        // JNI_OnLoad registers all three methods; if this is missing, RegisterNatives
        // fails and TProxyStartService/TProxyStopService are never bound either.
        @JvmStatic
        external fun TProxyGetStats(): LongArray
    }

    init {
        tryLoadNativeLibrary()
    }

    private fun tryLoadNativeLibrary() {
        try {
            System.loadLibrary("hev-socks5-tunnel")
            nativeLibLoaded = true
            Log.d(TAG, "libhev-socks5-tunnel loaded successfully")
        } catch (e: Throwable) {
            // Catches UnsatisfiedLinkError (missing/bad .so), SecurityException, etc.
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
        // Format per hev-socks5-tunnel v2.x documentation.
        // Notes: log-level must be "warn" (not "warning"); mtu 8500 is the
        // recommended default; multi-queue must be false for Android JNI mode.
        val configFile = File(vpnService.filesDir, "hev-socks5-tunnel.yml")
        configFile.writeText(
            """
tunnel:
  name: tun0
  mtu: 8500
  multi-queue: false

socks5:
  port: $socksPort
  address: $socksHost
  udp: udp

misc:
  task-stack-size: 20480
  connect-timeout: 5000
  read-write-timeout: 60000
  log-level: warn
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
        Log.i(TAG, "TProxyStartService: fd=$fdInt configExists=${configFile.exists()} configSize=${configFile.length()} path=$configPath")
        val threadError = arrayOfNulls<String>(1) // [0] written by thread, read after join/sleep
        proxyThread = Thread {
            Log.i(TAG, "hev-socks5-tunnel thread started, fd=$fdInt cfg=$configPath")
            try {
                TProxyStartService(configPath, fdInt)
            } catch (e: UnsatisfiedLinkError) {
                threadError[0] = "UnsatisfiedLinkError: ${e.message}"
                Log.e(TAG, "TProxyStartService UnsatisfiedLinkError: ${e.message}")
            } catch (e: Throwable) {
                threadError[0] = "${e::class.java.simpleName}: ${e.message}"
                Log.e(TAG, "TProxyStartService threw: ${e.message}")
            } finally {
                _running.set(false)
                Log.i(TAG, "hev-socks5-tunnel thread exited, error=${threadError[0]}")
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
            val cfgOk = configFile.exists() && configFile.length() > 0
            val reason = threadError[0] ?: "hev вернулся без исключения (config/fd?)"
            throw IllegalStateException(
                "hev-socks5-tunnel завершился немедленно: $reason [fd=$fdInt cfg=${if (cfgOk) "ok(${configFile.length()}b)" else "MISSING"}]"
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
            } catch (e: Throwable) {
                // Catches UnsatisfiedLinkError, Exception, etc. — must not propagate from stop()
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
