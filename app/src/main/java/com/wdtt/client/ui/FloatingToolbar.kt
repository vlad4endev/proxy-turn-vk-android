package com.wdtt.client.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wdtt.client.R
import androidx.compose.ui.graphics.Color
import kotlin.math.roundToInt

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings

@Composable
fun FloatingToolbar(
    activeProfile: Int,
    onActiveProfileChange: (Int) -> Unit,
    currentTheme: String,
    onThemeChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val screenHeightPx = remember(configuration.screenHeightDp, density) {
        with(density) { configuration.screenHeightDp.dp.toPx() }
    }
    val screenWidthPx = remember(configuration.screenWidthDp, density) {
        with(density) { configuration.screenWidthDp.dp.toPx() }
    }

    var parentWidthPx by remember { mutableFloatStateOf(0f) }
    var parentHeightPx by remember { mutableFloatStateOf(0f) }

    var offsetY by rememberSaveable { mutableFloatStateOf(-1f) }
    var isRightSide by rememberSaveable { mutableStateOf(true) }
    var isExpanded by rememberSaveable { mutableStateOf(false) }
    var tabHeightPx by remember { mutableFloatStateOf(0f) }
    var panelHeightPx by remember { mutableFloatStateOf(0f) }

    val tabWidthDp = 42.dp
    val tabHeightDp = 52.dp
    val panelWidthDp = remember(configuration.screenWidthDp) {
        (configuration.screenWidthDp - 64).coerceIn(180, 280).dp
    }

    val tabWidthPx = remember(density) { with(density) { tabWidthDp.toPx() } }
    val fallbackTabHeightPx = remember(density) { with(density) { tabHeightDp.toPx() } }
    val edgePaddingPx = remember(density) { with(density) { 8.dp.toPx() } }
    val safeTopPx = WindowInsets.safeDrawing.getTop(density).toFloat()
    val safeBottomPx = WindowInsets.safeDrawing.getBottom(density).toFloat()
    val effectiveTabHeightPx = maxOf(tabHeightPx, fallbackTabHeightPx)
    val floatingHeightPx = if (isExpanded && panelHeightPx > 0f) {
        maxOf(effectiveTabHeightPx, panelHeightPx)
    } else {
        effectiveTabHeightPx
    }
    
    val currentParentHeight = if (parentHeightPx > 0f) parentHeightPx else screenHeightPx
    val currentParentWidth = if (parentWidthPx > 0f) parentWidthPx else screenWidthPx

    val minOffsetY = safeTopPx + edgePaddingPx
    val maxOffsetY = (currentParentHeight - safeBottomPx - floatingHeightPx - edgePaddingPx)
        .coerceAtLeast(minOffsetY)
    val defaultOffsetY = (currentParentHeight * 0.24f).coerceIn(minOffsetY, maxOffsetY)

    val targetXPx = if (isRightSide) currentParentWidth - tabWidthPx else 0f

    val animatedTabXPx by animateFloatAsState(
        targetValue = targetXPx,
        animationSpec = spring(stiffness = Spring.StiffnessLow),
        label = "tab_shift"
    )

    LaunchedEffect(minOffsetY, maxOffsetY) {
        offsetY = if (offsetY < 0f) defaultOffsetY else offsetY.coerceIn(minOffsetY, maxOffsetY)
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .onGloballyPositioned { coordinates ->
                parentWidthPx = coordinates.size.width.toFloat()
                parentHeightPx = coordinates.size.height.toFloat()
            }
    ) {
        Surface(
            onClick = { isExpanded = !isExpanded },
            modifier = Modifier
                .offset { IntOffset(animatedTabXPx.roundToInt(), offsetY.roundToInt()) }
                .onGloballyPositioned { coordinates ->
                    tabHeightPx = coordinates.size.height.toFloat()
                }
                .pointerInput(minOffsetY, maxOffsetY) {
                    detectDragGestures(
                        onDrag = { change, dragAmount ->
                            change.consume()
                            offsetY = (offsetY + dragAmount.y).coerceIn(minOffsetY, maxOffsetY)
                        }
                    )
                },
            shape = if (isRightSide)
                RoundedCornerShape(topStart = 18.dp, bottomStart = 18.dp)
            else
                RoundedCornerShape(topEnd = 18.dp, bottomEnd = 18.dp),
            color = SkyflowColors.GlassSurfaceElevated,
            border = BorderStroke(1.dp, SkyflowColors.BorderGlow),
            shadowElevation = 0.dp,
            tonalElevation = 0.dp,
        ) {
            Box(
                modifier = Modifier.size(tabWidthDp, tabHeightDp),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.Settings,
                    contentDescription = "Настройки",
                    modifier = Modifier.size(22.dp),
                    tint = SkyflowColors.AccentLight
                )
            }
        }

        AnimatedVisibility(
            visible = isExpanded,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.offset {
                val panelWidthPx = with(density) { panelWidthDp.toPx() }
                val gap = with(density) { 8.dp.toPx() }
                val panelX = if (isRightSide) {
                    (targetXPx - panelWidthPx - gap).roundToInt()
                } else {
                    (tabWidthPx + gap).roundToInt()
                }
                IntOffset(panelX, offsetY.roundToInt())
            }
        ) {
            Surface(
                modifier = Modifier.onGloballyPositioned { coordinates ->
                    panelHeightPx = coordinates.size.height.toFloat()
                },
                shape = RoundedCornerShape(28.dp),
                color = SkyflowColors.GlassSurfaceElevated,
                border = BorderStroke(1.dp, SkyflowColors.BorderGlow),
                shadowElevation = 0.dp,
                tonalElevation = 0.dp,
            ) {
                val maxPanelHeight = with(density) {
                    (configuration.screenHeightDp.dp - 32.dp)
                }
                Column(
                    modifier = Modifier
                        .padding(12.dp)
                        .widthIn(max = panelWidthDp - 24.dp)
                        .heightIn(max = maxPanelHeight)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        "Профиль",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(start = 4.dp, bottom = 4.dp)
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf(0, 1, 2).forEach { profile ->
                            val selected = profile == activeProfile
                            Surface(
                                onClick = { onActiveProfileChange(profile) },
                                shape = RoundedCornerShape(12.dp),
                                color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                modifier = Modifier.weight(1f)
                            ) {
                                Box(
                                    modifier = Modifier.padding(vertical = 8.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "Пр. $profile",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                        fontSize = 12.sp
                                    )
                                }
                            }
                        }
                    }

                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 4.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                    )

                    Text(
                        "Тема",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(start = 4.dp, bottom = 4.dp)
                    )

                    ThemeOption(
                        icon = R.drawable.ic_auto,
                        label = "Системная",
                        selected = currentTheme == "system",
                        onClick = { onThemeChange("system"); isExpanded = false }
                    )
                    ThemeOption(
                        icon = R.drawable.ic_light_mode,
                        label = "Светлая",
                        selected = currentTheme == "light",
                        onClick = { onThemeChange("light"); isExpanded = false }
                    )
                    ThemeOption(
                        icon = R.drawable.ic_dark_mode,
                        label = "Тёмная",
                        selected = currentTheme == "dark",
                        onClick = { onThemeChange("dark"); isExpanded = false }
                    )

                }
            }
        }
    }
}

@Composable
private fun ThemeOption(
    icon: Int,
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(24.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surface,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(
                painter = painterResource(id = icon),
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                color = if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurface,
                fontSize = 13.sp
            )
        }
    }
}

