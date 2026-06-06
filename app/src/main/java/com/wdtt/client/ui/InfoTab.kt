package com.wdtt.client.ui

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wdtt.client.AppReleaseInfo
import com.wdtt.client.BuildConfig
import com.wdtt.client.ConnectionStage
import com.wdtt.client.TunnelManager
import com.wdtt.client.fetchLatestReleaseInfo
import com.wdtt.client.isNewerVersion

@Composable
fun InfoTab(onUpdateFound: (AppReleaseInfo) -> Unit = {}) {
    val tunnelRunning by TunnelManager.running.collectAsStateWithLifecycle()
    val connectionStage by TunnelManager.connectionStage.collectAsStateWithLifecycle()
    val versionName = remember { BuildConfig.VERSION_NAME.removePrefix("v") }
    val currentVersion = remember { "v$versionName" }
    var isCheckingUpdates by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        isCheckingUpdates = true
        val release = fetchLatestReleaseInfo(currentVersion)
        if (release != null && isNewerVersion(currentVersion, release.versionTag)) {
            onUpdateFound(release)
        }
        isCheckingUpdates = false
    }
    val deviceModel = remember { Build.MODEL.orEmpty().ifBlank { "—" } }
    val androidRelease = remember { Build.VERSION.RELEASE.orEmpty().ifBlank { "—" } }
    val buildDate = remember { "2026" }
    val infrastructureStatus = remember(tunnelRunning, connectionStage) {
        infrastructureStatusLabel(tunnelRunning, connectionStage)
    }
    val statusColor = remember(tunnelRunning, connectionStage) {
        if (tunnelRunning && connectionStage == ConnectionStage.VPN_READY) {
            SkyflowColors.Connected
        } else {
            SkyflowColors.TextSecondary
        }
    }
    val metrics = rememberScreenMetrics()

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = metrics.contentHorizontalPadding),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(bottom = 16.dp)
    ) {
        item {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 16.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .clip(SkyflowShapes.Logo)
                        .background(SkyflowColors.GlassSurface)
                        .border(SkyflowBorders.Logo, SkyflowShapes.Logo),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "S",
                        fontSize = 32.sp,
                        fontWeight = FontWeight.Black,
                        style = androidx.compose.ui.text.TextStyle(
                            brush = Brush.verticalGradient(
                                listOf(
                                    SkyflowColors.LogoGradientTop,
                                    SkyflowColors.LogoGradientMid,
                                    SkyflowColors.LogoGradientBottom
                                )
                            )
                        )
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "SKYFLOW",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = SkyflowColors.BrandTitle,
                    letterSpacing = 2.sp
                )
                Text(
                    text = "M",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = SkyflowColors.Accent,
                    letterSpacing = 3.sp
                )
                Spacer(Modifier.height(6.dp))
                Surface(
                    shape = SkyflowShapes.VersionBadge,
                    color = SkyflowColors.GlassSurface,
                    border = SkyflowBorders.GlassAccent
                ) {
                    Text(
                        text = "v${BuildConfig.VERSION_NAME} · build ${BuildConfig.VERSION_CODE}",
                        fontSize = 9.sp,
                        color = SkyflowColors.TextSecondary,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp)
                    )
                }
            }
        }

        item {
            InfoCard(title = "О приложении", icon = Icons.Outlined.Info) {
                FeatureRow("Защищённое VPN-соединение через облачную инфраструктуру")
                FeatureRow("Многоуровневое шифрование трафика")
                FeatureRow("Минимальные задержки, нет логов активности")
                FeatureRow("Работает везде — без ограничений и цензуры")
            }
        }

        item {
            InfoCard(title = "Система", icon = Icons.Outlined.PhoneAndroid) {
                InfoRow(label = "Устройство", value = deviceModel)
                InfoDivider()
                InfoRow(label = "Android", value = androidRelease)
                InfoDivider()
                InfoRow(
                    label = "Версия приложения",
                    value = versionName,
                    valueColor = SkyflowColors.Accent
                )
                InfoDivider()
                InfoRow(label = "Дата сборки", value = buildDate)
            }
        }

        item {
            InfoCard(title = "Инфраструктура", icon = Icons.Outlined.Computer) {
                InfoRow(
                    label = "Протокол",
                    value = "AES-256 / ChaCha20",
                    valueColor = SkyflowColors.Accent
                )
                InfoDivider()
                InfoRow(label = "Транспорт", value = "UDP · зашифрован")
                InfoDivider()
                InfoRow(label = "Регион", value = "EU · приватный")
                InfoDivider()
                InfoRow(
                    label = "Статус",
                    value = "● $infrastructureStatus",
                    valueColor = statusColor
                )
            }
        }

        item {
            InfoCard(title = "Конфиденциальность", icon = Icons.Outlined.Shield) {
                InfoRow(
                    label = "Логи соединений",
                    value = "Не ведутся",
                    valueColor = SkyflowColors.Connected
                )
                InfoDivider()
                InfoRow(
                    label = "Данные на сервере",
                    value = "Не хранятся",
                    valueColor = SkyflowColors.Connected
                )
                InfoDivider()
                InfoRow(
                    label = "Аналитика",
                    value = "Отсутствует",
                    valueColor = SkyflowColors.Connected
                )
            }
        }

        item {
            InfoCard(title = "Автор", icon = Icons.Outlined.Person) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.padding(vertical = 2.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(SkyflowColors.AuthorAvatarBg)
                            .border(SkyflowBorders.AuthorAvatar, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "ВЧ",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = SkyflowColors.AccentLight
                        )
                    }
                    Column {
                        Text(
                            text = "Влад Чендев",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = SkyflowColors.TextPrimary
                        )
                        Text(
                            text = "Разработчик · SKYFLOW M",
                            fontSize = 8.sp,
                            color = SkyflowColors.TextSecondary
                        )
                    }
                }
                InfoDivider()
                InfoRow(label = "Год", value = "2026", valueColor = SkyflowColors.Accent)
                InfoDivider()
                InfoRow(label = "Бренд", value = "SKYFLOW")
            }
        }

        item {
            Text(
                text = "SKYFLOW M · © Влад Чендев 2026",
                fontSize = 9.sp,
                color = SkyflowColors.TextMuted,
                letterSpacing = 0.3.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun InfoCard(
    title: String,
    icon: ImageVector,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = SkyflowShapes.Card,
        color = SkyflowColors.GlassSurface,
        border = SkyflowBorders.Glass
    ) {
        Column {
            Row(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = SkyflowColors.Accent,
                    modifier = Modifier.size(13.dp)
                )
                Text(
                    text = title,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    color = SkyflowColors.TextPrimary,
                    letterSpacing = 0.3.sp
                )
            }
            HorizontalDivider(color = SkyflowColors.Border, thickness = 0.5.dp)
            Column(
                modifier = Modifier.padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                content = content
            )
        }
    }
}

@Composable
private fun InfoRow(
    label: String,
    value: String,
    valueColor: Color = SkyflowColors.TextPrimary
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = label, fontSize = 9.sp, color = SkyflowColors.TextSecondary)
        Text(
            text = value,
            fontSize = 9.sp,
            color = valueColor,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun InfoDivider() = HorizontalDivider(color = SkyflowColors.Border, thickness = 0.5.dp)

@Composable
private fun FeatureRow(text: String) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.Top
    ) {
        Box(
            modifier = Modifier
                .size(4.dp)
                .offset(y = 3.dp)
                .clip(CircleShape)
                .background(SkyflowColors.Accent)
        )
        Text(text = text, fontSize = 7.5.sp, color = SkyflowColors.TextSecondary, lineHeight = 11.sp)
    }
}

private fun infrastructureStatusLabel(running: Boolean, stage: ConnectionStage): String = when {
    running && stage == ConnectionStage.VPN_READY -> "Подключено"
    running && stage == ConnectionStage.FAILED -> "Ошибка"
    running -> when (stage) {
        ConnectionStage.STARTING -> "Запуск…"
        ConnectionStage.VK_CREDS -> "Авторизация…"
        ConnectionStage.VK_CAPTCHA -> "Проверка…"
        ConnectionStage.SERVER_DTLS -> "Настройка…"
        else -> "Подключение…"
    }
    stage == ConnectionStage.FAILED -> "Ошибка"
    else -> "Не подключено"
}
