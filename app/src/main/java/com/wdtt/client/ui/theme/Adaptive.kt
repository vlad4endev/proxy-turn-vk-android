package com.wdtt.client.ui.theme

import android.content.res.Configuration
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

enum class ScreenSize {
    COMPACT,
    MEDIUM,
    EXPANDED,
}

object AdaptiveLayout {
    val MaxContentWidth = 560.dp
    val MaxNavBarWidth = 520.dp
    val MaxDialogWidth = 480.dp
    val MinTouchTarget = 48.dp
    val SettingsRowMinHeight = 56.dp
    val TrafficCardMinHeight = 80.dp
}

@Composable
fun rememberScreenSize(): ScreenSize {
    val config = LocalConfiguration.current
    return remember(config.screenWidthDp) {
        when {
            config.screenWidthDp < 360 -> ScreenSize.COMPACT
            config.screenWidthDp < 600 -> ScreenSize.MEDIUM
            else -> ScreenSize.EXPANDED
        }
    }
}

@Composable
fun adaptivePadding(): Dp {
    return when (rememberScreenSize()) {
        ScreenSize.COMPACT -> 12.dp
        ScreenSize.MEDIUM -> 16.dp
        ScreenSize.EXPANDED -> 24.dp
    }
}

@Composable
fun adaptiveButtonSize(): Dp {
    return when (rememberScreenSize()) {
        ScreenSize.COMPACT -> 140.dp
        ScreenSize.MEDIUM -> 180.dp
        ScreenSize.EXPANDED -> 220.dp
    }
}

@Composable
fun adaptiveIconSize(): Dp {
    return when (rememberScreenSize()) {
        ScreenSize.COMPACT -> 56.dp
        ScreenSize.MEDIUM -> 72.dp
        ScreenSize.EXPANDED -> 88.dp
    }
}

@Immutable
data class ScreenMetrics(
    val widthDp: Int,
    val heightDp: Int,
    val isLandscape: Boolean,
    val isCompactHeight: Boolean,
    val isWide: Boolean,
    val isVerySmall: Boolean,
    val screenSize: ScreenSize,
) {
    val contentHorizontalPadding: Dp
        get() = when (screenSize) {
            ScreenSize.COMPACT -> 12.dp
            ScreenSize.MEDIUM -> 16.dp
            ScreenSize.EXPANDED -> 24.dp
        }

    val onboardingArtSize: Dp
        get() = when {
            isVerySmall || isCompactHeight -> 120.dp
            isWide -> 148.dp
            else -> 168.dp
        }

    val onboardingGlowSize: Dp
        get() = when {
            isVerySmall || isCompactHeight -> 240.dp
            else -> 320.dp
        }
}

@Composable
fun rememberScreenMetrics(): ScreenMetrics {
    val config = LocalConfiguration.current
    val screenSize = rememberScreenSize()
    return remember(config.screenWidthDp, config.screenHeightDp, config.orientation, screenSize) {
        val width = config.screenWidthDp
        val height = config.screenHeightDp
        val landscape = config.orientation == Configuration.ORIENTATION_LANDSCAPE
        ScreenMetrics(
            widthDp = width,
            heightDp = height,
            isLandscape = landscape,
            isCompactHeight = height < 520 || landscape,
            isWide = width >= 600,
            isVerySmall = height < 420 || width < 320,
            screenSize = screenSize,
        )
    }
}

fun Modifier.adaptiveContentWidth(maxWidth: Dp = AdaptiveLayout.MaxContentWidth): Modifier =
    fillMaxWidth().widthIn(max = maxWidth)

/** Safe area: notch, dynamic island, navigation bar, IME. */
fun Modifier.adaptiveScreenInsets(): Modifier = this
    .statusBarsPadding()
    .navigationBarsPadding()
    .imePadding()

@Composable
fun AdaptiveContentHost(
    modifier: Modifier = Modifier,
    maxWidth: Dp = AdaptiveLayout.MaxContentWidth,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter,
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .adaptiveContentWidth(maxWidth),
            content = content,
        )
    }
}
