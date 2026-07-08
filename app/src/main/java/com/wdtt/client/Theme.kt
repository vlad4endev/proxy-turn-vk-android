package com.wdtt.client

import android.os.Build
import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.wdtt.client.ui.theme.SkyflowTypography
import com.wdtt.client.ui.setSkyflowDarkTheme

val WDTTTypography = SkyflowTypography

// ═══ Тёмная схема Material 3 (фоновые цвета, кнопки, поля) ═══════════════════
private val DarkColorScheme = darkColorScheme(
    primary            = Color(0xFF7C3AED),
    onPrimary          = Color(0xFFFFFFFF),
    primaryContainer   = Color(0xFF4A1D96),
    onPrimaryContainer = Color(0xFFEDE9FE),
    secondary          = Color(0xFFA78BFA),
    onSecondary        = Color(0xFF1E1B4B),
    secondaryContainer = Color(0xFF312E81),
    onSecondaryContainer = Color(0xFFE0E7FF),
    background         = Color(0xFF06060F),
    onBackground       = Color(0xFFF4F4F5),
    surface            = Color(0xFF12121F),
    onSurface          = Color(0xFFF4F4F5),
    surfaceVariant     = Color(0xFF1A1A2E),
    onSurfaceVariant   = Color(0xFF9CA3AF),
    outline            = Color(0xFF3D3D5C),
    outlineVariant     = Color(0xFF2A2A42),
    error              = Color(0xFFF87171),
    onError            = Color(0xFF7F1D1D),
    errorContainer     = Color(0xFF991B1B),
    onErrorContainer   = Color(0xFFFFDAD6),
    inverseSurface     = Color(0xFFEDE9FE),
    inverseOnSurface   = Color(0xFF1E1B4B),
    inversePrimary     = Color(0xFF7C3AED),
    surfaceTint        = Color(0xFF7C3AED),
)

// ═══ Светлая схема Material 3 ════════════════════════════════════════════════
private val LightColorScheme = lightColorScheme(
    primary            = Color(0xFF7C3AED),
    onPrimary          = Color(0xFFFFFFFF),
    primaryContainer   = Color(0xFFEDE9FE),
    onPrimaryContainer = Color(0xFF4A1D96),
    secondary          = Color(0xFF6D28D9),
    onSecondary        = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFDDD6FE),
    onSecondaryContainer = Color(0xFF3B0764),
    background         = Color(0xFFF4F4FF),
    onBackground       = Color(0xFF0D0D1A),
    surface            = Color(0xFFFFFFFF),
    onSurface          = Color(0xFF0D0D1A),
    surfaceVariant     = Color(0xFFEEEEFF),
    onSurfaceVariant   = Color(0xFF4B5563),
    outline            = Color(0xFFAAAACC),
    outlineVariant     = Color(0xFFDDDDEE),
    error              = Color(0xFFDC2626),
    onError            = Color(0xFFFFFFFF),
    errorContainer     = Color(0xFFFFDAD6),
    onErrorContainer   = Color(0xFF410002),
    inverseSurface     = Color(0xFF1A1A2E),
    inverseOnSurface   = Color(0xFFF4F4FF),
    inversePrimary     = Color(0xFFA78BFA),
    surfaceTint        = Color(0xFF7C3AED),
)


@Composable
fun WDTTTheme(
    themeMode: String = "system",
    content: @Composable () -> Unit
) {
    val darkTheme = when (themeMode) {
        "dark"  -> true
        "light" -> false
        else    -> isSystemInDarkTheme()
    }

    // Обновляем глобальный стейт — все SkyflowColors.* перерисуются
    setSkyflowDarkTheme(darkTheme)

    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            WindowCompat.setDecorFitsSystemWindows(window, false)
            val navigationBarColor = if (darkTheme) {
                Color.Transparent
            } else {
                lerp(colorScheme.background, colorScheme.surface, 0.55f)
            }
            window.statusBarColor  = Color.Transparent.toArgb()
            window.navigationBarColor = navigationBarColor.toArgb()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                window.isNavigationBarContrastEnforced = false
                window.isStatusBarContrastEnforced     = false
            }
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars     = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography  = SkyflowTypography,
        content     = content,
    )
}
