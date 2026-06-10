// SubscriptionSetupView.swift — SKYFLOW VPN iOS
// Mirrors Android SubscriptionSetupScreen.kt — first-launch subscription entry.

import SwiftUI

struct SubscriptionSetupView: View {

    let onFinish: () -> Void

    @EnvironmentObject var settings: SettingsStore
    @Environment(\.skyflow) var c

    @State private var url          = ""
    @State private var isLoading    = false
    @State private var result:      SubscriptionResult? = nil
    @State private var errorMsg:    String? = nil
    @State private var isWrongDomain = false

    private var isValidUrl: Bool {
        url.trimmingCharacters(in: .whitespaces).hasPrefix("http")
    }

    var body: some View {
        ZStack {
            c.background.ignoresSafeArea()

            ScrollView {
                VStack(spacing: 0) {
                    Spacer(minLength: 60)

                    // ── Logo ─────────────────────────────────────────
                    ZStack {
                        RoundedRectangle(cornerRadius: SkyflowRadius.logo)
                            .fill(c.surface)
                            .overlay(
                                RoundedRectangle(cornerRadius: SkyflowRadius.logo)
                                    .stroke(c.borderAccent, lineWidth: 1)
                            )
                            .frame(width: 72, height: 72)
                        Text("S")
                            .font(.system(size: 30, weight: .black))
                            .foregroundColor(c.accent)
                    }

                    Spacer(minLength: 20)

                    // ── Title ─────────────────────────────────────────
                    Text("Подключите подписку")
                        .font(.inter(24, weight: .bold))
                        .foregroundColor(c.textPrimary)
                    Spacer(minLength: 8)
                    Text("Вставьте ссылку от провайдера.\nПриложение загрузит серверы автоматически.")
                        .font(.inter(15))
                        .foregroundColor(c.textSecondary)
                        .multilineTextAlignment(.center)
                        .lineSpacing(4)

                    Spacer(minLength: 28)

                    // ── URL field ─────────────────────────────────────
                    HStack(spacing: 8) {
                        TextField("https://sub.skypath.fun/...", text: $url)
                            .font(.inter(14))
                            .foregroundColor(c.textPrimary)
                            .autocorrectionDisabled()
                            .textInputAutocapitalization(.never)
                            .keyboardType(.URL)
                            .onChange(of: url) { _ in
                                result = nil; errorMsg = nil; isWrongDomain = false
                            }

                        if !url.isEmpty {
                            Button { url = ""; result = nil; errorMsg = nil } label: {
                                Image(systemName: "xmark.circle.fill")
                                    .foregroundColor(c.textMuted)
                            }
                        }
                        Button {
                            if let str = UIPasteboard.general.string {
                                url = str.trimmingCharacters(in: .whitespacesAndNewlines)
                            }
                        } label: {
                            Image(systemName: "doc.on.clipboard")
                                .foregroundColor(c.accentLight)
                        }
                    }
                    .padding(14)
                    .background(c.glassSurface, in: RoundedRectangle(cornerRadius: SkyflowRadius.field))
                    .overlay(
                        RoundedRectangle(cornerRadius: SkyflowRadius.field)
                            .stroke(c.borderAccent, lineWidth: 0.5)
                    )

                    Spacer(minLength: 12)

                    // ── Load button ───────────────────────────────────
                    Button(action: loadSubscription) {
                        HStack(spacing: 8) {
                            if isLoading {
                                ProgressView().tint(c.onAccent).scaleEffect(0.85)
                                Text("Загрузка...")
                            } else {
                                Text("Загрузить серверы")
                            }
                        }
                        .font(.inter(16, weight: .semibold))
                        .foregroundColor(isValidUrl ? c.onAccent : c.textMuted)
                        .frame(maxWidth: .infinity)
                        .frame(height: 52)
                        .background(
                            RoundedRectangle(cornerRadius: SkyflowRadius.chip)
                                .fill(isValidUrl ? c.accent : c.border)
                        )
                    }
                    .disabled(!isValidUrl || isLoading)

                    // ── Wrong domain banner ───────────────────────────
                    if isWrongDomain {
                        Spacer(minLength: 12)
                        wrongDomainCard
                    }

                    // ── Result ────────────────────────────────────────
                    if let r = result {
                        Spacer(minLength: 12)
                        successCard(r)
                    } else if let err = errorMsg {
                        Spacer(minLength: 12)
                        errorCard(err)
                    }

                    Spacer(minLength: 20)

                    // ── Continue button ───────────────────────────────
                    let canProceed = result != nil && !(result!.servers.isEmpty)
                    Button {
                        guard canProceed else { return }
                        onFinish()
                    } label: {
                        Text("Продолжить →")
                            .font(.inter(17, weight: .semibold))
                            .foregroundColor(canProceed ? .white : c.textMuted)
                            .frame(maxWidth: .infinity)
                            .frame(height: 54)
                            .background(
                                RoundedRectangle(cornerRadius: SkyflowRadius.button)
                                    .fill(canProceed
                                          ? AnyShapeStyle(LinearGradient(colors: [c.accent, c.accentLight], startPoint: .leading, endPoint: .trailing))
                                          : AnyShapeStyle(c.border))
                            )
                    }
                    .disabled(!canProceed)

                    Spacer(minLength: 40)
                }
                .padding(.horizontal, 28)
            }
        }
    }

    // MARK: - Sub-views

    private var wrongDomainCard: some View {
        VStack(spacing: 10) {
            HStack(spacing: 10) {
                Text("⛔").font(.title3)
                VStack(alignment: .leading, spacing: 2) {
                    Text("Это не подписка SKYFLOW VPN")
                        .font(.inter(14, weight: .bold)).foregroundColor(c.errorColor)
                    Text("Для продолжения работы купите подписку")
                        .font(.inter(13)).foregroundColor(c.textSecondary)
                }
                Spacer()
            }
            HStack {
                Text("300 ₽ / месяц")
                    .font(.inter(14, weight: .bold)).foregroundColor(c.accentLight)
                    .padding(.horizontal, 10).padding(.vertical, 5)
                    .background(c.accentMuted, in: Capsule())
                Spacer()
                Button {
                    UIApplication.shared.open(URL(string: "https://t.me/skypathvpn_bot")!)
                } label: {
                    Text("✈  Купить в Telegram")
                        .font(.inter(13, weight: .semibold)).foregroundColor(c.onAccent)
                        .padding(.horizontal, 14).padding(.vertical, 9)
                        .background(c.accent, in: Capsule())
                }
            }
        }
        .padding(14)
        .background(c.errorColor.opacity(0.08), in: RoundedRectangle(cornerRadius: SkyflowRadius.card))
        .overlay(RoundedRectangle(cornerRadius: SkyflowRadius.card)
            .stroke(c.errorColor.opacity(0.28), lineWidth: 1))
    }

    private func successCard(_ r: SubscriptionResult) -> some View {
        HStack(spacing: 12) {
            Image(systemName: "checkmark.circle.fill").foregroundColor(c.connected)
            VStack(alignment: .leading) {
                Text(r.title.isEmpty ? "Подписка" : r.title)
                    .font(.inter(14, weight: .semibold)).foregroundColor(c.connected)
                Text("Загружено \(r.servers.count) серверов")
                    .font(.inter(13)).foregroundColor(c.textSecondary)
            }
        }
        .padding(14)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(c.connected.opacity(0.08), in: RoundedRectangle(cornerRadius: SkyflowRadius.card))
        .overlay(RoundedRectangle(cornerRadius: SkyflowRadius.card)
            .stroke(c.connected.opacity(0.25), lineWidth: 1))
    }

    private func errorCard(_ message: String) -> some View {
        HStack(spacing: 12) {
            Image(systemName: "exclamationmark.triangle.fill").foregroundColor(c.errorColor)
            Text(message).font(.inter(13)).foregroundColor(c.errorColor)
        }
        .padding(14)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(c.errorColor.opacity(0.08), in: RoundedRectangle(cornerRadius: SkyflowRadius.card))
        .overlay(RoundedRectangle(cornerRadius: SkyflowRadius.card)
            .stroke(c.errorColor.opacity(0.25), lineWidth: 1))
    }

    // MARK: - Logic

    private func loadSubscription() {
        let trimmed = url.trimmingCharacters(in: .whitespaces)
        guard !trimmed.isEmpty, !isLoading else { return }

        guard SubscriptionParser.isSkypathUrl(trimmed) else {
            isWrongDomain = true; errorMsg = nil; return
        }
        isWrongDomain = false

        Task {
            await MainActor.run { isLoading = true; result = nil; errorMsg = nil }
            do {
                let r = try await SubscriptionParser.fetchSubscription(trimmed)
                await MainActor.run {
                    if r.servers.isEmpty {
                        errorMsg = "Серверы не найдены — проверьте ссылку"
                    } else {
                        result = r
                        settings.subscriptionUrl = trimmed
                        settings.vlessInputMode  = "subscription"
                        settings.saveServers(r.servers)
                        settings.subExpireAt = r.expireAt
                        settings.subTitle    = r.title
                        settings.subUpload   = r.upload
                        settings.subDownload = r.download
                        settings.subTotal    = r.total
                        settings.subAnnounce = r.announce
                    }
                    isLoading = false
                }
            } catch {
                await MainActor.run {
                    errorMsg = "Не удалось загрузить: \(error.localizedDescription)"
                    isLoading = false
                }
            }
        }
    }
}
