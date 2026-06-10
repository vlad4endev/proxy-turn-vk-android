// DesignSystem.swift — SKYFLOW VPN iOS
// Mirrors Android DesignSystem.kt (SkyflowColors, SkyflowShapes)

import SwiftUI

// MARK: - Theme

final class SkyflowTheme: ObservableObject {
    @Published var isDark: Bool = true
    static let shared = SkyflowTheme()
}

// MARK: - Colors

struct SkyflowColors {

    // Reads current theme from environment — call inside View body only.
    static func resolve(_ dark: Bool) -> Palette { Palette(dark: dark) }

    struct Palette {
        let dark: Bool

        var background:          Color { dark ? Color(hex: 0x06060F) : Color(hex: 0xF4F4FF) }
        var surface:             Color { dark ? Color(hex: 0x12121F) : .white }
        var surfaceHigh:         Color { dark ? Color(hex: 0x1A1A2E) : Color(hex: 0xEEEEFF) }
        var glassSurface:        Color { dark ? Color(hex: 0x1C1C30, alpha: 0.72) : Color(hex: 0xEEEEF8) }
        var glassSurfaceElevated:Color { dark ? Color(hex: 0x242438, alpha: 0.85) : .white }
        var border:              Color { dark ? Color(hex: 0x2A2A42) : Color(hex: 0xC0C0D8) }
        var borderAccent:        Color { dark ? Color(hex: 0x3D3D5C) : Color(hex: 0x9090B8) }

        var accent:              Color { Color(hex: 0x7C3AED) }
        var accentLight:         Color { dark ? Color(hex: 0xA78BFA) : Color(hex: 0x6D28D9) }
        var accentMuted:         Color { Color(hex: 0x7C3AED, alpha: 0.16) }

        var idle:                Color { dark ? Color(hex: 0x4B4B6A) : Color(hex: 0xAAAACC) }
        var connecting:          Color { Color(hex: 0xFBBF24) }
        var connected:           Color { dark ? Color(hex: 0x34D399) : Color(hex: 0x059669) }
        var captchaBlue:         Color { dark ? Color(hex: 0x3B82F6) : Color(hex: 0x2563EB) }
        var warnColor:           Color { Color(hex: 0xF59E0B) }
        var errorColor:          Color { Color(hex: 0xEF4444) }

        var textPrimary:         Color { dark ? Color(hex: 0xF4F4F5) : Color(hex: 0x0D0D1A) }
        var textSecondary:       Color { dark ? Color(hex: 0x9CA3AF) : Color(hex: 0x4B5563) }
        var textMuted:           Color { dark ? Color(hex: 0x6B7280) : Color(hex: 0x9CA3AF) }
        var textAccent:          Color { dark ? Color(hex: 0xC4B5FD) : Color(hex: 0x5B21B6) }
        var onAccent:            Color { .white }
        var placeholder:         Color { dark ? Color(hex: 0x4B4B6A) : Color(hex: 0xAAAAAA) }

        var trafficDown:         Color { Color(hex: 0x34D399) }
        var vkStripe:            Color { Color(hex: 0x4A90E2) }
    }
}

// MARK: - Corner Radii

struct SkyflowRadius {
    static let card:   CGFloat = 16
    static let chip:   CGFloat = 12
    static let button: CGFloat = 14
    static let field:  CGFloat = 12
    static let tag:    CGFloat = 6
    static let logo:   CGFloat = 18
}

// MARK: - Typography

extension Font {
    static func inter(_ size: CGFloat, weight: Font.Weight = .regular) -> Font {
        .custom("Inter", size: size).weight(weight)
    }
}

// MARK: - Color extension

extension Color {
    init(hex: UInt, alpha: Double = 1) {
        self.init(
            .sRGB,
            red:   Double((hex >> 16) & 0xFF) / 255,
            green: Double((hex >> 8)  & 0xFF) / 255,
            blue:  Double( hex        & 0xFF) / 255,
            opacity: alpha
        )
    }
}

// MARK: - Environment Key

private struct ColorPaletteKey: EnvironmentKey {
    static let defaultValue = SkyflowColors.Palette(dark: true)
}

extension EnvironmentValues {
    var skyflow: SkyflowColors.Palette {
        get { self[ColorPaletteKey.self] }
        set { self[ColorPaletteKey.self] = newValue }
    }
}

// MARK: - Theme modifier

struct SkyflowThemeModifier: ViewModifier {
    @ObservedObject var theme = SkyflowTheme.shared
    func body(content: Content) -> some View {
        content
            .environment(\.skyflow, SkyflowColors.resolve(theme.isDark))
            .preferredColorScheme(theme.isDark ? .dark : .light)
    }
}

extension View {
    func skyflowTheme() -> some View {
        modifier(SkyflowThemeModifier())
    }
}
