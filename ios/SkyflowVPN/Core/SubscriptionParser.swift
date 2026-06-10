// SubscriptionParser.swift — SKYFLOW VPN iOS
// Mirrors Android SubscriptionParser.kt
// Parses VLESS subscription (Base64-encoded list of vless:// URIs)

import Foundation

struct SubscriptionResult {
    var servers:  [VlessServer]
    var expireAt: Int64
    var title:    String
    var upload:   Int64
    var download: Int64
    var total:    Int64
    var announce: String
}

struct VlessServer: Codable, Identifiable {
    var id:         String { uuid }
    var uuid:       String
    var address:    String
    var port:       Int
    var name:       String
    var type:       String   // tcp, ws, grpc
    var security:   String   // none, tls, reality
    var path:       String
    var host:       String
    var isSelected: Bool
    var latency:    Int

    // Reality params
    var pbk:        String
    var fp:         String
    var sni:        String
    var sid:        String
}

enum SubscriptionParser {

    // MARK: - Fetch & parse

    static func fetchSubscription(_ urlString: String) async throws -> SubscriptionResult {
        guard let url = URL(string: urlString) else {
            throw URLError(.badURL)
        }
        var request = URLRequest(url: url, timeoutInterval: 15)
        request.setValue("clash-verge/v2.2.3", forHTTPHeaderField: "User-Agent")
        let (data, _) = try await URLSession.shared.data(for: request)

        // Decode Base64
        let raw: String
        if let decoded = Data(base64Encoded: data, options: .ignoreUnknownCharacters),
           let str = String(data: decoded, encoding: .utf8) {
            raw = str
        } else if let str = String(data: data, encoding: .utf8) {
            raw = str
        } else {
            throw DecodingError.dataCorrupted(.init(codingPath: [], debugDescription: "Not UTF-8"))
        }

        let lines = raw.components(separatedBy: .newlines)
        var servers: [VlessServer] = []
        var expireAt: Int64 = 0
        var title = ""
        var upload: Int64 = 0
        var download: Int64 = 0
        var total: Int64 = 0
        var announce = ""

        // Parse subscription-userinfo header if present
        // format: upload=xxx; download=xxx; total=xxx; expire=xxx
        if let infoLine = lines.first(where: { $0.hasPrefix("# ") || $0.hasPrefix("//") }) {
            let info = infoLine.dropFirst(2)
            for part in info.components(separatedBy: "; ") {
                let kv = part.components(separatedBy: "=")
                guard kv.count == 2 else { continue }
                switch kv[0].trimmingCharacters(in: .whitespaces) {
                case "upload":   upload   = Int64(kv[1]) ?? 0
                case "download": download = Int64(kv[1]) ?? 0
                case "total":    total    = Int64(kv[1]) ?? 0
                case "expire":   expireAt = Int64(kv[1]) ?? 0
                case "title":    title    = kv[1]
                default: break
                }
            }
        }

        for line in lines {
            let trimmed = line.trimmingCharacters(in: .whitespaces)
            if trimmed.hasPrefix("vless://") {
                if let server = parseVlessUri(trimmed) {
                    servers.append(server)
                }
            }
        }

        return SubscriptionResult(
            servers: servers, expireAt: expireAt, title: title,
            upload: upload, download: download, total: total, announce: announce
        )
    }

    // MARK: - Parse single vless:// URI

    static func parseVlessUri(_ uri: String) -> VlessServer? {
        // vless://UUID@host:port?params#name
        guard uri.hasPrefix("vless://"),
              let noScheme = uri.dropFirst("vless://".count) as Substring?,
              let atRange = noScheme.range(of: "@", options: .backwards)
        else { return nil }

        let uuidPart  = String(noScheme[..<atRange.lowerBound])
        let remainder = String(noScheme[atRange.upperBound...])

        // Split remainder into host:port?params#name
        let hashParts = remainder.components(separatedBy: "#")
        let name = hashParts.count > 1
            ? (hashParts[1].removingPercentEncoding ?? hashParts[1])
            : ""

        let queryParts = hashParts[0].components(separatedBy: "?")
        let hostPort   = queryParts[0]
        let queryStr   = queryParts.count > 1 ? queryParts[1] : ""

        // Parse host:port (handle IPv6 [::1]:443)
        let (host, port): (String, Int)
        if hostPort.hasPrefix("[") {
            let closingBracket = hostPort.lastIndex(of: "]")!
            let h = String(hostPort[hostPort.index(after: hostPort.startIndex)..<closingBracket])
            let portStr = String(hostPort[hostPort.index(after: closingBracket)...].dropFirst())
            host = h; port = Int(portStr) ?? 443
        } else {
            let hp = hostPort.components(separatedBy: ":")
            host = hp[0]; port = Int(hp.last ?? "443") ?? 443
        }

        // Parse query params
        var params: [String: String] = [:]
        for pair in queryStr.components(separatedBy: "&") {
            let kv = pair.components(separatedBy: "=")
            if kv.count == 2 {
                params[kv[0]] = kv[1].removingPercentEncoding ?? kv[1]
            }
        }

        return VlessServer(
            uuid:       uuidPart,
            address:    host,
            port:       port,
            name:       name,
            type:       params["type"]     ?? "tcp",
            security:   params["security"] ?? "none",
            path:       params["path"]     ?? "",
            host:       params["host"]     ?? host,
            isSelected: false,
            latency:    0,
            pbk:        params["pbk"]      ?? "",
            fp:         params["fp"]       ?? "",
            sni:        params["sni"]      ?? "",
            sid:        params["sid"]      ?? ""
        )
    }

    // MARK: - Domain validation

    static func isSkypathUrl(_ urlString: String) -> Bool {
        guard let host = URL(string: urlString)?.host?.lowercased() else { return false }
        return host == "skypath.fun" || host.hasSuffix(".skypath.fun")
    }
}
