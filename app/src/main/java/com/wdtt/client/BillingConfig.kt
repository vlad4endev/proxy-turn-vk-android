package com.wdtt.client

/**
 * Конфигурация триала и платной подписки (device-auth интеграция с backend-ботом).
 *
 * Заполнить перед сборкой APK:
 *  - [BACKEND_BASE_URL] — публичный URL бота с device-auth эндпоинтами (см. [PaymentApi]).
 *  - [TRIAL_SUB_URL]    — общая триал-подписка 3X-UI (HTTP sub-URL, отдаёт vless-список
 *                         с заголовком Subscription-Userinfo). Парсится существующим
 *                         [com.wdtt.client.xray.SubscriptionParser].
 *
 * Секрет Platega (X-Secret) в приложение НЕ попадает: оплату проводит backend,
 * приложение лишь открывает payment_url в Custom Tab и опрашивает статус.
 */
object BillingConfig {
    /** Длительность бесплатного пробного периода. */
    const val TRIAL_DAYS: Int = 10

    /** Цена полного доступа, руб. (для отображения в UI). */
    const val SUB_PRICE_RUB: Int = 250

    /**
     * Публичный базовый URL backend-бота (Python), например "https://bot.example.com".
     * Device-auth эндпоинты (реализовать на стороне бота):
     *   POST {BASE}/api/app/pay
     *   GET  {BASE}/api/app/payment/{orderId}/status
     *   GET  {BASE}/api/app/subscription
     * Пусто = платёж/синхронизация отключены (доступен только локальный триал).
     */
    const val BACKEND_BASE_URL: String = ""

    /**
     * Общая триал-подписка 3X-UI для режима «Скорость» на время триала
     * (HTTP sub-URL вида {XUI_SUB_BASE_URL}/{subId}). Пусто = в триале доступна
     * только «Маскировка», «Скорость» открывается после покупки.
     */
    const val TRIAL_SUB_URL: String = ""

    val isBackendConfigured: Boolean get() = BACKEND_BASE_URL.isNotBlank()
    val isTrialSpeedAvailable: Boolean get() = TRIAL_SUB_URL.isNotBlank()
}
