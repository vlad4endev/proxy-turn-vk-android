package com.wdtt.client.ui

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.wdtt.client.SettingsStore
import com.wdtt.client.xray.SubscriptionParser
import com.wdtt.client.xray.VlessServer
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ServersScreen(
    settingsStore: SettingsStore,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var inputMode by remember { mutableStateOf(settingsStore.getVlessInputMode()) }
    var subUrl by remember { mutableStateOf(settingsStore.getSubscriptionUrl()) }
    var manualUri by remember { mutableStateOf(settingsStore.getManualVlessUri()) }
    var servers by remember { mutableStateOf(settingsStore.loadServers()) }
    var selectedIndex by remember { mutableIntStateOf(settingsStore.getSelectedServerIndex()) }
    var lastUpdate by remember { mutableLongStateOf(settingsStore.getLastSubUpdate()) }
    var isLoading by remember { mutableStateOf(false) }
    var isPinging by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(SkyflowColors.Background)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Назад",
                        tint = SkyflowColors.TextPrimary
                    )
                }
                Text(
                    "Серверы",
                    style = MaterialTheme.typography.headlineMedium,
                    color = SkyflowColors.TextPrimary
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = inputMode == "subscription",
                    onClick = {
                        inputMode = "subscription"
                        scope.launch { settingsStore.saveVlessInputMode("subscription") }
                    },
                    label = { Text("Подписка") },
                    leadingIcon = {
                        Icon(Icons.Default.Cloud, null, modifier = Modifier.size(16.dp))
                    },
                    colors = chipColors(inputMode == "subscription")
                )
                FilterChip(
                    selected = inputMode == "manual",
                    onClick = {
                        inputMode = "manual"
                        scope.launch { settingsStore.saveVlessInputMode("manual") }
                    },
                    label = { Text("Вручную") },
                    leadingIcon = {
                        Icon(Icons.Default.Edit, null, modifier = Modifier.size(16.dp))
                    },
                    colors = chipColors(inputMode == "manual")
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            if (inputMode == "subscription") {
                OutlinedTextField(
                    value = subUrl,
                    onValueChange = {
                        subUrl = it
                        scope.launch { settingsStore.saveSubscriptionUrl(it) }
                    },
                    label = { Text("URL подписки") },
                    placeholder = { Text("https://example.com/sub?token=...") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    colors = fieldColors(),
                    trailingIcon = {
                        if (subUrl.isNotBlank()) {
                            IconButton(onClick = {
                                subUrl = ""
                                scope.launch { settingsStore.saveSubscriptionUrl("") }
                            }) {
                                Icon(Icons.Default.Clear, contentDescription = "Очистить")
                            }
                        }
                    }
                )

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = {
                            scope.launch {
                                isLoading = true
                                error = null
                                try {
                                    val result = SubscriptionParser.fetchSubscription(subUrl)
                                    servers = result
                                    settingsStore.saveServers(result)
                                    val now = System.currentTimeMillis()
                                    settingsStore.saveLastSubUpdate(now)
                                    lastUpdate = now
                                    if (result.isEmpty()) {
                                        error = "Серверы не найдены"
                                    }
                                } catch (e: Exception) {
                                    error = "Ошибка: ${e.message}"
                                }
                                isLoading = false
                            }
                        },
                        enabled = subUrl.isNotBlank() && !isLoading,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = SkyflowColors.Accent,
                            contentColor = SkyflowColors.OnAccent
                        )
                    ) {
                        if (isLoading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = SkyflowColors.OnAccent
                            )
                        } else {
                            Icon(Icons.Default.Refresh, null, modifier = Modifier.size(18.dp))
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Обновить")
                    }

                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                isPinging = true
                                val pinged = servers.map { server ->
                                    val latency = SubscriptionParser.pingServer(server)
                                    server.copy(latency = latency)
                                }.sortedBy {
                                    if (it.latency < 0) Long.MAX_VALUE else it.latency
                                }
                                servers = pinged
                                settingsStore.saveServers(pinged)
                                isPinging = false
                            }
                        },
                        enabled = servers.isNotEmpty() && !isPinging,
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = SkyflowColors.TextPrimary
                        ),
                        border = BorderStroke(1.dp, SkyflowColors.Border)
                    ) {
                        Text(if (isPinging) "..." else "Пинг")
                    }
                }

                if (lastUpdate > 0) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "Обновлено: ${formatTime(lastUpdate)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = SkyflowColors.TextMuted
                    )
                }
            } else {
                OutlinedTextField(
                    value = manualUri,
                    onValueChange = {
                        manualUri = it
                        scope.launch { settingsStore.saveManualVlessUri(it) }
                    },
                    label = { Text("VLESS URI") },
                    placeholder = { Text("vless://uuid@host:port?...") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                    maxLines = 5,
                    colors = fieldColors()
                )

                Spacer(modifier = Modifier.height(8.dp))

                OutlinedButton(
                    onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        val text = clipboard.primaryClip?.getItemAt(0)?.text?.toString() ?: ""
                        if (text.startsWith("vless://")) {
                            manualUri = text
                            scope.launch { settingsStore.saveManualVlessUri(text) }
                        }
                    },
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = SkyflowColors.TextPrimary
                    ),
                    border = BorderStroke(1.dp, SkyflowColors.Border)
                ) {
                    Icon(Icons.Default.ContentPaste, null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Вставить из буфера")
                }
            }

            error?.let {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    it,
                    color = SkyflowColors.ErrorColor,
                    style = MaterialTheme.typography.bodySmall
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            if (servers.isNotEmpty()) {
                Text(
                    "Серверы (${servers.size})",
                    style = MaterialTheme.typography.titleMedium,
                    color = SkyflowColors.TextPrimary
                )

                Spacer(modifier = Modifier.height(8.dp))

                servers.forEachIndexed { index, server ->
                    ServerCard(
                        server = server,
                        isSelected = index == selectedIndex,
                        onClick = {
                            selectedIndex = index
                            scope.launch {
                                settingsStore.saveSelectedServerIndex(index)
                                val updated = servers.mapIndexed { i, s ->
                                    s.copy(isSelected = i == index)
                                }
                                servers = updated
                                settingsStore.saveServers(updated)
                            }
                        }
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }
        }
    }
}

@Composable
private fun ServerCard(
    server: VlessServer,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) {
                SkyflowColors.AccentMuted
            } else {
                SkyflowColors.GlassSurface
            }
        ),
        border = if (isSelected) {
            BorderStroke(2.dp, SkyflowColors.Accent)
        } else {
            BorderStroke(1.dp, SkyflowColors.Border)
        },
        shape = SkyflowShapes.Card
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RadioButton(
                selected = isSelected,
                onClick = onClick,
                colors = RadioButtonDefaults.colors(
                    selectedColor = SkyflowColors.Accent,
                    unselectedColor = SkyflowColors.TextMuted
                )
            )

            Spacer(modifier = Modifier.width(8.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    server.name,
                    style = MaterialTheme.typography.titleSmall,
                    color = SkyflowColors.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    "${server.host}:${server.port}",
                    style = MaterialTheme.typography.bodySmall,
                    color = SkyflowColors.TextSecondary
                )
                Text(
                    buildString {
                        append(server.security.uppercase())
                        if (server.type != "tcp") {
                            append(" · ${server.type.uppercase()}")
                        }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = SkyflowColors.TextMuted
                )
            }

            when {
                server.latency > 0 -> {
                    val color = when {
                        server.latency < 100 -> Color(0xFF4CAF50)
                        server.latency < 300 -> Color(0xFFFFC107)
                        else -> Color(0xFFF44336)
                    }
                    Text(
                        "${server.latency}ms",
                        color = color,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
                server.latency == -1L -> {
                    Text(
                        "—",
                        style = MaterialTheme.typography.labelMedium,
                        color = SkyflowColors.TextMuted
                    )
                }
            }
        }
    }
}

@Composable
private fun chipColors(selected: Boolean) = FilterChipDefaults.filterChipColors(
    selectedContainerColor = SkyflowColors.AccentMuted,
    selectedLabelColor = SkyflowColors.AccentLight,
    containerColor = SkyflowColors.GlassSurface,
    labelColor = SkyflowColors.TextSecondary
)

@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = SkyflowColors.TextPrimary,
    unfocusedTextColor = SkyflowColors.TextPrimary,
    focusedBorderColor = SkyflowColors.Accent,
    unfocusedBorderColor = SkyflowColors.Border,
    focusedLabelColor = SkyflowColors.AccentLight,
    unfocusedLabelColor = SkyflowColors.TextMuted,
    cursorColor = SkyflowColors.Accent
)

private fun formatTime(timestamp: Long): String {
    val fmt = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault())
    return fmt.format(Date(timestamp))
}
