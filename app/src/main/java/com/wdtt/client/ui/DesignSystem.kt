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
import androidx.compose.runtime.mutableStateOf
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

// ── Глобальный стейт темы (обновляется из WDTTTheme) ──────────────────────────
// Compose отслеживает чтение этого стейта и перерисовывает
// все composable, которые обращаются к SkyflowColors.*.
internal val _skyflowDarkTheme = mutableStateOf(true)

/** Установить текущий режим (вызывается из WDTTTheme). */
fun setSkyflowDarkTheme(isDark: Boolean) {
    _skyflowDarkTheme.value = isDark
}

object SkyflowColors {
    private val dark get() = _skyflowDarkTheme.value

    // ── Base ──────────────────────────────────────────────────────────────
    val Background get() = if (dark) Color(0xFF06060F) else Color(0xFFF4F4FF)
    val Surface    get() = if (dark) Color(0xFF12121F) else Color(0xFFFFFFFF)
    val SurfaceHigh get() = if (dark) Color(0xFF1A1A2E) else Color(0xFFEEEEFF)

    val GlassSurface get() =
        if (dark) Color(0xFF1C1C30).copy(alpha = 0.72f)
        else Color(0xFFEEEEF8)            // opaque card — no transparency in light mode

    val GlassSurfaceElevated get() =
        if (dark) Color(0xFF242438).copy(alpha = 0.85f)
        else Color(0xFFFFFFFF)            // pure white elevated card

    val Border get() =
        if (dark) Color(0xFF2A2A42) else Color(0xFFC0C0D8)  // darker, clearly visible

    val BorderAccent get() =
        if (dark) Color(0xFF3D3D5C) else Color(0xFF9090B8)  // darker accent border

    val BorderGlow get() =
        if (dark) Color(0xFF7C3AED).copy(alpha = 0.35f)
        else Color(0xFF7C3AED).copy(alpha = 0.20f)

    // ── Accent ────────────────────────────────────────────────────────────
    val Accent      = Color(0xFF7C3AED)           // одинаково в обеих темах
    val AccentLight get() =
        if (dark) Color(0xFFA78BFA) else Color(0xFF6D28D9)
    val AccentMuted = Color(0xFF7C3AED).copy(alpha = 0.16f)
    val AccentGlow  get() =
        if (dark) Color(0xFF7C3AED).copy(alpha = 0.22f)
        else Color(0xFF7C3AED).copy(alpha = 0.10f)

    // ── Status ────────────────────────────────────────────────────────────
    val Idle get() =
        if (dark) Color(0xFF4B4B6A) else Color(0xFFAAAACC)
    val Connecting  = Color(0xFFFBBF24)
    val Connected get() =
        if (dark) Color(0xFF34D399) else Color(0xFF059669)
    val ConnectedDark get() =
        if (dark) Color(0xFF065F46) else Color(0xFFA7F3D0)
    val CaptchaBlue get() =
        if (dark) Color(0xFF3B82F6) else Color(0xFF2563EB)

    // ── Text ──────────────────────────────────────────────────────────────
    val TextPrimary get() =
        if (dark) Color(0xFFF4F4F5) else Color(0xFF0D0D1A)
    val TextSecondary get() =
        if (dark) Color(0xFF9CA3AF) else Color(0xFF4B5563)
    val TextMuted get() =
        if (dark) Color(0xFF6B7280) else Color(0xFF5C6470)  // darker in light mode for readability
    val TextAccent get() =
        if (dark) Color(0xFFC4B5FD) else Color(0xFF6D28D9)

    // ── Semantic ──────────────────────────────────────────────────────────
    val ErrorColor get() =
        if (dark) Color(0xFFF87171) else Color(0xFFDC2626)
    val WarnColor get() =
        if (dark) Color(0xFFFBBF24) else Color(0xFFD97706)

    // ── Nav ───────────────────────────────────────────────────────────────
    val NavBg get() =
        if (dark) Color(0xFF0E0E1A).copy(alpha = 0.88f)
        else Color(0xFFF2F2FA)            // solid light background for nav bar
    val NavBorder get() =
        if (dark) Color(0xFF2A2A42).copy(alpha = 0.6f)
        else Color(0xFFCCCCE0)            // visible separator line
    val OnAccent = Color(0xFFFFFFFF)

    // ── Brand ─────────────────────────────────────────────────────────────
    val LogoGradientTop    = Color(0xFFFFFFFF)
    val LogoGradientMid    = Color(0xFFA78BFA)
    val LogoGradientBottom = Color(0xFF7C3AED)
    val BrandTitle get() =
        if (dark) Color(0xFFF4F4F5) else Color(0xFF0D0D1A)

    // ── Traffic / providers ───────────────────────────────────────────────
    val RelayStripe get() =
        if (dark) Color(0xFF34D399) else Color(0xFF059669)
    val VkStripe get() =
        if (dark) Color(0xFF60A5FA) else Color(0xFF2563EB)
    val TrafficDown     = Color(0xFF38BDF8)
    val TrafficDownBar  = Color(0xFF0EA5E9)
    val NodeIdle get() =
        if (dark) Color(0xFF2D2B52) else Color(0xFFE8E8FF)
    val Placeholder get() =
        if (dark) Color(0xFF52526A) else Color(0xFFAAAABB)
    val CheckboxBorder get() =
        if (dark) Color(0xFF3D3D5C) else Color(0xFFB0B0D0)
    val AuthorAvatarBg get() =
        if (dark) Color(0xFF1E1B4B) else Color(0xFFEDE9FE)
    val YandexTag get() =
        if (dark) Color(0xFFFBBF24) else Color(0xFFD97706)
}

object SkyflowGradients {
    // Тот же градиент, что и в макете: violet → violet-deep (#5B21B6).
    val Accent get() = Brush.linearGradient(
        colors = listOf(SkyflowColors.Accent, Color(0xFF5B21B6))
    )
    val Connected get() = Brush.linearGradient(
        colors = listOf(Color(0xFF34D399), Color(0xFF06B6D4))
    )
    val Surface get() = Brush.verticalGradient(
        colors = listOf(
            SkyflowColors.GlassSurfaceElevated,
            SkyflowColors.GlassSurface
        )
    )
    val PowerIdle get() = Brush.radialGradient(
        colors = listOf(
            SkyflowColors.SurfaceHigh,
            SkyflowColors.Background
        )
    )
    val PowerConnected get() = Brush.radialGradient(
        colors = listOf(
            SkyflowColors.Connected.copy(alpha = 0.18f),
            Color.Transparent
        )
    )
    val PowerConnecting get() = Brush.radialGradient(
        colors = listOf(
            SkyflowColors.Connecting.copy(alpha = 0.15f),
            Color.Transparent
        )
    )
    val PowerCaptcha get() = Brush.radialGradient(
        colors = listOf(
            SkyflowColors.CaptchaBlue.copy(alpha = 0.20f),
            Color.Transparent
        )
    )
}

object SkyflowShapes {
    val Card         = RoundedCornerShape(20.dp)
    val Field        = RoundedCornerShape(20.dp)
    val Button       = RoundedCornerShape(28.dp)
    val Chip         = RoundedCornerShape(14.dp)
    val Tag          = RoundedCornerShape(8.dp)
    val Circle       = CircleShape
    val LogEntry     = RoundedCornerShape(10.dp)
    val AppCard      = RoundedCornerShape(12.dp)
    val SearchField  = RoundedCornerShape(14.dp)
    val ModeButton   = RoundedCornerShape(10.dp)
    val Logo         = RoundedCornerShape(20.dp)
    val VersionBadge = RoundedCornerShape(14.dp)
    val LogTag       = RoundedCornerShape(6.dp)
    val Checkbox     = RoundedCornerShape(6.dp)
    val PasteButton  = RoundedCornerShape(8.dp)
    val NavBar       = RoundedCornerShape(32.dp)
    val NavIndicator = RoundedCornerShape(24.dp)
}

object SkyflowBorders {
    val Default get() = BorderStroke(0.5.dp, SkyflowColors.Border.copy(alpha = 0.7f))
    val Accent  get() = BorderStroke(0.5.dp, SkyflowColors.BorderAccent)
    // Для тёмной — тонкий белый штрих; для светлой — тонкий тёмный штрих
    val Glass   get() = BorderStroke(1.dp,
        if (_skyflowDarkTheme.value) Color.White.copy(alpha = 0.08f)
        else Color.Black.copy(alpha = 0.07f)
    )
    val GlassAccent get() = BorderStroke(1.dp, SkyflowColors.Accent.copy(alpha = 0.28f))
    val Focus   get() = BorderStroke(1.5.dp, SkyflowColors.Accent)
    val Success get() = BorderStroke(0.5.dp, SkyflowColors.ConnectedDark)
    val Warning get() = BorderStroke(0.5.dp, SkyflowColors.WarnColor.copy(alpha = 0.6f))
    val Error   get() = BorderStroke(0.5.dp, SkyflowColors.ErrorColor.copy(alpha = 0.6f))
    val Logo    get() = BorderStroke(1.5.dp, SkyflowColors.Accent.copy(alpha = 0.45f))
    val AuthorAvatar get() = BorderStroke(1.dp, SkyflowColors.Accent.copy(alpha = 0.4f))
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

// Вторая гарнитура из макета (display: "Space Grotesk", body: "Inter") — для
// крупных чисел и заголовков (статус подключения, счётчик дней подписки),
// как задумано в редизайне. Тело текста остаётся на Inter.
private val spaceGroteskGoogleFont = GoogleFont("Space Grotesk")

val displayFontFamily = FontFamily(
    Font(googleFont = spaceGroteskGoogleFont, fontProvider = googleFontProvider, weight = FontWeight.Medium),
    Font(googleFont = spaceGroteskGoogleFont, fontProvider = googleFontProvider, weight = FontWeight.SemiBold),
    Font(googleFont = spaceGroteskGoogleFont, fontProvider = googleFontProvider, weight = FontWeight.Bold),
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
