// SkyflowVPNApp.swift — SKYFLOW VPN iOS
// Entry point, mirrors Android MainActivity.kt

import SwiftUI

@main
struct SkyflowVPNApp: App {

    @StateObject private var tunnel   = TunnelManager.shared
    @StateObject private var settings = SettingsStore.shared
    @StateObject private var theme    = SkyflowTheme.shared

    var body: some Scene {
        WindowGroup {
            RootView()
                .environmentObject(tunnel)
                .environmentObject(settings)
                .environmentObject(theme)
                .skyflowTheme()
        }
    }
}

// MARK: - Root navigation (mirrors Android MainActivity navigation graph)

struct RootView: View {

    @EnvironmentObject var settings: SettingsStore
    @State private var isLoaded = false   // gates render until store is ready (no flash)

    var body: some View {
        Group {
            if !isLoaded {
                // Plain background — matches launch screen, imperceptible
                Color.clear
            } else if !settings.onboardingDone {
                OnboardingView()
            } else if !settings.subscriptionSetupDone {
                SubscriptionSetupView(onFinish: {
                    settings.subscriptionSetupDone = true
                })
            } else {
                MainTabView()
            }
        }
        .task {
            // Small delay — UserDefaults reads synchronously but @Published
            // needs one run-loop cycle to propagate initial values.
            try? await Task.sleep(for: .milliseconds(10))
            isLoaded = true
        }
    }
}

// MARK: - Main tab bar (mirrors Android bottom navigation)

struct MainTabView: View {

    @Environment(\.skyflow) var c

    var body: some View {
        TabView {
            TunnelView()
                .tabItem {
                    Label("Туннель", systemImage: "lock.shield")
                }
            ExclusionsView()
                .tabItem {
                    Label("Исключ.", systemImage: "slider.horizontal.3")
                }
            LogsView()
                .tabItem {
                    Label("Логи", systemImage: "terminal")
                }
            InfoView()
                .tabItem {
                    Label("Инфо", systemImage: "info.circle")
                }
        }
        .accentColor(c.accent)
    }
}
