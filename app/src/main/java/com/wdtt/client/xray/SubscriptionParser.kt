package com.wdtt.client.xray

import android.net.Uri
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL

/**
 * Результат загрузки подписки.
 * [expireAt] = Unix-timestamp (секунды) срока действия, или 0 если не указан.
 */
data class SubscriptionResult(
    val servers: List<VlessServer>,
    val expireAt: Long = 0L,
)

object SubscriptionParser {

    /**
     * Загружает подписку и возвращает серверы + дату истечения.
     * Дата берётся из заголовка ответа `subscription-userinfo: expire=TIMESTAMP`.
     */
    suspend fun fetchSubscription(url: String): SubscriptionResult {
        return withContext(Dispatchers.IO) {
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout    = 10_000
                setRequestProperty("User-Agent", "v2rayNG/1.9.0")
                requestMethod = "GET"
            }
            try {
                val code = connection.responseCode
                if (code !in 200..299) throw IOException("HTTP $code")

                // Читаем заголовок subscription-userinfo для expire
                val expireAt = parseExpireFromHeader(
                    connection.getHeaderField("subscription-userinfo")
                        ?: connection.getHeaderField("Subscription-Userinfo")
                )

                val body = connection.inputStream.bufferedReader().use { it.readText() }
                if (body.isBlank()) throw IOException("Пустой ответ")

                val servers = parseSubscriptionBody(body)
                SubscriptionResult(servers = servers, expireAt = expireAt)
            } finally {
                connection.disconnect()
            }
        }
    }

    /**
     * Парсит `upload=X; download=X; total=X; expire=1735689600` → Unix timestamp в секундах.
     */
    fun parseExpireFromHeader(header: String?): Long {
        if (header.isNullOrBlank()) return 0L
        return try {
            val expireStr = header
                .split(";")
                .map { it.trim() }
                .firstOrNull { it.startsWith("expire=") }
                ?.removePrefix("expire=")
                ?.trim()
                ?: return 0L
            expireStr.toLongOrNull() ?: 0L
        } catch (_: Exception) {
            0L
        }
    }

    fun parseSubscriptionBody(body: String): List<VlessServer> {
        val decoded = try {
            String(Base64.decode(body.trim(), Base64.DEFAULT), Charsets.UTF_8)
        } catch (_: Exception) {
            body
        }

        return decoded.lines()
            .map { it.trim() }
            .filter { it.startsWith("vless://") }
            .mapNotNull { parseVlessUri(it) }
    }

    fun parseVlessUri(uri: String): VlessServer? {
        return try {
            val parsed = Uri.parse(uri.trim())
            if (parsed.scheme?.lowercase() != "vless") return null

            val uuid = parsed.userInfo ?: return null
            val host = parsed.host ?: return null
            val port = parsed.port.takeIf { it > 0 } ?: 443

            val name = parsed.fragment?.let {
                java.net.URLDecoder.decode(it, "UTF-8")
            } ?: "Server"

            val type     = parsed.getQueryParameter("type")     ?: "tcp"
            val security = parsed.getQueryParameter("security") ?: "none"
            val path     = parsed.getQueryParameter("path")     ?: "/"
            val wsHost   = parsed.getQueryParameter("host")     ?: host
            val sni      = parsed.getQueryParameter("sni")      ?: ""
            val pbk      = parsed.getQueryParameter("pbk")      ?: ""
            val sid      = parsed.getQueryParameter("sid")      ?: ""
            val flow     = parsed.getQueryParameter("flow")     ?: ""
            val fp       = parsed.getQueryParameter("fp")       ?: "chrome"
            val alpn     = parsed.getQueryParameter("alpn")     ?: ""

            VlessServer(
                name     = name,
                uri      = uri.trim(),
                host     = host,
                port     = port,
                uuid     = uuid,
                type     = type,
                security = security,
                sni      = sni,
                pbk      = pbk,
                sid      = sid,
                flow     = flow,
                fp       = fp,
                path     = path,
                wsHost   = wsHost,
                alpn     = alpn
            )
        } catch (_: Exception) {
            null
        }
    }

    suspend fun pingServer(server: VlessServer): Long {
        return withContext(Dispatchers.IO) {
            try {
                val start = System.currentTimeMillis()
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(server.host, server.port), 5000)
                }
                System.currentTimeMillis() - start
            } catch (_: Exception) {
                -1L
            }
        }
    }
}
