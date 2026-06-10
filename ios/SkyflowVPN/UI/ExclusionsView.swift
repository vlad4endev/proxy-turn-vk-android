// ExclusionsView.swift — SKYFLOW VPN iOS
// Domain-based routing exclusions.
// iOS does not support per-app split tunneling for consumer VPNs —
// exclusions are applied at the domain/IP level inside PacketTunnelProvider.

import SwiftUI

// MARK: - Preset domain catalog

private struct PresetDomain: Identifiable {
    let id   = UUID()
    let name: String
    let host: String     // exact host stored in excludedDomains
}

private struct DomainGroup: Identifiable {
    let id       = UUID()
    let category: String
    let icon:     String
    let items:    [PresetDomain]
}

private let presetGroups: [DomainGroup] = [
    .init(category: "Банки", icon: "💳", items: [
        .init(name: "Сбербанк",    host: "sberbank.ru"),
        .init(name: "Т-Банк",      host: "tbank.ru"),
        .init(name: "Альфа-Банк",  host: "alfabank.ru"),
        .init(name: "ВТБ",         host: "vtb.ru"),
        .init(name: "Газпромбанк", host: "gazprombank.ru"),
        .init(name: "Райффайзен",  host: "raiffeisen.ru"),
        .init(name: "Открытие",    host: "open.ru"),
        .init(name: "Совкомбанк",  host: "sovcombank.ru"),
    ]),
    .init(category: "Госуслуги", icon: "🏛", items: [
        .init(name: "Госуслуги",   host: "gosuslugi.ru"),
        .init(name: "ФНС",         host: "nalog.ru"),
        .init(name: "Mos.ru",      host: "mos.ru"),
        .init(name: "СФР (ПФР)",   host: "sfr.gov.ru"),
        .init(name: "Портал РФ",   host: "gov.ru"),
        .init(name: "ЕИС закупки", host: "zakupki.gov.ru"),
    ]),
    .init(category: "Маркетплейсы", icon: "🛒", items: [
        .init(name: "Ozon",        host: "ozon.ru"),
        .init(name: "Wildberries", host: "wildberries.ru"),
        .init(name: "Авито",       host: "avito.ru"),
        .init(name: "Яндекс",      host: "yandex.ru"),
        .init(name: "Яндекс Маркет", host: "market.yandex.ru"),
        .init(name: "Mail.ru",     host: "mail.ru"),
    ]),
    .init(category: "Игры и развлечения", icon: "🎮", items: [
        .init(name: "ВКонтакте",   host: "vk.com"),
        .init(name: "ВКонтакте API", host: "api.vk.com"),
        .init(name: "ОК",          host: "ok.ru"),
        .init(name: "Rutube",      host: "rutube.ru"),
        .init(name: "Иви",         host: "ivi.ru"),
        .init(name: "Кинопоиск",   host: "kinopoisk.ru"),
    ]),
]

private let allPresetHosts: Set<String> = Set(presetGroups.flatMap { $0.items.map(\.host) })

// MARK: - ExclusionsView

struct ExclusionsView: View {
    @EnvironmentObject var settings: SettingsStore
    @Environment(\.skyflow) var c

    @State private var expandedGroups: Set<String> = ["Банки"]
    @State private var showAddSheet    = false
    @State private var newDomainText   = ""
    @State private var addError: String?

    // Parsed from settings.excludedDomains
    private var excludedSet: Set<String> {
        Set(
            settings.excludedDomains
                .split(separator: ",")
                .map { $0.trimmingCharacters(in: .whitespaces) }
                .filter { !$0.isEmpty }
        )
    }

    // Domains added by the user (not in presets)
    private var customDomains: [String] {
        excludedSet.filter { !allPresetHosts.contains($0) }.sorted()
    }

    private var totalCount: Int { excludedSet.count }

    var body: some View {
        NavigationView {
            ZStack {
                c.background.ignoresSafeArea()

                ScrollView {
                    VStack(spacing: 10) {
                        modeCard
                            .padding(.horizontal, 16)

                        presetsSection
                            .padding(.horizontal, 16)

                        customSection
                            .padding(.horizontal, 16)

                        infoNote
                            .padding(.horizontal, 16)
                            .padding(.bottom, 28)
                    }
                    .padding(.top, 10)
                }
            }
            .navigationTitle("Исключения")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .navigationBarTrailing) {
                    Button {
                        newDomainText = ""
                        addError      = nil
                        showAddSheet  = true
                    } label: {
                        Image(systemName: "plus")
                            .foregroundColor(c.accent)
                            .font(.system(size: 17, weight: .medium))
                    }
                }
            }
            .sheet(isPresented: $showAddSheet) { addSheet }
        }
    }

    // MARK: Mode card ──────────────────────────────────────────────────────────

    private var modeCard: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack {
                VStack(alignment: .leading, spacing: 2) {
                    Text("Режим маршрутизации")
                        .font(.inter(13, weight: .semibold))
                        .foregroundColor(c.textPrimary)
                    Text(totalCount == 0 ? "нет выбранных доменов" : "\(totalCount) домен\(pluralSuffix(totalCount))")
                        .font(.inter(11))
                        .foregroundColor(c.accent)
                }
                Spacer()
                modeToggle
            }

            Divider().background(c.border)

            Text(settings.isWhitelist
                 ? "Только домены из списка идут через VPN-туннель, остальной трафик — напрямую."
                 : "Домены из списка идут напрямую, минуя VPN-туннель.")
                .font(.inter(12))
                .foregroundColor(c.textSecondary)
                .lineSpacing(3)
                .fixedSize(horizontal: false, vertical: true)

            HStack(spacing: 6) {
                Image(systemName: "info.circle")
                    .font(.system(size: 11))
                    .foregroundColor(c.accentLight)
                Text("iOS не поддерживает исключения по приложениям — только по доменам")
                    .font(.inter(11))
                    .foregroundColor(c.textMuted)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
        .padding(14)
        .glassCard(c)
    }

    private var modeToggle: some View {
        HStack(spacing: 4) {
            modeChip(label: "Обход",    active: !settings.isWhitelist) { settings.isWhitelist = false }
            modeChip(label: "Только VPN", active: settings.isWhitelist)  { settings.isWhitelist = true  }
        }
    }

    @ViewBuilder
    private func modeChip(label: String, active: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(label)
                .font(.inter(11, weight: .semibold))
                .foregroundColor(active ? c.onAccent : c.textSecondary)
                .padding(.horizontal, 10)
                .padding(.vertical, 5)
                .background(active ? c.accent : c.border, in: Capsule())
        }
        .buttonStyle(.plain)
    }

    // MARK: Presets section ───────────────────────────────────────────────────

    private var presetsSection: some View {
        VStack(alignment: .leading, spacing: 6) {
            sectionHeader("ЧАСТО ИСПОЛЬЗУЕМЫЕ")
            ForEach(presetGroups) { group in
                presetGroupCard(group)
            }
        }
    }

    private func presetGroupCard(_ group: DomainGroup) -> some View {
        let isExpanded  = expandedGroups.contains(group.category)
        let activeCount = group.items.filter { excludedSet.contains($0.host) }.count

        return VStack(spacing: 0) {
            // ── Accordion header ──
            Button {
                withAnimation(.easeInOut(duration: 0.22)) {
                    if isExpanded { expandedGroups.remove(group.category) }
                    else          { expandedGroups.insert(group.category) }
                }
            } label: {
                HStack(spacing: 8) {
                    Text(group.icon)
                        .font(.system(size: 15))
                    Text(group.category)
                        .font(.inter(13, weight: .medium))
                        .foregroundColor(c.textPrimary)
                    Spacer()
                    if activeCount > 0 {
                        Text("\(activeCount)/\(group.items.count)")
                            .font(.inter(11, weight: .semibold))
                            .foregroundColor(c.accent)
                            .padding(.horizontal, 7).padding(.vertical, 2)
                            .background(c.accent.opacity(0.15), in: Capsule())
                    }
                    Image(systemName: isExpanded ? "chevron.up" : "chevron.down")
                        .font(.system(size: 11, weight: .medium))
                        .foregroundColor(c.textMuted)
                }
                .padding(.horizontal, 14).padding(.vertical, 11)
            }
            .buttonStyle(.plain)

            if isExpanded {
                Divider().background(c.border).padding(.horizontal, 14)
                VStack(spacing: 0) {
                    ForEach(group.items) { preset in
                        domainRow(
                            name:   preset.name,
                            host:   preset.host,
                            isLast: preset.id == group.items.last?.id
                        )
                    }
                }
            }
        }
        .glassCard(c)
    }

    // MARK: Custom section ────────────────────────────────────────────────────

    private var customSection: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack {
                sectionHeader("МОИ ДОМЕНЫ")
                Spacer()
                if !customDomains.isEmpty {
                    Text("\(customDomains.count)")
                        .font(.inter(11, weight: .semibold))
                        .foregroundColor(c.accent)
                }
            }

            if customDomains.isEmpty {
                addPlaceholderButton
            } else {
                VStack(spacing: 0) {
                    ForEach(Array(customDomains.enumerated()), id: \.element) { idx, domain in
                        HStack(spacing: 10) {
                            Image(systemName: "globe")
                                .font(.system(size: 12))
                                .foregroundColor(c.textMuted)
                                .frame(width: 22)
                            Text(domain)
                                .font(.inter(13))
                                .foregroundColor(c.textPrimary)
                                .lineLimit(1)
                            Spacer()
                            // Toggle
                            checkmark(on: excludedSet.contains(domain)) {
                                toggleDomain(domain)
                            }
                            // Delete
                            Button { removeDomain(domain) } label: {
                                Image(systemName: "xmark.circle.fill")
                                    .font(.system(size: 17))
                                    .foregroundColor(c.textMuted.opacity(0.7))
                            }
                            .buttonStyle(.plain)
                        }
                        .padding(.horizontal, 14).padding(.vertical, 10)

                        if idx < customDomains.count - 1 {
                            Divider().background(c.border).padding(.horizontal, 14)
                        }
                    }
                }
                .glassCard(c)
            }
        }
    }

    private var addPlaceholderButton: some View {
        Button {
            newDomainText = ""; addError = nil; showAddSheet = true
        } label: {
            HStack(spacing: 8) {
                Image(systemName: "plus.circle")
                    .foregroundColor(c.accent)
                Text("Добавить свой домен...")
                    .font(.inter(13))
                    .foregroundColor(c.textSecondary)
                Spacer()
            }
            .padding(14)
            .glassCard(c)
        }
        .buttonStyle(.plain)
    }

    // MARK: Info note ─────────────────────────────────────────────────────────

    private var infoNote: some View {
        HStack(alignment: .top, spacing: 10) {
            Image(systemName: "shield.lefthalf.filled")
                .font(.system(size: 15))
                .foregroundColor(c.accentLight)
                .padding(.top, 1)
            Text("Изменения применяются при следующем подключении. Переподключитесь, чтобы настройки вступили в силу.")
                .font(.inter(12))
                .foregroundColor(c.textMuted)
                .lineSpacing(3)
                .fixedSize(horizontal: false, vertical: true)
        }
        .padding(13)
        .background(c.accentLight.opacity(0.08), in: RoundedRectangle(cornerRadius: SkyflowRadius.card))
    }

    // MARK: Add-domain sheet ──────────────────────────────────────────────────

    private var addSheet: some View {
        NavigationView {
            ZStack {
                c.background.ignoresSafeArea()
                VStack(alignment: .leading, spacing: 18) {
                    VStack(alignment: .leading, spacing: 8) {
                        Text("Домен или IP-адрес")
                            .font(.inter(13, weight: .medium))
                            .foregroundColor(c.textSecondary)
                            .padding(.horizontal, 20)

                        HStack {
                            TextField("example.com", text: $newDomainText)
                                .font(.inter(15))
                                .foregroundColor(c.textPrimary)
                                .autocapitalization(.none)
                                .autocorrectionDisabled()
                                .keyboardType(.URL)
                                .onSubmit { commitNewDomain() }

                            if !newDomainText.isEmpty {
                                Button { newDomainText = "" } label: {
                                    Image(systemName: "xmark.circle.fill")
                                        .foregroundColor(c.textMuted)
                                }
                            }
                        }
                        .padding(13)
                        .background(c.glassSurface, in: RoundedRectangle(cornerRadius: SkyflowRadius.field))
                        .overlay(
                            RoundedRectangle(cornerRadius: SkyflowRadius.field)
                                .stroke(addError != nil ? Color.red.opacity(0.6) : c.border, lineWidth: 1)
                        )
                        .padding(.horizontal, 20)

                        if let err = addError {
                            Text(err)
                                .font(.inter(12))
                                .foregroundColor(.red.opacity(0.8))
                                .padding(.horizontal, 22)
                        }
                    }

                    Button(action: commitNewDomain) {
                        Text("Добавить")
                            .font(.inter(15, weight: .semibold))
                            .foregroundColor(.white)
                            .frame(maxWidth: .infinity).frame(height: 50)
                            .background(
                                newDomainText.trimmingCharacters(in: .whitespaces).isEmpty
                                    ? c.accent.opacity(0.4) : c.accent,
                                in: RoundedRectangle(cornerRadius: SkyflowRadius.button)
                            )
                    }
                    .disabled(newDomainText.trimmingCharacters(in: .whitespaces).isEmpty)
                    .padding(.horizontal, 20)

                    Spacer()
                }
                .padding(.top, 24)
            }
            .navigationTitle("Новый домен")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .navigationBarLeading) {
                    Button("Отмена") { showAddSheet = false }
                        .foregroundColor(c.accent)
                }
            }
        }
        .presentationDetents([.fraction(0.42)])
        .presentationDragIndicator(.visible)
    }

    // MARK: - Shared subviews ─────────────────────────────────────────────────

    private func domainRow(name: String, host: String, isLast: Bool) -> some View {
        let isOn = excludedSet.contains(host)
        return VStack(spacing: 0) {
            HStack(spacing: 10) {
                VStack(alignment: .leading, spacing: 1) {
                    Text(name)
                        .font(.inter(13, weight: .medium))
                        .foregroundColor(c.textPrimary)
                    Text(host)
                        .font(.inter(11))
                        .foregroundColor(c.textMuted)
                }
                Spacer()
                checkmark(on: isOn) { toggleDomain(host) }
            }
            .padding(.horizontal, 14).padding(.vertical, 9)
            if !isLast {
                Divider().background(c.border).padding(.horizontal, 14)
            }
        }
    }

    @ViewBuilder
    private func checkmark(on: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            ZStack {
                RoundedRectangle(cornerRadius: 5)
                    .fill(on ? c.accent : Color.clear)
                    .frame(width: 20, height: 20)
                RoundedRectangle(cornerRadius: 5)
                    .stroke(on ? c.accent : c.border, lineWidth: 1.5)
                    .frame(width: 20, height: 20)
                if on {
                    Image(systemName: "checkmark")
                        .font(.system(size: 12, weight: .bold))
                        .foregroundColor(c.onAccent)
                }
            }
        }
        .buttonStyle(.plain)
    }

    private func sectionHeader(_ text: String) -> some View {
        Text(text)
            .font(.inter(11, weight: .semibold))
            .foregroundColor(c.textMuted)
            .tracking(0.5)
    }

    // MARK: - Logic ────────────────────────────────────────────────────────────

    private func toggleDomain(_ host: String) {
        var set = excludedSet
        if set.contains(host) { set.remove(host) } else { set.insert(host) }
        settings.excludedDomains = set.joined(separator: ",")
    }

    private func removeDomain(_ host: String) {
        var set = excludedSet
        set.remove(host)
        settings.excludedDomains = set.joined(separator: ",")
    }

    private func commitNewDomain() {
        var raw = newDomainText
            .trimmingCharacters(in: .whitespaces)
            .lowercased()
        guard !raw.isEmpty else { return }

        // Strip URL scheme if user pasted a full URL
        for scheme in ["https://", "http://"] {
            if raw.hasPrefix(scheme) { raw = String(raw.dropFirst(scheme.count)) }
        }
        // Drop path
        raw = raw.components(separatedBy: "/").first ?? raw
        // Drop port
        raw = raw.components(separatedBy: ":").first ?? raw

        let parts = raw.split(separator: ".")
        guard parts.count >= 2, parts.allSatisfy({ !$0.isEmpty }) else {
            addError = "Введите корректный домен, например: example.com"
            return
        }
        guard !excludedSet.contains(raw) else {
            addError = "Этот домен уже в списке"
            return
        }
        toggleDomain(raw)
        showAddSheet    = false
        newDomainText   = ""
        addError        = nil
    }

    private func pluralSuffix(_ n: Int) -> String {
        let mod10  = n % 10
        let mod100 = n % 100
        if mod10 == 1 && mod100 != 11            { return "" }
        if mod10 >= 2 && mod10 <= 4 && !(mod100 >= 12 && mod100 <= 14) { return "а" }
        return "ов"
    }
}

// MARK: - Glass card modifier

private extension View {
    func glassCard(_ c: SkyflowColors.Palette) -> some View {
        self
            .background(c.glassSurface, in: RoundedRectangle(cornerRadius: SkyflowRadius.card))
            .overlay(
                RoundedRectangle(cornerRadius: SkyflowRadius.card)
                    .stroke(c.border, lineWidth: 0.5)
            )
    }
}
