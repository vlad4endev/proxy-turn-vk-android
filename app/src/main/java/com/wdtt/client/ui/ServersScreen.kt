package com.wdtt.client.ui

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext

import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wdtt.client.SettingsStore
import com.wdtt.client.xray.SubscriptionParser
import com.wdtt.client.xray.VlessServer
import com.wdtt.client.xray.XrayRoutingMode
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

// ─── Input type detection ─────────────────────────────────────────────────────
private enum class InputType { VLESS_URI, SUBSCRIPTION, UNKNOWN }

private fun detectInputType(text: String): InputType = when {
    text.trimStart().startsWith("vless://")  -> InputType.VLESS_URI
    text.trimStart().startsWith("http://")   -> InputType.SUBSCRIPTION
    text.trimStart().startsWith("https://")  -> InputType.SUBSCRIPTION
    else                                      -> InputType.UNKNOWN
}

// ─── Main screen ─────────────────────────────────────────────────────────────
@Composable
fun ServersScreen(
    settingsStore: SettingsStore,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope   = rememberCoroutineScope()

    // ── Unified input: starts with whatever was previously saved ───────────
    val initText = remember {
        val mode = settingsStore.getVlessInputMode()
        if (mode == "subscription") settingsStore.getSubscriptionUrl()
        else settingsStore.getManualVlessUri()
    }
    var inputText    by remember { mutableStateOf(initText) }
    var servers      by remember { mutableStateOf(settingsStore.loadServers()) }
    var selectedIndex by remember { mutableIntStateOf(settingsStore.getSelectedServerIndex()) }
    var lastUpdate   by remember { mutableLongStateOf(settingsStore.getLastSubUpdate()) }
    var subExpireAt  by remember { mutableLongStateOf(settingsStore.getSubExpireAt()) }
    var isLoading    by remember { mutableStateOf(false) }
    var isPinging    by remember { mutableStateOf(false) }
    var error        by remember { mutableStateOf<String?>(null) }
    var routingMode  by remember { mutableStateOf(settingsStore.getRoutingMode()) }

    // Stable URL that drives the auto-refresh timer
    var savedSubUrl by remember { mutableStateOf(
        if (settingsStore.getVlessInputMode() == "subscription")
            settingsStore.getSubscriptionUrl()
        else ""
    )}

    val inputType = detectInputType(inputText)

    // ── Persist on every change (debounce handled by the LaunchedEffect) ───
    LaunchedEffect(inputText) {
        when (detectInputType(inputText)) {
            InputType.VLESS_URI -> {
                settingsStore.saveVlessInputMode("manual")
                settingsStore.saveManualVlessUri(inputText)
                // Parse and apply immediately
                val srv = SubscriptionParser.parseVlessUri(inputText)
                if (srv != null) {
                    val updated = listOf(srv.copy(isSelected = true))
                    servers = updated
                    settingsStore.saveServers(updated)
                    settingsStore.saveSelectedServerIndex(0)
                    selectedIndex = 0
                }
                savedSubUrl = ""
            }
            InputType.SUBSCRIPTION -> {
                settingsStore.saveVlessInputMode("subscription")
                settingsStore.saveSubscriptionUrl(inputText)
                savedSubUrl = inputText
            }
            InputType.UNKNOWN -> { /* just keep typing */ }
        }
    }

    // ── Auto-refresh subscription (immediate on open, then every 5 minutes) ──
    LaunchedEffect(savedSubUrl) {
        if (savedSubUrl.isNotBlank()) {
            // Immediate fetch when screen opens (or URL changes)
            try {
                isLoading = true
                val result = SubscriptionParser.fetchSubscription(savedSubUrl)
                if (result.servers.isNotEmpty()) {
                    val reselected = result.servers.mapIndexed { i, s ->
                        s.copy(isSelected = i == selectedIndex)
                    }
                    servers = reselected
                    settingsStore.saveServers(reselected)
                    val now = System.currentTimeMillis()
                    settingsStore.saveLastSubUpdate(now)
                    lastUpdate = now
                    if (result.expireAt > 0L) {
                        subExpireAt = result.expireAt
                        settingsStore.saveSubExpireAt(result.expireAt)
                    }
                }
            } catch (_: Exception) { /* silent */ }
            finally { isLoading = false }
            // Then keep refreshing every 5 minutes
            while (true) {
                delay(300_000L)
                try {
                    val result = SubscriptionParser.fetchSubscription(savedSubUrl)
                    if (result.servers.isNotEmpty()) {
                        val reselected = result.servers.mapIndexed { i, s ->
                            s.copy(isSelected = i == selectedIndex)
                        }
                        servers = reselected
                        settingsStore.saveServers(reselected)
                        val now = System.currentTimeMillis()
                        settingsStore.saveLastSubUpdate(now)
                        lastUpdate = now
                        if (result.expireAt > 0L) {
                            subExpireAt = result.expireAt
                            settingsStore.saveSubExpireAt(result.expireAt)
                        }
                    }
                } catch (_: Exception) { /* silent on background refresh */ }
            }
        }
    }

    // ── Manual refresh action ─────────────────────────────────────────────
    fun refreshSubscription() {
        scope.launch {
            isLoading = true
            error = null
            try {
                val result = SubscriptionParser.fetchSubscription(inputText)
                val reselected = result.servers.mapIndexed { i, s ->
                    s.copy(isSelected = i == selectedIndex)
                }
                servers = reselected
                settingsStore.saveServers(reselected)
                val now = System.currentTimeMillis()
                settingsStore.saveLastSubUpdate(now)
                lastUpdate = now
                if (result.expireAt > 0L) {
                    subExpireAt = result.expireAt
                    settingsStore.saveSubExpireAt(result.expireAt)
                }
                savedSubUrl = inputText
                if (result.servers.isEmpty()) error = "Серверы не найдены"
            } catch (e: Exception) {
                error = "Ошибка: ${e.message}"
            }
            isLoading = false
        }
    }

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
            // ── Header ─────────────────────────────────────────────────────
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

            Spacer(Modifier.height(16.dp))

            // ── Routing mode ───────────────────────────────────────────────
            Text(
                "МАРШРУТИЗАЦИЯ",
                style = MaterialTheme.typography.labelSmall,
                color = SkyflowColors.TextMuted
            )
            Spacer(Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                XrayRoutingMode.entries.forEach { mode ->
                    FilterChip(
                        selected  = routingMode == mode.key,
                        onClick   = {
                            routingMode = mode.key
                            scope.launch { settingsStore.saveRoutingMode(mode.key) }
                        },
                        label     = { Text(mode.label, maxLines = 1) },
                        colors    = chipColors(routingMode == mode.key),
                        modifier  = Modifier.weight(1f)
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                when (routingMode) {
                    "bypass_ru"    -> "Российские сайты и IP открываются напрямую, зарубежные — через прокси"
                    "bypass_local" -> "Только LAN/loopback напрямую — весь интернет через прокси"
                    else           -> "Весь трафик направляется через прокси-сервер"
                },
                style = MaterialTheme.typography.bodySmall,
                color = SkyflowColors.TextMuted
            )

            Spacer(Modifier.height(20.dp))

            // ── Unified input section ──────────────────────────────────────
            Text(
                "VLESS / ПОДПИСКА",
                style = MaterialTheme.typography.labelSmall,
                color = SkyflowColors.TextMuted
            )
            Spacer(Modifier.height(8.dp))

            // Input type badge
            AnimatedVisibility(visible = inputType != InputType.UNKNOWN) {
                InputTypeBadge(inputType)
                Spacer(Modifier.height(6.dp))
            }

            // The single smart field
            OutlinedTextField(
                value         = inputText,
                onValueChange = { inputText = it },
                label         = {
                    Text(
                        when (inputType) {
                            InputType.VLESS_URI    -> "VLESS URI"
                            InputType.SUBSCRIPTION -> "URL подписки"
                            else                   -> "Вставьте VLESS URI или URL подписки"
                        }
                    )
                },
                placeholder   = { Text("vless://... или https://...") },
                modifier      = Modifier.fillMaxWidth(),
                singleLine    = inputType != InputType.VLESS_URI,
                minLines      = if (inputType == InputType.VLESS_URI) 2 else 1,
                maxLines      = if (inputType == InputType.VLESS_URI) 4 else 1,
                colors        = fieldColors(),
                trailingIcon  = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(end = 4.dp)
                    ) {
                        // Paste button — always visible
                        IconButton(
                            onClick = {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE)
                                        as ClipboardManager
                                val text = clipboard.primaryClip?.getItemAt(0)?.text?.toString() ?: ""
                                if (text.isNotBlank()) inputText = text.trim()
                            },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                Icons.Default.ContentPaste,
                                contentDescription  = "Вставить из буфера",
                                tint                = SkyflowColors.AccentLight,
                                modifier            = Modifier.size(20.dp)
                            )
                        }
                        // Clear button — when non-empty
                        if (inputText.isNotBlank()) {
                            IconButton(
                                onClick  = { inputText = "" },
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(
                                    Icons.Default.Clear,
                                    contentDescription = "Очистить",
                                    tint               = SkyflowColors.TextMuted,
                                    modifier           = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }
            )

            Spacer(Modifier.height(10.dp))

            // ── Action buttons (subscription mode only) ─────────────────
            AnimatedVisibility(visible = inputType == InputType.SUBSCRIPTION) {
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick  = ::refreshSubscription,
                            enabled  = inputText.isNotBlank() && !isLoading,
                            modifier = Modifier.weight(1f),
                            colors   = ButtonDefaults.buttonColors(
                                containerColor = SkyflowColors.Accent,
                                contentColor   = SkyflowColors.OnAccent
                            )
                        ) {
                            if (isLoading) {
                                CircularProgressIndicator(
                                    modifier    = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                    color       = SkyflowColors.OnAccent
                                )
                            } else {
                                Icon(Icons.Default.Refresh, null, modifier = Modifier.size(18.dp))
                            }
                            Spacer(Modifier.width(8.dp))
                            Text("Обновить")
                        }

                        OutlinedButton(
                            onClick  = {
                                scope.launch {
                                    isPinging = true
                                    val pinged = servers.map { server ->
                                        server.copy(latency = SubscriptionParser.pingServer(server))
                                    }.sortedBy { if (it.latency < 0) Long.MAX_VALUE else it.latency }
                                    servers = pinged
                                    settingsStore.saveServers(pinged)
                                    isPinging = false
                                }
                            },
                            enabled  = servers.isNotEmpty() && !isPinging,
                            colors   = ButtonDefaults.outlinedButtonColors(
                                contentColor = SkyflowColors.TextPrimary
                            ),
                            border   = BorderStroke(1.dp, SkyflowColors.Border)
                        ) {
                            Text(if (isPinging) "..." else "Пинг")
                        }
                    }

                    // Last update + auto-refresh hint
                    if (lastUpdate > 0L) {
                        Spacer(Modifier.height(4.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                "Обновлено: ${formatTime(lastUpdate)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = SkyflowColors.TextMuted
                            )
                            Text(
                                "• авто каждые 5 мин",
                                style = MaterialTheme.typography.bodySmall,
                                color = SkyflowColors.TextMuted.copy(alpha = 0.6f)
                            )
                        }
                    }

                    // Expiry badge — always show once servers are loaded
                    // (shows "дата не указана" if provider doesn't return expiry)
                    if (servers.isNotEmpty()) {
                        Spacer(Modifier.height(10.dp))
                        ExpiryBadge(expireAt = subExpireAt)
                    }

                    Spacer(Modifier.height(4.dp))
                }
            }

            // ── Error ──────────────────────────────────────────────────────
            error?.let {
                Spacer(Modifier.height(6.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        Icons.Default.Warning,
                        contentDescription = null,
                        tint               = SkyflowColors.ErrorColor,
                        modifier           = Modifier.size(16.dp)
                    )
                    Text(it, color = SkyflowColors.ErrorColor, style = MaterialTheme.typography.bodySmall)
                }
            }

            // ── Server list ────────────────────────────────────────────────
            if (servers.isNotEmpty()) {
                Spacer(Modifier.height(16.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        "Серверы",
                        style      = MaterialTheme.typography.titleMedium,
                        color      = SkyflowColors.TextPrimary,
                        fontWeight = FontWeight.SemiBold
                    )
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = SkyflowColors.AccentMuted
                    ) {
                        Text(
                            "${servers.size}",
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
                            style    = MaterialTheme.typography.labelMedium,
                            color    = SkyflowColors.AccentLight,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Spacer(Modifier.height(8.dp))

                servers.forEachIndexed { index, server ->
                    ServerCard(
                        server     = server,
                        isSelected = index == selectedIndex,
                        onClick    = {
                            selectedIndex = index
                            scope.launch {
                                settingsStore.saveSelectedServerIndex(index)
                                val updated = servers.mapIndexed { i, s -> s.copy(isSelected = i == index) }
                                servers = updated
                                settingsStore.saveServers(updated)
                            }
                        }
                    )
                    Spacer(Modifier.height(8.dp))
                }
            }

            // Bottom padding for nav bar
            Spacer(Modifier.height(80.dp))
        }
    }
}

// ─── Input type indicator badge ───────────────────────────────────────────────
@Composable
private fun InputTypeBadge(type: InputType) {
    val label: String
    val icon: ImageVector
    val containerColor: Color
    val contentColor: Color
    when (type) {
        InputType.SUBSCRIPTION -> {
            label          = "Подписка"
            icon           = Icons.Default.Cloud
            containerColor = SkyflowColors.AccentMuted
            contentColor   = SkyflowColors.AccentLight
        }
        InputType.VLESS_URI -> {
            label          = "VLESS URI"
            icon           = Icons.Default.Lock
            containerColor = SkyflowColors.Connected.copy(alpha = 0.12f)
            contentColor   = SkyflowColors.Connected
        }
        else -> return
    }
    Row(
        verticalAlignment     = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        modifier              = Modifier.padding(bottom = 6.dp)
    ) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = containerColor
        ) {
            Row(
                modifier              = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                verticalAlignment     = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                Icon(
                    imageVector        = icon,
                    contentDescription = null,
                    modifier           = Modifier.size(14.dp),
                    tint               = contentColor
                )
                Text(
                    label,
                    style      = MaterialTheme.typography.labelSmall,
                    color      = contentColor,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

// ─── Subscription expiry banner ───────────────────────────────────────────────
@Composable
private fun ExpiryBadge(expireAt: Long) {
    // Provider didn't include expiry info
    if (expireAt == 0L) {
        Surface(
            shape  = SkyflowShapes.Chip,
            color  = SkyflowColors.TextMuted.copy(alpha = 0.07f),
            border = BorderStroke(1.dp, SkyflowColors.TextMuted.copy(alpha = 0.18f))
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(
                    imageVector        = Icons.Default.Warning,
                    contentDescription = null,
                    tint               = SkyflowColors.TextMuted,
                    modifier           = Modifier.size(18.dp)
                )
                Text(
                    "Провайдер не указал срок действия",
                    style      = MaterialTheme.typography.labelMedium,
                    color      = SkyflowColors.TextMuted,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
        return
    }

    val nowSec   = System.currentTimeMillis() / 1000L
    val secondsLeft = expireAt - nowSec
    val daysLeft    = TimeUnit.SECONDS.toDays(secondsLeft)
    val hoursLeft   = TimeUnit.SECONDS.toHours(secondsLeft)

    val (containerColor, borderColor, iconTint, textColor, statusText) = when {
        secondsLeft <= 0 -> ExpiryColors(
            container  = SkyflowColors.ErrorColor.copy(alpha = 0.10f),
            border     = SkyflowColors.ErrorColor.copy(alpha = 0.30f),
            icon       = SkyflowColors.ErrorColor,
            text       = SkyflowColors.ErrorColor,
            status     = "Подписка истекла"
        )
        daysLeft < 7 -> ExpiryColors(
            container  = SkyflowColors.ErrorColor.copy(alpha = 0.08f),
            border     = SkyflowColors.ErrorColor.copy(alpha = 0.25f),
            icon       = SkyflowColors.ErrorColor,
            text       = SkyflowColors.ErrorColor,
            status     = if (daysLeft < 1) "Истекает через ${hoursLeft}ч" else "Истекает через ${daysLeft}д ⚠"
        )
        daysLeft < 30 -> ExpiryColors(
            container  = SkyflowColors.WarnColor.copy(alpha = 0.08f),
            border     = SkyflowColors.WarnColor.copy(alpha = 0.25f),
            icon       = SkyflowColors.WarnColor,
            text       = SkyflowColors.WarnColor,
            status     = "Истекает через $daysLeft дн."
        )
        else -> ExpiryColors(
            container  = SkyflowColors.Connected.copy(alpha = 0.08f),
            border     = SkyflowColors.Connected.copy(alpha = 0.20f),
            icon       = SkyflowColors.Connected,
            text       = SkyflowColors.Connected,
            status     = "Активна ещё $daysLeft дн."
        )
    }

    val dateStr = SimpleDateFormat("d MMM yyyy", Locale("ru")).format(Date(expireAt * 1000L))

    Surface(
        shape  = SkyflowShapes.Chip,
        color  = containerColor,
        border = BorderStroke(1.dp, borderColor)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(
                imageVector        = if (secondsLeft <= 0 || daysLeft < 7)
                    Icons.Default.Warning else Icons.Default.Refresh,
                contentDescription = null,
                tint               = iconTint,
                modifier           = Modifier.size(18.dp)
            )
            Column {
                Text(
                    statusText,
                    style      = MaterialTheme.typography.labelMedium,
                    color      = textColor,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    "До $dateStr",
                    style = MaterialTheme.typography.bodySmall,
                    color = textColor.copy(alpha = 0.75f)
                )
            }
        }
    }
}

// ─── Server card ──────────────────────────────────────────────────────────────
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
            containerColor = if (isSelected) SkyflowColors.AccentMuted else SkyflowColors.GlassSurface
        ),
        border = if (isSelected)
            BorderStroke(2.dp, SkyflowColors.Accent)
        else
            BorderStroke(1.dp, SkyflowColors.Border),
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
                onClick  = onClick,
                colors   = RadioButtonDefaults.colors(
                    selectedColor   = SkyflowColors.Accent,
                    unselectedColor = SkyflowColors.TextMuted
                )
            )
            Spacer(Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    server.name,
                    style    = MaterialTheme.typography.titleSmall,
                    color    = SkyflowColors.TextPrimary,
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
                        if (server.type != "tcp") append(" · ${server.type.uppercase()}")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = SkyflowColors.TextMuted
                )
            }
            when {
                server.latency > 0 -> {
                    val latencyColor = when {
                        server.latency < 100 -> Color(0xFF4CAF50)
                        server.latency < 300 -> Color(0xFFFFC107)
                        else                 -> Color(0xFFF44336)
                    }
                    Text(
                        "${server.latency}ms",
                        color      = latencyColor,
                        style      = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
                server.latency == -1L -> {
                    Text("—", style = MaterialTheme.typography.labelMedium, color = SkyflowColors.TextMuted)
                }
            }
        }
    }
}

// ─── Helpers ──────────────────────────────────────────────────────────────────
@Composable
private fun chipColors(selected: Boolean) = FilterChipDefaults.filterChipColors(
    selectedContainerColor = SkyflowColors.AccentMuted,
    selectedLabelColor     = SkyflowColors.AccentLight,
    containerColor         = SkyflowColors.GlassSurface,
    labelColor             = SkyflowColors.TextSecondary
)

@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor       = SkyflowColors.TextPrimary,
    unfocusedTextColor     = SkyflowColors.TextPrimary,
    focusedBorderColor     = SkyflowColors.Accent,
    unfocusedBorderColor   = SkyflowColors.Border,
    focusedLabelColor      = SkyflowColors.AccentLight,
    unfocusedLabelColor    = SkyflowColors.TextMuted,
    cursorColor            = SkyflowColors.Accent,
    focusedPlaceholderColor   = SkyflowColors.Placeholder,
    unfocusedPlaceholderColor = SkyflowColors.Placeholder
)

private fun formatTime(timestamp: Long): String {
    val fmt = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault())
    return fmt.format(Date(timestamp))
}

private data class ExpiryColors(
    val container: Color,
    val border: Color,
    val icon: Color,
    val text: Color,
    val status: String
)

