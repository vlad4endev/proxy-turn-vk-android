package com.wdtt.client.ui

import android.os.Build
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.shape.RoundedCornerShape
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

private val IndigoAccent = Color(0xFF6366F1)
private val StatusActive = Color(0xFF4ADE80)
private val StatusIdle = Color(0xFF71717A)

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
        if (tunnelRunning && connectionStage == ConnectionStage.VPN_READY) StatusActive else StatusIdle
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
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
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color(0xFF0D0B1A))
                        .border(
                            1.dp,
                            Color(0xFF6366F1).copy(alpha = 0.5f),
                            RoundedCornerShape(16.dp)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "S",
                        fontSize = 32.sp,
                        fontWeight = FontWeight.Black,
                        style = androidx.compose.ui.text.TextStyle(
                            brush = Brush.verticalGradient(
                                listOf(Color.White, Color(0xFF93C5FD), Color(0xFF3B82F6))
                            )
                        )
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "SKYFLOW",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFFF4F4F5),
                    letterSpacing = 2.sp
                )
                Text(
                    text = "M",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF6366F1),
                    letterSpacing = 3.sp
                )
                Spacer(Modifier.height(6.dp))
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = Color(0xFF13112A),
                    border = BorderStroke(0.5.dp, Color(0xFF2A2850))
                ) {
                    Text(
                        text = "v${BuildConfig.VERSION_NAME} · build ${BuildConfig.VERSION_CODE}",
                        fontSize = 9.sp,
                        color = Color(0xFF6B7280),
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
                    valueColor = Color(0xFF6366F1)
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
                    valueColor = Color(0xFF6366F1)
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
                    valueColor = Color(0xFF4ADE80)
                )
                InfoDivider()
                InfoRow(
                    label = "Данные на сервере",
                    value = "Не хранятся",
                    valueColor = Color(0xFF4ADE80)
                )
                InfoDivider()
                InfoRow(
                    label = "Аналитика",
                    value = "Отсутствует",
                    valueColor = Color(0xFF4ADE80)
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
                            .background(Color(0xFF1E1B4B))
                            .border(1.dp, Color(0xFF6366F1).copy(alpha = 0.4f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "ВЧ",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF818CF8)
                        )
                    }
                    Column {
                        Text(
                            text = "Влад Чендев",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color(0xFFD4D4D8)
                        )
                        Text(
                            text = "Разработчик · SKYFLOW M",
                            fontSize = 8.sp,
                            color = Color(0xFF6B7280)
                        )
                    }
                }
                InfoDivider()
                InfoRow(label = "Год", value = "2026", valueColor = Color(0xFF6366F1))
                InfoDivider()
                InfoRow(label = "Бренд", value = "SKYFLOW")
            }
        }

        item {
            Text(
                text = "SKYFLOW M · © Влад Чендев 2026",
                fontSize = 9.sp,
                color = Color(0xFF374151),
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
        shape = RoundedCornerShape(10.dp),
        color = Color(0xFF13112A),
        border = BorderStroke(0.5.dp, Color(0xFF1E1C3A))
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
                    tint = Color(0xFF6366F1),
                    modifier = Modifier.size(13.dp)
                )
                Text(
                    text = title,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFFD4D4D8),
                    letterSpacing = 0.3.sp
                )
            }
            HorizontalDivider(color = Color(0xFF1E1C3A), thickness = 0.5.dp)
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
    valueColor: Color = Color(0xFFD4D4D8)
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = label, fontSize = 8.sp, color = Color(0xFF6B7280))
        Text(
            text = value,
            fontSize = 8.sp,
            color = valueColor,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun InfoDivider() = HorizontalDivider(color = Color(0xFF1E1C3A), thickness = 0.5.dp)

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
                .background(Color(0xFF6366F1))
        )
        Text(text = text, fontSize = 7.5.sp, color = Color(0xFF6B7280), lineHeight = 11.sp)
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
