// ServersView.swift — SKYFLOW VPN iOS
// Mirrors Android ServersScreen.kt

import SwiftUI

struct ServersView: View {

    @EnvironmentObject var settings: SettingsStore
    @Environment(\.skyflow) var c
    @Environment(\.dismiss) var dismiss

    @State private var inputText:    String = ""
    @State private var servers:      [VlessServer] = []
    @State private var isLoading:    Bool = false
    @State private var isWrongDomain:Bool = false
    @State private var error:        String? = nil
    @State private var lastUpdate:   Date? = nil

    private var inputType: InputType {
        let t = inputText.trimmingCharacters(in: .whitespaces)
        if t.hasPrefix("vless://") { return .vlessUri }
        if t.hasPrefix("http://") || t.hasPrefix("https://") { return .subscription }
        return .unknown
    }

    var body: some View {
        NavigationView {
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {

                    // ── Routing ───────────────────────────────────────
                    sectionLabel("МАРШРУТИЗАЦИЯ")
                    routingPicker

                    // ── Input ─────────────────────────────────────────
                    sectionLabel("VLESS / ПОДПИСКА")
                    inputBadge
                    inputField

                    // ── Wrong domain banner ───────────────────────────
                    if isWrongDomain {
                        wrongDomainBanner
                    }

                    // ── Error ─────────────────────────────────────────
                    if let err = error {
                        errorRow(err)
                    }

                    // ── Action buttons ────────────────────────────────
                    if inputType == .subscription && !isWrongDomain {
                        actionButtons
                    }

                    // ── Update timestamp ──────────────────────────────
                    if let date = lastUpdate {
                        Text("Обновлено: \(date.formatted())")
                            .font(.inter(11))
                            .foregroundColor(c.textMuted)
                    }

                    // ── Server list ───────────────────────────────────
                    if !servers.isEmpty {
                        serverList
                    }
                }
                .padding(16)
            }
            .background(c.background.ignoresSafeArea())
            .navigationTitle("Серверы")
            .navigationBarTitleDisplayMode(.large)
            .toolbar {
                ToolbarItem(placement: .navigationBarLeading) {
                    Button("Назад") { dismiss() }
                }
            }
        }
        .onAppear { loadInitialState() }
        .onChange(of: inputText) { handleInputChange($0) }
    }

    // MARK: - Subviews

    private var routingPicker: some View {
        HStack(spacing: 6) {
            ForEach(RoutingMode.allCases, id: \.self) { mode in
                Button {
                    settings.routingMode = mode.key
                } label: {
                    Text(mode.label)
                        .font(.inter(12, weight: .semibold))
                        .foregroundColor(settings.routingMode == mode.key ? c.accentLight : c.textSecondary)
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 8)
                        .background(
                            RoundedRectangle(cornerRadius: SkyflowRadius.chip)
                                .fill(settings.routingMode == mode.key
                                      ? c.accentMuted
                                      : c.glassSurfaceElevated)
                                .overlay(
                                    RoundedRectangle(cornerRadius: SkyflowRadius.chip)
                                        .stroke(settings.routingMode == mode.key
                                                ? c.accent.opacity(0.4)
                                                : c.border, lineWidth: 0.5)
                                )
                        )
                }
                .buttonStyle(.plain)
            }
        }
    }

    @ViewBuilder
    private var inputBadge: some View {
        if inputType != .unknown {
            Text(inputType == .vlessUri ? "VLESS URI" : "URL подписки")
                .font(.inter(11, weight: .bold))
                .foregroundColor(c.accentLight)
                .padding(.horizontal, 8)
                .padding(.vertical, 3)
                .background(c.accentMuted, in: Capsule())
        }
    }

    private var inputField: some View {
        HStack(spacing: 8) {
            TextField(
                inputType == .subscription ? "URL подписки" : "Вставьте VLESS URI или URL подписки",
                text: $inputText, axis: inputType == .vlessUri ? .vertical : .horizontal
            )
            .font(.inter(13))
            .foregroundColor(c.textAccent)
            .autocorrectionDisabled()
            .textInputAutocapitalization(.never)
            .lineLimit(inputType == .vlessUri ? 4 : 1)

            if !inputText.isEmpty {
                Button {
                    inputText = ""
                    isWrongDomain = false
                    error = nil
                } label: {
                    Image(systemName: "xmark.circle.fill")
                        .foregroundColor(c.textMuted)
                }
            }

            Button {
                if let str = UIPasteboard.general.string {
                    inputText = str.trimmingCharacters(in: .whitespacesAndNewlines)
                }
            } label: {
                Image(systemName: "doc.on.clipboard")
                    .foregroundColor(c.accentLight)
            }
        }
        .padding(12)
        .background(c.glassSurface, in: RoundedRectangle(cornerRadius: SkyflowRadius.field))
        .overlay(
            RoundedRectangle(cornerRadius: SkyflowRadius.field)
                .stroke(c.borderAccent, lineWidth: 0.5)
        )
    }

    private var wrongDomainBanner: some View {
        VStack(spacing: 10) {
            HStack(spacing: 10) {
                Text("⛔").font(.title3)
                VStack(alignment: .leading, spacing: 2) {
                    Text("Это не подписка SKYFLOW VPN")
                        .font(.inter(14, weight: .bold))
                        .foregroundColor(c.errorColor)
                    Text("Для продолжения работы купите подписку")
                        .font(.inter(13))
                        .foregroundColor(c.textSecondary)
                }
                Spacer()
            }
            HStack {
                Text("300 ₽ / месяц")
                    .font(.inter(14, weight: .bold))
                    .foregroundColor(c.accentLight)
                    .padding(.horizontal, 10)
                    .padding(.vertical, 5)
                    .background(c.accentMuted, in: RoundedCornerShape(radius: SkyflowRadius.chip))
                Spacer()
                Button {
                    UIApplication.shared.open(URL(string: "https://t.me/skypathvpn_bot")!)
                } label: {
                    Text("✈  Купить в Telegram")
                        .font(.inter(13, weight: .semibold))
                        .foregroundColor(c.onAccent)
                        .padding(.horizontal, 14)
                        .padding(.vertical, 9)
                        .background(c.accent, in: RoundedCornerShape(radius: SkyflowRadius.chip))
                }
            }
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 14)
        .background(c.errorColor.opacity(0.08), in: RoundedRectangle(cornerRadius: SkyflowRadius.card))
        .overlay(
            RoundedRectangle(cornerRadius: SkyflowRadius.card)
                .stroke(c.errorColor.opacity(0.28), lineWidth: 1)
        )
    }

    private func errorRow(_ message: String) -> some View {
        HStack(spacing: 6) {
            Image(systemName: "exclamationmark.triangle.fill")
                .font(.caption)
                .foregroundColor(c.errorColor)
            Text(message)
                .font(.inter(12))
                .foregroundColor(c.errorColor)
        }
    }

    private var actionButtons: some View {
        HStack(spacing: 8) {
            Button {
                refreshSubscription()
            } label: {
                HStack(spacing: 6) {
                    if isLoading {
                        ProgressView().tint(c.onAccent).scaleEffect(0.8)
                    } else {
                        Image(systemName: "arrow.clockwise")
                    }
                    Text(isLoading ? "Загрузка..." : "Обновить")
                }
                .font(.inter(14, weight: .semibold))
                .foregroundColor(c.onAccent)
                .frame(maxWidth: .infinity)
                .padding(.vertical, 12)
                .background(c.accent, in: RoundedRectangle(cornerRadius: SkyflowRadius.chip))
            }
            .disabled(isLoading)
        }
    }

    @ViewBuilder
    private var serverList: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                sectionLabel("СЕРВЕРЫ")
                Spacer()
                Text("\(servers.count)")
                    .font(.inter(11, weight: .bold))
                    .foregroundColor(c.accentLight)
                    .padding(.horizontal, 7)
                    .padding(.vertical, 2)
                    .background(c.accentMuted, in: Capsule())
            }
            ForEach(servers.indices, id: \.self) { i in
                ServerRow(server: servers[i], isSelected: servers[i].isSelected) {
                    selectServer(at: i)
                }
            }
        }
    }

    private func sectionLabel(_ text: String) -> some View {
        Text(text)
            .font(.inter(10, weight: .semibold))
            .foregroundColor(c.textMuted)
    }

    // MARK: - Logic

    private func loadInitialState() {
        servers = settings.loadServers()
        inputText = settings.vlessInputMode == "subscription"
            ? settings.subscriptionUrl
            : ""
    }

    private func handleInputChange(_ text: String) {
        isWrongDomain = false
        error = nil
        let trimmed = text.trimmingCharacters(in: .whitespaces)
        switch inputType {
        case .subscription:
            if !SubscriptionParser.isSkypathUrl(trimmed) {
                isWrongDomain = true
            } else {
                settings.vlessInputMode = "subscription"
                settings.subscriptionUrl = trimmed
                Task { await autoRefresh(url: trimmed) }
            }
        case .vlessUri:
            settings.vlessInputMode = "manual"
            if let server = SubscriptionParser.parseVlessUri(trimmed) {
                servers = [server]
                settings.saveServers(servers)
            }
        case .unknown:
            break
        }
    }

    private func autoRefresh(url: String) async {
        guard !isLoading else { return }
        isLoading = true
        defer { isLoading = false }
        do {
            let result = try await SubscriptionParser.fetchSubscription(url)
            await MainActor.run {
                servers = result.servers
                settings.saveServers(result.servers)
                settings.subExpireAt = result.expireAt
                settings.subTitle    = result.title
                settings.subUpload   = result.upload
                settings.subDownload = result.download
                settings.subTotal    = result.total
                settings.subAnnounce = result.announce
                lastUpdate = Date()
            }
        } catch { }
    }

    private func refreshSubscription() {
        guard SubscriptionParser.isSkypathUrl(inputText) else {
            isWrongDomain = true; return
        }
        isWrongDomain = false
        Task { await autoRefresh(url: inputText) }
    }

    private func selectServer(at index: Int) {
        servers = servers.enumerated().map { i, s in
            var copy = s; copy.isSelected = (i == index); return copy
        }
        settings.saveServers(servers)
        settings.saveSelectedServerIndex(index)
    }
}

// MARK: - Server Row

struct ServerRow: View {
    let server:     VlessServer
    let isSelected: Bool
    let onTap:      () -> Void
    @Environment(\.skyflow) var c

    var body: some View {
        Button(action: onTap) {
            HStack(spacing: 12) {
                // Selection indicator
                ZStack {
                    Circle()
                        .stroke(isSelected ? c.accent : c.border, lineWidth: isSelected ? 2 : 1)
                        .frame(width: 20, height: 20)
                    if isSelected {
                        Circle()
                            .fill(c.accent)
                            .frame(width: 10, height: 10)
                    }
                }

                VStack(alignment: .leading, spacing: 3) {
                    Text(server.name.isEmpty ? server.address : server.name)
                        .font(.inter(13, weight: .semibold))
                        .foregroundColor(c.textPrimary)
                        .lineLimit(1)
                    Text("\(server.address):\(server.port)")
                        .font(.inter(11))
                        .foregroundColor(c.textMuted)
                        .lineLimit(1)
                }
                Spacer()

                // Protocol badge
                Text(server.type.uppercased())
                    .font(.inter(10, weight: .bold))
                    .foregroundColor(c.accentLight)
                    .padding(.horizontal, 6)
                    .padding(.vertical, 2)
                    .background(c.accentMuted, in: Capsule())

                // Latency
                if server.latency > 0 {
                    Text("\(server.latency)ms")
                        .font(.inter(10, weight: .semibold))
                        .foregroundColor(latencyColor(server.latency))
                }
            }
            .padding(12)
            .background(
                RoundedRectangle(cornerRadius: SkyflowRadius.card)
                    .fill(c.glassSurface)
                    .overlay(
                        RoundedRectangle(cornerRadius: SkyflowRadius.card)
                            .stroke(isSelected ? c.accent.opacity(0.45) : c.border, lineWidth: isSelected ? 1 : 0.5)
                    )
            )
        }
        .buttonStyle(.plain)
    }

    private func latencyColor(_ ms: Int) -> Color {
        ms < 300 ? c.connected : ms < 1000 ? c.warnColor : c.errorColor
    }
}

// MARK: - Helpers

enum InputType { case vlessUri, subscription, unknown }

enum RoutingMode: CaseIterable {
    case global, bypassRu, onlyProxy
    var key: String {
        switch self {
        case .global:    return "global"
        case .bypassRu:  return "bypass_ru"
        case .onlyProxy: return "bypass_local"
        }
    }
    var label: String {
        switch self {
        case .global:    return "Глобальный"
        case .bypassRu:  return "Без России"
        case .onlyProxy: return "Только"
        }
    }
}

struct RoundedCornerShape: Shape {
    var radius: CGFloat
    func path(in rect: CGRect) -> Path {
        Path(UIBezierPath(roundedRect: rect, cornerRadius: radius).cgPath)
    }
}
