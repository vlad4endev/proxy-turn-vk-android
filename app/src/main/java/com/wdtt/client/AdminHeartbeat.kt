package com.wdtt.client

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

private const val TAG = "Heartbeat"
private const val PREFS = "skyflow_prefs"
private const val PREF_ADMIN_URL = "admin_server_url"
private const val PREF_DEVICE_ID = "device_id"

/**
 * Возвращает стабильный идентификатор устройства, генерируя его один раз и
 * сохраняя в SharedPreferences. Раньше здесь использовалось несуществующее
 * свойство SettingsStore.deviceId — из-за чего файл не компилировался.
 */
private fun getOrCreateDeviceId(context: Context): String {
    val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    prefs.getString(PREF_DEVICE_ID, null)?.let { if (it.isNotBlank()) return it }
    val id = UUID.randomUUID().toString()
    prefs.edit().putString(PREF_DEVICE_ID, id).apply()
    return id
}

/**
 * Отправляет heartbeat на admin-сервер, если тот сконфигурирован
 * (ключ admin_server_url в skyflow_prefs). Без URL — no-op.
 *
 * suspend, т.к. читает connectionPassword из DataStore (Flow) через .first().
 */
suspend fun sendHeartbeat(context: Context, fcmToken: String? = null) {
    val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    val adminUrl = prefs.getString(PREF_ADMIN_URL, "").orEmpty()
    if (adminUrl.isBlank()) return

    val settingsStore = SettingsStore(context.applicationContext)
    val password = settingsStore.connectionPassword.first()
    val deviceId = getOrCreateDeviceId(context)
    if (password.isBlank() || deviceId.isBlank()) return

    val token = fcmToken ?: prefs.getString("fcm_token_cached", "").orEmpty()
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
