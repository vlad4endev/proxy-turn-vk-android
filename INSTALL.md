# Установка патча (упрощённый туннель)

## Файлы в проекте

| Файл | Путь |
|------|------|
| `ServerConfig.kt` | `app/src/main/java/com/wdtt/client/` |
| `HardcodedConfig.kt` | `app/src/main/java/com/wdtt/client/` |
| `ConfigInitializer.kt` | `app/src/main/java/com/wdtt/client/` |
| `TunnelTab.kt` | `app/src/main/java/com/wdtt/client/ui/` |

Правки `MainActivity.kt` и `WdttApplication.kt` уже внесены в репозиторий.

## Шаг 1 — Заполни ServerConfig.kt

```kotlin
object ServerConfig {
    const val HOST     = "185.x.x.x"      // IP твоего VPS
    const val PORT     = 56000
    const val PASSWORD = "пароль_туннеля"  // из install.sh
}
```

## Шаг 2 — Заполни HardcodedConfig.kt (опционально, для автозаполнения при первом запуске)

```kotlin
object HardcodedConfig {
    const val SSH_IP       = "185.x.x.x"
    const val SSH_LOGIN    = "root"
    const val SSH_PASSWORD = "пароль_vps"
    const val SSH_PORT     = "22"
    const val SERVER_HOST    = "185.x.x.x"
    const val SERVER_PORT    = 56000
    const val TUNNEL_PASSWORD = "пароль_туннеля"
}
```

Достаточно заполнить **ServerConfig** — при первом запуске `ConfigInitializer` подставит peer и пароль в DataStore.

## Шаг 3 — Сборка

```bash
./gradlew assembleRelease
```

Перед сборкой нужны `libclient.so` (NDK/CI) и при необходимости `app/src/main/assets/server` — см. `.github/workflows/build-release.yml`.

## Поведение

- Вкладка **Туннель** — только ссылка VK + кнопка питания (без полей сервера в UI).
- Вкладка **Деплой** убрана из навигации (код `DeployTab` остаётся в проекте).
- IP, порт и пароль берутся из `ServerConfig` / DataStore.
