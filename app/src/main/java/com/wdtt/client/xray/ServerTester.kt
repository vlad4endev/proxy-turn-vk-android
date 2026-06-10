package com.wdtt.client.xray

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.URL

private const val TAG             = "ServerTester"
private const val TEST_BASE_PORT  = 11001    // test instances get 11001..11003
private const val MAX_PARALLEL    = 3        // concurrent Xray processes
private const val STARTUP_DELAY   = 1600L   // ms to wait for Xray event-loop
private const val CONNECT_TIMEOUT = 5000     // ms
private const val READ_TIMEOUT    = 5000     // ms
// Lightweight YouTube endpoint — 204 No Content when reachable, no HTML body
private const val TEST_URL        = "https://www.youtube.com/generate_204"

enum class CheckStatus { WAITING, TESTING, OK, FAIL }

data class ServerCheckResult(
    val index:     Int,
    val status:    CheckStatus = CheckStatus.WAITING,
    val latencyMs: Long        = -1L,
)

/**
 * Tests VLESS servers by spinning up a real Xray process per server on an
 * ephemeral SOCKS5 port and probing [TEST_URL] through it.
 *
 * Up to [MAX_PARALLEL] Xray processes run concurrently.  Each uses its own
 * config file and port so they never collide.  The binary is the same
 * `libxray.so` that the production tunnel uses.
 *
 * Usage:
 * ```
 * val tester = ServerTester(context)
 * tester.testAll(servers,
 *     onProgress  = { results -> /* update UI */ },
 *     onBestFound = { idx    -> /* auto-select immediately */ },
 * )
 * ```
 */
class ServerTester(private val context: Context) {

    private val xrayBin = File(context.applicationInfo.nativeLibraryDir, "libxray.so")
    private val xrayDir = File(context.filesDir, "xray")          // working dir for Xray
    private val testDir = File(context.filesDir, "xray_tests")    // temp config files

    // ─── Public API ──────────────────────────────────────────────────────────

    /**
     * Tests every server in [servers], calling [onProgress] after each result
     * and [onBestFound] once — with the index of the best (lowest-latency) working
     * server, or -1 if none passed.
     *
     * Must be called from a coroutine; cancellation propagates correctly.
     */
    suspend fun testAll(
        servers:     List<VlessServer>,
        onProgress:  (List<ServerCheckResult>) -> Unit,
        onBestFound: (Int) -> Unit,
    ) {
        if (servers.isEmpty()) { onBestFound(-1); return }

        testDir.mkdirs()

        // Shared mutable results array — written only from the main dispatcher
        val results = MutableList(servers.size) { i -> ServerCheckResult(i) }
        onProgress(results.toList())

        val semaphore  = Semaphore(MAX_PARALLEL)
        var bestIndex  = -1
        var bestMs     = Long.MAX_VALUE

        coroutineScope {
            servers.mapIndexed { idx, server ->
                launch {
                    semaphore.withPermit {
                        // Each slot 0..MAX_PARALLEL-1 gets a dedicated port
                        val slotPort = TEST_BASE_PORT + (idx % MAX_PARALLEL)

                        withContext(Dispatchers.Main) {
                            results[idx] = results[idx].copy(status = CheckStatus.TESTING)
                            onProgress(results.toList())
                        }

                        val (ok, latency) = probe(server, slotPort)

                        withContext(Dispatchers.Main) {
                            results[idx] = ServerCheckResult(
                                index     = idx,
                                status    = if (ok) CheckStatus.OK else CheckStatus.FAIL,
                                latencyMs = latency,
                            )
                            // Track best candidate (update in real-time)
                            if (ok && latency < bestMs) {
                                bestMs    = latency
                                bestIndex = idx
                                onBestFound(idx)
                            }
                            onProgress(results.toList())
                        }
                    }
                }
            }
        }

        // Final winner after all tests complete
        onBestFound(bestIndex)
    }

    // ─── Core probe ──────────────────────────────────────────────────────────

    private suspend fun probe(server: VlessServer, port: Int): Pair<Boolean, Long> =
        withContext(Dispatchers.IO) {
            val configFile = File(testDir, "test_$port.json")
            var proc: Process? = null

            try {
                if (!xrayBin.exists()) {
                    Log.w(TAG, "libxray.so not found — skipping ${server.name}")
                    return@withContext false to -1L
                }
                if (!xrayBin.canExecute()) xrayBin.setExecutable(true, true)

                configFile.writeText(buildConfig(server, port))

                val pb = ProcessBuilder(
                    xrayBin.absolutePath, "run", "-c", configFile.absolutePath
                ).redirectErrorStream(true).directory(xrayDir)

                proc = pb.start()

                // Drain stdout so the pipe never blocks
                Thread { proc.inputStream.bufferedReader().use { it.readText() } }
                    .apply { isDaemon = true; start() }

                delay(STARTUP_DELAY)

                if (!proc.isAlive) {
                    Log.d(TAG, "${server.name} — Xray exited early")
                    return@withContext false to -1L
                }

                // Probe YouTube via the ephemeral SOCKS5 proxy
                val t0  = System.currentTimeMillis()
                val ok  = try {
                    val proxy = java.net.Proxy(
                        java.net.Proxy.Type.SOCKS,
                        InetSocketAddress("127.0.0.1", port)
                    )
                    val conn = URL(TEST_URL).openConnection(proxy) as HttpURLConnection
                    conn.connectTimeout         = CONNECT_TIMEOUT
                    conn.readTimeout            = READ_TIMEOUT
                    conn.instanceFollowRedirects = false
                    conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Android) Chrome/120")
                    val code = conn.responseCode
                    conn.disconnect()
                    code in 200..299
                } catch (e: Exception) {
                    Log.d(TAG, "${server.name} — probe failed: ${e.message}")
                    false
                }
                val ms = System.currentTimeMillis() - t0

                Log.i(TAG, "${server.name} → ${if (ok) "OK ${ms}ms" else "FAIL"} (port $port)")
                ok to (if (ok) ms else -1L)

            } catch (e: Exception) {
                Log.w(TAG, "${server.name} — unexpected error: ${e.message}")
                false to -1L
            } finally {
                try { proc?.destroyForcibly(); proc?.waitFor(1, java.util.concurrent.TimeUnit.SECONDS) } catch (_: Exception) {}
                try { configFile.delete() } catch (_: Exception) {}
            }
        }

    // ─── Minimal Xray config (no geo rules, test-only) ───────────────────────

    private fun buildConfig(server: VlessServer, socksPort: Int): String {
        val user = JSONObject().apply {
            put("id", server.uuid)
            put("encryption", "none")
            if (server.flow.isNotBlank()) put("flow", server.flow)
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
            put("streamSettings", buildStream(server))
            put("tag", "proxy")
        }
        return JSONObject().apply {
            put("log",      JSONObject().apply { put("loglevel", "warning") })
            put("inbounds", JSONArray().apply {
                put(JSONObject().apply {
                    put("tag",      "socks-test")
                    put("port",     socksPort)
                    put("listen",   "127.0.0.1")
                    put("protocol", "socks")
                    put("settings", JSONObject().apply {
                        put("auth", "noauth")
                        put("udp", false)
                    })
                })
            })
            put("outbounds", JSONArray().apply {
                put(outbound)
                put(JSONObject().apply { put("protocol", "freedom"); put("tag", "direct") })
            })
            put("routing", JSONObject().apply {
                put("domainStrategy", "IPIfNonMatch")
                put("rules", JSONArray().apply {
                    put(JSONObject().apply {
                        put("type", "field"); put("outboundTag", "direct")
                        put("ip", JSONArray().apply {
                            put("127.0.0.0/8"); put("10.0.0.0/8")
                            put("172.16.0.0/12"); put("192.168.0.0/16")
                        })
                    })
                })
            })
        }.toString(2)
    }

    private fun buildStream(s: VlessServer): JSONObject {
        val net = s.type.lowercase()
        val sec = s.security.lowercase()
        return JSONObject().apply {
            put("network",  net)
            put("security", sec)
            when (net) {
                "ws"   -> put("wsSettings",   JSONObject().apply {
                    put("path", s.path)
                    put("headers", JSONObject().apply { put("Host", s.wsHost) })
                })
                "grpc" -> put("grpcSettings", JSONObject().apply {
                    put("serviceName", s.path.trimStart('/'))
                })
            }
            when (sec) {
                "tls"     -> put("tlsSettings",     JSONObject().apply {
                    put("serverName",  s.sni.ifBlank { s.wsHost.ifBlank { s.host } })
                    put("fingerprint", s.fp)
                    put("allowInsecure", false)
                    if (s.alpn.isNotBlank())
                        put("alpn", JSONArray().apply { s.alpn.split(",").forEach { put(it.trim()) } })
                })
                "reality" -> put("realitySettings", JSONObject().apply {
                    put("show",        false)
                    put("fingerprint", s.fp)
                    put("serverName",  s.sni.ifBlank { s.host })
                    put("publicKey",   s.pbk)
                    put("shortId",     s.sid)
                })
            }
        }
    }
}
