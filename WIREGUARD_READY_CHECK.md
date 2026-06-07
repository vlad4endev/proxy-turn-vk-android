# WireGuard Ready State Detection — Fallback Logic

## Проблема

WireGuard туннель поднимался успешно, но приложение не переходило в состояние `VPN_READY` и показывало:
```
Статус: "Ожидание данных..."
Воркеры: 0
```

**Причина:** Приложение ждало конкретных логов от Go-клиента (например, `[READY]`), которые могут не приходить если:
- TURN соединение работает напрямую без Go-клиента
- Go-клиент завис или выключен
- Логи буферизировались и не были проанализированы
- Конфиг для WireGuard был получен другим способом

## Решение

### 1️⃣ **WireGuard Status Polling** (новая переменная)

```kotlin
val wireGuardUp = MutableStateFlow(false)
```

- Хранит текущий статус WireGuard независимо от логов
- Обновляется watchdog каждые 5 секунд
- Доступен для UI (если нужно показать индикатор)

### 2️⃣ **refreshWireGuardUp()** — проверка реального статуса

```kotlin
private suspend fun refreshWireGuardUp() {
    try {
        wireGuardUp.value = wgHelper?.isTunnelUp() ?: false  // ← Реальный запрос к WireGuard
    } catch (e: Exception) {
        wireGuardUp.value = false
    }
}
```

- Вызывает `wgHelper.isTunnelUp()` чтобы узнать действительный статус туннеля
- Безопасна: ошибки не ломают приложение
- Асинхронна: не блокирует UI

### 3️⃣ **checkWireGuardReadiness()** — fallback переход в VPN_READY

```kotlin
private fun checkWireGuardReadiness() {
    if (wireGuardStarted && wireGuardUp.value && 
        connectionStage.value != ConnectionStage.VPN_READY) {
        connectionStage.value = ConnectionStage.VPN_READY
        running.value = true
        markConnectedIfNeeded()
        updateLog("wg_ready_fallback", "[VPN] WireGuard готов (проверка статуса) ✓", 2)
    }
}
```

**Логика:**
1. Если WireGuard был запущен (`wireGuardStarted = true`)
2. И сейчас WireGuard действительно UP (`wireGuardUp.value = true`)
3. И приложение ещё не в VPN_READY состоянии
4. → Переходим в VPN_READY немедленно

### 4️⃣ **Watchdog проверяет каждые 5 секунд**

```kotlin
private fun startWatchdog(context: Context, params: TunnelParams) {
    watchdogJob = scope.launch {
        var wgCheckCounter = 0
        while (isActive && running.value) {
            delay(5_000)  // ← Проверка каждые 5 сек (вместо 30 сек)
            
            // WireGuard check
            if (wireGuardStarted) {
                try {
                    refreshWireGuardUp()        // ← Спрашиваем статус
                    checkWireGuardReadiness()   // ← Проверяем готовность
                } catch (e: Exception) { }
            }
            
            // Main watchdog logic every 30 sec
            wgCheckCounter++
            if (wgCheckCounter < 6) continue  // 6 × 5сек = 30сек
            wgCheckCounter = 0
            
            // Остальная логика watchdog...
        }
    }
}
```

**Преимущества:**
- Быстрое обнаружение WireGuard готовности (до 5 сек вместо бесконечного ожидания)
- Основная логика watchdog не пострадала (всё ещё каждые 30 сек)
- CPU эффективно (5 сек это нормально для мобильной)

### 5️⃣ **Сброс состояния при отключении**

```kotlin
fun stop() {
    // ...
    wireGuardUp.value = false  // ← Сброс перед следующим подключением
    wireGuardStarted = false
    // ...
}
```

## Поток выполнения

### Сценарий: WireGuard поднялся, но логи не приходят

```
t=0s:    Go-клиент стартует
t=0.5s:  WireGuard запускается
         launchWireGuardIfNeeded() → wgHelper.startTunnel()
         updateLog("wg_started", "[VPN] WireGuard туннель запущен ✓")

t=5s:    Watchdog tick #1
         refreshWireGuardUp() → wgHelper.isTunnelUp() → TRUE
         checkWireGuardReadiness():
           wireGuardStarted=true ✓
           wireGuardUp.value=true ✓
           connectionStage != VPN_READY ✓
           → SET connectionStage = VPN_READY
           → SET running = true
           → Log: "[VPN] WireGuard готов (проверка статуса) ✓"

t=5.1s:  UI обновляется:
         connectionStage.value = VPN_READY
         ConnectionSnapshot обновляется
         Пользователь видит "Готово" вместо "Ожидание"
```

### Логи перед фиксом (зависание)

```
[SYS] WireGuard туннель запущен ✓
⏳ (ничего дальше не появляется)
⏳ (приложение ждёт логов которые не приходят)
⏳ (бесконечно)
```

### Логи после фикса (работает)

```
[SYS] WireGuard туннель запущен ✓
[5 sec later]
[VPN] WireGuard готов (проверка статуса) ✓  ← Fallback срабатывает
✓ Подключено
```

## Сборка

На вашей машине (с Java и Android SDK):

```bash
cd /Users/vl4endev/Documents/wdtt-final/proxy-turn-vk-android
./gradlew assembleDebug

# APK будет в:
# app/build/outputs/apk/debug/wdtt-client-debug.apk
```

Или используйте Actions на GitHub для Release сборки.

## Тестирование

1. **Обычное подключение WHITELIST (WireGuard)**
   - Проверить что `[VPN] WireGuard готов` появляется в логах
   - Должно быть либо через [READY] лог, либо через fallback check

2. **Без Go-клиента**
   - Если Go-клиент не работает, но WireGuard поднялся
   - Fallback должен автоматически переключить в VPN_READY

3. **Переход между режимами**
   - TUN → WHITELIST
   - WHITELIST → TUN
   - Проверить cleanups

## На что обратить внимание

✅ `wireGuardUp` — истинный статус туннеля (от WireGuard API)
✅ `checkWireGuardReadiness()` — срабатывает только если условия выполнены
✅ Логирование fallback как `[wg_ready_fallback]` для отладки
✅ Сброс `wireGuardUp` при stop() для чистоты состояния

---

**Дата:** 2026-06-07  
**Коммит:** 9564d5d
**Статус:** ✅ Ready for testing
