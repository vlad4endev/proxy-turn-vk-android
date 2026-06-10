// SettingsStore.swift — SKYFLOW VPN iOS
// Mirrors Android SettingsStore.kt using UserDefaults + AppStorage.
// All keys are identical to Android so shared server config stays compatible.

import Foundation
import Combine

final class SettingsStore: ObservableObject {

    static let shared = SettingsStore()

    // MARK: - Suite (shared with Network Extension via App Group)
    private let defaults: UserDefaults = {
        UserDefaults(suiteName: "group.com.wdtt.client") ?? .standard
    }()

    // MARK: - Tunnel mode
    @Published var tunnelMode: String          { didSet { set("tunnel_mode", tunnelMode) } }
    @Published var tunnelModeAutoSwitch: Bool  { didSet { set("tunnel_mode_auto_switch", tunnelModeAutoSwitch) } }

    // MARK: - Whitelist mode
    @Published var wdttLink: String            { didSet { set("wdtt_link", wdttLink) } }
    @Published var vkHashes: String            { didSet { set("vk_hashes", vkHashes) } }
    @Published var connectionPassword: String  { didSet { set("connection_password", connectionPassword) } }
    @Published var workersPerHash: Int         { didSet { set("workers_per_hash", workersPerHash) } }
    @Published var peer: String                { didSet { set("peer", peer) } }

    // MARK: - Speed mode / Subscription
    @Published var subscriptionUrl: String     { didSet { set("subscription_url", subscriptionUrl) } }
    @Published var vlessInputMode: String      { didSet { set("vless_input_mode", vlessInputMode) } }
    @Published var routingMode: String         { didSet { set("routing_mode", routingMode) } }
    @Published var subExpireAt: Int64          { didSet { set("sub_expire_at", subExpireAt) } }
    @Published var subTitle: String            { didSet { set("sub_title", subTitle) } }
    @Published var subUpload: Int64            { didSet { set("sub_upload", subUpload) } }
    @Published var subDownload: Int64          { didSet { set("sub_download", subDownload) } }
    @Published var subTotal: Int64             { didSet { set("sub_total", subTotal) } }
    @Published var subAnnounce: String         { didSet { set("sub_announce", subAnnounce) } }

    // MARK: - Onboarding flags
    @Published var onboardingDone: Bool            { didSet { set("onboarding_done", onboardingDone) } }
    @Published var permissionsSetupDone: Bool      { didSet { set("permissions_setup_done", permissionsSetupDone) } }
    @Published var subscriptionSetupDone: Bool     { didSet { set("subscription_setup_done", subscriptionSetupDone) } }

    // MARK: - Excluded apps (Android key — kept for cross-platform compatibility)
    @Published var excludedApps: String        { didSet { set("excluded_apps", excludedApps) } }

    // MARK: - Domain exclusions (iOS-specific; applied by PacketTunnelProvider)
    /// Comma-separated list of domains/IPs to route outside the tunnel (blacklist)
    /// or exclusively through the tunnel (whitelist), depending on `isWhitelist`.
    @Published var excludedDomains: String     { didSet { set("excluded_domains", excludedDomains) } }
    /// false = bypass selected domains (blacklist); true = only route selected domains (whitelist)
    @Published var isWhitelist: Bool           { didSet { set("is_whitelist", isWhitelist) } }

    // MARK: - Theme
    @Published var themeMode: String           { didSet { set("theme_mode", themeMode) } }

    // MARK: - Init

    private init() {
        tunnelMode             = defaults.string(forKey: "tunnel_mode") ?? "whitelist"
        tunnelModeAutoSwitch   = defaults.bool(forKey: "tunnel_mode_auto_switch") == false ? true : defaults.bool(forKey: "tunnel_mode_auto_switch")
        wdttLink               = defaults.string(forKey: "wdtt_link") ?? ""
        vkHashes               = defaults.string(forKey: "vk_hashes") ?? ""
        connectionPassword     = defaults.string(forKey: "connection_password") ?? ""
        workersPerHash         = defaults.integer(forKey: "workers_per_hash")
        peer                   = defaults.string(forKey: "peer") ?? ""
        subscriptionUrl        = defaults.string(forKey: "subscription_url") ?? ""
        vlessInputMode         = defaults.string(forKey: "vless_input_mode") ?? "subscription"
        routingMode            = defaults.string(forKey: "routing_mode") ?? "bypass_ru"
        subExpireAt            = Int64(defaults.integer(forKey: "sub_expire_at"))
        subTitle               = defaults.string(forKey: "sub_title") ?? ""
        subUpload              = Int64(defaults.integer(forKey: "sub_upload"))
        subDownload            = Int64(defaults.integer(forKey: "sub_download"))
        subTotal               = Int64(defaults.integer(forKey: "sub_total"))
        subAnnounce            = defaults.string(forKey: "sub_announce") ?? ""
        onboardingDone         = defaults.bool(forKey: "onboarding_done")
        permissionsSetupDone   = defaults.bool(forKey: "permissions_setup_done")
        subscriptionSetupDone  = defaults.bool(forKey: "subscription_setup_done")
        excludedApps           = defaults.string(forKey: "excluded_apps") ?? ""
        excludedDomains        = defaults.string(forKey: "excluded_domains") ?? ""
        isWhitelist            = defaults.bool(forKey: "is_whitelist")
        themeMode              = defaults.string(forKey: "theme_mode") ?? "dark"
    }

    // MARK: - Servers

    func saveServers(_ servers: [VlessServer]) {
        if let data = try? JSONEncoder().encode(servers) {
            defaults.set(data, forKey: "vless_servers_json")
        }
    }

    func loadServers() -> [VlessServer] {
        guard let data = defaults.data(forKey: "vless_servers_json"),
              let servers = try? JSONDecoder().decode([VlessServer].self, from: data)
        else { return [] }
        return servers
    }

    func getSelectedServerIndex() -> Int {
        defaults.integer(forKey: "selected_server_index")
    }

    func saveSelectedServerIndex(_ index: Int) {
        set("selected_server_index", index)
    }

    // MARK: - Helpers

    private func set(_ key: String, _ value: Any) {
        defaults.set(value, forKey: key)
    }

    func isSubExpired() -> Bool {
        subExpireAt > 0 && subExpireAt < Int64(Date().timeIntervalSince1970)
    }
}
