// StubViews.swift — SKYFLOW VPN iOS
// Placeholder screens: Onboarding, Exclusions, Logs, Info.
// Replace with full implementations.

import SwiftUI

// MARK: - Onboarding

struct OnboardingView: View {
    @EnvironmentObject var settings: SettingsStore
    @Environment(\.skyflow) var c

    var body: some View {
        VStack(spacing: 24) {
            Spacer()
            Image(systemName: "lock.shield.fill")
                .font(.system(size: 80))
                .foregroundColor(c.accent)
            Text("SKYFLOW VPN")
                .font(.inter(28, weight: .bold))
                .foregroundColor(c.textPrimary)
            Text("Защищённый VPN с двумя режимами.\nБез логов. Без ограничений.")
                .font(.inter(16))
                .foregroundColor(c.textSecondary)
                .multilineTextAlignment(.center)
            Spacer()
            Button {
                settings.onboardingDone = true
                settings.permissionsSetupDone = true
            } label: {
                Text("Начать →")
                    .font(.inter(17, weight: .semibold))
                    .foregroundColor(.white)
                    .frame(maxWidth: .infinity).frame(height: 54)
                    .background(c.accent, in: RoundedRectangle(cornerRadius: SkyflowRadius.button))
            }
            .padding(.horizontal, 28)
            Spacer(minLength: 40)
        }
        .background(c.background.ignoresSafeArea())
    }
}

// MARK: - Exclusions

struct ExclusionsView: View {
    @Environment(\.skyflow) var c
    var body: some View {
        NavigationView {
            List {
                Text("Исключения приложений")
                    .foregroundColor(c.textSecondary)
            }
            .navigationTitle("Исключения")
            .background(c.background)
        }
        // TODO: List installed apps with toggle, mirror Android ExceptionsTab.kt
    }
}

// MARK: - Logs

struct LogsView: View {
    @Environment(\.skyflow) var c
    var body: some View {
        NavigationView {
            VStack {
                Spacer()
                Text("Событий пока нет.\nУстановите соединение — здесь появится\nполный путь: APP → VK → RELAY → VPS → VPN.")
                    .font(.inter(14))
                    .foregroundColor(c.textMuted)
                    .multilineTextAlignment(.center)
                    .padding()
                Spacer()
            }
            .background(c.background.ignoresSafeArea())
            .navigationTitle("Диагностика")
        }
        // TODO: Mirror Android LogsTab.kt with filter chips and live log stream
    }
}

// MARK: - Info

struct InfoView: View {
    @Environment(\.skyflow) var c
    var body: some View {
        NavigationView {
            List {
                Section("О приложении") {
                    HStack { Text("Версия"); Spacer(); Text("1.2.0 (120)").foregroundColor(c.textMuted) }
                    HStack { Text("Платформа"); Spacer(); Text("iOS").foregroundColor(c.textMuted) }
                }
                Section("Система") {
                    HStack { Text("Устройство"); Spacer(); Text(UIDevice.current.model).foregroundColor(c.textMuted) }
                    HStack { Text("iOS"); Spacer(); Text(UIDevice.current.systemVersion).foregroundColor(c.textMuted) }
                }
                Section {
                    Button("✈ Купить подписку") {
                        UIApplication.shared.open(URL(string: "https://t.me/skypathvpn_bot")!)
                    }
                    .foregroundColor(c.accentLight)
                }
            }
            .navigationTitle("Инфо")
        }
    }
}
