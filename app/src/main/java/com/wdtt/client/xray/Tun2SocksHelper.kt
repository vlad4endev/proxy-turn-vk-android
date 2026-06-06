package com.wdtt.client.xray

import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log
import com.wdtt.client.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Создаёт TUN-интерфейс через [VpnService.Builder] и запускает hev-socks5-tunnel
 * (libtun2socks.so), направляя весь трафик устройства в SOCKS5-прокси Xray.
 *
 * Жизненный цикл:
 *   1. TunnelService (VpnService) уже запущен → [TunnelService.instance] != null
 *   2. [start] → VpnService.Builder.establish() → TUN fd → запуск libtun2socks.so
 *   3. [stop]  → убиваем процесс → закрываем fd
 */
class Tun2SocksHelper(private val vpnService: VpnService) {

    @Volatile private var process: Process? = null
    @Volatile private var tunFd: ParcelFileDescriptor? = null

    companion object {
        private const val TAG = "Tun2Socks"
        private const val TUN_ADDRESS = "10.1.0.1"
        private const val TUN_PREFIX = 30
        private const val TUN_MTU = 1500
    }

    /**
     * Создаёт TUN через VpnService.Builder и запускает libtun2socks.so.
     * Должен вызываться из IO-корутины.
     */
    suspend fun start(socksHost: String, socksPort: Int) = withContext(Dispatchers.IO) {
        stop()

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
        val configFile = File(vpnService.filesDir, "tun2socks.yml")
        configFile.writeText(
            """
tunnel:
  mtu: $TUN_MTU

socks5:
  address: $socksHost:$socksPort
  udp: true

misc:
  task-stack-size: 20480
  connect-timeout: 5000
  read-write-timeout: 60000
  log-level: warning
            """.trimIndent()
        )

        // ── 3. Запуск libtun2socks.so ─────────────────────────────────────
        val binaryPath = vpnService.applicationInfo.nativeLibraryDir + "/libtun2socks.so"
        val binaryFile = File(binaryPath)
        if (!binaryFile.exists()) {
            tunFd?.close()
            tunFd = null
            throw IllegalStateException("libtun2socks.so не найден: $binaryPath")
        }

        val pb = ProcessBuilder(
            binaryPath,
            "-config", configFile.absolutePath,
            "-vconfig", "fd://${tunFd!!.fd}"
        )
        pb.redirectErrorStream(true)
        pb.directory(vpnService.filesDir)

        process = pb.start()
        Log.i(TAG, "tun2socks запущен (pid=${pidOf(process!!)})")

        // Дренаж логов в отдельном потоке
        val proc = process!!
        Thread({
            proc.inputStream.bufferedReader().use { reader ->
                reader.lineSequence().forEach { line -> Log.d(TAG, line) }
            }
        }, "tun2socks-log").apply { isDaemon = true; start() }
    }

    /** Останавливает процесс tun2socks и закрывает TUN-дескриптор. */
    suspend fun stop() = withContext(Dispatchers.IO) {
        val proc = process
        process = null
        if (proc != null) {
            try { proc.destroy() } catch (_: Exception) {}
            try { proc.waitFor(2, TimeUnit.SECONDS) } catch (_: Exception) {}
            if (proc.isAlive) {
                try { proc.destroyForcibly() } catch (_: Exception) {}
                try { proc.waitFor(1, TimeUnit.SECONDS) } catch (_: Exception) {}
            }
            Log.i(TAG, "tun2socks остановлен")
        }
        val fd = tunFd
        tunFd = null
        try { fd?.close() } catch (_: Exception) {}
    }

    fun isRunning(): Boolean = process?.isAlive == true

    private fun pidOf(p: Process): Long = runCatching {
        val f = p.javaClass.getDeclaredField("pid")
        f.isAccessible = true
        f.getLong(p)
    }.getOrElse {
        runCatching { Process::class.java.getMethod("pid").invoke(p) as Long }
            .getOrDefault(-1L)
    }
}
