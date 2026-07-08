# Android ↔ backend: контракт device-auth (триал + подписка)

Android-приложение **standalone** (не Telegram Mini App), поэтому оно не может
авторизоваться через Telegram и **не должно** хранить `PLATEGA_SECRET` (его
вытащат из APK). Оплату проводит backend; приложение лишь открывает `payment_url`
в Custom Tab и опрашивает статус.

Реализация на стороне приложения:
- `BillingConfig.kt` — константы (`BACKEND_BASE_URL`, `TRIAL_SUB_URL`, `TRIAL_DAYS=10`, `SUB_PRICE_RUB=250`).
- `PaymentApi.kt` — HTTP-клиент по контракту ниже.
- `AccessManager.kt` — состояние доступа `Trial | Paid | Expired`.
- `SettingsStore` — `trialStartAt`, стабильный анонимный `deviceId`.

## Идентификация устройства

Приложение генерирует случайный `deviceId` (UUID, хранится в DataStore) и шлёт его
в каждом запросе заголовком:

```
X-Device-Id: <uuid>
```

Backend сопоставляет `deviceId` со своим пользователем/подпиской (аналог
`telegram_id` в существующем `bot/`). Рекомендуется завести таблицу
`device_id → user/subscription`. При желании — дополнительный `X-Device-Secret`,
выдаваемый при первой регистрации устройства.

## Эндпоинты (реализовать в `bot/`)

Все ответы — JSON. Базовый URL = `BACKEND_BASE_URL`.

### 1. Создать заказ
```
POST {BASE}/api/app/pay
Headers: X-Device-Id
Body:    {"plan":"BASIC","months":1}
Resp:    {"order_id":"<uuid>","payment_url":"https://app.platega.io/..."}
```
Внутри — существующий `create_paid_order(...)` → `platega.create_payment(...)`.
`return`/`failedUrl` можно указать на deep-link приложения (`skyflow://payment`),
чтобы Custom Tab закрылся автоматически (необязательно — приложение и так опрашивает статус).

### 2. Статус платежа
```
GET {BASE}/api/app/payment/{orderId}/status
Headers: X-Device-Id
Resp:    {"status":"pending|succeeded|cancelled","sub_url":"...","expire_at":1730000000}
```
Внутри — существующий `process_manual_check(...)`. При `succeeded` backend уже
провизионировал клиента в 3X-UI (`provision_vpn_for_subscription`) и возвращает:
- `sub_url` — HTTP sub-URL 3X-UI (`{XUI_SUB_BASE_URL}/{subId}`), НЕ `vless://`;
- `expire_at` — Unix-секунды (0 = бессрочно; для «бессрочной» выставлять далёкое будущее).

Приложение грузит `sub_url` существующим `SubscriptionParser.fetchSubscription()`.

### 3. Текущая подписка (restore / refresh)
```
GET {BASE}/api/app/subscription
Headers: X-Device-Id
Resp:    {"active":true,"sub_url":"...","expire_at":1730000000}
```
Для восстановления доступа после переустановки и периодического обновления
конфигурации. Если подписки нет — `{"active":false,"sub_url":"","expire_at":0}`.

### 4. Вход в существующую подписку (по subId / Telegram ID)
```
POST {BASE}/api/app/link
Headers: X-Device-Id
Body:    {"type":"subid|telegram_id","value":"<subId или tgId>"}
Resp:    {"found":true,"sub_url":"...","expire_at":1730000000,"title":"..."}  | {"found":false}
```
Реализация поверх существующего `XUIClient`:
`build_client_index()` → `find_panel_client(sub_id=<value>)` либо
`find_panel_client(telegram_id=<value>)` → `enrich_client_from_panel` →
`build_sub_url(subId)`. Заодно привязать `device_id → subscription`
(чтобы работал эндпоинт #3). Если клиент не найден — `{"found":false}`.

Идентификаторы: только **subId** (из ссылки подписки) и **Telegram ID**.
Подтверждение (OTP) не требуется — subId работает как секрет-токен; Telegram ID
без кода менее безопасен (риск принят продуктом). Телефон не поддерживается
(в 3X-UI телефонов нет).

Приложение затем грузит `sub_url` тем же `SubscriptionParser.fetchSubscription()`.

## Триал

- **Маскировка** (VK→TURN→WG) — своя инфраструктура, backend не нужен: триал
  чисто локальный (`AccessManager` + `trialStartAt`, 10 дней).
- **Скорость** в триале — общая триал-подписка 3X-UI, зашитая в приложение
  (`BillingConfig.TRIAL_SUB_URL`). Рекомендация: отдельный inbound/клиент 3X-UI
  с лимитами (скорость/трафик) для демо. Смена ссылки = обновить константу и
  пересобрать APK.

## Статусы Platega (уже есть в `payment.py`)
- paid: `CONFIRMED, PAID, SUCCESS, SUCCEEDED, COMPLETED`
- cancelled: `CANCELED, FAILED, EXPIRED, REJECTED`

`PaymentApi.parseState()` в приложении маппит их в `SUCCEEDED | CANCELLED | PENDING`.

## Поток покупки (Android)
1. Пользователь жмёт «Купить · 250 ₽» → `PaymentApi.createPayment()`.
2. Приложение открывает `payment_url` в Custom Tab.
3. Приложение опрашивает `paymentStatus(orderId)` (например каждые 3с, до ~5 мин).
4. При `SUCCEEDED` — грузит `sub_url` в режим «Скорость`, выставляет `subExpireAt`,
   открывает оба режима.
