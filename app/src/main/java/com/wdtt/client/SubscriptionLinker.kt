package com.wdtt.client

import com.wdtt.client.xray.SubscriptionParser
import com.wdtt.client.xray.SubscriptionResult
import java.io.IOException

/** Результат входа в существующую подписку. */
sealed interface LinkResult {
    data class Success(val serverCount: Int, val expireAt: Long) : LinkResult
    data object NotFound : LinkResult
    data class Error(val message: String) : LinkResult
}

/**
 * Вход в уже существующую подписку по идентификатору (subId / Telegram ID).
 * Резолвит идентификатор через backend ([PaymentApi.linkExisting]) → грузит серверы
 * существующим [SubscriptionParser] → сохраняет в [SettingsStore] тем же набором
 * полей, что и ручной ввод ссылки в SubscriptionSetupScreen.
 *
 * Приложение НЕ обращается к 3X-UI напрямую — только через доверенный backend.
 */
object SubscriptionLinker {

    const val TYPE_SUBID = "subid"
    const val TYPE_TELEGRAM = "telegram_id"

    suspend fun linkByIdentifier(store: SettingsStore, type: String, value: String): LinkResult {
        val id = value.trim()
        if (id.isEmpty()) return LinkResult.Error("Введите значение")
        if (!BillingConfig.isBackendConfigured) {
            return LinkResult.Error("Сервер подписок не настроен")
        }
        return try {
            val api = PaymentApi(store.getOrCreateDeviceId())
            val remote = api.linkExisting(type, id) ?: return LinkResult.NotFound
            val parsed = SubscriptionParser.fetchSubscription(remote.subUrl)
            if (parsed.servers.isEmpty()) return LinkResult.Error("Серверы не найдены")
            saveSubscription(store, remote.subUrl, parsed, remote.expireAt)
            val expireAt = if (parsed.expireAt > 0L) parsed.expireAt else remote.expireAt
            LinkResult.Success(parsed.servers.size, expireAt)
        } catch (e: IOException) {
            LinkResult.Error(e.message?.take(120) ?: "Ошибка сети")
        } catch (e: Exception) {
            LinkResult.Error(e.message?.take(120) ?: "Не удалось подключить")
        }
    }

    /** Единый блок сохранения подписки (для ручной ссылки и входа по ID). */
    suspend fun saveSubscription(
        store: SettingsStore,
        url: String,
        result: SubscriptionResult,
        fallbackExpireAt: Long = 0L,
    ) {
        store.saveVlessInputMode("subscription")
        store.saveSubscriptionUrl(url)
        store.saveServers(result.servers)
        store.saveSubExpireAt(if (result.expireAt > 0L) result.expireAt else fallbackExpireAt)
        store.saveSubTitle(result.title)
        store.saveSubUpload(result.upload)
        store.saveSubDownload(result.download)
        store.saveSubTotal(result.total)
        store.saveSubAnnounce(result.announce)
    }
}
