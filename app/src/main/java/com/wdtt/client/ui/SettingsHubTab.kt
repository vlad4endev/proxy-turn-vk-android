package com.wdtt.client.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * «Настройки» — 3-я вкладка нижней навигации (была «Инфо»). Единственный вход
 * в настройки: собирает то, что раньше было отдельной вкладкой верхнего уровня
 * («Исключения»), плюс «О приложении», плюс профиль/тему — раньше это была
 * отдельная перетаскиваемая плавающая кнопка (FloatingToolbar), которая
 * наезжала на новый верх главного экрана и дублировала вход в настройки.
 */
@Composable
fun SettingsHubTab(
    themeMode: String = "system",
    onThemeChange: (String) -> Unit = {},
    activeProfile: Int = 0,
    onActiveProfileChange: (Int) -> Unit = {},
    onUpdateFound: (com.wdtt.client.AppReleaseInfo) -> Unit = {},
) {
    var openScreen by rememberSaveable { mutableStateOf<String?>(null) }

    when (openScreen) {
        "exceptions" -> {
            BackHandler { openScreen = null }
            Column(Modifier.fillMaxSize()) {
                SettingsSubBar(title = "Исключения приложений", onBack = { openScreen = null })
                Box(Modifier.fillMaxSize()) { ExceptionsTab() }
            }
        }
        "info" -> {
            BackHandler { openScreen = null }
            Column(Modifier.fillMaxSize()) {
                SettingsSubBar(title = "О приложении", onBack = { openScreen = null })
                Box(Modifier.fillMaxSize()) { InfoTab(onUpdateFound = onUpdateFound) }
            }
        }
        else -> {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(SkyflowColors.Background)
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(horizontal = 20.dp, vertical = 16.dp),
            ) {
                Text(
                    "Настройки",
                    fontFamily = displayFontFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 24.sp,
                    color = SkyflowColors.TextPrimary,
                )
                Spacer(Modifier.height(20.dp))

                SettingsSectionLabel("Профиль")
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    listOf(0, 1, 2).forEach { profile ->
                        SegmentOption(
                            label = "Профиль ${profile + 1}",
                            selected = profile == activeProfile,
                            modifier = Modifier.weight(1f),
                            onClick = { onActiveProfileChange(profile) },
                        )
                    }
                }

                Spacer(Modifier.height(18.dp))
                SettingsSectionLabel("Тема")
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    SegmentOption("Системная", themeMode == "system", Modifier.weight(1f)) { onThemeChange("system") }
                    SegmentOption("Светлая", themeMode == "light", Modifier.weight(1f)) { onThemeChange("light") }
                    SegmentOption("Тёмная", themeMode == "dark", Modifier.weight(1f)) { onThemeChange("dark") }
                }

                Spacer(Modifier.height(20.dp))
                SettingsRow(
                    icon = Icons.Filled.FilterList,
                    title = "Исключения приложений",
                    subtitle = "Какие приложения идут в обход туннеля",
                    onClick = { openScreen = "exceptions" },
                )
                Spacer(Modifier.height(10.dp))
                SettingsRow(
                    icon = Icons.Filled.Info,
                    title = "О приложении",
                    subtitle = "Версия, обновления, поддержка",
                    onClick = { openScreen = "info" },
                )
            }
        }
    }
}

@Composable
private fun SettingsSectionLabel(text: String) {
    Text(
        text,
        fontFamily = interFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 12.sp,
        color = SkyflowColors.TextMuted,
        modifier = Modifier.padding(bottom = 8.dp),
    )
}

@Composable
private fun SegmentOption(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Surface(
        modifier = modifier.clickable(onClick = onClick),
        shape = SkyflowShapes.Chip,
        color = if (selected) SkyflowColors.Accent else SkyflowColors.GlassSurface,
        border = SkyflowBorders.Glass,
    ) {
        Box(
            modifier = Modifier.padding(vertical = 10.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                label,
                fontFamily = interFontFamily,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                fontSize = 12.sp,
                color = if (selected) SkyflowColors.OnAccent else SkyflowColors.TextSecondary,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun SettingsSubBar(title: String, onBack: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = onBack) {
            Text("‹ Назад", color = SkyflowColors.TextSecondary, fontSize = 14.sp)
        }
        Spacer(Modifier.width(4.dp))
        Text(
            title,
            fontFamily = interFontFamily,
            fontWeight = FontWeight.SemiBold,
            fontSize = 15.sp,
            color = SkyflowColors.TextPrimary,
        )
    }
}

@Composable
private fun SettingsRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = SkyflowShapes.Card,
        color = SkyflowColors.GlassSurface,
        border = SkyflowBorders.Glass,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .background(SkyflowColors.AccentMuted, RoundedCornerShape(11.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = SkyflowColors.AccentLight, modifier = Modifier.size(19.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, fontFamily = interFontFamily, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = SkyflowColors.TextPrimary)
                Text(subtitle, fontFamily = interFontFamily, fontSize = 12.sp, color = SkyflowColors.TextSecondary)
            }
            Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = SkyflowColors.TextMuted, modifier = Modifier.size(18.dp))
        }
    }
}
