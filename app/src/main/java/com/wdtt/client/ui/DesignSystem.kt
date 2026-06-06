package com.wdtt.client.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
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
    // ── Base ──────────────────────────────────────────────────────────────
    val Background = Color(0xFF06060F)
    val Surface = Color(0xFF12121F)
    val SurfaceHigh = Color(0xFF1A1A2E)
    val GlassSurface = Color(0xFF1C1C30).copy(alpha = 0.72f)
    val GlassSurfaceElevated = Color(0xFF242438).copy(alpha = 0.85f)
    val Border = Color(0xFF2A2A42)
    val BorderAccent = Color(0xFF3D3D5C)
    val BorderGlow = Color(0xFF6366F1).copy(alpha = 0.35f)

    // ── Accent ────────────────────────────────────────────────────────────
    val Accent = Color(0xFF7C3AED)
    val AccentLight = Color(0xFFA78BFA)
    val AccentMuted = Color(0x287C3AED)
    val AccentGlow = Color(0xFF7C3AED).copy(alpha = 0.22f)

    // ── Status ────────────────────────────────────────────────────────────
    val Idle = Color(0xFF4B4B6A)
    val Connecting = Color(0xFFFBBF24)
    val Connected = Color(0xFF34D399)
    val ConnectedDark = Color(0xFF065F46)

    // ── Text ──────────────────────────────────────────────────────────────
    val TextPrimary = Color(0xFFF4F4F5)
    val TextSecondary = Color(0xFF9CA3AF)
    val TextMuted = Color(0xFF6B7280)
    val TextAccent = Color(0xFFC4B5FD)

    // ── Semantic ──────────────────────────────────────────────────────────
    val ErrorColor = Color(0xFFF87171)
    val WarnColor = Color(0xFFFBBF24)

    // ── Nav ───────────────────────────────────────────────────────────────
    val NavBg = Color(0xFF0E0E1A).copy(alpha = 0.88f)
    val NavBorder = Color(0xFF2A2A42).copy(alpha = 0.6f)
    val OnAccent = Color(0xFFFFFFFF)

    // ── Brand ─────────────────────────────────────────────────────────────
    val LogoGradientTop = Color(0xFFFFFFFF)
    val LogoGradientMid = Color(0xFFA78BFA)
    val LogoGradientBottom = Color(0xFF7C3AED)
    val BrandTitle = Color(0xFFF4F4F5)

    // ── Traffic / providers ─────────────────────────────────────────────────
    val RelayStripe = Color(0xFF34D399)
    val VkStripe = Color(0xFF60A5FA)
    val TrafficDown = Color(0xFF38BDF8)
    val TrafficDownBar = Color(0xFF0EA5E9)
    val NodeIdle = Color(0xFF2D2B52)
    val Placeholder = Color(0xFF52526A)
    val CheckboxBorder = Color(0xFF3D3D5C)
    val AuthorAvatarBg = Color(0xFF1E1B4B)
    val YandexTag = Color(0xFFFBBF24)
}

object SkyflowGradients {
    val Accent = Brush.linearGradient(
        colors = listOf(SkyflowColors.Accent, Color(0xFF6366F1))
    )
    val Connected = Brush.linearGradient(
        colors = listOf(Color(0xFF34D399), Color(0xFF06B6D4))
    )
    val Surface = Brush.verticalGradient(
        colors = listOf(
            SkyflowColors.GlassSurfaceElevated,
            SkyflowColors.GlassSurface
        )
    )
    val PowerIdle = Brush.radialGradient(
        colors = listOf(
            SkyflowColors.SurfaceHigh,
            SkyflowColors.Background
        )
    )
    val PowerConnected = Brush.radialGradient(
        colors = listOf(
            SkyflowColors.Connected.copy(alpha = 0.18f),
            Color.Transparent
        )
    )
    val PowerConnecting = Brush.radialGradient(
        colors = listOf(
            SkyflowColors.Connecting.copy(alpha = 0.15f),
            Color.Transparent
        )
    )
}

object SkyflowShapes {
    val Card = RoundedCornerShape(20.dp)
    val Field = RoundedCornerShape(20.dp)
    val Button = RoundedCornerShape(28.dp)
    val Chip = RoundedCornerShape(14.dp)
    val Tag = RoundedCornerShape(8.dp)
    val Circle = CircleShape
    val LogEntry = RoundedCornerShape(10.dp)
    val AppCard = RoundedCornerShape(12.dp)
    val SearchField = RoundedCornerShape(14.dp)
    val ModeButton = RoundedCornerShape(10.dp)
    val Logo = RoundedCornerShape(20.dp)
    val VersionBadge = RoundedCornerShape(14.dp)
    val LogTag = RoundedCornerShape(6.dp)
    val Checkbox = RoundedCornerShape(6.dp)
    val PasteButton = RoundedCornerShape(8.dp)
    val NavBar = RoundedCornerShape(32.dp)
    val NavIndicator = RoundedCornerShape(24.dp)
}

object SkyflowBorders {
    val Default = BorderStroke(0.5.dp, SkyflowColors.Border.copy(alpha = 0.7f))
    val Accent = BorderStroke(0.5.dp, SkyflowColors.BorderAccent)
    val Glass = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f))
    val GlassAccent = BorderStroke(1.dp, SkyflowColors.Accent.copy(alpha = 0.25f))
    val Focus = BorderStroke(1.5.dp, SkyflowColors.Accent)
    val Success = BorderStroke(0.5.dp, SkyflowColors.ConnectedDark)
    val Warning = BorderStroke(0.5.dp, Color(0xFF78350F))
    val Error = BorderStroke(0.5.dp, Color(0xFF7F1D1D))
    val Logo = BorderStroke(1.5.dp, SkyflowColors.Accent.copy(alpha = 0.45f))
    val AuthorAvatar = BorderStroke(1.dp, SkyflowColors.Accent.copy(alpha = 0.4f))
}

@Composable
fun GlassSurface(
    modifier: Modifier = Modifier,
    shape: Shape = SkyflowShapes.Card,
    border: BorderStroke? = SkyflowBorders.Glass,
    elevated: Boolean = false,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        modifier = modifier,
        shape = shape,
        color = if (elevated) SkyflowColors.GlassSurfaceElevated else SkyflowColors.GlassSurface,
        border = border,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
        content = {
            Column(content = content)
        }
    )
}

@Composable
fun AccentGlow(
    modifier: Modifier = Modifier,
    color: Color = SkyflowColors.AccentGlow,
    intensity: Float = 1f
) {
    Box(
        modifier = modifier
            .clip(CircleShape)
            .background(
                Brush.radialGradient(
                    colors = listOf(
                        color.copy(alpha = color.alpha * intensity),
                        Color.Transparent
                    )
                )
            )
    )
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

object SkyflowTextStyles {
    val labelUppercase = TextStyle(
        fontFamily = interFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 12.sp,
        letterSpacing = 1.2.sp,
    )
    val statusTitle = TextStyle(
        fontFamily = interFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
    )
    val cardTitle = TextStyle(
        fontFamily = interFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
    )
}
