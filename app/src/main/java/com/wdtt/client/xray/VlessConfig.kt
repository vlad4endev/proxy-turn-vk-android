package com.wdtt.client.xray

import android.net.Uri

/**
 * Parsed representation of a VLESS URI.
 *
 * Format:
 *   vless://<uuid>@<host>:<port>?type=<net>&path=<path>&host=<wsHost>&security=<tls>#<remark>
 */
data class VlessConfig(
    val uuid: String,
    val serverHost: String,
    val serverPort: Int,
    val network: String,      // ws, tcp, grpc …
    val wsPath: String,       // WebSocket path, e.g. /skyflow
    val wsHost: String,       // WebSocket Host header
    val encryption: String,   // none / auto / …
    val security: String,     // none / tls / reality
    val remark: String        // fragment after #
)

/**
 * Parses a VLESS URI into [VlessConfig].
 *
 * @throws IllegalArgumentException if the URI is malformed or the scheme is not "vless".
 */
fun parseVlessUri(uri: String): VlessConfig {
    val parsed = Uri.parse(uri.trim())
    require(parsed.scheme?.lowercase() == "vless") {
        "Expected vless:// scheme, got: ${parsed.scheme}"
    }

    val uuid = parsed.userInfo
        ?: throw IllegalArgumentException("VLESS URI missing UUID (userInfo)")
    val host = parsed.host
        ?: throw IllegalArgumentException("VLESS URI missing host")
    val port = parsed.port.takeIf { it > 0 }
        ?: throw IllegalArgumentException("VLESS URI missing or invalid port")

    val network    = parsed.getQueryParameter("type")       ?: "tcp"
    val path       = parsed.getQueryParameter("path")       ?: "/"
    val wsHost     = parsed.getQueryParameter("host")       ?: host
    val encryption = parsed.getQueryParameter("encryption") ?: "none"
    val security   = parsed.getQueryParameter("security")   ?: "none"
    val remark     = parsed.fragment                         ?: ""

    return VlessConfig(
        uuid       = uuid,
        serverHost = host,
        serverPort = port,
        network    = network,
        wsPath     = path,
        wsHost     = wsHost,
        encryption = encryption,
        security   = security,
        remark     = remark
    )
}
