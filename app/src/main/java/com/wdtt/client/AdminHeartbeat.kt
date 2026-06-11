package com.wdtt.client

import android.content.Context
import android.os.Build
import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

private const val TAG = "Heartbeat"
private const val PREF_ADMIN_URL = "admin_server_url"

fun sendHeartbeat(context: Context, fcmToken: String? = null) {
    val prefs = context.getSharedPreferences("skyflow_prefs", Context.MODE_PRIVATE)
    val adminUrl = prefs.getString(PREF_ADMIN_URL, "") ?: ""
    if (adminUrl.isBlank()) return

    val settingsStore = SettingsStore.getInstance(context)
    val password = settingsStore.connectionPassword
    val deviceId = settingsStore.deviceId
    if (password.isBlank() || deviceId.isBlank()) return

    val token = fcmToken ?: prefs.getString("fcm_token_cached", "") ?: ""
    if (fcmToken != null) prefs.edit().putString("fcm_token_cached", fcmToken).apply()

    try {
        val payload = JSONObject().apply {
            put("device_id", deviceId)
            put("password", password)
            put("fcm_token", token)
            put("app_version", BuildConfig.VERSION_NAME)
        }
        val url = URL("$adminUrl/admin/api/heartbeat".replace("//admin", "/admin"))
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/json")
        conn.doOutput = true
        conn.connectTimeout = 8_000
        conn.readTimeout = 8_000
        conn.outputStream.use { it.write(payload.toString().toByteArray()) }
        val code = conn.responseCode
        Log.d(TAG, "Heartbeat → $code")
        conn.disconnect()
    } catch (e: Exception) {
        Log.w(TAG, "Heartbeat failed: ${e.message}")
    }
}
