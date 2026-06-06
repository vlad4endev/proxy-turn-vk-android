package com.wdtt.client.xray

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

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

    companion object {
        private const val TAG = "XrayHelper"
        private const val SOCKS_PORT  = 10808
        private const val HTTP_PORT   = 10809
        private const val API_PORT    = 10085
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

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

        val config = JSONObject().apply {
            put("log", JSONObject().apply {
                put("loglevel", "warning")
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
            put("routing", JSONObject().apply {
                put("domainStrategy", "IPIfNonMatch")
                put("rules", JSONArray().apply {
                    // Route loopback/RFC-1918 ranges directly without geoip.dat
                    put(JSONObject().apply {
                        put("type", "field")
                        put("outboundTag", "direct")
                        put("ip", JSONArray().apply {
                            put("127.0.0.0/8")
                            put("10.0.0.0/8")
                            put("172.16.0.0/12")
                            put("192.168.0.0/16")
                        })
                    })
                })
            })
        }

        return config.toString(2)
    }

    /**
     * Writes [configJson] to disk and starts the Xray process.
     * No-ops (with a warning) if libxray.so is not present in the nativeLibraryDir.
     */
    suspend fun start(configJson: String) = withContext(Dispatchers.IO) {
        stop()

        if (!xrayBin.exists()) {
            Log.w(TAG, "Xray binary not found at ${xrayBin.absolutePath} — skipping start")
            return@withContext
        }

        try {
            xrayDir.mkdirs()
            configFile.writeText(configJson, Charsets.UTF_8)

            if (!xrayBin.canExecute()) {
                xrayBin.setExecutable(true, true)
            }

            val pb = ProcessBuilder(xrayBin.absolutePath, "run", "-c", configFile.absolutePath)
                .redirectErrorStream(true)
                .directory(xrayDir)

            process = pb.start()
            Log.i(TAG, "Xray started (pid ${process?.let { pidOf(it) } ?: "?"})")

            // Drain stdout/stderr asynchronously to avoid blocking the pipe buffer
            val proc = process!!
            Thread({
                proc.inputStream.bufferedReader().use { reader ->
                    reader.lineSequence().forEach { line ->
                        Log.d(TAG, line)
                    }
                }
            }, "xray-log").apply { isDaemon = true; start() }

        } catch (e: Exception) {
            Log.e(TAG, "Failed to start Xray: ${e.message}", e)
            throw e
        }
    }

    /**
     * Stops the Xray process if running.
     */
    suspend fun stop() = withContext(Dispatchers.IO) {
        process?.let { proc ->
            try {
                proc.destroy()
                proc.waitFor()
                Log.i(TAG, "Xray stopped")
            } catch (e: Exception) {
                Log.w(TAG, "Error stopping Xray: ${e.message}")
            } finally {
                process = null
            }
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
