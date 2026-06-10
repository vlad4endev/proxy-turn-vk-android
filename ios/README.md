# SKYFLOW VPN — iOS

SwiftUI-порт Android-приложения. Архитектура максимально приближена к Android-версии — те же имена классов, та же структура состояний.

## Структура

```
ios/
├── SkyflowVPN/                  ← Основное приложение
│   ├── App/
│   │   └── SkyflowVPNApp.swift  ← @main + RootView + MainTabView
│   ├── UI/
│   │   ├── DesignSystem.swift   ← Цвета / шрифты (порт DesignSystem.kt)
│   │   ├── TunnelView.swift     ← Главный экран (порт TunnelTab.kt)
│   │   ├── ServersView.swift    ← Серверы / подписка (порт ServersScreen.kt)
│   │   ├── SubscriptionSetupView.swift ← Онбординг (порт SubscriptionSetupScreen.kt)
│   │   └── StubViews.swift      ← Заглушки: Onboarding, Exclusions, Logs, Info
│   ├── Core/
│   │   ├── TunnelManager.swift  ← NEVPNManager обёртка (порт TunnelManager.kt)
│   │   ├── SettingsStore.swift  ← UserDefaults / App Group (порт SettingsStore.kt)
│   │   └── SubscriptionParser.swift ← VLESS parser (порт SubscriptionParser.kt)
│   └── Frameworks/              ← XCFrameworks (собираются скриптом, не в git)
│       ├── SkyflowClient.xcframework   ← Go client (gomobile)
│       ├── SkyflowXray.xcframework     ← Xray-core
│       └── SkyflowHev.xcframework      ← hev-socks5-tunnel
│
├── SkyflowTunnel/               ← Network Extension (отдельный target)
│   ├── PacketTunnelProvider.swift  ← NEPacketTunnelProvider (порт TunnelService.kt)
│   └── Info.plist
│
└── README.md

shared/
└── gomobile/
    ├── bridge.go    ← gomobile-экспортируемый API Go-клиента
    └── go.mod

scripts/
└── build-native-ios.sh  ← Сборка XCFrameworks
```

## Требования

- **Xcode 15+**
- **Go 1.22+** — `brew install go`
- **gomobile** — `go install golang.org/x/mobile/cmd/gomobile@latest && gomobile init`
- **cmake** — `brew install cmake`
- **Apple Developer account** с entitlement `com.apple.developer.networking.networkextension`

## Сборка нативных библиотек

```bash
./scripts/build-native-ios.sh
```

Собирает три XCFramework в `ios/SkyflowVPN/Frameworks/`:
- `SkyflowClient.xcframework` — Go-клиент (Whitelist mode, gomobile)
- `SkyflowXray.xcframework` — Xray-core (Speed mode)
- `SkyflowHev.xcframework` — hev-socks5-tunnel TUN (Speed mode)

## Настройка Xcode проекта

1. Создай Xcode-проект с двумя targets:
   - `SkyflowVPN` — iOS App, Bundle ID: `com.wdtt.client`
   - `SkyflowTunnel` — Network Extension, Bundle ID: `com.wdtt.client.tunnel`

2. Добавь все файлы из `ios/SkyflowVPN/` в target `SkyflowVPN`

3. Добавь `ios/SkyflowTunnel/PacketTunnelProvider.swift` в target `SkyflowTunnel`

4. Добавь три XCFramework в оба target (`Frameworks, Libraries, and Embedded Content`)

5. Включи App Group `group.com.wdtt.client` в обоих targets (Settings → Signing & Capabilities)

6. Включи **Network Extensions → Packet Tunnel** в `SkyflowTunnel`

7. В `SkyflowVPN` включи **Personal VPN**

## Что реализовано

| Компонент | Статус |
|---|---|
| DesignSystem (цвета, шрифты) | ✅ Готово |
| SettingsStore (UserDefaults / App Group) | ✅ Готово |
| SubscriptionParser (VLESS / Base64) | ✅ Готово |
| TunnelManager (NEVPNManager) | ✅ Готово |
| TunnelView (кнопка, режимы, виджет) | ✅ Готово |
| ServersView (список, ввод, домен-чек) | ✅ Готово |
| SubscriptionSetupView (онбординг) | ✅ Готово |
| PacketTunnelProvider (Network Extension) | ✅ Архитектура, нужны XCFrameworks |
| gomobile bridge (Go client → Swift) | ✅ Структура, нужна сборка |
| build-native-ios.sh | ✅ Готово |
| ExclusionsView | 🔲 Заглушка |
| LogsView (live log stream) | 🔲 Заглушка |
| InfoView | 🔲 Заглушка |
| XCFrameworks (Xray, hev, gomobile) | 🔲 Требует сборки |

## Архитектура туннелей

```
SPEED MODE (iOS)
─────────────────────────────────────────────────────
SkyflowVPN app  →  TunnelManager.start(.speed)
                →  NETunnelProviderManager
                →  PacketTunnelProvider (Extension)
                →  XrayStart(config) [SkyflowXray.xcframework]
                →  SOCKS5 :11000
                →  TProxyStartService(config, tunFd) [SkyflowHev.xcframework]
                →  TUN fd  ←→  iOS Network Stack

WHITELIST MODE (iOS)
─────────────────────────────────────────────────────
SkyflowVPN app  →  TunnelManager.start(.whitelist)
                →  NETunnelProviderManager
                →  PacketTunnelProvider (Extension)
                →  GoClientStart(peer, hashes, ...) [SkyflowClient.xcframework]
                →  VK TURN/DTLS → wdtt-server VPS
                →  WireGuard → TUN fd  ←→  iOS Network Stack
```
