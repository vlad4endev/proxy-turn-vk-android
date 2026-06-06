package com.wdtt.client.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.googlefonts.Font
import androidx.compose.ui.text.googlefonts.GoogleFont
import androidx.compose.ui.text.googlefonts.GoogleFont.Provider
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wdtt.client.R

object SkyflowColors {
    val Background = Color(0xFF0D0B1A)
    val Surface = Color(0xFF13112A)
    val SurfaceHigh = Color(0xFF1A1838)
    val Border = Color(0xFF1E1C3A)
    val BorderAccent = Color(0xFF2A2850)
    val Accent = Color(0xFF6366F1)
    val AccentLight = Color(0xFF818CF8)
    val AccentMuted = Color(0x206366F1)
    val Idle = Color(0xFF3D3B6A)
    val Connecting = Color(0xFFF59E0B)
    val Connected = Color(0xFF4ADE80)
    val ConnectedDark = Color(0xFF166534)
    val TextPrimary = Color(0xFFD4D4D8)
    val TextSecondary = Color(0xFF6B7280)
    val TextMuted = Color(0xFF374151)
    val TextAccent = Color(0xFFA5B4FC)
    val ErrorColor = Color(0xFFF87171)
    val WarnColor = Color(0xFFF59E0B)
    val NavBg = Color(0xFF0A0912)
    val OnAccent = Color(0xFFFFFFFF)
    val LogoGradientTop = Color(0xFFFFFFFF)
    val LogoGradientMid = Color(0xFF93C5FD)
    val LogoGradientBottom = Color(0xFF3B82F6)
    val BrandTitle = Color(0xFFF4F4F5)
    val RelayStripe = Color(0xFF34D399)
    val VkStripe = Color(0xFF60A5FA)
    val TrafficDown = Color(0xFF60A5FA)
    val TrafficDownBar = Color(0xFF3B82F6)
    val NodeIdle = Color(0xFF2D2B52)
    val Placeholder = Color(0xFF3F3F5A)
    val CheckboxBorder = Color(0xFF2D2B52)
    val AuthorAvatarBg = Color(0xFF1E1B4B)
    val YandexTag = Color(0xFFFBBF24)
}

object SkyflowShapes {
    val Card = RoundedCornerShape(11.dp)
    val Field = RoundedCornerShape(14.dp)
    val Button = RoundedCornerShape(24.dp)
    val Chip = RoundedCornerShape(10.dp)
    val Tag = RoundedCornerShape(6.dp)
    val Circle = CircleShape
    val LogEntry = RoundedCornerShape(8.dp)
    val AppCard = RoundedCornerShape(8.dp)
    val SearchField = RoundedCornerShape(8.dp)
    val ModeButton = RoundedCornerShape(7.dp)
    val Logo = RoundedCornerShape(16.dp)
    val VersionBadge = RoundedCornerShape(12.dp)
    val LogTag = RoundedCornerShape(4.dp)
    val Checkbox = RoundedCornerShape(4.dp)
    val PasteButton = RoundedCornerShape(5.dp)
}

object SkyflowBorders {
    val Default = BorderStroke(0.5.dp, SkyflowColors.Border)
    val Accent = BorderStroke(0.5.dp, SkyflowColors.BorderAccent)
    val Focus = BorderStroke(1.dp, SkyflowColors.Accent)
    val Success = BorderStroke(0.5.dp, SkyflowColors.ConnectedDark)
    val Warning = BorderStroke(0.5.dp, Color(0xFF78350F))
    val Error = BorderStroke(0.5.dp, Color(0xFF7F1D1D))
    val Logo = BorderStroke(1.5.dp, SkyflowColors.Accent.copy(alpha = 0.5f))
    val AuthorAvatar = BorderStroke(1.dp, SkyflowColors.Accent.copy(alpha = 0.4f))
}

private val googleFontProvider = Provider(
    providerAuthority = "com.google.android.gms.fonts",
    providerPackage = "com.google.android.gms",
    certificates = R.array.com_google_android_gms_fonts_certs
)

private val interGoogleFont = GoogleFont("Inter")

val interFontFamily = FontFamily(
    Font(googleFont = interGoogleFont, fontProvider = googleFontProvider, weight = FontWeight.Normal),
    Font(googleFont = interGoogleFont, fontProvider = googleFontProvider, weight = FontWeight.Medium),
    Font(googleFont = interGoogleFont, fontProvider = googleFontProvider, weight = FontWeight.SemiBold),
    Font(googleFont = interGoogleFont, fontProvider = googleFontProvider, weight = FontWeight.Bold),
)

object SkyflowTypography {
    val labelUppercase = TextStyle(
        fontFamily = interFontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 8.sp,
        letterSpacing = 0.8.sp,
    )
}
