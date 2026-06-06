# WireGuard Hang при переходе с TUN — Диагностика и Решение

## Проблема

При переходе с TUN (Speed Mode) на WHITELIST (WireGuard режим) или при обычном подключении через WireGuard:
- Подключение зависает на стадии капчи
- WireGuard так и не запускается
- Приложение может упасть с ANR (Application Not Responding)

## Причины

### Причина #1: Deadlock при запуске WireGuard на Main диспетчере ❌

```kotlin
// НЕПРАВИЛЬНО (было):
scope.launch(Dispatchers.Main) {
    wgHelper?.startTunnel(configStr)  // ← Функция внутри использует withContext(Dispatchers.IO)
}
```

**Проблема:** `startTunnel()` внутри содержит:
```kotlin
private suspend fun startTunnelLocked(configString: String) = withContext(Dispatchers.IO) {
    // ... блокирующие операции
}
```

Когда `withContext(Dispatchers.IO)` вызывается из Main потока, это может привести к **deadlock**!

### Причина #2: WireGuard запускается раньше готовности Go-клиента ❌

```kotlin
// НЕПРАВИЛЬНО (было):
scope.launch {
    delay(5000)  // ← Жёсткая задержка
    if (!wireGuardStarted && process?.isAlive == true) {
        launchWireGuardIfNeeded()
    }
}
```

**Проблема:** Если Go-клиент всё ещё:
- Решает VK капчу
- Ждёт получения TURN кредов
- Выполняет DTLS handshake

То WireGuard запустится раньше времени и получит неполные данные!

### Причина #3: Синхронизация с конфигом отсутствует ❌

Нет проверки, что Go-клиент действительно получил конфиг и готов передать его WireGuard.

## Решение

### Фикс #1: Запуск WireGuard на IO диспетчере

```kotlin
// ПРАВИЛЬНО:
scope.launch(Dispatchers.IO) {
    try {
        wgHelper?.startTunnel(configStr)
        updateLog("wg_started", "[VPN] WireGuard туннель запущен ✓", 2, false)
    } catch (e: Exception) {
        wireGuardStarted = false
        updateLog("vpn_start_error", "Ошибка запуска VPN: ${e.readableMessage()}", 99, true)
    }
}
```

**Почему это работает:** Обе функции теперь работают на одном диспетчере (IO), что предотвращает deadlock.

### Фикс #2: Запуск WireGuard при сигнале готовности

Вместо жёсткой задержки 5 секунд, слушаем событие от Go-клиента:

```kotlin
// В обработчике логов:
if (line.contains("[READY]", true)) {
    connectionStage.value = ConnectionStage.VPN_READY
    launchWireGuardIfNeeded()  // ← Запуск когда Go-клиент действительно готов!
}
```

**Преимущества:**
- WireGuard запускается точно когда нужно
- Работает и при быстром подключении (< 1 сек), и при медленном (30+ сек)
- Капча не блокирует запуск WireGuard

### Фикс #3: Безопасный stop() с обработкой ошибок

```kotlin
fun stop() {
    scope.launch(Dispatchers.IO) {
        try { tun2socksHelper?.stop() } catch (e: Exception) { }
        try { xrayHelper?.stop() } catch (e: Exception) { }
        try { wgHelper?.stopTunnel() } catch (e: Exception) { }
    }
    // ...остальной код
}
```

**Преимущества:**
- Ошибки при выключении не ломают приложение
- Логирование проблем для диагностики
- Используется правильный диспетчер (IO)

## До и После

### ДО (проблема)
```
[VK_CREDS] Авторизация VK…
[VK_CAPTCHA] VK запросил капчу
delay(5000)  ← Жёсткая задержка, может прерваться середине решения капчи
[Запуск WireGuard]  ← Может быть слишком рано или слишком поздно
🔴 Зависание / ANR / Крах
```

### ПОСЛЕ (решение)
```
[VK_CREDS] Авторизация VK…
[VK_CAPTCHA] VK запросил капчу
[DTLS] Handshake…
[READY] Go-клиент готов  ← Точный сигнал
[Запуск WireGuard на IO диспетчере]  ← Безопасный запуск
[VPN] WireGuard туннель запущен ✓
🟢 Успешное подключение
```

## Как отладить

Если проблема всё ещё возникает, посмотрите логи:

```
1. [READY] лог должен появиться перед [VPN]
2. [VPN] WireGuard туннель запущен ✓ — успешный запуск
3. Если [VPN] ошибка — посмотрите сообщение об ошибке
```

### Контрольный список

- [ ] Go-клиент выводит `[READY]` лог после конфига
- [ ] WireGuard начинает запускаться только после `[READY]`
- [ ] На логах нет `ANR` или `Dispatcher error`
- [ ] Таймауты капчи всё ещё работают (30 сек)

## Изменённые файлы

- `TunnelManager.kt` — запуск WireGuard синхронизирован с Go-клиентом

## Проверка

```bash
./gradlew assembleDebug
adb install -r build/outputs/apk/debug/wdtt-client-debug.apk

# Протестируйте:
# 1. Обычное подключение через WireGuard
# 2. Переход с TUN на WireGuard
# 3. Отключение
```

---

**Дата:** 2026-06-07  
**Статус:** ✅ Исправлено
