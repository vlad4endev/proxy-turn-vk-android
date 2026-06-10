package com.wdtt.client.xray

import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log
import com.wdtt.client.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Создаёт TUN-интерфейс через [VpnService.Builder] и запускает hev-socks5-tunnel
 * ([libhev-socks5-tunnel.so] через JNI), направляя весь трафик устройства в SOCKS5-прокси Xray.
 *
 * ВАЖНО о архитектуре JNI (hev-socks5-tunnel v2.15.0):
 *   - [TProxyStartService] — NON-BLOCKING. Внутри native_start_service JNI создаёт
 *     нативный C-поток с hev_socks5_tunnel_main() и НЕМЕДЛЕННО возвращает управление Kotlin.
 *     Туннель живёт в C-потоке, управляемом самой библиотекой.
 *   - [TProxyStopService] — BLOCKING. Внутри native_stop_service вызывает
 *     hev_socks5_tunnel_quit(), а затем pthread_join на нативный C-поток.
 *
 * Жизненный цикл:
 *   1. TunnelService (VpnService) уже запущен → [TunnelService.instance] != null
 *   2. [start] → VpnService.Builder.establish() → TUN fd → TProxyStartService(config, fd) (non-blocking)
 *   3. [stop]  → TProxyStopService() (blocking, ждёт завершения C-потока) → закрываем fd
 *
 * ПОТОКОБЕЗОПАСНОСТЬ: hev-socks5-tunnel — глобальный нативный синглтон.
 * TProxyStartService и TProxyStopService НЕЛЬЗЯ вызывать одновременно из разных
 * экземпляров (разных корутин). [nativeLock] в companion object сериализует все
 * нативные операции через все экземпляры класса.
 */
class Tun2SocksHelper(private val vpnService: VpnService) {

    @Volatile private var tunFd: ParcelFileDescriptor? = null
    @Volatile private var nativeLibLoaded = false

    companion object {
        private const val TAG = "Tun2Socks"
        private const val TUN_ADDRESS = "10.1.0.2"
        private const val TUN_PREFIX = 30
        private const val TUN_MTU = 1500

        // Global mutex — serialises ALL TProxyStartService / TProxyStopService calls
        // across every Tun2SocksHelper instance. Prevents the crash that occurs when
        // TProxyStopService (from a previous instance's stop coroutine) and
        // TProxyStartService (from a new instance's start) run concurrently, e.g.
        // after START_STICKY service restart.
        private val nativeLock = Mutex()

        // Global flag that mirrors whether the native C-thread is alive.
        // Shared because the native library is a process-wide singleton.
        @Volatile private var nativeRunning = false

        // TProxyStartService — NON-BLOCKING: запускает внутренний C-поток и возвращается.
        @JvmStatic
        private external fun TProxyStartService(configPath: String, fd: Int)

        // TProxyStopService — BLOCKING: вызывает quit() и pthread_join на C-поток.
        @JvmStatic
        private external fun TProxyStopService()

        // Required by libhev-socks5-tunnel.so RegisterNatives (()[J descriptor).
        // JNI_OnLoad registers all three methods atomically; missing one → all fail.
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
            Log.w(TAG, "Failed to load libhev-socks5-tunnel: ${e.message}")
            nativeLibLoaded = false
        }
    }

    /**
     * Создаёт TUN через VpnService.Builder и запускает hev-socks5-tunnel.
     * Удерживает [nativeLock] на всё время операции, чтобы предотвратить
     * конкурентный вызов TProxyStopService из параллельной stop()-корутины.
     */
    suspend fun start(socksHost: String, socksPort: Int) {
        withContext(Dispatchers.IO) {
            nativeLock.withLock {
                // ── 0. Остановить предыдущий нативный сервис (если запущен) ──
                // Inline (не вызываем stop()) — Mutex нереентрантен.
                if (nativeRunning) {
                    nativeRunning = false
                    try {
                        TProxyStopService()
                        Log.i(TAG, "Previous hev-socks5-tunnel stopped before restart")
                    } catch (e: Throwable) {
                        Log.w(TAG, "TProxyStopService (pre-start cleanup): ${e.message}")
                    }
                }
                // Close any leftover fd from this instance
                val oldFd = tunFd; tunFd = null
                try { oldFd?.close() } catch (_: Exception) {}

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

                val fdInt = tunFd!!.fd
                Log.i(TAG, "TUN создан, fd=$fdInt")

                // ── 2. Конфиг hev-socks5-tunnel v2.15.0 ─────────────────────────
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
  task-stack-size: 86016
  connect-timeout: 5000
  tcp-read-write-timeout: 300000
  udp-read-write-timeout: 60000
  log-file: stderr
  log-level: info
                    """.trimIndent()
                )

                // ── 3. Запуск hev-socks5-tunnel через JNI ────────────────────────
                val configPath = configFile.absolutePath
                Log.i(TAG, "TProxyStartService: fd=$fdInt cfg=$configPath (${configFile.length()}b)")

                try {
                    TProxyStartService(configPath, fdInt)
                } catch (e: UnsatisfiedLinkError) {
                    tunFd?.close(); tunFd = null
                    throw IllegalStateException("libhev-socks5-tunnel.so не загружена: ${e.message}")
                } catch (e: Throwable) {
                    tunFd?.close(); tunFd = null
                    throw IllegalStateException("TProxyStartService: ${e.message}")
                }

                // Даём нативному C-потоку 300 мс на старт event-loop'а.
                // Thread.sleep (не delay) намеренно: не прерывается отменой корутины,
                // поэтому nativeRunning = true гарантированно выставится после запуска.
                Thread.sleep(300)

                nativeRunning = true
                Log.i(TAG, "hev-socks5-tunnel запущен (нативный C-поток внутри JNI)")
            }
        }
    }

    /**
     * Останавливает hev-socks5-tunnel и закрывает TUN-дескриптор.
     * Удерживает [nativeLock] — ждёт завершения любого параллельного start().
     * TProxyStopService — блокирующий: ждёт pthread_join нативного C-потока.
     */
    suspend fun stop() {
        withContext(Dispatchers.IO) {
            nativeLock.withLock {
                if (nativeRunning) {
                    nativeRunning = false
                    try {
                        TProxyStopService()
                    } catch (e: Throwable) {
                        Log.w(TAG, "TProxyStopService: ${e.message}")
                    }
                    Log.i(TAG, "hev-socks5-tunnel остановлен")
                }
                val fd = tunFd
                tunFd = null
                try {
                    fd?.close()
                } catch (_: Exception) {}
            }
        }
    }

    fun isRunning(): Boolean = nativeRunning && tunFd != null
}
