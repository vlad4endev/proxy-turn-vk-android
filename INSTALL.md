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

Перед сборкой нужны нативные библиотеки в `app/src/main/jniLibs/`:

| Библиотека | Режим | Как получить |
|------------|-------|--------------|
| `libclient.so` | VK/TURN туннель | CI или `go build` из `go_client/` (см. workflow) |
| `libxray.so` | Скоростной (VLESS) | `./scripts/build-native-speed.sh` |
| `libhev-socks5-tunnel.so` | Скоростной (TUN) | `./scripts/build-native-speed.sh` |

Для скоростного режима (Xray + TUN) после установки NDK и Go:

```bash
export ANDROID_NDK_HOME="$ANDROID_HOME/ndk/27.2.12479018"   # путь к вашему NDK
./scripts/build-native-speed.sh
./gradlew assembleRelease
```

Без `libxray.so` и `libhev-socks5-tunnel.so` скоростной режим не запустится. CI собирает всё автоматически — см. `.github/workflows/build-release.yml`.

При необходимости также нужен `app/src/main/assets/server` — см. workflow.

## Поведение

- Вкладка **Туннель** — только ссылка VK + кнопка питания (без полей сервера в UI).
- Вкладка **Деплой** убрана из навигации (код `DeployTab` остаётся в проекте).
- IP, порт и пароль берутся из `ServerConfig` / DataStore.
