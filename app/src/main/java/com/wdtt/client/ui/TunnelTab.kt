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
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.ripple.rememberRipple
import androidx.compose.ui.draw.drawBehind
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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

private val ColorIdle = Color(0xFF52525B)
private val ColorConnecting = Color(0xFFF59E0B)
private val ColorConnected = Color(0xFF4ADE80)

private val Peer = "${ServerConfig.HOST}:${ServerConfig.PORT}"

@Composable
fun TunnelTab() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { SettingsStore(context) }

    val tunnelRunning by TunnelManager.running.collectAsStateWithLifecycle()
    val activeWorkers by TunnelManager.activeWorkers.collectAsStateWithLifecycle()
    val connectionHint by TunnelManager.connectionHint.collectAsStateWithLifecycle()

    var vkLink by rememberSaveable { mutableStateOf("") }
    var elapsedSec by remember { mutableLongStateOf(0L) }
    var pendingConnectAfterVpn by remember { mutableStateOf(false) }
    var isStarting by remember { mutableStateOf(false) }

    val isConnected = tunnelRunning && activeWorkers > 0
    val isConnecting = isStarting || (tunnelRunning && activeWorkers <= 0)
    val vkHash = remember(vkLink) { parseVkHash(vkLink) }
    val powerEnabled = tunnelRunning || isStarting ||
        (vkLink.isNotBlank() && vkHash.length >= 16)

    LaunchedEffect(tunnelRunning) {
        if (!tunnelRunning) isStarting = false
    }

    LaunchedEffect(Unit) {
        val saved = store.wdttLink.first()
        if (saved.isNotBlank()) vkLink = saved
    }

    LaunchedEffect(vkLink) {
        if (vkLink.isBlank()) return@LaunchedEffect
        delay(400)
        store.saveWdttLink(vkLink.trim())
    }

    LaunchedEffect(isConnected) {
        if (!isConnected) {
            elapsedSec = 0L
            return@LaunchedEffect
        }
        elapsedSec = 0L
        while (true) {
            delay(1000)
            elapsedSec++
        }
    }

    val vpnLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (!pendingConnectAfterVpn) return@rememberLauncherForActivityResult
        pendingConnectAfterVpn = false
        if (result.resultCode == android.app.Activity.RESULT_OK && VpnService.prepare(context) == null) {
            scope.launch { connectTunnel(context, store, vkLink) { isStarting = it } }
        } else {
            isStarting = false
            Toast.makeText(context, "VPN-разрешение не выдано", Toast.LENGTH_SHORT).show()
        }
    }

    val startConnect: () -> Unit = {
        val vpnIntent = VpnService.prepare(context)
        if (vpnIntent != null) {
            pendingConnectAfterVpn = true
            vpnLauncher.launch(vpnIntent)
        } else {
            scope.launch { connectTunnel(context, store, vkLink) { isStarting = it } }
        }
    }

    val onPowerClick: () -> Unit = {
        if (tunnelRunning || isStarting) {
            isStarting = false
            disconnectTunnel(context)
        } else if (vkLink.isBlank()) {
            Toast.makeText(context, "Вставьте ссылку VK звонка", Toast.LENGTH_SHORT).show()
        } else if (vkHash.isBlank()) {
            Toast.makeText(
                context,
                "Неверная ссылка: vk.com/call/join/... или https://vk.com/call/join/...",
                Toast.LENGTH_LONG
            ).show()
        } else {
            isStarting = true
            scope.launch {
                withContext(Dispatchers.IO) {
                    store.saveConnectionPassword(ServerConfig.PASSWORD)
                }
                startConnect()
            }
        }
    }

    val scrollState = rememberScrollState()
    val linkFieldBringIntoView = remember { BringIntoViewRequester() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .imePadding()
            .verticalScroll(scrollState)
            .padding(horizontal = 24.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        Spacer(Modifier.height(16.dp))

        PowerButton(
            tunnelRunning = tunnelRunning || isStarting,
            isConnecting = isConnecting,
            enabled = powerEnabled,
            onClick = onPowerClick
        )

        StatusLabel(
            tunnelRunning = tunnelRunning || isStarting,
            isConnecting = isConnecting,
            isConnected = isConnected,
            elapsedSec = elapsedSec,
            hint = connectionHint
        )

        AnimatedVisibility(
            visible = !isConnected,
            enter = fadeIn(tween(300)) + expandVertically(tween(300)),
            exit = fadeOut(tween(200)) + shrinkVertically(tween(200))
        ) {
            VkLinkField(
                value = vkLink,
                onChange = { vkLink = it },
                enabled = !tunnelRunning,
                bringIntoViewRequester = linkFieldBringIntoView
            )
        }

        Spacer(Modifier.height(24.dp))

        ServerStatusRow(
            tunnelRunning = tunnelRunning || isStarting,
            isConnecting = isConnecting,
            isConnected = isConnected
        )

        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun PowerButton(
    tunnelRunning: Boolean,
    isConnecting: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    val targetColor = when {
        !tunnelRunning -> ColorIdle
        isConnecting -> ColorConnecting
        else -> ColorConnected
    }
    val ringColor by animateColorAsState(targetColor, tween(400), label = "ring")
    val bgAlpha by animateFloatAsState(
        if (!tunnelRunning) 0f else 0.09f,
        tween(400),
        label = "bg"
    )

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

    val interactionSource = remember { MutableInteractionSource() }

    Box(
        modifier = Modifier
            .size(124.dp)
            .alpha(if (enabled) 1f else 0.38f)
            .clip(CircleShape)
            .clickable(
                enabled = enabled,
                interactionSource = interactionSource,
                indication = rememberRipple(bounded = true, radius = 62.dp),
                onClick = onClick
            )
            .drawBehind {
                val r = size.minDimension / 2f
                drawCircle(ringColor.copy(alpha = bgAlpha))
                drawCircle(ringColor, radius = r - 2.dp.toPx(), style = Stroke(2.dp.toPx()))
                if (tunnelRunning && isConnecting) {
                    rotate(spinAngle) {
                        drawArc(
                            color = ringColor,
                            startAngle = 0f,
                            sweepAngle = arcSweep,
                            useCenter = false,
                            topLeft = Offset(6.dp.toPx(), 6.dp.toPx()),
                            size = Size(size.width - 12.dp.toPx(), size.height - 12.dp.toPx()),
                            style = Stroke(2.dp.toPx(), cap = StrokeCap.Round)
                        )
                    }
                }
                val iconSize = 46.dp.toPx()
                val iconLeft = (size.width - iconSize) / 2f
                val iconTop = (size.height - iconSize) / 2f
                val cx = size.width / 2f
                val sw = 2.2.dp.toPx()
                drawLine(
                    ringColor,
                    Offset(cx, iconTop),
                    Offset(cx, iconTop + iconSize * 0.36f),
                    sw,
                    StrokeCap.Round
                )
                drawArc(
                    color = ringColor,
                    startAngle = -210f,
                    sweepAngle = 240f,
                    useCenter = false,
                    topLeft = Offset(iconLeft + iconSize * 0.12f, iconTop + iconSize * 0.18f),
                    size = Size(iconSize * 0.76f, iconSize * 0.76f),
                    style = Stroke(sw, cap = StrokeCap.Round)
                )
            }
    )
}

@Composable
private fun StatusLabel(
    tunnelRunning: Boolean,
    isConnecting: Boolean,
    isConnected: Boolean,
    elapsedSec: Long,
    hint: String
) {
    val color = when {
        !tunnelRunning -> MaterialTheme.colorScheme.onSurfaceVariant
        isConnecting -> ColorConnecting
        else -> ColorConnected
    }
    val text = when {
        !tunnelRunning -> "Отключено"
        isConnecting -> "Подключение..."
        else -> {
            val h = elapsedSec / 3600
            val m = (elapsedSec % 3600) / 60
            val s = elapsedSec % 60
            if (h > 0) "Подключено · %02d:%02d:%02d".format(h, m, s)
            else "Подключено · %02d:%02d".format(m, s)
        }
    }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text,
            color = color,
            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium)
        )
        if (isConnecting && hint.isNotBlank()) {
            Text(
                hint,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun VkLinkField(
    value: String,
    onChange: (String) -> Unit,
    enabled: Boolean,
    bringIntoViewRequester: BringIntoViewRequester
) {
    val scope = rememberCoroutineScope()
    val hashInvalid = value.isNotBlank() && parseVkHash(value).isBlank()
    OutlinedTextField(
        value = value,
        onValueChange = { raw ->
            val trimmed = raw.trim()
            onChange(trimmed)
        },
        label = { Text("Ссылка VK звонка") },
        placeholder = {
            Text(
                "vk.com/call/join/...",
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
            )
        },
        supportingText = if (hashInvalid) {
            {
                Text(
                    "Вставьте ссылку vk.com/call/join/HASH или https://vk.com/call/join/HASH",
                    color = MaterialTheme.colorScheme.error
                )
            }
        } else null,
        isError = hashInvalid,
        singleLine = true,
        enabled = enabled,
        modifier = Modifier
            .fillMaxWidth()
            .bringIntoViewRequester(bringIntoViewRequester)
            .onFocusChanged { focus ->
                if (focus.isFocused) {
                    scope.launch {
                        delay(100)
                        bringIntoViewRequester.bringIntoView()
                    }
                }
            },
        shape = RoundedCornerShape(14.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = MaterialTheme.colorScheme.primary,
            unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
        )
    )
}

@Composable
private fun ServerStatusRow(
    tunnelRunning: Boolean,
    isConnecting: Boolean,
    isConnected: Boolean
) {
    val dotColor = when {
        !tunnelRunning -> Color(0xFF3F3F46)
        isConnecting -> ColorConnecting
        else -> ColorConnected
    }
    val statusText = when {
        !tunnelRunning -> "ожидание"
        isConnecting -> "соединяемся..."
        else -> "VK TURN · готов"
    }
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
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

private suspend fun connectTunnel(
    context: Context,
    store: SettingsStore,
    rawLink: String,
    onStarting: (Boolean) -> Unit
) {
    try {
        withContext(Dispatchers.IO) {
            store.saveConnectionPassword(ServerConfig.PASSWORD)
        }

        val hash = parseVkHash(rawLink)
        if (hash.isBlank()) {
            withContext(Dispatchers.Main) {
                onStarting(false)
                Toast.makeText(context, "Неверная ссылка VK звонка", Toast.LENGTH_SHORT).show()
            }
            return
        }

        val linkToSave = rawLink.trim()
        withContext(Dispatchers.IO) {
            store.save(
                peer = Peer,
                vkHashes = hash,
                secondaryVkHash = "",
                workersPerHash = 18,
                protocol = "udp",
                listenPort = 9000
            )
            store.saveConnectionPassword(ServerConfig.PASSWORD)
            store.saveWdttLink(linkToSave)
        }

        val captchaMode = store.captchaMode.first().ifBlank { "auto" }
        val captchaSolveMethod = store.captchaSolveMethod.first().ifBlank { "auto" }

        val svcIntent = Intent(context, TunnelService::class.java).apply {
            action = "START"
            putExtra("peer", Peer)
            putExtra("vk_hashes", hash)
            putExtra("secondary_vk_hash", "")
            putExtra("workers_per_hash", 18)
            putExtra("port", 9000)
            putExtra("sni", "")
            putExtra("connection_password", ServerConfig.PASSWORD)
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
            onStarting(false)
            Toast.makeText(context, "Ошибка запуска: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
}

/** Хеш VK-звонка: не короче 16 символов, иначе пустая строка. */
private fun parseVkHash(input: String): String {
    val hash = VkHashParser.parse(input)
    return if (hash.length >= 16) hash else ""
}

private fun disconnectTunnel(context: Context) {
    TunnelManager.stop()
    try {
        context.startService(
            Intent(context, TunnelService::class.java).apply { action = "STOP" }
        )
    } catch (_: Exception) {
    }
}
