package com.wdtt.client

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Заказ на оплату: id для опроса статуса + URL страницы Platega для Custom Tab. */
data class PaymentOrder(val orderId: String, val paymentUrl: String)

enum class PaymentState { PENDING, SUCCEEDED, CANCELLED, UNKNOWN }

/**
 * Статус платежа. При [PaymentState.SUCCEEDED] backend уже провизионировал клиента
 * в 3X-UI и возвращает [subUrl] (HTTP sub-URL) + [expireAt] (Unix-секунды, 0 = бессрочно).
 */
data class PaymentStatus(val state: PaymentState, val subUrl: String?, val expireAt: Long)

/** Текущая подписка устройства (для restore/refresh). */
data class RemoteSubscription(val active: Boolean, val subUrl: String, val expireAt: Long)

/**
 * Device-auth клиент к backend-боту (Python) для покупки подписки и получения
 * 3X-UI sub-URL. Секрет Platega в приложение не попадает — оплату проводит backend.
 *
 * Контракт backend (реализовать на стороне бота; заголовок во всех запросах
 * `X-Device-Id: <uuid>`):
 *
 *   POST {BASE}/api/app/pay
 *        body: {"plan":"BASIC","months":1}
 *        resp: {"order_id":"...","payment_url":"https://app.platega.io/..."}
 *
 *   GET  {BASE}/api/app/payment/{orderId}/status
 *        resp: {"status":"pending|succeeded|cancelled","sub_url":"...","expire_at":1730000000}
 *
 *   GET  {BASE}/api/app/subscription
 *        resp: {"active":true,"sub_url":"...","expire_at":1730000000}
 *
 * Все методы бросают [IOException] при сетевой/HTTP-ошибке.
 */
class PaymentApi(
    private val deviceId: String,
    private val baseUrl: String = BillingConfig.BACKEND_BASE_URL,
) {
    val isConfigured: Boolean get() = baseUrl.isNotBlank() && deviceId.isNotBlank()

    suspend fun createPayment(plan: String = "BASIC", months: Int = 1): PaymentOrder =
        withContext(Dispatchers.IO) {
            val body = JSONObject().put("plan", plan).put("months", months).toString()
            val json = request("POST", "/api/app/pay", body, "createPayment")
            val orderId = json.optString("order_id").ifBlank {
                throw IOException("createPayment: order_id отсутствует")
            }
            val payUrl = json.optString("payment_url").ifBlank {
                throw IOException("createPayment: payment_url отсутствует")
            }
            PaymentOrder(orderId, payUrl)
        }

    suspend fun paymentStatus(orderId: String): PaymentStatus =
        withContext(Dispatchers.IO) {
            val json = request("GET", "/api/app/payment/$orderId/status", null, "paymentStatus")
            PaymentStatus(
                state = parseState(json.optString("status")),
                subUrl = json.optString("sub_url").ifBlank { null },
                expireAt = json.optLong("expire_at", 0L),
            )
        }

    suspend fun currentSubscription(): RemoteSubscription? =
        withContext(Dispatchers.IO) {
            val json = request("GET", "/api/app/subscription", null, "currentSubscription")
            val subUrl = json.optString("sub_url")
            if (subUrl.isBlank()) return@withContext null
            RemoteSubscription(
                active = json.optBoolean("active", false),
                subUrl = subUrl,
                expireAt = json.optLong("expire_at", 0L),
            )
        }

    /**
     * Вход в существующую подписку по идентификатору ([type] = "subid" | "telegram_id").
     * Backend ищет клиента в 3X-UI и, если найден, возвращает `sub_url` + `expire_at`.
     * Возвращает null, если подписка не найдена.
     */
    suspend fun linkExisting(type: String, value: String): RemoteSubscription? =
        withContext(Dispatchers.IO) {
            val body = JSONObject().put("type", type).put("value", value).toString()
            val json = request("POST", "/api/app/link", body, "linkExisting")
            if (!json.optBoolean("found", false)) return@withContext null
            val subUrl = json.optString("sub_url")
            if (subUrl.isBlank()) return@withContext null
            RemoteSubscription(
                active = true,
                subUrl = subUrl,
                expireAt = json.optLong("expire_at", 0L),
            )
        }

    private fun parseState(raw: String): PaymentState = when (raw.trim().lowercase()) {
        "succeeded", "success", "paid", "confirmed", "completed" -> PaymentState.SUCCEEDED
        "cancelled", "canceled", "failed", "expired", "rejected" -> PaymentState.CANCELLED
        "pending", "processing", "new", "created" -> PaymentState.PENDING
        else -> PaymentState.UNKNOWN
    }

    private fun request(method: String, path: String, jsonBody: String?, label: String): JSONObject {
        if (baseUrl.isBlank()) throw IOException("$label: BACKEND_BASE_URL не задан")
        val url = URL(baseUrl.trimEnd('/') + path)
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15_000
            readTimeout = 15_000
            setRequestProperty("X-Device-Id", deviceId)
            setRequestProperty("Accept", "application/json")
            if (jsonBody != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
        }
        try {
            if (jsonBody != null) {
                conn.outputStream.use { it.write(jsonBody.toByteArray(Charsets.UTF_8)) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw IOException("$label: HTTP $code ${text.take(200)}")
            if (text.isBlank()) throw IOException("$label: пустой ответ")
            return JSONObject(text)
        } finally {
            conn.disconnect()
        }
    }
}
