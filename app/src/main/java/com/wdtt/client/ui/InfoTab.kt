package com.wdtt.client.ui

import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wdtt.client.BuildConfig
import com.wdtt.client.ConnectionStage
import com.wdtt.client.R
import com.wdtt.client.TunnelManager

private val IndigoAccent = Color(0xFF6366F1)
private val StatusActive = Color(0xFF4ADE80)
private val StatusIdle = Color(0xFF71717A)

@Composable
fun InfoTab() {
    val tunnelRunning by TunnelManager.running.collectAsStateWithLifecycle()
    val connectionStage by TunnelManager.connectionStage.collectAsStateWithLifecycle()
    val versionName = remember { BuildConfig.VERSION_NAME.removePrefix("v") }
    val deviceModel = remember { Build.MODEL.orEmpty().ifBlank { "—" } }
    val androidRelease = remember { Build.VERSION.RELEASE.orEmpty().ifBlank { "—" } }
    val infrastructureStatus = remember(tunnelRunning, connectionStage) {
        infrastructureStatusLabel(tunnelRunning, connectionStage)
    }
    val statusColor = remember(tunnelRunning, connectionStage) {
        if (tunnelRunning && connectionStage == ConnectionStage.VPN_READY) StatusActive else StatusIdle
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        InfoLogoHeader()

        InfoSectionCard(
            title = "О приложении",
            icon = Icons.Default.Info
        ) {
            Text(
                text = "SKYFLOW M",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = "Клиент VPN-туннеля для обхода ограничений через TURN/VK с WireGuard и DTLS.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 20.sp
            )
        }

        InfoSectionCard(
            title = "Система",
            icon = Icons.Default.PhoneAndroid
        ) {
            InfoDetailRow(label = "Модель", value = deviceModel)
            InfoDetailRow(label = "Android", value = androidRelease)
            InfoDetailRow(label = "Версия приложения", value = versionName)
        }

        InfoSectionCard(
            title = "Инфраструктура",
            icon = Icons.Default.Cloud
        ) {
            InfoDetailRow(label = "Протокол", value = "WireGuard · DTLS")
            InfoDetailRow(label = "Регион", value = "EU")
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Статус",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                StatusBadge(text = infrastructureStatus, color = statusColor)
            }
        }

        InfoSectionCard(
            title = "Конфиденциальность",
            icon = Icons.Default.Lock
        ) {
            PrivacyBullet("Нет логов трафика на стороне приложения")
            PrivacyBullet("Нет хранения пользовательских данных")
            PrivacyBullet("Нет аналитики и трекинга")
        }

        Spacer(modifier = Modifier.height(12.dp))
    }
}

@Composable
private fun InfoLogoHeader() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = IndigoAccent.copy(alpha = 0.12f),
            tonalElevation = 0.dp,
            shadowElevation = 0.dp
        ) {
            Image(
                painter = painterResource(R.mipmap.ic_launcher),
                contentDescription = "SKYFLOW M",
                modifier = Modifier
                    .size(88.dp)
                    .padding(10.dp)
                    .clip(RoundedCornerShape(20.dp))
            )
        }
        Text(
            text = "SKYFLOW M",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = "Информация",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun InfoSectionCard(
    title: String,
    icon: ImageVector,
    content: @Composable () -> Unit
) {
    AppSectionCard(
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = IndigoAccent.copy(alpha = 0.18f)
            ) {
                Box(
                    modifier = Modifier.size(40.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = IndigoAccent,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
        }

        HorizontalDivider(
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
        )

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            content()
        }
    }
}

@Composable
private fun InfoDetailRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        Spacer(modifier = Modifier.size(12.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun StatusBadge(text: String, color: Color) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = color.copy(alpha = 0.14f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = CircleShape,
                color = color,
                modifier = Modifier.size(8.dp)
            )
            Text(
                text = text,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = color
            )
        }
    }
}

@Composable
private fun PrivacyBullet(text: String) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top
    ) {
        Surface(
            shape = CircleShape,
            color = IndigoAccent,
            modifier = Modifier
                .padding(top = 7.dp)
                .size(6.dp)
        ) {}
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            lineHeight = 20.sp
        )
    }
}

private fun infrastructureStatusLabel(running: Boolean, stage: ConnectionStage): String = when {
    running && stage == ConnectionStage.VPN_READY -> "Подключено"
    running && stage == ConnectionStage.FAILED -> "Ошибка"
    running -> when (stage) {
        ConnectionStage.STARTING -> "Запуск…"
        ConnectionStage.VK_CREDS -> "VK-креды…"
        ConnectionStage.VK_CAPTCHA -> "Капча…"
        ConnectionStage.SERVER_DTLS -> "DTLS…"
        else -> "Подключение…"
    }
    stage == ConnectionStage.FAILED -> "Ошибка"
    else -> "Не подключено"
}
