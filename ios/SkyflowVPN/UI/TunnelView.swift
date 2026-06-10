// TunnelView.swift — SKYFLOW VPN iOS
// Main screen with power button, mode switch and subscription widget.
// Mirrors Android TunnelTab.kt

import SwiftUI

struct TunnelView: View {

    @EnvironmentObject var tunnel:   TunnelManager
    @EnvironmentObject var settings: SettingsStore
    @Environment(\.skyflow) var c

    @State private var showServers = false

    var isSpeedMode: Bool { settings.tunnelMode == "speed" }
    var isConnected: Bool { tunnel.running && tunnel.connectionStage == .vpnReady }
    var isConnecting: Bool {
        tunnel.running && tunnel.connectionStage == .connecting
    }
    var isSubExpired: Bool { settings.isSubExpired() }
    var hasServers: Bool { !settings.loadServers().isEmpty }
    var isVlessBlocked: Bool { !hasServers || isSubExpired }

    var body: some View {
        ScrollView {
            VStack(spacing: 20) {

                // ── Mode switch ───────────────────────────────────────
                TunnelModeSwitch()

                // ── Power button ──────────────────────────────────────
                PowerButton(
                    isRunning:    tunnel.running,
                    isConnecting: isConnecting,
                    isConnected:  isConnected
                ) {
                    onPowerTap()
                }

                // ── Status label ──────────────────────────────────────
                StatusLabel(
                    isRunning:    tunnel.running,
                    isConnecting: isConnecting,
                    isConnected:  isConnected,
                    hint:         tunnel.connectionHint,
                    connectedSince: tunnel.connectedSince
                )

                // ── Subscription gate banner ──────────────────────────
                if isVlessBlocked && !tunnel.running {
                    VlessBlockedBanner(isExpired: isSubExpired) {
                        showServers = true
                    }
                }

                // ── Subscription widget (Speed) ───────────────────────
                if !tunnel.running && isSpeedMode {
                    SubscriptionWidget(showServers: $showServers)
                }

                // ── Compact sub chip (Whitelist) ──────────────────────
                if !tunnel.running && !isSpeedMode && hasServers {
                    SubStatusChip(showServers: $showServers)
                }

                // ── VK Link field (Whitelist only) ────────────────────
                if !isConnected && !isSpeedMode {
                    VKLinkField()
                }

                // ── Traffic stats (when connected) ────────────────────
                if isConnected {
                    TrafficBlock(bytesIn: tunnel.bytesIn, bytesOut: tunnel.bytesOut)
                }

                Spacer(minLength: 24)
            }
            .padding(.horizontal, 16)
            .padding(.top, 12)
        }
        .background(c.background.ignoresSafeArea())
        .sheet(isPresented: $showServers) {
            ServersView()
        }
    }

    private func onPowerTap() {
        if tunnel.running {
            tunnel.stop()
        } else {
            let mode: TunnelMode = isSpeedMode ? .speed : .whitelist
            tunnel.start(mode: mode, settings: settings)
        }
    }
}

// MARK: - Power Button

struct PowerButton: View {
    let isRunning:    Bool
    let isConnecting: Bool
    let isConnected:  Bool
    let action:       () -> Void

    @Environment(\.skyflow) var c
    @State private var rotation: Double = 0
    @State private var scale: CGFloat = 1

    var ringColor: Color {
        if !isRunning   { return c.idle }
        if isConnecting { return c.connecting }
        return c.connected
    }

    var body: some View {
        Button(action: action) {
            ZStack {
                // Glow
                if isConnected {
                    Circle()
                        .fill(c.connected.opacity(0.12))
                        .frame(width: 184, height: 184)
                }
                // Background
                Circle()
                    .fill(
                        RadialGradient(
                            colors: [c.glassSurfaceElevated, c.surface],
                            center: .center,
                            startRadius: 0,
                            endRadius: 70
                        )
                    )
                    .frame(width: 140, height: 140)
                    .overlay(
                        Circle()
                            .stroke(ringColor, lineWidth: 2)
                    )
                // Spinner arc (connecting)
                if isConnecting {
                    Circle()
                        .trim(from: 0, to: 0.3)
                        .stroke(ringColor, style: StrokeStyle(lineWidth: 2, lineCap: .round))
                        .frame(width: 136, height: 136)
                        .rotationEffect(.degrees(rotation))
                        .onAppear {
                            withAnimation(.linear(duration: 1).repeatForever(autoreverses: false)) {
                                rotation = 360
                            }
                        }
                        .onDisappear { rotation = 0 }
                }
                // Power icon
                PowerIcon(color: ringColor)
                    .frame(width: 50, height: 50)
            }
        }
        .scaleEffect(isConnected ? scale : 1)
        .onAppear {
            if isConnected {
                withAnimation(.easeInOut(duration: 1.4).repeatForever(autoreverses: true)) {
                    scale = 1.04
                }
            }
        }
    }
}

struct PowerIcon: View {
    let color: Color
    var body: some View {
        GeometryReader { geo in
            let w = geo.size.width, h = geo.size.height
            let cx = w / 2
            Path { path in
                // Vertical line
                path.move(to: CGPoint(x: cx, y: 0))
                path.addLine(to: CGPoint(x: cx, y: h * 0.36))
            }
            .stroke(color, style: StrokeStyle(lineWidth: 2, lineCap: .round))

            Path { path in
                path.addArc(
                    center: CGPoint(x: cx, y: h * 0.55),
                    radius: h * 0.38,
                    startAngle: .degrees(-210),
                    endAngle: .degrees(30),
                    clockwise: false
                )
            }
            .stroke(color, style: StrokeStyle(lineWidth: 2, lineCap: .round))
        }
    }
}

// MARK: - Status Label

struct StatusLabel: View {
    let isRunning:      Bool
    let isConnecting:   Bool
    let isConnected:    Bool
    let hint:           String
    let connectedSince: Date?

    @Environment(\.skyflow) var c
    @State private var elapsed: Int = 0
    let timer = Timer.publish(every: 1, on: .main, in: .common).autoconnect()

    var statusColor: Color {
        if !isRunning   { return c.idle }
        if isConnecting { return c.connecting }
        return c.connected
    }

    var statusText: String {
        if !isRunning   { return "Отключено" }
        if isConnecting { return "Подключение..." }
        let h = elapsed / 3600, m = (elapsed % 3600) / 60, s = elapsed % 60
        return h > 0
            ? String(format: "Подключено · %02d:%02d:%02d", h, m, s)
            : String(format: "Подключено · %02d:%02d", m, s)
    }

    var body: some View {
        VStack(spacing: 6) {
            Text(statusText)
                .font(.system(size: 22, weight: .semibold))
                .foregroundColor(statusColor)
                .animation(.easeInOut(duration: 0.3), value: statusText)

            if isConnected {
                Text("Соединение защищено")
                    .font(.inter(13))
                    .foregroundColor(c.textSecondary)
            }
            if isConnecting && !hint.isEmpty {
                Text(hint)
                    .font(.inter(12))
                    .foregroundColor(c.textMuted)
                    .multilineTextAlignment(.center)
            }
        }
        .onReceive(timer) { _ in
            guard let since = connectedSince else { return }
            elapsed = Int(Date().timeIntervalSince(since))
        }
    }
}

// MARK: - Mode Switch (stub — full impl in TunnelModeSwitch.swift)

struct TunnelModeSwitch: View {
    @EnvironmentObject var settings: SettingsStore
    @Environment(\.skyflow) var c

    var body: some View {
        HStack(spacing: 8) {
            ModeOption(label: "☁ Белый список", subtitle: "VK/TURN → WG",
                       selected: settings.tunnelMode == "whitelist", accent: c.accentLight) {
                settings.tunnelMode = "whitelist"
            }
            ModeOption(label: "⚡ Скоростной", subtitle: "WG → VPS",
                       selected: settings.tunnelMode == "speed", accent: c.connected) {
                settings.tunnelMode = "speed"
            }
        }
        .padding(10)
        .background(c.glassSurface, in: RoundedRectangle(cornerRadius: SkyflowRadius.card))
        .overlay(
            RoundedRectangle(cornerRadius: SkyflowRadius.card)
                .stroke(c.border, lineWidth: 0.5)
        )
    }
}

struct ModeOption: View {
    let label:    String
    let subtitle: String
    let selected: Bool
    let accent:   Color
    let action:   () -> Void
    @Environment(\.skyflow) var c

    var body: some View {
        Button(action: action) {
            VStack(spacing: 2) {
                Text(label)
                    .font(.inter(11, weight: .bold))
                    .foregroundColor(selected ? accent : c.textSecondary)
                    .lineLimit(1)
                Text(subtitle)
                    .font(.inter(9))
                    .foregroundColor(c.textMuted)
            }
            .frame(maxWidth: .infinity)
            .padding(.vertical, 8)
            .background(
                RoundedRectangle(cornerRadius: SkyflowRadius.chip)
                    .fill(selected ? accent.opacity(0.18) : c.glassSurfaceElevated)
                    .overlay(
                        RoundedRectangle(cornerRadius: SkyflowRadius.chip)
                            .stroke(selected ? accent.opacity(0.55) : c.border,
                                    lineWidth: selected ? 1 : 0.5)
                    )
            )
        }
        .buttonStyle(.plain)
    }
}

// MARK: - Subscription Widget (compact stub)

struct SubscriptionWidget: View {
    @Binding var showServers: Bool
    @EnvironmentObject var settings: SettingsStore
    @Environment(\.skyflow) var c

    var body: some View {
        Button { showServers = true } label: {
            HStack(spacing: 12) {
                Text("🛡").font(.title3)
                VStack(alignment: .leading, spacing: 2) {
                    Text(settings.subTitle.isEmpty ? "Подписка" : settings.subTitle)
                        .font(.inter(14, weight: .semibold))
                        .foregroundColor(c.textPrimary)
                    Text(expiryText)
                        .font(.inter(12))
                        .foregroundColor(c.textSecondary)
                }
                Spacer()
                Image(systemName: "chevron.right")
                    .font(.caption)
                    .foregroundColor(c.textMuted)
            }
            .padding(14)
            .background(c.glassSurface, in: RoundedRectangle(cornerRadius: SkyflowRadius.card))
            .overlay(
                RoundedRectangle(cornerRadius: SkyflowRadius.card)
                    .stroke(c.border, lineWidth: 0.5)
            )
        }
        .buttonStyle(.plain)
    }

    var expiryText: String {
        let exp = settings.subExpireAt
        if exp == 0 { return "∞ Бессрочно" }
        if settings.isSubExpired() { return "Истекла" }
        let days = (exp - Int64(Date().timeIntervalSince1970)) / 86400
        return days < 1 ? "Сегодня" : "ещё \(days)д"
    }
}

// MARK: - Compact Sub Chip (Whitelist mode)

struct SubStatusChip: View {
    @Binding var showServers: Bool
    @EnvironmentObject var settings: SettingsStore
    @Environment(\.skyflow) var c

    var isActive: Bool {
        settings.subExpireAt == 0 || !settings.isSubExpired()
    }

    var body: some View {
        Button { showServers = true } label: {
            HStack(spacing: 8) {
                Circle()
                    .fill(isActive ? c.connected : c.errorColor)
                    .frame(width: 6, height: 6)
                Text(settings.subTitle.isEmpty ? "Подписка" : settings.subTitle)
                    .font(.inter(13, weight: .semibold))
                    .foregroundColor(c.textPrimary)
                    .lineLimit(1)
                Spacer()
                Text(expiryText)
                    .font(.inter(11, weight: .semibold))
                    .foregroundColor(isActive ? c.connected : c.errorColor)
                Image(systemName: "chevron.right")
                    .font(.system(size: 10))
                    .foregroundColor(c.textMuted)
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 9)
            .background(c.glassSurface, in: RoundedRectangle(cornerRadius: SkyflowRadius.chip))
            .overlay(
                RoundedRectangle(cornerRadius: SkyflowRadius.chip)
                    .stroke((isActive ? c.connected : c.errorColor).opacity(0.22), lineWidth: 0.5)
            )
        }
        .buttonStyle(.plain)
    }

    var expiryText: String {
        let exp = settings.subExpireAt
        if exp == 0 { return "∞" }
        if settings.isSubExpired() { return "Истекла" }
        let days = (exp - Int64(Date().timeIntervalSince1970)) / 86400
        return days < 1 ? "Сегодня" : "ещё \(days)д"
    }
}

// MARK: - VK Link Field (stub)

struct VKLinkField: View {
    @EnvironmentObject var settings: SettingsStore
    @Environment(\.skyflow) var c

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("ССЫЛКА ПОДКЛЮЧЕНИЯ")
                .font(.inter(10, weight: .semibold))
                .foregroundColor(c.accentLight)
            TextField("vk.com/call/join/... или telemost.yandex.ru/j/...", text: $settings.wdttLink)
                .font(.inter(13, weight: .medium))
                .foregroundColor(c.textAccent)
                .autocorrectionDisabled()
                .textInputAutocapitalization(.never)
        }
        .padding(14)
        .background(c.glassSurface, in: RoundedRectangle(cornerRadius: SkyflowRadius.field))
        .overlay(
            RoundedRectangle(cornerRadius: SkyflowRadius.field)
                .stroke(c.borderAccent, lineWidth: 0.5)
        )
    }
}

// MARK: - Traffic Block

struct TrafficBlock: View {
    let bytesIn:  Int64
    let bytesOut: Int64
    @Environment(\.skyflow) var c

    var body: some View {
        HStack(spacing: 6) {
            TrafficCell(label: "Загружено", arrow: "↓",
                        arrowColor: c.trafficDown, bytes: bytesIn)
            TrafficCell(label: "Отдано", arrow: "↑",
                        arrowColor: c.accentLight, bytes: bytesOut)
        }
        .padding(10)
        .background(c.glassSurface, in: RoundedRectangle(cornerRadius: SkyflowRadius.card))
        .overlay(
            RoundedRectangle(cornerRadius: SkyflowRadius.card)
                .stroke(c.border, lineWidth: 0.5)
        )
    }
}

struct TrafficCell: View {
    let label:      String
    let arrow:      String
    let arrowColor: Color
    let bytes:      Int64
    @Environment(\.skyflow) var c

    var formatted: String {
        let mb = Double(bytes) / (1024 * 1024)
        return mb >= 1000 ? String(format: "%.1f GB", mb / 1024) : String(format: "%.1f MB", mb)
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack(spacing: 4) {
                Text(arrow).foregroundColor(arrowColor).font(.caption)
                Text(label).font(.inter(10)).foregroundColor(c.textSecondary)
            }
            Text(formatted)
                .font(.inter(18, weight: .bold))
                .foregroundColor(c.textPrimary)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(8)
    }
}

// MARK: - Vless Blocked Banner

struct VlessBlockedBanner: View {
    let isExpired: Bool
    let onAction:  () -> Void
    @Environment(\.skyflow) var c

    var body: some View {
        VStack(spacing: 10) {
            HStack(spacing: 8) {
                Text(isExpired ? "⛔" : "🔒").font(.title3)
                VStack(alignment: .leading, spacing: 2) {
                    Text(isExpired ? "Подписка истекла" : "Подписка не настроена")
                        .font(.inter(14, weight: .bold))
                        .foregroundColor(c.errorColor)
                    Text(isExpired
                         ? "VPN недоступен до оплаты"
                         : "Настройте VLESS-подписку для работы")
                        .font(.inter(12))
                        .foregroundColor(c.textSecondary)
                }
                Spacer()
            }
            if isExpired {
                Button {
                    UIApplication.shared.open(URL(string: "https://t.me/skypathvpn_bot")!)
                } label: {
                    Text("✈  Оплатить через Telegram")
                        .font(.inter(14, weight: .semibold))
                        .foregroundColor(c.onAccent)
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 10)
                        .background(c.accent, in: RoundedRectangle(cornerRadius: SkyflowRadius.chip))
                }
            } else {
                Button(action: onAction) {
                    Text("Настроить подписку →")
                        .font(.inter(14, weight: .semibold))
                        .foregroundColor(c.accentLight)
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 10)
                        .overlay(
                            RoundedRectangle(cornerRadius: SkyflowRadius.chip)
                                .stroke(c.accent, lineWidth: 1)
                        )
                }
            }
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 14)
        .background(c.errorColor.opacity(0.08), in: RoundedRectangle(cornerRadius: SkyflowRadius.card))
        .overlay(
            RoundedRectangle(cornerRadius: SkyflowRadius.card)
                .stroke(c.errorColor.opacity(0.30), lineWidth: 1)
        )
    }
}
