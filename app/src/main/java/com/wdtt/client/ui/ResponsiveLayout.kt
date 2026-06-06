package com.wdtt.client.ui

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import com.wdtt.client.ui.theme.AdaptiveContentHost as ThemeAdaptiveContentHost
import com.wdtt.client.ui.theme.AdaptiveLayout
import com.wdtt.client.ui.theme.adaptiveContentWidth as themeAdaptiveContentWidth
import com.wdtt.client.ui.theme.rememberScreenMetrics as themeRememberScreenMetrics

typealias ResponsiveLayout = AdaptiveLayout

@Composable
fun rememberScreenMetrics() = themeRememberScreenMetrics()

fun Modifier.adaptiveContentWidth(maxWidth: Dp = AdaptiveLayout.MaxContentWidth): Modifier =
    themeAdaptiveContentWidth(maxWidth)

@Composable
fun AdaptiveContentHost(
    modifier: Modifier = Modifier,
    maxWidth: Dp = AdaptiveLayout.MaxContentWidth,
    content: @Composable BoxScope.() -> Unit,
) = ThemeAdaptiveContentHost(modifier, maxWidth, content)
