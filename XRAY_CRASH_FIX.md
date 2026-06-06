# Xray Speed Mode Crash — Диагностика и Решение

## Проблема

При подключении к Speed Mode (Xray + TUN) приложение крашится. Это происходит потому, что:

1. **Отсутствуют скомпилированные native библиотеки** для вашей платформы
2. **Нет информативных сообщений об ошибке** при загрузке JNI библиотек

## Причины

### Список отсутствующих библиотек

```
✅ app/src/main/jniLibs/arm64-v8a/
   - libclient.so (200KB)
   - libxray.so (15MB)
   - libhev-socks5-tunnel.so (1.2MB)

❌ app/src/main/jniLibs/armeabi-v7a/
   - libclient.so (MISSING)
   - libxray.so (MISSING)
   - ✅ libhev-socks5-tunnel.so (480KB)

❌ app/src/main/jniLibs/x86_64/
   - libclient.so (MISSING)
   - libxray.so (MISSING)
   - libhev-socks5-tunnel.so (MISSING)
```

### Чего требует Speed Mode

Speed Mode требует:
- **Xray (VLESS прокси)** — `libxray.so` (15MB)
- **hev-socks5-tunnel (TUN to SOCKS)** — `libhev-socks5-tunnel.so` (1.2MB)

Обе библиотеки должны быть собраны для вашей архитектуры:
- `arm64-v8a` (64-bit ARM, большинство современных телефонов)
- `armeabi-v7a` (32-bit ARM, старые телефоны и эмуляторы)
- `x86_64` (Intel эмуляторы)

## Решение

### Вариант 1: Собрать библиотеки локально (рекомендуется)

Требует:
- Android NDK 27.2.x
- Go 1.22+
- ~30 минут времени

```bash
# 1. Установите Android NDK
export ANDROID_NDK_HOME="/path/to/android-ndk-r27.2.12479018"

# 2. Убедитесь что Go установлен
go version  # должен быть 1.22+

# 3. Запустите скрипт сборки
chmod +x scripts/build-native-speed.sh
./scripts/build-native-speed.sh

# 4. Проверьте что собралось
find app/src/main/jniLibs -type f -name "libxray.so" -exec ls -lh {} \;
find app/src/main/jniLibs -type f -name "libhev-socks5-tunnel.so" -exec ls -lh {} \;
```

**Ожидаемый результат:**

```
app/src/main/jniLibs/arm64-v8a/libxray.so
app/src/main/jniLibs/armeabi-v7a/libxray.so
app/src/main/jniLibs/x86_64/libxray.so
app/src/main/jniLibs/arm64-v8a/libhev-socks5-tunnel.so
app/src/main/jniLibs/armeabi-v7a/libhev-socks5-tunnel.so
app/src/main/jniLibs/x86_64/libhev-socks5-tunnel.so
```

### Вариант 2: Собрать через Docker

```bash
docker run --rm -v $(pwd):/project -w /project ubuntu:24.04 bash -c '
  apt-get update
  apt-get install -y git golang-go
  wget -q https://dl.google.com/android/repository/android-ndk-r27.2.12479018-linux.zip
  unzip -q android-ndk-r27.2.12479018-linux.zip
  export ANDROID_NDK_HOME=$(pwd)/android-ndk-r27.2.12479018
  chmod +x scripts/build-native-speed.sh
  ./scripts/build-native-speed.sh
'
```

### Вариант 3: Использовать предсобранные библиотеки

Если сборка невозможна, можно:

1. Скопировать `libxray.so` и `libhev-socks5-tunnel.so` из другого проекта
2. Или загрузить из GitHub releases (если доступны)

## Как это исправлено в коде

### Улучшения в v1.2.1+

1. **Безопасная загрузка JNI** (`Tun2SocksHelper.kt`)
   - Загрузка `libhev-socks5-tunnel` больше не вызывает instant crash
   - Добавлена диагностика с информативным сообщением

2. **Лучшая обработка ошибок** (`TunnelManager.kt`)
   - Разделены ошибки инициализации и запуска
   - Добавлены подсказки о том, что делать при ошибке

3. **Информативные логи** (`XrayHelper.kt`)
   - При отсутствии `libxray.so` выводится понятное сообщение
   - Указывается команда для пересборки APK

## Проверка после сборки

1. **Соберите APK**
   ```bash
   ./gradlew assembleRelease
   ```

2. **Установите на устройство**
   ```bash
   adb install build/outputs/apk/release/wdtt-client-release.apk
   ```

3. **Проверьте Speed Mode**
   - Откройте приложение
   - Нажмите на "Режим" → "Скоростной" (если доступно)
   - Нажмите "Включить"
   - Проверьте логи в приложении

## Диагностика логов

Если Speed Mode не работает, посмотрите логи:

```
# Информационные логи
[XRAY] SOCKS5 прокси запущен (:10808)
[TUN] Интерфейс создан, tun2socks запущен

# Ошибки
[ОШИБКА] libxray.so не найден
[ОШИБКА] libhev-socks5-tunnel не загружена
[ОШИБКА] Не удалось запустить tun2socks
```

## Контакты и поддержка

Если даже после сборки Speed Mode не работает:

1. Проверьте, что используется правильная версия NDK (27.2.x)
2. Убедитесь что Go ≥ 1.22
3. Посмотрите логи сборки (`./scripts/build-native-speed.sh > build.log 2>&1`)
4. Откройте issue на GitHub с логами сборки

---

**Последнее обновление:** 2026-06-07
