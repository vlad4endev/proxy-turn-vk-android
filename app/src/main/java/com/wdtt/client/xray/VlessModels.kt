package com.wdtt.client.xray

import org.json.JSONArray
import org.json.JSONObject

data class VlessServer(
    val name: String,
    val uri: String,
    val host: String,
    val port: Int,
    val uuid: String,
    val type: String,        // tcp, ws, grpc
    val security: String,    // none, tls, reality
    val sni: String,
    val pbk: String,         // reality public key
    val sid: String,         // reality short id
    val flow: String,
    val fp: String,          // fingerprint
    val path: String,        // ws path
    val wsHost: String,      // ws host header
    val alpn: String,
    var latency: Long = -1,  // ping in ms, -1 = not checked
    var isSelected: Boolean = false
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("name", name)
        put("uri", uri)
        put("host", host)
        put("port", port)
        put("uuid", uuid)
        put("type", type)
        put("security", security)
        put("sni", sni)
        put("pbk", pbk)
        put("sid", sid)
        put("flow", flow)
        put("fp", fp)
        put("path", path)
        put("wsHost", wsHost)
        put("alpn", alpn)
        put("latency", latency)
        put("isSelected", isSelected)
    }

    companion object {
        fun fromJson(o: JSONObject): VlessServer = VlessServer(
            name = o.optString("name", "Server"),
            uri = o.optString("uri", ""),
            host = o.optString("host", ""),
            port = o.optInt("port", 443),
            uuid = o.optString("uuid", ""),
            type = o.optString("type", "tcp"),
            security = o.optString("security", "none"),
            sni = o.optString("sni", ""),
            pbk = o.optString("pbk", ""),
            sid = o.optString("sid", ""),
            flow = o.optString("flow", ""),
            fp = o.optString("fp", "chrome"),
            path = o.optString("path", "/"),
            wsHost = o.optString("wsHost", ""),
            alpn = o.optString("alpn", ""),
            latency = o.optLong("latency", -1),
            isSelected = o.optBoolean("isSelected", false)
        )

        fun listToJson(servers: List<VlessServer>): String {
            val arr = JSONArray()
            servers.forEach { arr.put(it.toJson()) }
            return arr.toString()
        }

        fun listFromJson(json: String): List<VlessServer> {
            if (json.isBlank() || json == "[]") return emptyList()
            return try {
                val arr = JSONArray(json)
                buildList {
                    for (i in 0 until arr.length()) {
                        add(fromJson(arr.getJSONObject(i)))
                    }
                }
            } catch (_: Exception) {
                emptyList()
            }
        }
    }
}

data class Subscription(
    val url: String,
    val name: String,
    val servers: List<VlessServer>,
    val updatedAt: Long
)
