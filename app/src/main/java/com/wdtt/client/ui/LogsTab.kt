package com.wdtt.client.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
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
import com.wdtt.client.SettingsStore
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class PathNode(val label: String, val short: String) {
    PHONE("Телефон", "APP"),
    VK("Авторизация", "VK"),
    TURN("Облачный релей", "РЕЛЕЙ"),
    VPS("Сервер", "VPS"),
    VPN("Защита", "VPN")
}

private enum class NodeState { IDLE, ACTIVE, DONE, ERROR }

private data class PathNodeUi(val node: PathNode, val state: NodeState)

private enum class LogFilter(val label: String, val categories: Set<LogCategory>?) {
    ALL("Все", null),
    PATH("Путь", setOf(LogCategory.VK, LogCategory.CAPTCHA, LogCategory.TURN, LogCategory.WRAP, LogCategory.SERVER, LogCategory.VPN)),
    SERVER("VPS", setOf(LogCategory.SERVER, LogCategory.WRAP)),
    VK("VK", setOf(LogCategory.VK, LogCategory.CAPTCHA)),
    RELAY("Релей", setOf(LogCategory.TURN)),
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
        val preFiltered = currentLogs.filter { !shouldSuppressLog(it) }
        when (logFilter) {
            LogFilter.ALL -> preFiltered
            LogFilter.ERRORS -> preFiltered.filter { it.isError }
            else -> preFiltered.filter { logFilter.categories!!.contains(it.category) }
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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(SkyflowColors.Background)
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
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

        Spacer(Modifier.height(8.dp))

        LogFilterRow(selected = logFilter, onSelect = { logFilter = it })

        Spacer(Modifier.height(8.dp))

        Card(
            modifier = Modifier.fillMaxSize(),
            colors = CardDefaults.cardColors(containerColor = SkyflowColors.Background),
            shape = RoundedCornerShape(20.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
        ) {
            if (filteredLogs.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize().padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (loggingEnabled) {
                            "Событий пока нет.\nУстановите соединение — здесь появится полный путь: Авторизация → Облачный релей → Сервер → Защита."
                        } else {
                            "Логирование выключено."
                        },
                        color = SkyflowColors.TextSecondary,
                        fontSize = 13.sp,
                        lineHeight = 18.sp,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(5.dp),
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
    val stageLabel = stageLabel(stage, running)
    val captchaMode = snapshot.captchaMode.ifBlank { "auto" }
    val captchaText = when {
        captchaMode.contains("auto", ignoreCase = true) -> "auto"
        captchaMode.contains("manual", ignoreCase = true) -> "ручная"
        else -> "—"
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = SkyflowShapes.Card,
        color = SkyflowColors.Surface,
        border = SkyflowBorders.Default
    ) {
        Column(Modifier.padding(10.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                nodes.forEachIndexed { i, nodeUi ->
                    val dotColor = when (nodeUi.state) {
                        NodeState.DONE -> SkyflowColors.Connected
                        NodeState.ACTIVE -> SkyflowColors.AccentLight
                        NodeState.ERROR -> SkyflowColors.ErrorColor
                        NodeState.IDLE -> SkyflowColors.NodeIdle
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(
                            Modifier
                                .size(7.dp)
                                .clip(CircleShape)
                                .background(dotColor)
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(nodeUi.node.short, fontSize = 6.sp, color = SkyflowColors.TextSecondary)
                    }
                    if (i < nodes.size - 1) {
                        Text(
                            "→",
                            fontSize = 8.sp,
                            color = SkyflowColors.NodeIdle,
                            modifier = Modifier
                                .padding(horizontal = 4.dp)
                                .padding(bottom = 8.dp)
                        )
                    }
                }
            }

            Spacer(Modifier.height(6.dp))
            HorizontalDivider(color = SkyflowColors.Border, thickness = 0.5.dp)
            Spacer(Modifier.height(6.dp))

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                LogMetaItem("Этап", stageLabel, stageColor(stage, running))
                LogMetaItem("Потоки", snapshot.workers.toString(), SkyflowColors.TextAccent)
                LogMetaItem("Воркеры", workers.toString(), SkyflowColors.TextAccent)
                LogMetaItem("Капча", captchaText, SkyflowColors.TextSecondary)
            }

            if (hint.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(hint, fontSize = 8.sp, color = SkyflowColors.ErrorColor, lineHeight = 12.sp)
            }
        }
    }
}

@Composable
private fun LogMetaItem(key: String, value: String, valueColor: Color) {
    Column {
        Text(
            key.uppercase(),
            fontSize = 6.sp,
            color = SkyflowColors.TextSecondary,
            letterSpacing = 0.6.sp
        )
        Text(value, fontSize = 7.sp, color = valueColor)
    }
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LogFilterRow(selected: LogFilter, onSelect: (LogFilter) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        LogFilter.entries.forEach { filter ->
            val isSelected = selected == filter
            Surface(
                onClick = { onSelect(filter) },
                shape = SkyflowShapes.Chip,
                color = if (isSelected) SkyflowColors.Accent else SkyflowColors.Surface,
                border = if (isSelected) null else SkyflowBorders.Accent
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Text(
                        filter.label,
                        fontSize = 8.sp,
                        color = if (isSelected) SkyflowColors.OnAccent else SkyflowColors.TextSecondary
                    )
                }
            }
        }
    }
}

@Composable
fun LogLine(entry: LogEntry) {
    val isError = entry.isError
    val isWarn = !isError &&
        (entry.category == LogCategory.ERROR || entry.message.contains("warn", ignoreCase = true))
    val isOk = !isError && !isWarn && entry.priority <= 2
    val isVk = !isError && !isWarn && !isOk && entry.category == LogCategory.VK

    val accentColor = when {
        isError || entry.category == LogCategory.ERROR -> SkyflowColors.ErrorColor
        isOk -> SkyflowColors.Connected
        entry.category == LogCategory.SYSTEM -> SkyflowColors.Accent
        entry.category == LogCategory.SERVER || entry.category == LogCategory.WRAP -> SkyflowColors.AccentLight
        entry.category == LogCategory.VK -> SkyflowColors.VkStripe
        entry.category == LogCategory.CAPTCHA -> SkyflowColors.WarnColor
        entry.category == LogCategory.TURN -> SkyflowColors.RelayStripe
        isWarn -> SkyflowColors.WarnColor
        isVk -> SkyflowColors.VkStripe
        else -> SkyflowColors.Accent
    }

    val tagColor = categoryColor(entry.category)
    val tag = categoryShort(entry.category)
    val timeStr = remember(entry.timestampMs) {
        SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(entry.timestampMs))
    }
    val displayMessage = transformLogMessage(entry.message)

    var trigger by remember { mutableIntStateOf(0) }
    LaunchedEffect(entry.count) { trigger++ }
    val animatedScale by animateFloatAsState(
        targetValue = if (trigger > 0) 1.12f else 1.0f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "scale",
        finishedListener = { trigger = 0 }
    )

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .drawBehind {
                drawRect(
                    color = accentColor,
                    size = Size(3.dp.toPx(), size.height)
                )
            },
        shape = SkyflowShapes.LogEntry,
        color = SkyflowColors.Surface,
        border = SkyflowBorders.Default
    ) {
        Row(
            modifier = Modifier
                .padding(start = 10.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.Top
        ) {
            Column(modifier = Modifier.width(32.dp)) {
                Text(
                    timeStr,
                    fontSize = 8.sp,
                    color = SkyflowColors.TextSecondary,
                    fontFamily = FontFamily.Monospace
                )
                Spacer(Modifier.height(2.dp))
                Surface(
                    shape = SkyflowShapes.LogTag,
                    color = tagColor.copy(alpha = 0.15f)
                ) {
                    Text(
                        tag,
                        fontSize = 8.sp,
                        fontWeight = FontWeight.Bold,
                        color = tagColor,
                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                    )
                }
            }

            Row(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.Top
            ) {
                Text(
                    text = displayMessage,
                    fontSize = 9.sp,
                    lineHeight = 13.sp,
                    color = SkyflowColors.TextPrimary,
                    modifier = Modifier.weight(1f)
                )
                if (entry.count > 1) {
                    Surface(
                        color = SkyflowColors.Border,
                        shape = SkyflowShapes.VersionBadge,
                        modifier = Modifier
                            .defaultMinSize(minWidth = 18.dp, minHeight = 14.dp)
                            .graphicsLayer(scaleX = animatedScale, scaleY = animatedScale)
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                        ) {
                            Text(
                                text = "×${entry.count}",
                                color = SkyflowColors.TextAccent,
                                fontSize = 7.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }
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
    stage == ConnectionStage.VK_CREDS -> "Авторизация…"
    stage == ConnectionStage.VK_CAPTCHA -> "Проверка…"
    stage == ConnectionStage.SERVER_DTLS -> "Сервер: настройка…"
    stage == ConnectionStage.VPN_READY -> "Защита активна"
    stage == ConnectionStage.FAILED -> "Ошибка подключения"
    running -> "Подключение…"
    else -> "—"
}

private fun stageColor(stage: ConnectionStage, running: Boolean): Color = when {
    stage == ConnectionStage.VPN_READY -> SkyflowColors.Connected
    stage == ConnectionStage.FAILED -> SkyflowColors.ErrorColor
    running -> SkyflowColors.Accent
    else -> SkyflowColors.TextSecondary
}

@Composable
private fun nodeColors(state: NodeState): Triple<Color, Color, Color> {
    val idle = MaterialTheme.colorScheme.surfaceVariant
    val idleFg = MaterialTheme.colorScheme.onSurfaceVariant
    return when (state) {
        NodeState.DONE -> Triple(
            SkyflowColors.Connected.copy(alpha = 0.15f),
            SkyflowColors.Connected,
            SkyflowColors.Connected.copy(alpha = 0.4f)
        )
        NodeState.ACTIVE -> Triple(
            SkyflowColors.Accent.copy(alpha = 0.18f),
            SkyflowColors.Accent,
            SkyflowColors.Accent.copy(alpha = 0.5f)
        )
        NodeState.ERROR -> Triple(
            SkyflowColors.ErrorColor.copy(alpha = 0.15f),
            SkyflowColors.ErrorColor,
            SkyflowColors.ErrorColor.copy(alpha = 0.45f)
        )
        NodeState.IDLE -> Triple(idle, idleFg, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
    }
}

private fun categoryShort(category: LogCategory): String = when (category) {
    LogCategory.VK -> "VK"
    LogCategory.CAPTCHA -> "CAP"
    LogCategory.TURN -> "РЕЛЕЙ"
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
    LogCategory.VK -> SkyflowColors.VkStripe
    LogCategory.CAPTCHA -> SkyflowColors.WarnColor
    LogCategory.TURN -> SkyflowColors.RelayStripe
    LogCategory.WRAP -> SkyflowColors.AccentLight
    LogCategory.SERVER -> SkyflowColors.AccentLight
    LogCategory.VPN -> SkyflowColors.RelayStripe
    LogCategory.STATS -> SkyflowColors.Accent
    LogCategory.NETWORK -> SkyflowColors.AccentLight
    LogCategory.DEPLOY -> SkyflowColors.TextSecondary
    LogCategory.ERROR -> SkyflowColors.ErrorColor
    LogCategory.SYSTEM -> SkyflowColors.Accent
}

private fun stripCategoryPrefix(message: String): String {
    val bracket = message.indexOf(']')
    return if (message.startsWith("[") && bracket in 1..12) {
        message.substring(bracket + 1).trim()
    } else message
}

private fun shouldSuppressLog(entry: LogEntry): Boolean {
    val msg = entry.message
    return msg.contains("failed to persist client ID", ignoreCase = true) ||
        msg.contains("warning:", ignoreCase = true)
}

private fun transformLogMessage(message: String): String {
    val stripped = stripCategoryPrefix(message)

    val dtlsMatch = Regex("""\[STREAM (\d+)].*Established DTLS connection""", RegexOption.IGNORE_CASE).find(stripped)
    if (dtlsMatch != null) return "Канал ${dtlsMatch.groupValues[1]} установлен ✓"

    if (Regex("""TURN urls \(\d+ total\)""", RegexOption.IGNORE_CASE).containsMatchIn(stripped)) {
        return "Облачный релей подключён ✓"
    }

    if (stripped.contains("Success with client_id", ignoreCase = true)) return "Авторизация успешна ✓"

    if (stripped.contains("Handshake done", ignoreCase = true)) return "Рукопожатие выполнено ✓"

    return stripped
        .replace("[TURN]", "[РЕЛЕЙ]")
        .replace("TURN urls", "облачный релей", ignoreCase = true)
        .replace("VK TURN", "облачный релей", ignoreCase = true)
        .replace("DTLS connection", "защищённый канал", ignoreCase = true)
        .replace("DTLS", "шифрование")
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
    appendLine("Сервер: ${snapshot.peer.ifBlank { ServerConfig.defaultPeer() }}")
    appendLine("Локально: ${snapshot.listen}")
    appendLine("Авторизация: ${snapshot.hashMode} x${snapshot.hashCount}, потоки=${snapshot.workers}")
    appendLine("Воркеры: $workers | $stats")
    if (hint.isNotBlank()) appendLine("Подсказка: $hint")
    appendLine("Путь: APP → Авторизация → Облачный релей → Сервер → Защита")
    appendLine("--- события ---")
    val fmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    logs.filter { !shouldSuppressLog(it) }.forEach { e ->
        val t = fmt.format(Date(e.timestampMs))
        val tag = categoryShort(e.category)
        val msg = transformLogMessage(e.message)
        appendLine("[$t][$tag] $msg${if (e.count > 1) " (x${e.count})" else ""}")
    }
}
