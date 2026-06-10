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
 * Полная информация из заголовка Subscription-Userinfo.
 * [total] = 0 означает безлимитный трафик (∞).
 * [expireAt] = 0 означает бессрочную подписку.
 */
data class SubscriptionUserInfo(
    val upload: Long = 0L,
    val download: Long = 0L,
    val total: Long = 0L,     // 0 = unlimited
    val expireAt: Long = 0L,  // 0 = no expiry
)

/**
 * Результат загрузки подписки — серверы плюс полная мета-информация.
 */
data class SubscriptionResult(
    val servers: List<VlessServer>,
    // ── из Subscription-Userinfo ────────────────────────────────────────────
    val expireAt: Long = 0L,
    val upload: Long = 0L,
    val download: Long = 0L,
    val total: Long = 0L,        // 0 = unlimited
    // ── из прочих заголовков ────────────────────────────────────────────────
    val title: String = "",      // Profile-Title (base64 или plain)
    val announce: String = "",   // Announce (base64 или plain)
    val supportUrl: String = "", // Support-Url
    val updateInterval: Int = 0, // Profile-Update-Interval (hours)
)

object SubscriptionParser {

    /**
     * Загружает подписку, парсит все стандартные заголовки (Profile-Title, Announce,
     * Subscription-Userinfo) и возвращает полный SubscriptionResult.
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

                // ── Заголовки ────────────────────────────────────────────────
                val userInfo = parseUserInfo(
                    connection.getHeaderField("subscription-userinfo")
                        ?: connection.getHeaderField("Subscription-Userinfo")
                )
                val title = decodeHeaderValue(
                    connection.getHeaderField("profile-title")
                        ?: connection.getHeaderField("Profile-Title")
                )
                val announce = decodeHeaderValue(
                    connection.getHeaderField("announce")
                        ?: connection.getHeaderField("Announce")
                )
                val supportUrl = connection.getHeaderField("support-url")
                    ?: connection.getHeaderField("Support-Url") ?: ""
                val updateInterval = (connection.getHeaderField("profile-update-interval")
                    ?: connection.getHeaderField("Profile-Update-Interval"))
                    ?.trim()?.toIntOrNull() ?: 0

                // ── Тело ─────────────────────────────────────────────────────
                val body = connection.inputStream.bufferedReader().use { it.readText() }
                if (body.isBlank()) throw IOException("Пустой ответ")

                // Уточнение expire: заголовок → тело (fallback)
                val expireAt = if (userInfo.expireAt > 0L) userInfo.expireAt else {
                    val decoded = try {
                        String(Base64.decode(body.trim(), Base64.DEFAULT), Charsets.UTF_8)
                    } catch (_: Exception) { body }
                    parseExpireFromBody(decoded)
                }

                val servers = parseSubscriptionBody(body)
                SubscriptionResult(
                    servers        = servers,
                    expireAt       = expireAt,
                    upload         = userInfo.upload,
                    download       = userInfo.download,
                    total          = userInfo.total,
                    title          = title,
                    announce       = announce,
                    supportUrl     = supportUrl,
                    updateInterval = updateInterval,
                )
            } finally {
                connection.disconnect()
            }
        }
    }

    /**
     * Декодирует значение заголовка, которое может быть в формате `base64:XXXX`.
     */
    fun decodeHeaderValue(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        return try {
            if (raw.startsWith("base64:", ignoreCase = true)) {
                String(Base64.decode(raw.substring(7), Base64.DEFAULT), Charsets.UTF_8).trim()
            } else {
                raw.trim()
            }
        } catch (_: Exception) {
            raw.trim()
        }
    }

    /**
     * Парсит полный Subscription-Userinfo: `upload=X; download=X; total=X; expire=X`.
     */
    fun parseUserInfo(header: String?): SubscriptionUserInfo {
        if (header.isNullOrBlank()) return SubscriptionUserInfo()
        val map = header.split(";").associate { part ->
            val idx = part.indexOf('=')
            if (idx < 0) "" to 0L
            else part.substring(0, idx).trim() to (part.substring(idx + 1).trim().toLongOrNull() ?: 0L)
        }
        return SubscriptionUserInfo(
            upload    = map["upload"]   ?: 0L,
            download  = map["download"] ?: 0L,
            total     = map["total"]    ?: 0L,
            expireAt  = map["expire"]   ?: 0L,
        )
    }

    /**
     * Парсит `upload=X; download=X; total=X; expire=1735689600` → Unix timestamp в секундах.
     */
    fun parseExpireFromHeader(header: String?): Long = parseUserInfo(header).expireAt

    /**
     * Fallback: scan decoded subscription body for `expire=TIMESTAMP` or `expire:TIMESTAMP`.
     */
    fun parseExpireFromBody(body: String): Long {
        val regex = Regex("""expire[=:]\s*(\d{10})""")
        return regex.find(body)?.groupValues?.getOrNull(1)?.toLongOrNull() ?: 0L
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
