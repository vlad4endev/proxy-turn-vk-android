package com.wdtt.client.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wdtt.client.ConnectionStage
import com.wdtt.client.LogCategory
import com.wdtt.client.LogEntry
import com.wdtt.client.ServerConfig
import com.wdtt.client.TunnelConnectionSnapshot
import com.wdtt.client.TunnelManager
import com.wdtt.client.WDTTColors
import com.wdtt.client.SettingsStore
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class PathNode(val label: String, val short: String) {
    PHONE("Телефон", "APP"),
    VK("VK API", "VK"),
    TURN("TURN relay", "TURN"),
    VPS("VPS DTLS", "VPS"),
    VPN("WireGuard", "VPN")
}

private enum class NodeState { IDLE, ACTIVE, DONE, ERROR }

private data class PathNodeUi(val node: PathNode, val state: NodeState)

private enum class LogFilter(val label: String, val categories: Set<LogCategory>?) {
    ALL("Все", null),
    PATH("Путь", setOf(LogCategory.VK, LogCategory.CAPTCHA, LogCategory.TURN, LogCategory.WRAP, LogCategory.SERVER, LogCategory.VPN)),
    SERVER("VPS", setOf(LogCategory.SERVER, LogCategory.WRAP)),
    VK("VK", setOf(LogCategory.VK, LogCategory.CAPTCHA)),
    ERRORS("Ошибки", null)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogsTab() {
    val context = LocalContext.current
    val settingsStore = remember { SettingsStore(context) }
    val loggingEnabled by settingsStore.loggingEnabled.collectAsStateWithLifecycle(initialValue = true)
    val scope = rememberCoroutineScope()
    val currentLogs by TunnelManager.logs.collectAsStateWithLifecycle()
    val tunnelRunning by TunnelManager.running.collectAsStateWithLifecycle()
    val connectionStage by TunnelManager.connectionStage.collectAsStateWithLifecycle()
    val connectionHint by TunnelManager.connectionHint.collectAsStateWithLifecycle()
    val snapshot by TunnelManager.connectionSnapshot.collectAsStateWithLifecycle()
    val stats by TunnelManager.stats.collectAsStateWithLifecycle()
    val activeWorkers by TunnelManager.activeWorkers.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    var logFilter by remember { mutableStateOf(LogFilter.ALL) }

    val filteredLogs = remember(currentLogs, logFilter) {
        when (logFilter) {
            LogFilter.ALL -> currentLogs
            LogFilter.ERRORS -> currentLogs.filter { it.isError }
            else -> currentLogs.filter { logFilter.categories!!.contains(it.category) }
        }
    }

    val pathNodes = remember(tunnelRunning, connectionStage, currentLogs) {
        buildPathNodes(tunnelRunning, connectionStage, currentLogs)
    }

    LaunchedEffect(filteredLogs.size) {
        if (filteredLogs.isNotEmpty()) {
            listState.animateScrollToItem(filteredLogs.lastIndex)
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "Диагностика",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface
            )
            Row {
                IconButton(onClick = { TunnelManager.clearLogs() }) {
                    Icon(Icons.Default.Delete, contentDescription = "Очистить", tint = MaterialTheme.colorScheme.primary)
                }
                IconButton(onClick = {
                    val text = formatLogsForCopy(currentLogs, snapshot, connectionStage, connectionHint, stats, activeWorkers)
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("SKYFLOW M Logs", text))
                    Toast.makeText(context, "Отчёт скопирован", Toast.LENGTH_SHORT).show()
                }) {
                    Icon(Icons.Default.ContentCopy, contentDescription = "Копировать", tint = MaterialTheme.colorScheme.primary)
                }
            }
        }

        ConnectionPathCard(
            nodes = pathNodes,
            snapshot = snapshot,
            stage = connectionStage,
            hint = connectionHint,
            running = tunnelRunning,
            stats = stats,
            workers = activeWorkers
        )

        Spacer(Modifier.height(10.dp))

        LogFilterRow(selected = logFilter, onSelect = { logFilter = it })

        Spacer(Modifier.height(8.dp))

        AppSectionCard(
            modifier = Modifier.padding(bottom = 8.dp),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(0.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    "Запись событий",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Switch(
                    checked = loggingEnabled,
                    onCheckedChange = { enabled ->
                        scope.launch {
                            settingsStore.saveLoggingEnabled(enabled)
                            if (!enabled) TunnelManager.clearLogs()
                        }
                    }
                )
            }
        }

        val isDark = isSystemInDarkTheme()
        val terminalBg = if (isDark) WDTTColors.terminalBgDark else WDTTColors.terminalBg

        Card(
            modifier = Modifier.fillMaxSize(),
            colors = CardDefaults.cardColors(containerColor = terminalBg),
            shape = RoundedCornerShape(20.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 6.dp)
        ) {
            if (filteredLogs.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize().padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (loggingEnabled) {
                            "Событий пока нет.\nПодключите туннель — здесь появится полный путь: VK → TURN → VPS → VPN."
                        } else {
                            "Логирование выключено."
                        },
                        color = WDTTColors.terminalText.copy(alpha = 0.6f),
                        fontSize = 13.sp,
                        lineHeight = 18.sp,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 10.dp),
                    contentPadding = PaddingValues(bottom = 12.dp)
                ) {
                    items(filteredLogs, key = { it.key }) { entry ->
                        LogLine(entry)
                    }
                }
            }
        }
    }
}

@Composable
private fun ConnectionPathCard(
    nodes: List<PathNodeUi>,
    snapshot: TunnelConnectionSnapshot,
    stage: ConnectionStage,
    hint: String,
    running: Boolean,
    stats: String,
    workers: Int
) {
    AppSectionCard(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            "Путь подключения",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            nodes.forEachIndexed { index, nodeUi ->
                PathNodeChip(nodeUi)
                if (index < nodes.lastIndex) {
                    PathArrow(nodeUi.state, nodes.getOrNull(index + 1)?.state ?: NodeState.IDLE)
                }
            }
        }

        val peer = snapshot.peer.ifBlank { ServerConfig.defaultPeer() }
        val stageLabel = stageLabel(stage, running)
        val stageColor = stageColor(stage, running)

        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            StatusDetailRow("Этап", stageLabel, stageColor)
            StatusDetailRow("VPS", peer, MaterialTheme.colorScheme.onSurface)
            if (snapshot.listen.isNotBlank()) {
                StatusDetailRow("Локально", snapshot.listen, MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (snapshot.hashMode.isNotBlank()) {
                StatusDetailRow(
                    "VK хеш",
                    "${snapshot.hashMode} · ${snapshot.hashCount} шт.",
                    MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (snapshot.workers > 0) {
                StatusDetailRow(
                    "Потоки",
                    "${snapshot.workers} · капча ${snapshot.captchaMode}/${snapshot.captchaSolve}",
                    MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            StatusDetailRow(
                "Воркеры",
                if (workers > 0) "$workers активных" else if (running) "ожидание…" else "—",
                if (workers > 0) WDTTColors.terminalGreen else MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (stats != "Ожидание данных..." && stats.isNotBlank()) {
                StatusDetailRow("Трафик", stats, WDTTColors.terminalBlue)
            }
            if (hint.isNotBlank()) {
                Text(
                    text = hint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    lineHeight = 16.sp
                )
            }
        }
    }
}

@Composable
private fun PathNodeChip(nodeUi: PathNodeUi) {
    val (bg, fg, border) = nodeColors(nodeUi.state)
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(
            color = bg,
            shape = RoundedCornerShape(12.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, border)
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(fg)
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    nodeUi.node.short,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = fg
                )
            }
        }
        Text(
            nodeUi.node.label,
            fontSize = 9.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun PathArrow(from: NodeState, to: NodeState) {
    val active = from == NodeState.DONE || from == NodeState.ACTIVE || to == NodeState.ACTIVE
    Text(
        "→",
        fontSize = 14.sp,
        fontWeight = FontWeight.Bold,
        color = if (active) WDTTColors.terminalGreen else MaterialTheme.colorScheme.outline,
        modifier = Modifier.padding(horizontal = 2.dp)
    )
}

@Composable
private fun StatusDetailRow(label: String, value: String, valueColor: Color) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.widthIn(max = 100.dp)
        )
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            color = valueColor,
            modifier = Modifier.weight(1f),
            textAlign = androidx.compose.ui.text.style.TextAlign.End,
            lineHeight = 16.sp
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LogFilterRow(selected: LogFilter, onSelect: (LogFilter) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        LogFilter.entries.forEach { filter ->
            FilterChip(
                selected = selected == filter,
                onClick = { onSelect(filter) },
                label = { Text(filter.label, fontSize = 12.sp) }
            )
        }
    }
}

@Composable
fun LogLine(entry: LogEntry) {
    val color = when {
        entry.isError -> WDTTColors.terminalRed
        entry.priority <= 2 -> WDTTColors.terminalGreen
        entry.priority == 3 -> WDTTColors.terminalBlue
        else -> WDTTColors.terminalText
    }
    val tagColor = categoryColor(entry.category)
    val timeStr = remember(entry.timestampMs) {
        SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(entry.timestampMs))
    }

    var trigger by remember { mutableIntStateOf(0) }
    LaunchedEffect(entry.count) { trigger++ }

    val animatedScale by animateFloatAsState(
        targetValue = if (trigger > 0) 1.12f else 1.0f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "scale",
        finishedListener = { trigger = 0 }
    )

    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = timeStr,
            color = WDTTColors.terminalText.copy(alpha = 0.45f),
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.width(52.dp)
        )

        Surface(
            color = tagColor.copy(alpha = 0.18f),
            shape = RoundedCornerShape(6.dp),
            modifier = Modifier.padding(end = 6.dp)
        ) {
            Text(
                text = categoryShort(entry.category),
                color = tagColor,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp),
                maxLines = 1
            )
        }

        Surface(
            color = WDTTColors.terminalCounter.copy(alpha = 0.2f),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier
                .defaultMinSize(minWidth = 22.dp, minHeight = 22.dp)
                .graphicsLayer(scaleX = animatedScale, scaleY = animatedScale)
        ) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = 5.dp)) {
                Text(
                    text = "${entry.count}",
                    color = WDTTColors.terminalBlue,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1
                )
            }
        }

        Spacer(modifier = Modifier.width(8.dp))

        Text(
            text = stripCategoryPrefix(entry.message),
            color = color,
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = if (entry.isError) FontWeight.Bold else FontWeight.Normal,
            lineHeight = 17.sp,
            modifier = Modifier.weight(1f)
        )
    }
}

private fun buildPathNodes(
    running: Boolean,
    stage: ConnectionStage,
    logs: List<LogEntry>
): List<PathNodeUi> {
    if (!running && stage == ConnectionStage.IDLE) {
        return PathNode.entries.map { PathNodeUi(it, NodeState.IDLE) }
    }

    val vkDone = logs.any { it.key == "creds_ok" || it.message.contains("Креды OK", true) }
    val turnSeen = logs.any { it.category == LogCategory.TURN || it.message.startsWith("[TURN]") }
    val vpsDone = logs.any { it.key == "dtls_ok" }
    val vpnDone = logs.any { it.key == "ready" } || stage == ConnectionStage.VPN_READY
    val hasFatal = stage == ConnectionStage.FAILED ||
        logs.any { it.isError && it.priority >= 99 && it.key != "stats" }

    fun stateFor(node: PathNode): NodeState = when (node) {
        PathNode.PHONE -> when {
            hasFatal && !vpnDone -> NodeState.ERROR
            running -> if (vpnDone) NodeState.DONE else NodeState.ACTIVE
            else -> NodeState.IDLE
        }
        PathNode.VK -> when {
            hasFatal && !vkDone && stage != ConnectionStage.VPN_READY -> NodeState.ERROR
            stage == ConnectionStage.VK_CAPTCHA || stage == ConnectionStage.VK_CREDS -> NodeState.ACTIVE
            vkDone || stage.ordinal >= ConnectionStage.SERVER_DTLS.ordinal -> NodeState.DONE
            running -> NodeState.ACTIVE
            else -> NodeState.IDLE
        }
        PathNode.TURN -> when {
            turnSeen && vpnDone -> NodeState.DONE
            turnSeen -> NodeState.ACTIVE
            vkDone && running -> NodeState.ACTIVE
            vkDone -> NodeState.DONE
            else -> NodeState.IDLE
        }
        PathNode.VPS -> when {
            hasFatal && !vpsDone && stage == ConnectionStage.FAILED -> NodeState.ERROR
            stage == ConnectionStage.SERVER_DTLS -> NodeState.ACTIVE
            vpsDone || vpnDone -> NodeState.DONE
            vkDone && running -> NodeState.ACTIVE
            else -> NodeState.IDLE
        }
        PathNode.VPN -> when {
            vpnDone -> NodeState.DONE
            vpsDone && running -> NodeState.ACTIVE
            else -> NodeState.IDLE
        }
    }

    return PathNode.entries.map { PathNodeUi(it, stateFor(it)) }
}

private fun stageLabel(stage: ConnectionStage, running: Boolean): String = when {
    !running && stage == ConnectionStage.IDLE -> "Не подключено"
    stage == ConnectionStage.STARTING -> "Запуск процесса…"
    stage == ConnectionStage.VK_CREDS -> "VK: учётные данные"
    stage == ConnectionStage.VK_CAPTCHA -> "VK: капча"
    stage == ConnectionStage.SERVER_DTLS -> "VPS: DTLS / WRAP"
    stage == ConnectionStage.VPN_READY -> "VPN активен"
    stage == ConnectionStage.FAILED -> "Ошибка подключения"
    running -> "Подключение…"
    else -> "—"
}

private fun stageColor(stage: ConnectionStage, running: Boolean): Color = when {
    stage == ConnectionStage.VPN_READY -> WDTTColors.terminalGreen
    stage == ConnectionStage.FAILED -> WDTTColors.terminalRed
    running -> WDTTColors.terminalBlue
    else -> Color(0xFF71717A)
}

@Composable
private fun nodeColors(state: NodeState): Triple<Color, Color, Color> {
    val idle = MaterialTheme.colorScheme.surfaceVariant
    val idleFg = MaterialTheme.colorScheme.onSurfaceVariant
    return when (state) {
        NodeState.DONE -> Triple(
            WDTTColors.terminalGreen.copy(alpha = 0.15f),
            WDTTColors.terminalGreen,
            WDTTColors.terminalGreen.copy(alpha = 0.4f)
        )
        NodeState.ACTIVE -> Triple(
            WDTTColors.terminalBlue.copy(alpha = 0.18f),
            WDTTColors.terminalBlue,
            WDTTColors.terminalBlue.copy(alpha = 0.5f)
        )
        NodeState.ERROR -> Triple(
            WDTTColors.terminalRed.copy(alpha = 0.15f),
            WDTTColors.terminalRed,
            WDTTColors.terminalRed.copy(alpha = 0.45f)
        )
        NodeState.IDLE -> Triple(idle, idleFg, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
    }
}

private fun categoryShort(category: LogCategory): String = when (category) {
    LogCategory.VK -> "VK"
    LogCategory.CAPTCHA -> "CAP"
    LogCategory.TURN -> "TURN"
    LogCategory.WRAP -> "WRAP"
    LogCategory.SERVER -> "VPS"
    LogCategory.VPN -> "VPN"
    LogCategory.STATS -> "STAT"
    LogCategory.NETWORK -> "NET"
    LogCategory.DEPLOY -> "DEP"
    LogCategory.ERROR -> "ERR"
    LogCategory.SYSTEM -> "SYS"
}

private fun categoryColor(category: LogCategory): Color = when (category) {
    LogCategory.VK -> Color(0xFF818CF8)
    LogCategory.CAPTCHA -> Color(0xFFF472B6)
    LogCategory.TURN -> Color(0xFF38BDF8)
    LogCategory.WRAP -> Color(0xFFFB923C)
    LogCategory.SERVER -> Color(0xFF4ADE80)
    LogCategory.VPN -> Color(0xFF34D399)
    LogCategory.STATS -> WDTTColors.terminalBlue
    LogCategory.NETWORK -> Color(0xFFA78BFA)
    LogCategory.DEPLOY -> Color(0xFF94A3B8)
    LogCategory.ERROR -> WDTTColors.terminalRed
    LogCategory.SYSTEM -> WDTTColors.terminalText
}

private fun stripCategoryPrefix(message: String): String {
    val bracket = message.indexOf(']')
    return if (message.startsWith("[") && bracket in 1..12) {
        message.substring(bracket + 1).trim()
    } else message
}

private fun formatLogsForCopy(
    logs: List<LogEntry>,
    snapshot: TunnelConnectionSnapshot,
    stage: ConnectionStage,
    hint: String,
    stats: String,
    workers: Int
): String = buildString {
    appendLine("=== SKYFLOW M — отчёт диагностики ===")
    appendLine("Этап: ${stageLabel(stage, logs.isNotEmpty())}")
    appendLine("VPS: ${snapshot.peer.ifBlank { ServerConfig.defaultPeer() }}")
    appendLine("Локально: ${snapshot.listen}")
    appendLine("VK: ${snapshot.hashMode} x${snapshot.hashCount}, потоки=${snapshot.workers}")
    appendLine("Воркеры: $workers | $stats")
    if (hint.isNotBlank()) appendLine("Подсказка: $hint")
    appendLine("Путь: APP → VK → TURN → VPS → WireGuard")
    appendLine("--- события ---")
    val fmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    logs.forEach { e ->
        val t = fmt.format(Date(e.timestampMs))
        val tag = categoryShort(e.category)
        val msg = stripCategoryPrefix(e.message)
        appendLine("[$t][$tag] $msg${if (e.count > 1) " (x${e.count})" else ""}")
    }
}
