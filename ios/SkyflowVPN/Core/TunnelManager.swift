// TunnelManager.swift — SKYFLOW VPN iOS
// Mirrors Android TunnelManager.kt
// Manages VPN tunnel via NetworkExtension (NEVPNManager / NETunnelProviderManager)

import Foundation
import NetworkExtension
import Combine

enum TunnelMode {
    case speed      // Xray VLESS → hev-socks5-tunnel TUN
    case whitelist  // Go client → VK TURN/DTLS → WireGuard
}

enum ConnectionStage: String {
    case idle        = "Отключено"
    case connecting  = "Подключение..."
    case vpnReady    = "Подключено"
    case failed      = "Ошибка"
}

final class TunnelManager: ObservableObject {

    static let shared = TunnelManager()

    // MARK: - Published state (mirrors Android TunnelManager StateFlow)
    @Published private(set) var running:         Bool            = false
    @Published private(set) var connectionStage: ConnectionStage = .idle
    @Published private(set) var connectionHint:  String          = ""
    @Published private(set) var tunnelMode:      TunnelMode      = .whitelist
    @Published private(set) var connectedSince:  Date?           = nil
    @Published private(set) var activeWorkers:   Int             = 0
    @Published private(set) var bytesIn:         Int64           = 0
    @Published private(set) var bytesOut:        Int64           = 0

    // MARK: - NETunnelProviderManager
    private var providerManager: NETunnelProviderManager?
    private var statusObserver:  Any?
    private var statsTimer:      Timer?

    private init() {
        loadProviderManager()
    }

    // MARK: - Load / Setup

    private func loadProviderManager() {
        NETunnelProviderManager.loadAllFromPreferences { [weak self] managers, error in
            guard let self else { return }
            if let manager = managers?.first {
                self.providerManager = manager
            } else {
                self.providerManager = NETunnelProviderManager()
            }
            self.observeStatus()
        }
    }

    private func observeStatus() {
        statusObserver = NotificationCenter.default.addObserver(
            forName: .NEVPNStatusDidChange,
            object: providerManager?.connection,
            queue: .main
        ) { [weak self] _ in
            self?.syncStatus()
        }
        syncStatus()
    }

    private func syncStatus() {
        guard let status = providerManager?.connection.status else { return }
        switch status {
        case .invalid, .disconnected:
            running = false
            connectionStage = .idle
            connectedSince = nil
            stopStatsTimer()
        case .connecting, .reasserting:
            running = true
            connectionStage = .connecting
        case .connected:
            running = true
            connectionStage = .vpnReady
            if connectedSince == nil { connectedSince = Date() }
            startStatsTimer()
        case .disconnecting:
            connectionStage = .connecting
        @unknown default: break
        }
    }

    // MARK: - Start / Stop

    func start(mode: TunnelMode, settings: SettingsStore) {
        tunnelMode = mode
        setupProviderConfig(mode: mode, settings: settings)
        do {
            try providerManager?.connection.startVPNTunnel()
            running = true
            connectionStage = .connecting
        } catch {
            connectionHint = error.localizedDescription
            connectionStage = .failed
        }
    }

    func stop() {
        providerManager?.connection.stopVPNTunnel()
        running = false
        connectionStage = .idle
        connectedSince = nil
        stopStatsTimer()
    }

    // MARK: - Provider config

    private func setupProviderConfig(mode: TunnelMode, settings: SettingsStore) {
        let proto = NETunnelProviderProtocol()
        proto.providerBundleIdentifier = "com.wdtt.client.tunnel"
        proto.serverAddress = mode == .speed ? "skyflow-speed" : "skyflow-whitelist"

        // Pass config to extension via providerConfiguration dict
        var cfg: [String: Any] = [
            "mode":            mode == .speed ? "speed" : "whitelist",
            "tunnel_mode":     settings.tunnelMode,
            "routing_mode":    settings.routingMode,
        ]
        if mode == .speed {
            let servers = settings.loadServers()
            if let selected = servers.first(where: { $0.isSelected }) ?? servers.first,
               let data = try? JSONEncoder().encode(selected) {
                cfg["vless_server"] = String(data: data, encoding: .utf8) ?? ""
            }
        } else {
            cfg["wdtt_link"]            = settings.wdttLink
            cfg["vk_hashes"]            = settings.vkHashes
            cfg["connection_password"]  = settings.connectionPassword
            cfg["workers_per_hash"]     = settings.workersPerHash
            cfg["peer"]                 = settings.peer
        }
        proto.providerConfiguration = cfg

        let manager = providerManager ?? NETunnelProviderManager()
        manager.protocolConfiguration = proto
        manager.localizedDescription  = "SKYFLOW VPN"
        manager.isEnabled             = true
        manager.saveToPreferences { [weak self] _ in
            self?.providerManager = manager
            self?.observeStatus()
        }
        providerManager = manager
    }

    // MARK: - Stats polling

    private func startStatsTimer() {
        statsTimer?.invalidate()
        statsTimer = Timer.scheduledTimer(withTimeInterval: 2, repeats: true) { [weak self] _ in
            self?.fetchStats()
        }
    }

    private func stopStatsTimer() {
        statsTimer?.invalidate()
        statsTimer = nil
        bytesIn = 0; bytesOut = 0; activeWorkers = 0
    }

    private func fetchStats() {
        guard let session = providerManager?.connection as? NETunnelProviderSession else { return }
        try? session.sendProviderMessage(Data("STATS".utf8)) { [weak self] reply in
            guard let data = reply,
                  let json = try? JSONDecoder().decode([String: Int64].self, from: data)
            else { return }
            DispatchQueue.main.async {
                self?.bytesIn      = json["bytes_in"]  ?? 0
                self?.bytesOut     = json["bytes_out"] ?? 0
                self?.activeWorkers = Int(json["workers"] ?? 0)
            }
        }
    }
}
