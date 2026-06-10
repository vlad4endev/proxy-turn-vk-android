// PacketTunnelProvider.swift — SKYFLOW VPN iOS
// NEPacketTunnelProvider — runs inside the Network Extension process.
// This is the iOS equivalent of Android TunnelService.kt.
//
// Bundle ID:  com.wdtt.client.tunnel
// Entitlement: com.apple.developer.networking.networkextension (Packet Tunnel Provider)
//
// SPEED MODE:  Starts Xray VLESS process + hev-socks5-tunnel (C lib via dylib)
// WHITELIST:   Starts Go client (libclient.xcframework) → VK TURN/DTLS → WireGuard

import NetworkExtension
import os.log

private let log = Logger(subsystem: "com.wdtt.client.tunnel", category: "PacketTunnel")

final class PacketTunnelProvider: NEPacketTunnelProvider {

    // MARK: - State

    private var mode:        String = "whitelist"
    private var xrayProcess: Process?
    private var stopCalled   = false

    // MARK: - Start tunnel

    override func startTunnel(options: [String: NSObject]? = nil) async throws {
        guard let cfg = protocolConfiguration as? NETunnelProviderProtocol,
              let providerCfg = cfg.providerConfiguration
        else { throw TunnelError.missingConfig }

        mode = providerCfg["mode"] as? String ?? "whitelist"
        log.info("Starting tunnel — mode: \(self.mode)")

        if mode == "speed" {
            try await startSpeedMode(cfg: providerCfg)
        } else {
            try await startWhitelistMode(cfg: providerCfg)
        }
    }

    // MARK: - Stop tunnel

    override func stopTunnel(with reason: NEProviderStopReason) async {
        guard !stopCalled else { return }
        stopCalled = true
        log.info("Stopping tunnel — mode: \(self.mode), reason: \(reason.rawValue)")

        if mode == "speed" {
            stopSpeedMode()
        } else {
            stopWhitelistMode()
        }
    }

    // MARK: - Message from app (stats)

    override func handleAppMessage(_ messageData: Data) async -> Data? {
        guard let message = String(data: messageData, encoding: .utf8),
              message == "STATS"
        else { return nil }

        let stats: [String: Int64] = [
            "bytes_in":  getSpeedBytesIn(),
            "bytes_out": getSpeedBytesOut(),
            "workers":   Int64(getActiveWorkers()),
        ]
        return try? JSONEncoder().encode(stats)
    }

    // MARK: - SPEED MODE (Xray VLESS + hev-socks5-tunnel)

    private func startSpeedMode(cfg: [String: Any]) async throws {
        guard let serverJson = cfg["vless_server"] as? String,
              let serverData = serverJson.data(using: .utf8),
              let server = try? JSONDecoder().decode(VlessServerCodable.self, from: serverData)
        else { throw TunnelError.missingConfig }

        let routingMode = cfg["routing_mode"] as? String ?? "global"
        let socksPort   = 11000

        // 1. Write Xray config
        let xrayCfg = buildXrayConfig(server: server, socksPort: socksPort, routingMode: routingMode)
        let cfgPath = (containerURL?.appendingPathComponent("xray.json").path)!
        try xrayCfg.write(toFile: cfgPath, atomically: true, encoding: .utf8)

        // 2. Start Xray process
        try startXrayProcess(configPath: cfgPath)

        // 3. Wait for Xray SOCKS5 to be ready
        try await waitForPort(socksPort, timeout: 5)

        // 4. Setup TUN via NEPacketTunnelNetworkSettings
        let settings = NEPacketTunnelNetworkSettings(tunnelRemoteAddress: server.address)
        settings.ipv4Settings = {
            let s = NEIPv4Settings(addresses: ["10.1.0.2"], subnetMasks: ["255.255.255.252"])
            s.includedRoutes = routingMode == "bypass_ru"
                ? foreignRoutes()
                : [NEIPv4Route.default()]
            s.excludedRoutes = routingMode == "bypass_ru"
                ? russianRoutes()
                : []
            return s
        }()
        settings.dnsSettings = NEDNSSettings(servers: ["1.1.1.1", "8.8.8.8"])
        settings.mtu = 1500

        try await setTunnelNetworkSettings(settings)

        // 5. Start hev-socks5-tunnel (C library — tun2socks)
        // TODO: call TProxyStartService(configPath, tunFd) via C interop
        // See: shared/gomobile/bridge.go for the XCFramework interface
        log.info("Speed mode TUN active — SOCKS5 on :\(socksPort)")
    }

    private func stopSpeedMode() {
        xrayProcess?.terminate()
        xrayProcess = nil
        // TODO: TProxyStopService()
    }

    private func startXrayProcess(configPath: String) throws {
        let xrayBin = Bundle.main.bundleURL
            .appendingPathComponent("Frameworks/libxray.dylib")
            .path
        // Xray on iOS runs as a library call, not a subprocess.
        // Call XrayStart(configPath) from the XCFramework.
        // TODO: XrayStart(configPath)
        log.info("Xray start requested: \(configPath)")
    }

    // MARK: - WHITELIST MODE (Go client + WireGuard)

    private func startWhitelistMode(cfg: [String: Any]) async throws {
        let vkHashes    = cfg["vk_hashes"]           as? String ?? ""
        let password    = cfg["connection_password"]  as? String ?? ""
        let workers     = cfg["workers_per_hash"]     as? Int    ?? 4
        let peer        = cfg["peer"]                 as? String ?? ""

        // Configure WireGuard tunnel via Go client gomobile XCFramework
        // TODO: GoClientStart(peer, vkHashes, password, workers, tunFd)

        let settings = NEPacketTunnelNetworkSettings(tunnelRemoteAddress: peer.isEmpty ? "0.0.0.0" : peer)
        settings.ipv4Settings = {
            let s = NEIPv4Settings(addresses: ["10.66.66.2"], subnetMasks: ["255.255.255.0"])
            s.includedRoutes = [NEIPv4Route.default()]
            return s
        }()
        settings.dnsSettings = NEDNSSettings(servers: ["1.1.1.1", "8.8.8.8"])
        settings.mtu = 1420

        try await setTunnelNetworkSettings(settings)
        log.info("Whitelist mode TUN active — WireGuard over VK TURN")
    }

    private func stopWhitelistMode() {
        // TODO: GoClientStop()
    }

    // MARK: - Helpers

    private var containerURL: URL? {
        FileManager.default.containerURL(
            forSecurityApplicationGroupIdentifier: "group.com.wdtt.client"
        )
    }

    private func waitForPort(_ port: Int, timeout: TimeInterval) async throws {
        let deadline = Date().addingTimeInterval(timeout)
        while Date() < deadline {
            if isPortOpen(port) { return }
            try await Task.sleep(for: .milliseconds(200))
        }
        throw TunnelError.startTimeout
    }

    private func isPortOpen(_ port: Int) -> Bool {
        let sock = socket(AF_INET, SOCK_STREAM, 0)
        guard sock >= 0 else { return false }
        defer { close(sock) }
        var addr = sockaddr_in()
        addr.sin_family = sa_family_t(AF_INET)
        addr.sin_port   = in_port_t(port).bigEndian
        addr.sin_addr.s_addr = INADDR_LOOPBACK.bigEndian
        return withUnsafePointer(to: &addr) {
            $0.withMemoryRebound(to: sockaddr.self, capacity: 1) {
                connect(sock, $0, socklen_t(MemoryLayout<sockaddr_in>.size)) == 0
            }
        }
    }

    private func getSpeedBytesIn()  -> Int64 { 0 } // TODO: from hev stats
    private func getSpeedBytesOut() -> Int64 { 0 }
    private func getActiveWorkers() -> Int   { 0 } // TODO: from go client

    // Simplified route lists — full lists in RouteTable.swift
    private func foreignRoutes() -> [NEIPv4Route] { [NEIPv4Route.default()] }
    private func russianRoutes() -> [NEIPv4Route]  { [] }

    private func buildXrayConfig(server: VlessServerCodable, socksPort: Int, routingMode: String) -> String {
        """
        {
          "inbounds": [{
            "port": \(socksPort), "listen": "127.0.0.1",
            "protocol": "socks",
            "settings": { "auth": "noauth", "udp": true }
          }],
          "outbounds": [{
            "protocol": "vless",
            "settings": {
              "vnext": [{
                "address": "\(server.address)", "port": \(server.port),
                "users": [{ "id": "\(server.uuid)", "encryption": "none" }]
              }]
            },
            "streamSettings": {
              "network": "\(server.type)",
              "security": "\(server.security)",
              "wsSettings": { "path": "\(server.path)" }
            }
          }]
        }
        """
    }
}

// MARK: - Supporting types

enum TunnelError: Error {
    case missingConfig
    case startTimeout
}

struct VlessServerCodable: Codable {
    var uuid:     String
    var address:  String
    var port:     Int
    var type:     String
    var security: String
    var path:     String
}
