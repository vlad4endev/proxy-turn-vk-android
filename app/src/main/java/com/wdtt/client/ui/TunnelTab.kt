package com.wdtt.client.ui

import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wdtt.client.ServerConfig
import com.wdtt.client.SettingsStore
import com.wdtt.client.TunnelManager
import com.wdtt.client.TunnelService
import com.wdtt.client.VkHashParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val ColorIdle       = Color(0xFF52525B)
private val ColorConnecting = Color(0xFFF59E0B)
private val ColorConnected  = Color(0xFF4ADE80)

private enum class UiState { IDLE, CONNECTING, CONNECTED }

@Composable
fun TunnelTab() {
    val context = LocalContext.current
    val store   = remember { SettingsStore(context) }

    val tunnelRunning by TunnelManager.running.collectAsStateWithLifecycle()
    val activeWorkers by TunnelManager.activeWorkers.collectAsStateWithLifecycle()
    val connectionHint by TunnelManager.connectionHint.collectAsStateWithLifecycle()
    var vkLink     by rememberSaveable { mutableStateOf("") }
    var elapsedSec by remember { mutableLongStateOf(0L) }
    var downMb     by remember { mutableFloatStateOf(0f) }
    var upMb       by remember { mutableFloatStateOf(0f) }
    var uiState    by remember { mutableStateOf(if (tunnelRunning) UiState.CONNECTED else UiState.IDLE) }

    LaunchedEffect(tunnelRunning, activeWorkers) {
        when {
            tunnelRunning && activeWorkers > 0 -> uiState = UiState.CONNECTED
            tunnelRunning -> {
                if (uiState != UiState.IDLE) uiState = UiState.CONNECTING
            }
            else -> {
                uiState = UiState.IDLE
                elapsedSec = 0L
                downMb = 0f
                upMb = 0f
            }
        }
    }

    LaunchedEffect(uiState) {
        if (uiState == UiState.CONNECTED) {
            elapsedSec = 0L
            while (true) {
                delay(1000)
                elapsedSec++
            }
        }
    }

    val logs by TunnelManager.logs.collectAsStateWithLifecycle()
    LaunchedEffect(logs) {
        logs.lastOrNull()?.message?.let { msg ->
            Regex("""(\d+\.?\d*)\s*[МM][Бб].*?(\d+\.?\d*)\s*[МM][Бб]""").find(msg)?.let {
                downMb = it.groupValues[1].toFloatOrNull() ?: downMb
                upMb   = it.groupValues[2].toFloatOrNull() ?: upMb
            }
        }
    }

    val vpnLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (it.resultCode == android.app.Activity.RESULT_OK) {
            uiState = UiState.CONNECTING
            doConnect(context, store, vkLink) { s -> uiState = s }
        }
    }

    fun onPower() {
        when (uiState) {
            UiState.IDLE -> {
                if (vkLink.isBlank()) {
                    Toast.makeText(context, "Вставьте ссылку VK звонка", Toast.LENGTH_SHORT).show()
                    return
                }
                if (VkHashParser.parse(vkLink).isBlank()) {
                    Toast.makeText(
                        context,
                        "Неверная ссылка: вставьте полную ссылку vk.com/call/join/... или только хеш",
                        Toast.LENGTH_LONG
                    ).show()
                    return
                }
                val intent = VpnService.prepare(context)
                if (intent != null) {
                    vpnLauncher.launch(intent)
                } else {
                    uiState = UiState.CONNECTING
                    doConnect(context, store, vkLink) { s -> uiState = s }
                }
            }
            UiState.CONNECTED, UiState.CONNECTING -> {
                uiState = UiState.IDLE
                TunnelManager.stop()
                try {
                    context.startService(
                        Intent(context, TunnelService::class.java).apply { action = "STOP" }
                    )
                } catch (_: Exception) {
                }
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        Spacer(Modifier.height(16.dp))

        PowerButton(uiState = uiState, onClick = ::onPower)

        StatusLabel(uiState = uiState, elapsedSec = elapsedSec, hint = connectionHint)

        AnimatedVisibility(
            visible = uiState == UiState.CONNECTED,
            enter = fadeIn(tween(300)) + expandVertically(tween(300)),
            exit  = fadeOut(tween(200)) + shrinkVertically(tween(200))
        ) {
            TrafficStats(downMb = downMb, upMb = upMb)
        }

        AnimatedVisibility(
            visible = uiState != UiState.CONNECTED,
            enter = fadeIn(tween(300)) + expandVertically(tween(300)),
            exit  = fadeOut(tween(200)) + shrinkVertically(tween(200))
        ) {
            VkLinkField(
                value    = vkLink,
                onChange = { vkLink = it },
                enabled  = uiState == UiState.IDLE
            )
        }

        Spacer(Modifier.weight(1f))

        ServerStatusRow(uiState = uiState)
    }
}

@Composable
private fun PowerButton(uiState: UiState, onClick: () -> Unit) {
    val targetColor = when (uiState) {
        UiState.IDLE       -> ColorIdle
        UiState.CONNECTING -> ColorConnecting
        UiState.CONNECTED  -> ColorConnected
    }
    val ringColor by animateColorAsState(targetColor, tween(400), label = "ring")
    val bgAlpha   by animateFloatAsState(if (uiState == UiState.IDLE) 0f else 0.09f, tween(400), label = "bg")

    val inf = rememberInfiniteTransition(label = "spin")
    val spinAngle by inf.animateFloat(
        0f,
        360f,
        infiniteRepeatable(tween(1100, easing = LinearEasing), RepeatMode.Restart),
        label = "sa"
    )
    val arcSweep by inf.animateFloat(
        25f,
        260f,
        infiniteRepeatable(tween(800, easing = LinearEasing), RepeatMode.Reverse),
        label = "sw"
    )

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(124.dp)
            .clip(CircleShape)
            .clickable(remember { MutableInteractionSource() }, null) { onClick() }
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val r = size.minDimension / 2f
            drawCircle(ringColor.copy(alpha = bgAlpha))
            drawCircle(ringColor, radius = r - 2.dp.toPx(), style = Stroke(2.dp.toPx()))
            if (uiState == UiState.CONNECTING) {
                rotate(spinAngle) {
                    drawArc(
                        color      = ringColor,
                        startAngle = 0f,
                        sweepAngle = arcSweep,
                        useCenter  = false,
                        topLeft    = Offset(6.dp.toPx(), 6.dp.toPx()),
                        size       = Size(size.width - 12.dp.toPx(), size.height - 12.dp.toPx()),
                        style      = Stroke(2.dp.toPx(), cap = StrokeCap.Round)
                    )
                }
            }
        }
        Canvas(Modifier.size(46.dp)) {
            val cx = size.width / 2f
            val sw = 2.2.dp.toPx()
            drawLine(ringColor, Offset(cx, 0f), Offset(cx, size.height * 0.36f), sw, StrokeCap.Round)
            drawArc(
                color      = ringColor,
                startAngle = -210f,
                sweepAngle = 240f,
                useCenter  = false,
                topLeft    = Offset(size.width * 0.12f, size.height * 0.18f),
                size       = Size(size.width * 0.76f, size.height * 0.76f),
                style      = Stroke(sw, cap = StrokeCap.Round)
            )
        }
    }
}

@Composable
private fun StatusLabel(uiState: UiState, elapsedSec: Long, hint: String) {
    val color = when (uiState) {
        UiState.IDLE       -> MaterialTheme.colorScheme.onSurfaceVariant
        UiState.CONNECTING -> ColorConnecting
        UiState.CONNECTED  -> ColorConnected
    }
    val text = when (uiState) {
        UiState.IDLE       -> "Отключено"
        UiState.CONNECTING -> "Подключение..."
        UiState.CONNECTED  -> {
            val h = elapsedSec / 3600
            val m = (elapsedSec % 3600) / 60
            val s = elapsedSec % 60
            if (h > 0) "Подключено · %02d:%02d:%02d".format(h, m, s)
            else "Подключено · %02d:%02d".format(m, s)
        }
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text,
            color = color,
            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium)
        )
        if (uiState == UiState.CONNECTING && hint.isNotBlank()) {
            Text(
                hint,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
        }
    }
}

@Composable
private fun TrafficStats(downMb: Float, upMb: Float) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        StatCard("Загружено", "%.1f".format(downMb), "МБ", Modifier.weight(1f))
        StatCard("Отдано", "%.1f".format(upMb), "МБ", Modifier.weight(1f))
    }
}

@Composable
private fun StatCard(label: String, value: String, unit: String, modifier: Modifier = Modifier) {
    Surface(
        modifier,
        RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Text(value, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Medium))
                Text(
                    unit,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun VkLinkField(value: String, onChange: (String) -> Unit, enabled: Boolean) {
    val hashInvalid = value.isNotBlank() && VkHashParser.looksInvalid(value)
    OutlinedTextField(
        value = value,
        onValueChange = { raw ->
            val trimmed = raw.trim()
            val parsed = VkHashParser.parse(trimmed)
            onChange(parsed.ifBlank { trimmed })
        },
        label       = { Text("Ссылка VK звонка") },
        placeholder = {
            Text(
                "vk.com/call/join/...",
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
            )
        },
        supportingText = if (hashInvalid) {
            {
                Text(
                    "Вставьте полную ссылку или хеш после /join/ (без «https:» в начале)",
                    color = MaterialTheme.colorScheme.error
                )
            }
        } else null,
        isError = hashInvalid,
        singleLine  = true,
        enabled     = enabled,
        modifier    = Modifier.fillMaxWidth(),
        shape       = RoundedCornerShape(14.dp),
        colors      = OutlinedTextFieldDefaults.colors(
            focusedBorderColor   = MaterialTheme.colorScheme.primary,
            unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
        )
    )
}

@Composable
private fun ServerStatusRow(uiState: UiState) {
    val dotColor = when (uiState) {
        UiState.IDLE       -> Color(0xFF3F3F46)
        UiState.CONNECTING -> ColorConnecting
        UiState.CONNECTED  -> ColorConnected
    }
    val statusText = when (uiState) {
        UiState.IDLE       -> "ожидание"
        UiState.CONNECTING -> "соединяемся..."
        UiState.CONNECTED  -> "VK TURN · готов"
    }
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment     = Alignment.CenterVertically
    ) {
        Text(
            "СЕРВЕР",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f),
            letterSpacing = 1.sp
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Canvas(Modifier.size(6.dp)) { drawCircle(dotColor) }
            Text(statusText, style = MaterialTheme.typography.labelSmall, color = dotColor)
        }
    }
}

private fun doConnect(
    context: Context,
    store: SettingsStore,
    rawLink: String,
    onState: (UiState) -> Unit
) {
    TunnelManager.scope.launch {
        try {
            val hash = VkHashParser.parse(rawLink)
            if (hash.isBlank()) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Неверная ссылка VK звонка", Toast.LENGTH_SHORT).show()
                    onState(UiState.IDLE)
                }
                return@launch
            }

            val peer = if (ServerConfig.isConfigured()) {
                ServerConfig.defaultPeer()
            } else {
                ensurePeerHasPort(
                    store.peer.first().ifBlank { ServerConfig.defaultPeer() },
                    ServerConfig.PORT
                )
            }
            val password = if (ServerConfig.isConfigured()) {
                ServerConfig.PASSWORD
            } else {
                store.connectionPassword.first().ifBlank { ServerConfig.PASSWORD }
            }
            if (password.isBlank()) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        context,
                        "Не задан пароль VPS. Укажите в ServerConfig.kt и пересоберите APK",
                        Toast.LENGTH_LONG
                    ).show()
                    onState(UiState.IDLE)
                }
                return@launch
            }
            store.saveConnectionPassword(password)
            val port = store.listenPort.first()
            val captchaMode = store.captchaMode.first().ifBlank { "auto" }
            val captchaSolveMethod = store.captchaSolveMethod.first().ifBlank { "auto" }

            store.save(
                peer = peer,
                vkHashes = hash,
                secondaryVkHash = "",
                workersPerHash = 18,
                protocol = "udp",
                listenPort = port
            )

            val svcIntent = Intent(context, TunnelService::class.java).apply {
                action = "START"
                putExtra("peer", peer)
                putExtra("vk_hashes", hash)
                putExtra("secondary_vk_hash", "")
                putExtra("workers_per_hash", 18)
                putExtra("port", port)
                putExtra("sni", "")
                putExtra("connection_password", password)
                putExtra("protocol", "udp")
                putExtra("captcha_mode", captchaMode)
                putExtra("captcha_solve_method", captchaSolveMethod)
            }
            withContext(Dispatchers.Main) {
                if (Build.VERSION.SDK_INT >= 26) {
                    context.startForegroundService(svcIntent)
                } else {
                    context.startService(svcIntent)
                }
            }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                Toast.makeText(context, "Ошибка: ${e.message}", Toast.LENGTH_SHORT).show()
                onState(UiState.IDLE)
            }
        }
    }
}

private fun ensurePeerHasPort(peer: String, defaultPort: Int): String {
    val trimmed = peer.trim()
    if (trimmed.isBlank()) return trimmed
    if (trimmed.contains(":")) return trimmed
    return "$trimmed:$defaultPort"
}
