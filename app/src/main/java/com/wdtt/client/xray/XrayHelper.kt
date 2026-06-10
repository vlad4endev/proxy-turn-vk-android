package com.wdtt.client.xray

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Режим маршрутизации Xray.
 *
 * - GLOBAL: весь трафик через прокси
 * - BYPASS_RU: российские IP/домены — напрямую, остальное — через прокси
 * - BYPASS_LOCAL: только LAN/loopback напрямую (расширенный режим по умолчанию)
 *
 * BYPASS_RU требует geoip.dat и geosite.dat в папке Xray (assets → filesDir/xray/).
 */
enum class XrayRoutingMode(val key: String, val label: String) {
    GLOBAL("global", "Глобальный"),
    BYPASS_RU("bypass_ru", "Без России"),
    BYPASS_LOCAL("bypass_local", "Только локальные");

    companion object {
        fun fromKey(key: String): XrayRoutingMode =
            entries.firstOrNull { it.key == key } ?: GLOBAL
    }
}

/**
 * Manages the lifecycle of the Xray-core process and config generation.
 *
 * Xray binary is expected at [Context.getFilesDir]/xray/xray  (arm64 or x86_64 slice,
 * extracted at install time or copied from assets on first run).
 *
 * Usage:
 *   val helper = XrayHelper(context)
 *   val config = helper.generateConfig(parseVlessUri(ServerConfig.VLESS_URI))
 *   helper.start(config)
 *   …
 *   helper.stop()
 */
class XrayHelper(context: Context) {

    private val appContext  = context.applicationContext
    private val xrayDir    = File(appContext.filesDir, "xray")
    private val configFile = File(xrayDir, "config.json")
    // Execute directly from nativeLibraryDir — SELinux allows exec there;
    // app_data_file (filesDir) blocks execute_no_trans on Android 10+.
    private val xrayBin: File
        get() = File(appContext.applicationInfo.nativeLibraryDir, "libxray.so")

    @Volatile private var process: Process? = null

    /** Last N lines of Xray stdout/stderr — used for error reporting when it crashes. */
    private val lastOutputBuf = ArrayDeque<String>()
    private val lastOutputLock = Any()

    val lastOutput: List<String>
        get() = synchronized(lastOutputLock) { lastOutputBuf.toList() }

    companion object {
        private const val TAG = "XrayHelper"
        private const val SOCKS_PORT  = 10808
        private const val HTTP_PORT   = 10809
        private const val API_PORT    = 10085
        private const val MAX_OUTPUT_LINES = 40
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Generates an Xray JSON config for the given [VlessServer].
     * Supports tcp, ws, grpc with none/tls/reality security.
     * [routingMode] controls which traffic bypasses the proxy.
     */
    fun generateConfigFromServer(
        server: VlessServer,
        routingMode: XrayRoutingMode = XrayRoutingMode.GLOBAL
    ): String {
        val streamSettings = buildStreamSettingsFromServer(server)

        val user = JSONObject().apply {
            put("id", server.uuid)
            put("encryption", "none")
            if (server.flow.isNotBlank()) {
                put("flow", server.flow)
            }
        }

        val outbound = JSONObject().apply {
            put("protocol", "vless")
            put("settings", JSONObject().apply {
                put("vnext", JSONArray().apply {
                    put(JSONObject().apply {
                        put("address", server.host)
                        put("port", server.port)
                        put("users", JSONArray().apply { put(user) })
                    })
                })
            })
            put("streamSettings", streamSettings)
            put("tag", "proxy")
        }

        return buildFullConfig(outbound, routingMode)
    }

    /**
     * Generates an Xray JSON config for the given [VlessConfig].
     * The local SOCKS5 proxy will listen on 127.0.0.1:[SOCKS_PORT].
     */
    fun generateConfig(cfg: VlessConfig): String {
        val streamSettings = buildStreamSettings(cfg)

        val outbound = JSONObject().apply {
            put("protocol", "vless")
            put("settings", JSONObject().apply {
                put("vnext", JSONArray().apply {
                    put(JSONObject().apply {
                        put("address", cfg.serverHost)
                        put("port", cfg.serverPort)
                        put("users", JSONArray().apply {
                            put(JSONObject().apply {
                                put("id", cfg.uuid)
                                put("encryption", cfg.encryption)
                                put("flow", "")
                            })
                        })
                    })
                })
            })
            put("streamSettings", streamSettings)
            put("tag", "proxy")
        }

        return buildFullConfig(outbound)
    }

    /**
     * Writes [configJson] to disk and starts the Xray process.
     * Also copies geoip.dat / geosite.dat from assets if not yet present.
     * No-ops (with a warning) if libxray.so is not present in the nativeLibraryDir.
     *
     * @param onLogLine Optional callback invoked for every line of Xray stdout/stderr.
     *                  Called from a background thread — must be thread-safe.
     */
    suspend fun start(configJson: String, onLogLine: ((String) -> Unit)? = null) = withContext(Dispatchers.IO) {
        stop()

        // Clear output buffer from previous run
        synchronized(lastOutputLock) { lastOutputBuf.clear() }

        if (!xrayBin.exists()) {
            Log.w(TAG, "Xray binary not found at ${xrayBin.absolutePath} — skipping start")
            throw IllegalStateException(
                "libxray.so not found at ${xrayBin.absolutePath}. " +
                "This architecture may not be supported. " +
                "Run: scripts/build-native-speed.sh"
            )
        }

        try {
            xrayDir.mkdirs()
            copyGeoFilesIfNeeded(configJson)
            configFile.writeText(configJson, Charsets.UTF_8)

            if (!xrayBin.canExecute()) {
                xrayBin.setExecutable(true, true)
            }

            val pb = ProcessBuilder(xrayBin.absolutePath, "run", "-c", configFile.absolutePath)
                .redirectErrorStream(true)
                .directory(xrayDir)

            process = pb.start()
            Log.i(TAG, "Xray started (pid ${process?.let { pidOf(it) } ?: "?"})")

            // Drain stdout/stderr asynchronously to avoid blocking the pipe buffer.
            // Lines are stored in lastOutputBuf (capped at MAX_OUTPUT_LINES) and
            // forwarded to the optional onLogLine callback for UI display.
            val proc = process!!
            Thread({
                proc.inputStream.bufferedReader().use { reader ->
                    reader.lineSequence().forEach { line ->
                        Log.d(TAG, line)
                        // Store in rolling buffer
                        synchronized(lastOutputLock) {
                            if (lastOutputBuf.size >= MAX_OUTPUT_LINES) {
                                lastOutputBuf.removeFirst()
                            }
                            lastOutputBuf.addLast(line)
                        }
                        // Forward to caller (e.g., TunnelManager for UI logs)
                        try { onLogLine?.invoke(line) } catch (_: Exception) {}
                    }
                }
            }, "xray-log").apply { isDaemon = true; start() }

        } catch (e: Exception) {
            Log.e(TAG, "Failed to start Xray: ${e.message}", e)
            process = null
            throw e
        }
    }

    /**
     * Stops the Xray process if running.
     * Uses a 3-second timeout on waitFor() to avoid blocking indefinitely if Xray hangs.
     */
    suspend fun stop() = withContext(Dispatchers.IO) {
        val proc = process ?: return@withContext
        process = null
        try {
            proc.destroy()
            val exited = proc.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)
            if (!exited) {
                proc.destroyForcibly()
                proc.waitFor(1, java.util.concurrent.TimeUnit.SECONDS)
            }
            Log.i(TAG, "Xray stopped")
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping Xray: ${e.message}")
        }
    }

    /** Returns true if the Xray process is currently alive. */
    fun isRunning(): Boolean = process?.isAlive == true

    /** SOCKS5 proxy port exposed by Xray (for use in ProxyInfo / VPN routing). */
    fun socksPort(): Int = SOCKS_PORT

    /** HTTP proxy port exposed by Xray. */
    fun httpPort(): Int = HTTP_PORT

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private fun buildFullConfig(
        outbound: JSONObject,
        routingMode: XrayRoutingMode = XrayRoutingMode.GLOBAL
    ): String {
        val hasGeoData = File(xrayDir, "geoip.dat").exists() && File(xrayDir, "geosite.dat").exists()
        val config = JSONObject().apply {
            put("log", JSONObject().apply {
                // Use "info" so startup sequence (inbound listening, config parsed, etc.)
                // is visible in the app log panel. Switch to "warning" once stable.
                put("loglevel", "info")
            })
            put("inbounds", JSONArray().apply {
                put(buildSocksInbound())
                put(buildHttpInbound())
            })
            put("outbounds", JSONArray().apply {
                put(outbound)
                put(JSONObject().apply {
                    put("protocol", "freedom")
                    put("tag", "direct")
                })
                put(JSONObject().apply {
                    put("protocol", "blackhole")
                    put("tag", "block")
                })
            })
            put("routing", buildRoutingRules(routingMode, hasGeoData))
        }
        return config.toString(2)
    }

    /**
     * Builds Xray routing rules for the given mode.
     *
     * GLOBAL:      only LAN/loopback direct (same as before)
     * BYPASS_RU:   Russian IP ranges + geosite:category-ru + geoip:ru → direct
     * BYPASS_LOCAL: same as GLOBAL (only private IPs direct)
     *
     * When geoip.dat / geosite.dat are absent, BYPASS_RU falls back to GLOBAL
     * with a warning in the log.
     */
    private fun buildRoutingRules(mode: XrayRoutingMode, hasGeoData: Boolean): JSONObject {
        val rules = JSONArray()

        // ── Always direct: loopback + RFC-1918 LAN ────────────────────────────
        rules.put(JSONObject().apply {
            put("type", "field")
            put("outboundTag", "direct")
            put("ip", JSONArray().apply {
                put("127.0.0.0/8")
                put("10.0.0.0/8")
                put("172.16.0.0/12")
                put("192.168.0.0/16")
            })
        })

        // ── BYPASS_RU: route Russian traffic directly ─────────────────────────
        if (mode == XrayRoutingMode.BYPASS_RU) {
            if (hasGeoData) {
                // Russian geosite domains → direct
                rules.put(JSONObject().apply {
                    put("type", "field")
                    put("outboundTag", "direct")
                    put("domain", JSONArray().apply {
                        put("geosite:category-ru")
                    })
                })
                // Russian IP ranges → direct
                rules.put(JSONObject().apply {
                    put("type", "field")
                    put("outboundTag", "direct")
                    put("ip", JSONArray().apply {
                        put("geoip:ru")
                        put("geoip:private")
                    })
                })
            } else {
                // Geo data missing: fall back to well-known Russian IPs only
                Log.w(TAG, "BYPASS_RU: geoip.dat/geosite.dat not found — falling back to known-RU CIDRs")
                rules.put(JSONObject().apply {
                    put("type", "field")
                    put("outboundTag", "direct")
                    put("ip", JSONArray().apply {
                        // Major Russian operator ranges (approximate fallback)
                        put("5.3.0.0/16"); put("5.8.0.0/16"); put("5.16.0.0/14")
                        put("31.13.0.0/16"); put("31.44.0.0/16"); put("37.9.0.0/16")
                        put("45.8.0.0/16"); put("77.40.0.0/14"); put("77.88.0.0/17")
                        put("84.52.0.0/14"); put("85.142.0.0/16"); put("91.108.0.0/16")
                        put("95.167.0.0/16"); put("149.154.0.0/16")
                        put("178.208.0.0/16"); put("185.67.0.0/16")
                        put("217.69.0.0/16")
                    })
                })
            }
        }

        return JSONObject().apply {
            put("domainStrategy", "IPIfNonMatch")
            put("rules", rules)
        }
    }

    /**
     * Copies geoip.dat and geosite.dat from app assets into xrayDir, but ONLY when the
     * generated config actually references them (i.e. BYPASS_RU mode with geo rules active).
     *
     * Skipping the copy for GLOBAL / BYPASS_LOCAL:
     *  - Avoids a 28 MB I/O on every startup for users who don't need it.
     *  - Prevents Xray from unexpectedly loading large geo databases on startup
     *    (some versions pre-load any .dat files found in the working directory).
     *
     * No-op if the files are already present and non-empty (avoids redundant I/O).
     * Silently skips files that are missing from assets (e.g. dev builds without CI step).
     */
    private fun copyGeoFilesIfNeeded(configJson: String) {
        // Only copy if the config actually uses geoip:/geosite: routing rules
        val needsGeoData = configJson.contains("geoip:") || configJson.contains("geosite:")
        if (!needsGeoData) {
            Log.d(TAG, "Geo routing not active — skipping geoip.dat/geosite.dat copy")
            return
        }
        for (name in listOf("geoip.dat", "geosite.dat")) {
            val dest = File(xrayDir, name)
            if (dest.exists() && dest.length() > 0) continue
            try {
                appContext.assets.open(name).use { input ->
                    dest.outputStream().use { output -> input.copyTo(output) }
                }
                Log.i(TAG, "Copied $name from assets (${dest.length()} bytes)")
            } catch (e: Exception) {
                Log.w(TAG, "Geo file $name not in assets — BYPASS_RU will use fallback CIDRs: ${e.message}")
            }
        }
    }

    private fun buildStreamSettingsFromServer(server: VlessServer): JSONObject {
        val network = server.type.lowercase()
        val security = server.security.lowercase()

        return JSONObject().apply {
            put("network", network)
            put("security", security)

            when (network) {
                "ws" -> put("wsSettings", JSONObject().apply {
                    put("path", server.path)
                    put("headers", JSONObject().apply {
                        put("Host", server.wsHost)
                    })
                })
                "grpc" -> put("grpcSettings", JSONObject().apply {
                    put("serviceName", server.path.trimStart('/'))
                })
            }

            when (security) {
                "tls" -> put("tlsSettings", JSONObject().apply {
                    put("serverName", server.sni.ifBlank { server.wsHost.ifBlank { server.host } })
                    put("fingerprint", server.fp)
                    put("allowInsecure", false)
                    if (server.alpn.isNotBlank()) {
                        put("alpn", JSONArray().apply {
                            server.alpn.split(",").forEach { put(it.trim()) }
                        })
                    }
                })
                "reality" -> put("realitySettings", JSONObject().apply {
                    put("show", false)
                    put("fingerprint", server.fp)
                    put("serverName", server.sni.ifBlank { server.host })
                    put("publicKey", server.pbk)
                    put("shortId", server.sid)
                })
            }
        }
    }

    private fun buildStreamSettings(cfg: VlessConfig): JSONObject = when (cfg.network.lowercase()) {
        "ws" -> JSONObject().apply {
            put("network", "ws")
            put("security", if (cfg.security == "tls") "tls" else "none")
            put("wsSettings", JSONObject().apply {
                put("path", cfg.wsPath)
                put("headers", JSONObject().apply {
                    put("Host", cfg.wsHost)
                })
            })
            if (cfg.security == "tls") {
                put("tlsSettings", JSONObject().apply {
                    put("serverName", cfg.wsHost)
                    put("allowInsecure", false)
                })
            }
        }
        "grpc" -> JSONObject().apply {
            put("network", "grpc")
            put("security", if (cfg.security == "tls") "tls" else "none")
            put("grpcSettings", JSONObject().apply {
                put("serviceName", cfg.wsPath.trimStart('/'))
            })
        }
        else -> JSONObject().apply {
            put("network", "tcp")
            put("security", "none")
        }
    }

    private fun buildSocksInbound(): JSONObject = JSONObject().apply {
        put("tag", "socks")
        put("port", SOCKS_PORT)
        put("listen", "127.0.0.1")
        put("protocol", "socks")
        put("settings", JSONObject().apply {
            put("auth", "noauth")
            put("udp", true)
        })
        put("sniffing", JSONObject().apply {
            put("enabled", true)
            put("destOverride", JSONArray().apply {
                put("http")
                put("tls")
            })
        })
    }

    private fun buildHttpInbound(): JSONObject = JSONObject().apply {
        put("tag", "http")
        put("port", HTTP_PORT)
        put("listen", "127.0.0.1")
        put("protocol", "http")
        put("settings", JSONObject().apply {
            put("allowTransparent", false)
        })
    }

    private fun pidOf(process: Process): Long = runCatching {
        val field = process.javaClass.getDeclaredField("pid")
        field.isAccessible = true
        field.getLong(process)
    }.getOrElse {
        runCatching { Process::class.java.getMethod("pid").invoke(process) as Long }
            .getOrDefault(-1L)
    }
}
