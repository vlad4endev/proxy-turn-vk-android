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
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ripple
import androidx.compose.ui.draw.drawBehind
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wdtt.client.YandexParser
import com.wdtt.client.LinkProvider
import com.wdtt.client.ConnectionStage
import com.wdtt.client.NetworkTransport
import com.wdtt.client.rememberNetworkTransport
import com.wdtt.client.ServerConfig
import com.wdtt.client.SettingsStore
import com.wdtt.client.TunnelManager
import com.wdtt.client.TunnelMode
import com.wdtt.client.TunnelService
import com.wdtt.client.VkHashParser
import com.wdtt.client.ui.theme.AdaptiveLayout
import com.wdtt.client.ui.theme.ScreenSize
import com.wdtt.client.ui.theme.adaptiveButtonSize
import com.wdtt.client.ui.theme.adaptivePadding
import com.wdtt.client.ui.theme.adaptiveScreenInsets
import com.wdtt.client.ui.components.CaptchaModal
import com.wdtt.client.ui.theme.readableSp
import com.wdtt.client.ui.theme.rememberScreenSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.graphics.graphicsLayer

private val Peer = "${ServerConfig.HOST}:${ServerConfig.PORT}"

private data class ServiceStatus(
    val name: String,
    val emoji: String,
    val iconBg: Color,
    val host: String,
    val pingMs: Int = -1,
)

enum class LinkStatus { IDLE, CHECKING, ACTIVE, DEAD, NO_NETWORK }

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TunnelTab() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { SettingsStore(context) }

    val tunnelRunning by TunnelManager.running.collectAsStateWithLifecycle()
    val activeWorkers by TunnelManager.activeWorkers.collectAsStateWithLifecycle()
    val connectionHint by TunnelManager.connectionHint.collectAsStateWithLifecycle()
    val connectionStage by TunnelManager.connectionStage.collectAsStateWithLifecycle()
    val stats by TunnelManager.stats.collectAsStateWithLifecycle()
    val savedTunnelMode by store.tunnelMode.collectAsStateWithLifecycle(initialValue = "whitelist")
    val autoSwitch by store.tunnelModeAutoSwitch.collectAsStateWithLifecycle(initialValue = true)
    val manualOverride by store.tunnelModeManualOverride.collectAsStateWithLifecycle(initialValue = false)
    val networkTransport by rememberNetworkTransport()
    var lastNetworkTransport by remember { mutableStateOf<NetworkTransport?>(null) }
    val runtimeTunnelMode by TunnelManager.tunnelMode.collectAsStateWithLifecycle()
    val showCaptcha by TunnelManager.showCaptchaModal.collectAsStateWithLifecycle()
    val activeRoutingModeKey by TunnelManager.activeRoutingMode.collectAsStateWithLifecycle()
    val isSpeedMode = savedTunnelMode == "speed" ||
        runtimeTunnelMode == TunnelMode.SPEED

    var vkLink by rememberSaveable { mutableStateOf("") }
    val connectedSince by TunnelManager.connectedSince.collectAsStateWithLifecycle()
    val elapsedSec by produceState(0L, connectedSince) {
        while (true) {
            value = if (connectedSince > 0L) {
                (System.currentTimeMillis() - connectedSince) / 1000
            } else {
                0L
            }
            delay(1000L)
        }
    }
    var pendingConnectAfterVpn by remember { mutableStateOf(false) }
    var isStarting by remember { mutableStateOf(false) }
    var isCreatingLink by remember { mutableStateOf(false) }
    var autoLinkError by remember { mutableStateOf("") }
    var linkCreatedAt by remember { mutableStateOf(store.getLinkCreatedAt()) }
    val LINK_LIFETIME_SEC = 24 * 3600L
    var linkRemainingSeconds by remember { mutableStateOf(0L) }

    var downMb by remember { mutableStateOf(0f) }
    var upMb by remember { mutableStateOf(0f) }
    var downSpeedMbs by remember { mutableStateOf(0f) }
    var upSpeedMbs by remember { mutableStateOf(0f) }
    var downHistory by remember { mutableStateOf(List(8) { 0f }) }
    var upHistory by remember { mutableStateOf(List(8) { 0f }) }
    var prevDownBytes by remember { mutableLongStateOf(0L) }
    var prevUpBytes by remember { mutableLongStateOf(0L) }
    var prevStatsTimeMs by remember { mutableLongStateOf(0L) }

    var servicesExpanded by remember { mutableStateOf(false) }
    var services by remember {
        mutableStateOf(listOf(
            ServiceStatus("YouTube",   "▶", SkyflowColors.SurfaceHigh, "youtube.com"),
            ServiceStatus("Telegram",  "✈", SkyflowColors.SurfaceHigh, "t.me"),
            ServiceStatus("Instagram", "📷", SkyflowColors.SurfaceHigh, "instagram.com"),
            ServiceStatus("WhatsApp",  "💬", SkyflowColors.SurfaceHigh, "whatsapp.com"),
            ServiceStatus("TikTok",    "🎵", SkyflowColors.SurfaceHigh, "tiktok.com"),
        ))
    }
    var linkProvider by remember { mutableStateOf(LinkProvider.UNKNOWN) }
    var linkStatus by remember { mutableStateOf(LinkStatus.IDLE) }
    var showServersScreen by rememberSaveable { mutableStateOf(false) }
    val activeSpeedServer by TunnelManager.activeSpeedServer.collectAsStateWithLifecycle()

    fun detectProvider(link: String): LinkProvider = YandexParser.detectProvider(link)

    suspend fun checkLink(link: String): LinkStatus {
        return withContext(Dispatchers.IO) {
            try {
                val url = java.net.URL(
                    if (link.startsWith("http")) link else "https://$link"
                )
                val conn = url.openConnection() as java.net.HttpURLConnection
                conn.connectTimeout = 4000
                conn.readTimeout = 4000
                conn.instanceFollowRedirects = false
                conn.requestMethod = "GET"
                conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Android) Chrome/120.0.0.0")
                val code = conn.responseCode
                conn.disconnect()
                when (code) {
                    200 -> LinkStatus.ACTIVE
                    404, 410 -> LinkStatus.DEAD
                    else -> LinkStatus.ACTIVE
                }
            } catch (e: java.net.UnknownHostException) {
                LinkStatus.NO_NETWORK
            } catch (e: Exception) {
                LinkStatus.NO_NETWORK
            }
        }
    }

    val isConnected = if (isSpeedMode) {
        tunnelRunning && connectionStage == ConnectionStage.VPN_READY
    } else {
        tunnelRunning && activeWorkers > 0
    }
    val isConnecting = if (isSpeedMode) {
        isStarting || (tunnelRunning && connectionStage != ConnectionStage.VPN_READY && connectionStage != ConnectionStage.FAILED)
    } else {
        isStarting || (tunnelRunning && activeWorkers <= 0)
    }
    val vkHash = remember(vkLink) { parseVkHash(vkLink) }
    val powerEnabled = tunnelRunning || isStarting ||
        isSpeedMode ||
        (vkLink.isNotBlank() && vkHash.isNotBlank())

    LaunchedEffect(tunnelRunning, activeWorkers, connectionStage, isSpeedMode) {
        when {
            !tunnelRunning -> isStarting = false
            isSpeedMode && connectionStage == ConnectionStage.VPN_READY -> isStarting = false
            !isSpeedMode && activeWorkers > 0 -> isStarting = false
        }
    }

    LaunchedEffect(Unit) {
        val saved = store.wdttLink.first()
        if (saved.isNotBlank()) vkLink = saved
    }

    LaunchedEffect(autoSwitch, networkTransport, tunnelRunning, isStarting, manualOverride) {
        if (tunnelRunning || isStarting || !autoSwitch) {
            lastNetworkTransport = networkTransport
            return@LaunchedEffect
        }

        val previous = lastNetworkTransport
        lastNetworkTransport = networkTransport
        val targetMode = networkTransport.recommendedTunnelMode()
        val transportChanged = previous != null && previous != networkTransport

        when {
            transportChanged -> store.applyAutoTunnelMode(targetMode)
            previous == null && !manualOverride -> {
                val current = store.tunnelMode.first()
                if (current != targetMode) {
                    store.applyAutoTunnelMode(targetMode)
                }
            }
        }
    }

    LaunchedEffect(vkLink) {
        if (vkLink.isBlank()) return@LaunchedEffect
        delay(400)
        store.saveWdttLink(vkLink.trim())
    }

    LaunchedEffect(vkLink) {
        if (vkLink.isNotBlank() && linkCreatedAt == 0L) {
            val now = System.currentTimeMillis() / 1000
            linkCreatedAt = now
            store.saveLinkCreatedAt(now)
        }
    }

    LaunchedEffect(linkCreatedAt) {
        if (linkCreatedAt == 0L) return@LaunchedEffect
        while (true) {
            val now = System.currentTimeMillis() / 1000
            val elapsed = now - linkCreatedAt
            linkRemainingSeconds = maxOf(0L, LINK_LIFETIME_SEC - elapsed)
            if (linkRemainingSeconds <= 0L) break
            delay(1000L)
        }
    }

    val timerColor = when {
        linkRemainingSeconds <= 0 -> SkyflowColors.ErrorColor
        linkRemainingSeconds <= 3600 -> SkyflowColors.WarnColor
        linkRemainingSeconds <= 6 * 3600 -> SkyflowColors.TextPrimary
        else -> SkyflowColors.Connected
    }
    val timerBarColor = when {
        linkRemainingSeconds <= 0 -> SkyflowColors.ErrorColor
        linkRemainingSeconds <= 3600 -> SkyflowColors.WarnColor
        linkRemainingSeconds <= 6 * 3600 -> SkyflowColors.Accent
        else -> SkyflowColors.Connected
    }
    val timerPct = if (LINK_LIFETIME_SEC > 0)
        (linkRemainingSeconds.toFloat() / LINK_LIFETIME_SEC).coerceIn(0f, 1f)
    else 0f
    val timerLabel = when {
        linkRemainingSeconds <= 0 -> "Истекла"
        linkRemainingSeconds <= 3600 -> "Истекает"
        else -> "Действует"
    }
    val timerIcon = when {
        linkRemainingSeconds <= 0 -> "❌"
        linkRemainingSeconds <= 3600 -> "⚠"
        else -> "⏱"
    }
    val autoBtnText = when {
        isCreatingLink -> "Создание..."
        linkRemainingSeconds <= 0 && vkLink.isNotBlank() -> "Ссылка истекла · создать новую"
        linkRemainingSeconds in 1..3600 && vkLink.isNotBlank() -> "Ссылка скоро истечёт · обновить"
        vkLink.isNotBlank() -> "Создана · обновить"
        else -> "⚡ Создать автоматически"
    }
    val autoBtnTextColor = when {
        isCreatingLink -> SkyflowColors.Accent
        linkRemainingSeconds <= 0 && vkLink.isNotBlank() -> SkyflowColors.ErrorColor
        linkRemainingSeconds in 1..3600 && vkLink.isNotBlank() -> SkyflowColors.WarnColor
        vkLink.isNotBlank() -> SkyflowColors.Connected
        else -> SkyflowColors.AccentLight
    }

    LaunchedEffect(vkLink) {
        if (vkLink.isBlank()) {
            linkProvider = LinkProvider.UNKNOWN
            linkStatus = LinkStatus.IDLE
            return@LaunchedEffect
        }
        linkProvider = detectProvider(vkLink)
        if (linkProvider == LinkProvider.UNKNOWN) {
            linkStatus = LinkStatus.IDLE
            return@LaunchedEffect
        }
        delay(600)
        linkStatus = LinkStatus.CHECKING
        linkStatus = checkLink(vkLink)
    }

    LaunchedEffect(isConnected, "traffic_services") {
        if (!isConnected) {
            downMb = 0f; upMb = 0f
            downSpeedMbs = 0f; upSpeedMbs = 0f
            downHistory = List(8) { 0f }; upHistory = List(8) { 0f }
            prevDownBytes = 0L; prevUpBytes = 0L; prevStatsTimeMs = 0L
            services = services.map { it.copy(pingMs = -1) }
            return@LaunchedEffect
        }
        services = services.map { it.copy(pingMs = -1) }
        services.forEachIndexed { i, svc ->
            launch(Dispatchers.IO) {
                val start = System.currentTimeMillis()
                val ping = try {
                    val url = java.net.URL("https://${svc.host}")
                    val conn = url.openConnection() as java.net.HttpURLConnection
                    conn.connectTimeout = 4000
                    conn.readTimeout = 4000
                    conn.requestMethod = "HEAD"
                    conn.connect()
                    val code = conn.responseCode
                    conn.disconnect()
                    if (code in 200..403) (System.currentTimeMillis() - start).toInt() else -2
                } catch (e: Exception) { -2 }
                withContext(Dispatchers.Main) {
                    services = services.toMutableList().also { it[i] = svc.copy(pingMs = ping) }
                }
            }
        }
    }

    LaunchedEffect(stats) {
        if (!isConnected) return@LaunchedEffect
        val downBytes = Regex("""total_dtls-to-backend[:\s]+(\d+)""").find(stats)
            ?.groupValues?.get(1)?.toLongOrNull() ?: return@LaunchedEffect
        val upBytes = Regex("""total_backend-to-dtls[:\s]+(\d+)""").find(stats)
            ?.groupValues?.get(1)?.toLongOrNull() ?: return@LaunchedEffect
        val now = System.currentTimeMillis()
        val dtSec = if (prevStatsTimeMs > 0L) (now - prevStatsTimeMs) / 1000f else 1f
        val newDownSpeed = ((downBytes - prevDownBytes) / (1024f * 1024f) / dtSec).coerceAtLeast(0f)
        val newUpSpeed = ((upBytes - prevUpBytes) / (1024f * 1024f) / dtSec).coerceAtLeast(0f)
        downMb = downBytes / (1024f * 1024f)
        upMb = upBytes / (1024f * 1024f)
        downSpeedMbs = newDownSpeed
        upSpeedMbs = newUpSpeed
        downHistory = downHistory.drop(1) + listOf(newDownSpeed)
        upHistory = upHistory.drop(1) + listOf(newUpSpeed)
        prevDownBytes = downBytes
        prevUpBytes = upBytes
        prevStatsTimeMs = now
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
        } else if (isSpeedMode) {
            isStarting = true
            scope.launch {
                withContext(Dispatchers.IO) {
                    store.saveConnectionPassword(ServerConfig.PASSWORD)
                }
                startConnect()
            }
        } else if (vkLink.isBlank()) {
            Toast.makeText(context, "Вставьте ссылку подключения", Toast.LENGTH_SHORT).show()
        } else if (vkHash.isBlank()) {
            Toast.makeText(
                context,
                "Неверная ссылка: vk.com/call/join/... или vk.ru/call/join/...",
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
    val metrics = rememberScreenMetrics()
    val screenSize = rememberScreenSize()
    val powerSize = adaptiveButtonSize()
    val contentPadding = adaptivePadding()
    val showPowerGlow = screenSize != ScreenSize.COMPACT || !metrics.isCompactHeight
    val glowSize = powerSize + 44.dp

    Box(modifier = Modifier.fillMaxSize()) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .adaptiveScreenInsets()
            .padding(
                horizontal = contentPadding,
                vertical = if (metrics.isCompactHeight) contentPadding else contentPadding + 12.dp,
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(if (metrics.isCompactHeight) 14.dp else 20.dp)
    ) {
        if (!metrics.isVerySmall) {
            Spacer(Modifier.height(if (metrics.isCompactHeight) 4.dp else 12.dp))
        }

        TunnelModeSwitch(
            selectedMode = savedTunnelMode,
            enabled = !tunnelRunning && !isStarting,
            autoSwitchEnabled = autoSwitch,
            manualOverride = manualOverride,
            networkTransport = networkTransport,
            onModeChange = { mode ->
                scope.launch { store.saveTunnelModeManual(mode) }
            },
            onAutoSwitchChange = { enabled ->
                scope.launch {
                    store.saveTunnelModeAutoSwitch(enabled)
                    if (enabled && !tunnelRunning && !isStarting) {
                        store.applyAutoTunnelMode(networkTransport.recommendedTunnelMode())
                    }
                }
            }
        )

        val isCaptchaPending = connectionStage == ConnectionStage.VK_CAPTCHA

        Box(contentAlignment = Alignment.Center) {
            val glowBrush = when {
                isConnected -> SkyflowGradients.PowerConnected
                isCaptchaPending -> SkyflowGradients.PowerCaptcha
                isConnecting -> SkyflowGradients.PowerConnecting
                else -> null
            }
            if (showPowerGlow && glowBrush != null) {
                Box(
                    Modifier
                        .size(glowSize)
                        .background(glowBrush, CircleShape)
                )
            }
            PowerButton(
                tunnelRunning = tunnelRunning || isStarting,
                isConnecting = isConnecting,
                isCaptchaPending = isCaptchaPending,
                enabled = powerEnabled,
                onClick = onPowerClick,
                buttonSize = powerSize,
            )
        }

        StatusLabel(
            tunnelRunning = tunnelRunning || isStarting,
            isConnecting = isConnecting,
            isConnected = isConnected,
            elapsedSec = elapsedSec,
            hint = connectionHint,
            speedServer = if (isSpeedMode) activeSpeedServer else null
        )

        AnimatedVisibility(
            visible = isSpeedMode && !tunnelRunning && !isStarting,
            enter = fadeIn(tween(300)) + expandVertically(tween(300)),
            exit = fadeOut(tween(200)) + shrinkVertically(tween(200))
        ) {
            val servers = remember(showServersScreen) { store.loadServers() }
            val selectedIdx = remember(showServersScreen) { store.getSelectedServerIndex() }
            val serverCount = servers.size
            val selectedName = servers.getOrNull(selectedIdx)?.name ?: "Не выбран"
            val summary = if (serverCount > 0) {
                "$serverCount серверов · $selectedName"
            } else {
                "Настроить серверы"
            }

            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showServersScreen = true },
                shape = SkyflowShapes.Card,
                color = SkyflowColors.GlassSurface,
                border = SkyflowBorders.Glass
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            Icons.Default.Dns,
                            contentDescription = null,
                            tint = SkyflowColors.AccentLight,
                            modifier = Modifier.size(20.dp)
                        )
                        Column {
                            Text(
                                "Серверы",
                                style = SkyflowTextStyles.labelUppercase,
                                color = SkyflowColors.AccentLight
                            )
                            Text(
                                summary,
                                fontSize = readableSp(12f),
                                color = SkyflowColors.TextSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    Icon(
                        Icons.Default.ChevronRight,
                        contentDescription = null,
                        tint = SkyflowColors.TextMuted,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }

        AnimatedVisibility(
            visible = !isConnected && !isSpeedMode,
            enter = fadeIn(tween(300)) + expandVertically(tween(300)),
            exit = fadeOut(tween(200)) + shrinkVertically(tween(200))
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .bringIntoViewRequester(linkFieldBringIntoView),
                shape = SkyflowShapes.Field,
                color = SkyflowColors.GlassSurface,
                border = SkyflowBorders.GlassAccent
            ) {
                Column(
                    modifier = Modifier
                        .padding(horizontal = 16.dp, vertical = 14.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "Ссылка подключения",
                            style = SkyflowTextStyles.labelUppercase,
                            color = SkyflowColors.AccentLight,
                            modifier = Modifier.padding(bottom = 5.dp)
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            if (linkProvider != LinkProvider.UNKNOWN) {
                                val tagBg: Color
                                val tagBorder: Color
                                val tagColor: Color
                                val tagText: String
                                when (linkProvider) {
                                    LinkProvider.VK -> {
                                        tagBg = SkyflowColors.VkStripe.copy(alpha = 0.15f)
                                        tagBorder = SkyflowColors.VkStripe.copy(alpha = 0.3f)
                                        tagColor = SkyflowColors.VkStripe
                                        tagText = "VK"
                                    }
                                    LinkProvider.YANDEX -> {
                                        tagBg = SkyflowColors.YandexTag.copy(alpha = 0.15f)
                                        tagBorder = SkyflowColors.YandexTag.copy(alpha = 0.3f)
                                        tagColor = SkyflowColors.YandexTag
                                        tagText = "Яндекс"
                                    }
                                    else -> {
                                        tagBg = Color.Transparent
                                        tagBorder = Color.Transparent
                                        tagColor = Color.Transparent
                                        tagText = ""
                                    }
                                }
                                Surface(
                                    shape = SkyflowShapes.LogEntry,
                                    color = tagBg,
                                    border = BorderStroke(0.5.dp, tagBorder)
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(3.dp)
                                    ) {
                                        Box(
                                            Modifier
                                                .size(4.dp)
                                                .clip(CircleShape)
                                                .background(tagColor)
                                        )
                                        Text(
                                            tagText,
                                            fontSize = readableSp(12f), fontWeight = FontWeight.Bold,
                                            color = tagColor
                                        )
                                    }
                                }
                            }
                            when (linkStatus) {
                                LinkStatus.CHECKING -> {
                                    Surface(
                                        shape = SkyflowShapes.LogEntry,
                                        color = SkyflowColors.AccentMuted,
                                        border = BorderStroke(0.5.dp, SkyflowColors.Accent.copy(alpha = 0.25f))
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                                        ) {
                                            CircularProgressIndicator(
                                                modifier = Modifier.size(6.dp),
                                                color = SkyflowColors.AccentLight,
                                                strokeWidth = 1.dp
                                            )
                                            Text(
                                                "Проверка", fontSize = readableSp(12f),
                                                fontWeight = FontWeight.SemiBold,
                                                color = SkyflowColors.AccentLight
                                            )
                                        }
                                    }
                                }
                                LinkStatus.ACTIVE -> {
                                    Surface(
                                        shape = SkyflowShapes.LogEntry,
                                        color = SkyflowColors.Connected.copy(alpha = 0.125f),
                                        border = BorderStroke(0.5.dp, SkyflowColors.Connected.copy(alpha = 0.25f))
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(3.dp)
                                        ) {
                                            Box(
                                                Modifier
                                                    .size(4.dp)
                                                    .clip(CircleShape)
                                                    .background(SkyflowColors.Connected)
                                            )
                                            Text(
                                                "Активна", fontSize = readableSp(12f),
                                                fontWeight = FontWeight.SemiBold,
                                                color = SkyflowColors.Connected
                                            )
                                        }
                                    }
                                }
                                LinkStatus.DEAD -> {
                                    Surface(
                                        shape = SkyflowShapes.LogEntry,
                                        color = SkyflowColors.ErrorColor.copy(alpha = 0.125f),
                                        border = BorderStroke(0.5.dp, SkyflowColors.ErrorColor.copy(alpha = 0.25f))
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(3.dp)
                                        ) {
                                            Box(
                                                Modifier
                                                    .size(4.dp)
                                                    .clip(CircleShape)
                                                    .background(SkyflowColors.ErrorColor)
                                            )
                                            Text(
                                                "Недоступна", fontSize = readableSp(12f),
                                                fontWeight = FontWeight.SemiBold,
                                                color = SkyflowColors.ErrorColor
                                            )
                                        }
                                    }
                                }
                                LinkStatus.NO_NETWORK -> {
                                    Surface(
                                        shape = SkyflowShapes.LogEntry,
                                        color = SkyflowColors.WarnColor.copy(alpha = 0.125f),
                                        border = BorderStroke(0.5.dp, SkyflowColors.WarnColor.copy(alpha = 0.25f))
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(3.dp)
                                        ) {
                                            Box(
                                                Modifier
                                                    .size(4.dp)
                                                    .clip(CircleShape)
                                                    .background(SkyflowColors.WarnColor)
                                            )
                                            Text(
                                                "Не проверена", fontSize = readableSp(12f),
                                                fontWeight = FontWeight.SemiBold,
                                                color = SkyflowColors.WarnColor
                                            )
                                        }
                                    }
                                }
                                else -> {}
                            }
                        }
                    }
                    BasicTextField(
                        value = vkLink,
                        onValueChange = { newValue ->
                            val trimmed = newValue.trim()
                            vkLink = trimmed
                            if (trimmed.isBlank()) {
                                linkCreatedAt = 0L
                                linkRemainingSeconds = 0L
                                scope.launch { store.saveLinkCreatedAt(0L) }
                            }
                        },
                        readOnly = tunnelRunning,
                        maxLines = 1,
                        textStyle = TextStyle(
                            fontSize = 13.sp,
                            fontFamily = interFontFamily,
                            fontWeight = FontWeight.Medium,
                            color = SkyflowColors.TextAccent,
                        ),
                        decorationBox = { inner ->
                            if (vkLink.isEmpty()) {
                                Text(
                                    "vk.com/call/join/... или telemost.yandex.ru/j/...",
                                    fontSize = 11.sp, color = SkyflowColors.Placeholder
                                )
                            }
                            inner()
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .onFocusChanged { focus ->
                                if (focus.isFocused) {
                                    scope.launch {
                                        delay(100)
                                        linkFieldBringIntoView.bringIntoView()
                                    }
                                }
                            }
                    )
                    AnimatedVisibility(
                        visible = vkLink.isNotBlank() && linkCreatedAt > 0L
                    ) {
                        Column {
                            Spacer(Modifier.height(5.dp))
                            HorizontalDivider(color = SkyflowColors.Border, thickness = 0.5.dp)
                            Spacer(Modifier.height(5.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(timerIcon, fontSize = 10.sp)
                                Text(
                                    timerLabel,
                                    fontSize = readableSp(12f),
                                    color = if (linkRemainingSeconds <= 3600) timerColor
                                    else SkyflowColors.TextSecondary
                                )
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(3.dp)
                                        .clip(RoundedCornerShape(2.dp))
                                        .background(SkyflowColors.Border)
                                ) {
                                    val animatedPct by animateFloatAsState(
                                        timerPct,
                                        animationSpec = tween(500),
                                        label = "timer_pct"
                                    )
                                    Box(
                                        modifier = Modifier
                                            .fillMaxHeight()
                                            .fillMaxWidth(animatedPct)
                                            .clip(RoundedCornerShape(2.dp))
                                            .background(timerBarColor)
                                    )
                                }
                                Text(
                                    text = if (linkRemainingSeconds > 0)
                                        formatRemaining(linkRemainingSeconds)
                                    else "00:00:00",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace,
                                    color = timerColor
                                )
                            }
                        }
                    }
                    AnimatedVisibility(visible = !isConnected) {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Spacer(Modifier.height(8.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Surface(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clickable(enabled = !isCreatingLink) {
                                            if (!isCreatingLink) {
                                                isCreatingLink = true
                                                autoLinkError = ""
                                                scope.launch {
                                                    val link = createVkCallLink()
                                                    if (link != null) {
                                                        vkLink = link
                                                        autoLinkError = ""
                                                        val now = System.currentTimeMillis() / 1000
                                                        linkCreatedAt = now
                                                        store.saveLinkCreatedAt(now)
                                                    } else {
                                                        autoLinkError = "Не удалось создать — вставьте вручную"
                                                    }
                                                    isCreatingLink = false
                                                }
                                            }
                                        },
                                    shape = SkyflowShapes.Chip,
                                    color = SkyflowColors.GlassSurfaceElevated,
                                    border = SkyflowBorders.GlassAccent
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 11.dp),
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        if (isCreatingLink) {
                                            CircularProgressIndicator(
                                                modifier = Modifier.size(10.dp),
                                                color = SkyflowColors.Accent,
                                                strokeWidth = 1.5.dp
                                            )
                                        }
                                        Text(
                                            autoBtnText,
                                            fontSize = 12.sp,
                                            color = autoBtnTextColor,
                                            fontWeight = FontWeight.Medium,
                                            fontFamily = interFontFamily
                                        )
                                    }
                                }
                            }
                            if (autoLinkError.isNotBlank()) {
                                Text(
                                    text = autoLinkError,
                                    fontSize = 10.sp,
                                    color = SkyflowColors.WarnColor,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(7.dp))
                    HorizontalDivider(color = SkyflowColors.Border, thickness = 0.5.dp)
                    Spacer(Modifier.height(7.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val parsedHash = parseVkHash(vkLink)
                        when {
                            linkStatus == LinkStatus.DEAD -> {
                                Text(
                                    "Звонок завершён или ссылка устарела",
                                    fontSize = readableSp(12f), color = SkyflowColors.ErrorColor,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                            linkStatus == LinkStatus.NO_NETWORK -> {
                                Text(
                                    "Нет подключения для проверки",
                                    fontSize = readableSp(12f), color = SkyflowColors.WarnColor,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                            parsedHash.isNotBlank() -> {
                                Row(
                                    modifier = Modifier.weight(1f),
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text("Хеш:", fontSize = readableSp(12f), color = SkyflowColors.TextSecondary)
                                    Text(
                                        parsedHash,
                                        fontSize = readableSp(12f),
                                        fontFamily = FontFamily.Monospace,
                                        color = if (linkProvider == LinkProvider.YANDEX)
                                            SkyflowColors.YandexTag else SkyflowColors.AccentLight,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f)
                                    )
                                    if (linkStatus == LinkStatus.ACTIVE) {
                                        Text("✓ Готова", fontSize = readableSp(12f), color = SkyflowColors.Connected)
                                    }
                                }
                            }
                            vkLink.isBlank() -> {
                                Text(
                                    "Вставьте ссылку для проверки",
                                    fontSize = readableSp(12f), color = SkyflowColors.Placeholder,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                            else -> {
                                Text(
                                    "Формат не распознан",
                                    fontSize = readableSp(12f), color = SkyflowColors.ErrorColor,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                        if (vkLink.isBlank()) {
                            val clipboardManager = LocalClipboardManager.current
                            Surface(
                                shape = SkyflowShapes.PasteButton,
                                color = SkyflowColors.Border,
                                border = SkyflowBorders.Accent,
                                modifier = Modifier.clickable {
                                    clipboardManager.getText()?.text?.let { vkLink = it }
                                }
                            ) {
                                Text(
                                    "Вставить",
                                    fontSize = readableSp(12f), color = SkyflowColors.TextSecondary,
                                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
                                )
                            }
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(4.dp))

        ServerStatusRow(
            tunnelRunning = tunnelRunning || isStarting,
            isConnecting = isConnecting,
            isConnected = isConnected,
            isSpeedMode = isSpeedMode,
            speedServer = activeSpeedServer,
            routingModeLabel = if (isSpeedMode && isConnected) {
                com.wdtt.client.xray.XrayRoutingMode.fromKey(activeRoutingModeKey).label
            } else null
        )

        Spacer(Modifier.height(12.dp))

        AnimatedVisibility(
            visible = isConnected,
            enter = fadeIn(tween(300)) + expandVertically(tween(300)),
            exit = fadeOut(tween(200)) + shrinkVertically(tween(200))
        ) {
            val livePulse = rememberInfiniteTransition(label = "live_dot")
            val liveDotAlpha by livePulse.animateFloat(
                initialValue = 0.5f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(tween(700, easing = LinearEasing), RepeatMode.Reverse),
                label = "dot_pulse"
            )
            val chevronRotation by animateFloatAsState(
                targetValue = if (servicesExpanded) 180f else 0f,
                animationSpec = tween(250),
                label = "chevron"
            )

            Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                // ── Traffic Block ──────────────────────────────────────────
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = SkyflowShapes.Card,
                    color = SkyflowColors.GlassSurface,
                    border = SkyflowBorders.Glass
                ) {
                    Column {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "ТРАФИК",
                                style = SkyflowTextStyles.labelUppercase,
                                color = SkyflowColors.TextSecondary
                            )
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(3.dp)
                            ) {
                                Box(
                                    Modifier
                                        .size(4.dp)
                                        .clip(CircleShape)
                                        .background(SkyflowColors.Connected)
                                        .graphicsLayer { alpha = liveDotAlpha }
                                )
                                Text(
                                    "Live", fontSize = readableSp(12f), color = SkyflowColors.Connected,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                        HorizontalDivider(color = SkyflowColors.Border, thickness = 0.5.dp)
                        Row(
                            modifier = Modifier.padding(8.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            TrafficBox(
                                label = "Загружено",
                                arrow = "↓",
                                arrowColor = SkyflowColors.TrafficDown,
                                valueMb = downMb,
                                speedMbs = downSpeedMbs,
                                history = downHistory,
                                barColor = SkyflowColors.TrafficDownBar,
                                modifier = Modifier.weight(1f)
                            )
                            TrafficBox(
                                label = "Отдано",
                                arrow = "↑",
                                arrowColor = SkyflowColors.AccentLight,
                                valueMb = upMb,
                                speedMbs = upSpeedMbs,
                                history = upHistory,
                                barColor = SkyflowColors.Accent,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }

                // ── Services Block ─────────────────────────────────────────
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = SkyflowShapes.Card,
                    color = SkyflowColors.GlassSurface,
                    border = SkyflowBorders.Glass
                ) {
                    Column {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { servicesExpanded = !servicesExpanded }
                                .padding(horizontal = 14.dp, vertical = 11.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    "СЕРВИСЫ",
                                    style = SkyflowTextStyles.labelUppercase,
                                    color = SkyflowColors.TextSecondary
                                )
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    services.forEach { svc ->
                                        val dotColor = when {
                                            svc.pingMs == -1 -> SkyflowColors.Accent
                                            svc.pingMs == -2 -> SkyflowColors.ErrorColor
                                            svc.pingMs > 300 -> SkyflowColors.WarnColor
                                            else -> SkyflowColors.Connected
                                        }
                                        Box(Modifier.size(4.dp).clip(CircleShape).background(dotColor))
                                    }
                                }
                                val okCount = services.count { it.pingMs > 0 && it.pingMs <= 300 }
                                val totalDone = services.count { it.pingMs != -1 }
                                if (totalDone > 0) {
                                    Text(
                                        "$okCount/${services.size} ОК",
                                        fontSize = readableSp(12f),
                                        fontWeight = FontWeight.Bold,
                                        color = if (okCount == services.size) SkyflowColors.Connected
                                        else SkyflowColors.WarnColor
                                    )
                                }
                            }
                            Text(
                                "▾", fontSize = 12.sp, color = SkyflowColors.TextMuted,
                                modifier = Modifier.graphicsLayer { rotationX = chevronRotation }
                            )
                        }

                        AnimatedVisibility(
                            visible = servicesExpanded,
                            enter = expandVertically(tween(250)) + fadeIn(tween(250)),
                            exit = shrinkVertically(tween(200)) + fadeOut(tween(150))
                        ) {
                            Column {
                                HorizontalDivider(color = SkyflowColors.Border, thickness = 0.5.dp)
                                services.forEachIndexed { idx, svc ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 10.dp, vertical = 7.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(22.dp)
                                                .clip(SkyflowShapes.Tag)
                                                .background(svc.iconBg),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(svc.emoji, fontSize = 11.sp)
                                        }
                                        Text(
                                            svc.name,
                                            fontSize = readableSp(12f),
                                            fontWeight = FontWeight.Medium,
                                            color = SkyflowColors.TextPrimary,
                                            modifier = Modifier.weight(1f)
                                        )
                                        when {
                                            svc.pingMs == -1 -> {
                                                CircularProgressIndicator(
                                                    modifier = Modifier.size(8.dp),
                                                    color = SkyflowColors.AccentLight,
                                                    strokeWidth = 1.dp
                                                )
                                            }
                                            svc.pingMs == -2 -> {
                                                Box(
                                                    Modifier.size(5.dp).clip(CircleShape)
                                                        .background(SkyflowColors.ErrorColor)
                                                )
                                                Text(
                                                    "Недоступен", fontSize = readableSp(12f),
                                                    color = SkyflowColors.ErrorColor,
                                                    fontWeight = FontWeight.SemiBold
                                                )
                                            }
                                            svc.pingMs > 300 -> {
                                                Text(
                                                    "${svc.pingMs}мс", fontSize = readableSp(12f),
                                                    color = SkyflowColors.TextMuted
                                                )
                                                Box(
                                                    Modifier.size(5.dp).clip(CircleShape)
                                                        .background(SkyflowColors.WarnColor)
                                                )
                                                Text(
                                                    "Мед.", fontSize = readableSp(12f),
                                                    color = SkyflowColors.WarnColor,
                                                    fontWeight = FontWeight.SemiBold
                                                )
                                            }
                                            else -> {
                                                Text(
                                                    "${svc.pingMs}мс", fontSize = readableSp(12f),
                                                    color = SkyflowColors.TextMuted
                                                )
                                                Box(
                                                    Modifier.size(5.dp).clip(CircleShape)
                                                        .background(SkyflowColors.Connected)
                                                )
                                                Text(
                                                    "ОК", fontSize = readableSp(12f),
                                                    color = SkyflowColors.Connected,
                                                    fontWeight = FontWeight.SemiBold
                                                )
                                            }
                                        }
                                    }
                                    if (idx < services.size - 1) {
                                        HorizontalDivider(
                                            modifier = Modifier.padding(horizontal = 10.dp),
                                            color = SkyflowColors.Border,
                                            thickness = 0.5.dp
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

        AnimatedVisibility(
            visible = showCaptcha,
            modifier = Modifier.fillMaxSize(),
            enter = fadeIn(animationSpec = tween(300)) +
                slideInVertically(
                    initialOffsetY = { it / 4 },
                    animationSpec = tween(300)
                ),
            exit = fadeOut(animationSpec = tween(200)) +
                slideOutVertically(
                    targetOffsetY = { it / 4 },
                    animationSpec = tween(200)
                )
        ) {
            CaptchaModal(
                isVisible = showCaptcha,
                onDismiss = { TunnelManager.dismissCaptchaModal() },
                onCaptchaSolved = { TunnelManager.onCaptchaSolved() }
            )
        }

        if (showServersScreen) {
            ServersScreen(
                settingsStore = store,
                onBack = { showServersScreen = false }
            )
        }
    }
}

@Composable
private fun TunnelModeSwitch(
    selectedMode: String,
    enabled: Boolean,
    autoSwitchEnabled: Boolean,
    manualOverride: Boolean,
    networkTransport: NetworkTransport,
    onModeChange: (String) -> Unit,
    onAutoSwitchChange: (Boolean) -> Unit,
) {
    val isSpeed = selectedMode == "speed"
    val autoHint = when (networkTransport) {
        NetworkTransport.WIFI -> "Wi‑Fi → скоростной"
        NetworkTransport.CELLULAR -> "LTE → белый список"
        NetworkTransport.UNKNOWN -> "Wi‑Fi → скоростной, LTE → белый список"
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (enabled || autoSwitchEnabled) 1f else 0.5f),
        shape = SkyflowShapes.Card,
        color = SkyflowColors.GlassSurface,
        border = SkyflowBorders.Glass
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "РЕЖИМ ПОДКЛЮЧЕНИЯ",
                    style = SkyflowTextStyles.labelUppercase,
                    color = SkyflowColors.TextMuted
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        "Авто",
                        fontSize = readableSp(10f),
                        color = if (autoSwitchEnabled) SkyflowColors.AccentLight else SkyflowColors.TextMuted
                    )
                    Switch(
                        checked = autoSwitchEnabled,
                        onCheckedChange = onAutoSwitchChange,
                        modifier = Modifier.height(24.dp),
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = SkyflowColors.Connected,
                            checkedTrackColor = SkyflowColors.Connected.copy(alpha = 0.35f),
                            uncheckedThumbColor = SkyflowColors.TextMuted,
                            uncheckedTrackColor = SkyflowColors.Border
                        )
                    )
                }
            }
            if (autoSwitchEnabled) {
                Text(
                    "Авто: $autoHint",
                    fontSize = readableSp(10f),
                    color = SkyflowColors.TextSecondary,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            if (manualOverride && autoSwitchEnabled) {
                Text(
                    "Выбрано вручную — авто снова при смене Wi‑Fi/LTE",
                    fontSize = readableSp(10f),
                    color = SkyflowColors.WarnColor,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TunnelModeOption(
                    label = "☁ Белый список",
                    subtitle = "VK/TURN → WG",
                    selected = !isSpeed,
                    enabled = enabled,
                    accentColor = SkyflowColors.AccentLight,
                    modifier = Modifier.weight(1f),
                    onClick = { onModeChange("whitelist") }
                )
                TunnelModeOption(
                    label = "⚡ Скоростной",
                    subtitle = "WG → VPS",
                    selected = isSpeed,
                    enabled = enabled,
                    accentColor = SkyflowColors.Connected,
                    modifier = Modifier.weight(1f),
                    onClick = { onModeChange("speed") }
                )
            }
            if (!enabled) {
                Text(
                    "Смена режима доступна после отключения",
                    fontSize = readableSp(10f),
                    color = SkyflowColors.TextMuted,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

@Composable
private fun TunnelModeOption(
    label: String,
    subtitle: String,
    selected: Boolean,
    enabled: Boolean,
    accentColor: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val bg by animateColorAsState(
        if (selected) accentColor.copy(alpha = 0.18f) else SkyflowColors.GlassSurfaceElevated,
        label = "mode_opt_bg"
    )
    val borderColor by animateColorAsState(
        if (selected) accentColor.copy(alpha = 0.55f) else SkyflowColors.Border,
        label = "mode_opt_border"
    )
    Surface(
        modifier = modifier,
        shape = SkyflowShapes.Chip,
        color = bg,
        border = BorderStroke(if (selected) 1.dp else 0.5.dp, borderColor),
        onClick = { if (enabled) onClick() },
        enabled = enabled,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                label,
                fontSize = readableSp(11f),
                fontWeight = FontWeight.Bold,
                color = if (selected) accentColor else SkyflowColors.TextSecondary,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                subtitle,
                fontSize = readableSp(9f),
                color = SkyflowColors.TextMuted,
                textAlign = TextAlign.Center,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun PowerButton(
    tunnelRunning: Boolean,
    isConnecting: Boolean,
    isCaptchaPending: Boolean = false,
    enabled: Boolean,
    onClick: () -> Unit,
    buttonSize: androidx.compose.ui.unit.Dp = 140.dp,
) {
    val targetColor = when {
        !tunnelRunning -> SkyflowColors.Idle
        isCaptchaPending -> SkyflowColors.CaptchaBlue   // синий пока ждём капчу
        isConnecting -> SkyflowColors.Connecting
        else -> SkyflowColors.Connected
    }
    val ringColor by animateColorAsState(targetColor, tween(400), label = "ring")

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
    val pulseScale by inf.animateFloat(
        1f,
        1.04f,
        infiniteRepeatable(tween(1400, easing = LinearEasing), RepeatMode.Reverse),
        label = "pulse"
    )

    val interactionSource = remember { MutableInteractionSource() }
    val isActive = tunnelRunning && !isConnecting

    Box(
        modifier = Modifier
            .size(buttonSize)
            .then(if (isActive) Modifier.graphicsLayer { scaleX = pulseScale; scaleY = pulseScale } else Modifier)
            .alpha(if (enabled) 1f else 0.38f)
            .clip(CircleShape)
            .clickable(
                enabled = enabled,
                interactionSource = interactionSource,
                indication = ripple(bounded = true, radius = buttonSize / 2),
                onClick = onClick
            )
            .drawBehind {
                val canvasSize = this.size
                val r = canvasSize.minDimension / 2f
                val ringStroke = 2.dp.toPx()
                val ringRadius = r - ringStroke / 2f

                if (isActive) {
                    drawCircle(
                        ringColor.copy(alpha = 0.12f),
                        radius = ringRadius + 6.dp.toPx()
                    )
                }

                drawCircle(
                    brush = androidx.compose.ui.graphics.Brush.radialGradient(
                        colors = listOf(
                            SkyflowColors.GlassSurfaceElevated,
                            SkyflowColors.Surface
                        ),
                        center = center,
                        radius = r
                    ),
                    radius = r - ringStroke
                )

                drawCircle(ringColor, radius = ringRadius, style = Stroke(ringStroke))
                if (tunnelRunning && isConnecting) {
                    rotate(spinAngle) {
                        drawArc(
                            color = ringColor,
                            startAngle = 0f,
                            sweepAngle = arcSweep,
                            useCenter = false,
                            topLeft = Offset(ringStroke / 2f, ringStroke / 2f),
                            size = Size(canvasSize.width - ringStroke, canvasSize.height - ringStroke),
                            style = Stroke(ringStroke, cap = StrokeCap.Round)
                        )
                    }
                }
                val iconSize = 50.dp.toPx()
                val iconLeft = (canvasSize.width - iconSize) / 2f
                val iconTop = (canvasSize.height - iconSize) / 2f
                val cx = canvasSize.width / 2f
                val sw = 2.dp.toPx()
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
    hint: String,
    speedServer: com.wdtt.client.xray.VlessServer? = null
) {
    val color = when {
        !tunnelRunning -> SkyflowColors.Idle
        isConnecting -> SkyflowColors.Connecting
        else -> SkyflowColors.Connected
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
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            text,
            style = MaterialTheme.typography.headlineMedium,
            color = color,
        )
        if (isConnected) {
            if (speedServer != null) {
                Text(
                    speedServer.name,
                    style = MaterialTheme.typography.bodySmall,
                    color = SkyflowColors.TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                val protocolLabel = buildString {
                    append("VLESS")
                    if (speedServer.type != "tcp") append(" · ${speedServer.type.uppercase()}")
                    when (speedServer.security) {
                        "tls" -> append(" · TLS")
                        "reality" -> append(" · Reality")
                    }
                }
                Text(
                    protocolLabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = SkyflowColors.TextMuted,
                )
                if (speedServer.latency > 0) {
                    Text(
                        "${speedServer.latency} ms",
                        style = MaterialTheme.typography.bodySmall,
                        color = SkyflowColors.Connected,
                    )
                }
            } else {
                Text(
                    "Соединение защищено",
                    style = MaterialTheme.typography.bodySmall,
                    color = SkyflowColors.TextSecondary,
                )
            }
        }
        if (isConnecting && hint.isNotBlank()) {
            Text(
                hint,
                style = MaterialTheme.typography.bodySmall,
                color = SkyflowColors.TextMuted,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun ServerStatusRow(
    tunnelRunning: Boolean,
    isConnecting: Boolean,
    isConnected: Boolean,
    isSpeedMode: Boolean = false,
    speedServer: com.wdtt.client.xray.VlessServer? = null,
    routingModeLabel: String? = null
) {
    val dotColor = when {
        !tunnelRunning -> SkyflowColors.Placeholder
        isConnecting -> SkyflowColors.Connecting
        else -> SkyflowColors.Connected
    }
    val header = if (isSpeedMode) "VLESS СЕРВЕР" else "ОБЛАЧНЫЙ РЕЛЕЙ"
    val statusText = when {
        !tunnelRunning -> if (isSpeedMode) "ожидание" else "ожидание"
        isConnecting -> "подключение..."
        isConnected && isSpeedMode && speedServer != null -> {
            buildString {
                append(speedServer.name)
                if (speedServer.latency > 0) append(" · ${speedServer.latency}ms")
            }
        }
        isConnected -> "активен"
        else -> "готов"
    }
    val subtitle = if (isConnected && isSpeedMode && speedServer != null) {
        buildString {
            append("VLESS")
            if (speedServer.type != "tcp") append(" · ${speedServer.type.uppercase()}")
            when (speedServer.security) {
                "tls" -> append(" · TLS")
                "reality" -> append(" · Reality")
            }
            if (!routingModeLabel.isNullOrBlank()) append(" · $routingModeLabel")
        }
    } else null

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            header,
            style = SkyflowTextStyles.labelUppercase,
            color = SkyflowColors.TextMuted
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Canvas(Modifier.size(5.dp)) { drawCircle(dotColor) }
            Text(statusText, fontSize = readableSp(12f), color = dotColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        subtitle?.let {
            Text(it, fontSize = readableSp(10f), color = SkyflowColors.TextMuted)
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

        val tunnelMode = store.tunnelMode.first()
        val serverWgPort = store.serverWgPort.first()
        val isSpeed = tunnelMode == "speed"

        if (isSpeed) {
            val storedPeer = withContext(Dispatchers.IO) { store.peer.first() }
            if (storedPeer.trim().substringBefore(":").isBlank()) {
                withContext(Dispatchers.Main) {
                    onStarting(false)
                    Toast.makeText(context, "Укажите IP сервера в настройках", Toast.LENGTH_SHORT).show()
                }
                return
            }

            val svcIntent = Intent(context, TunnelService::class.java).apply {
                action = "START"
                putExtra("peer", storedPeer)
                putExtra("vk_hashes", "")
                putExtra("secondary_vk_hash", "")
                putExtra("workers_per_hash", 18)
                putExtra("port", 9000)
                putExtra("sni", "")
                putExtra("connection_password", ServerConfig.PASSWORD)
                putExtra("protocol", "udp")
                putExtra("provider", "vk")
                putExtra("captcha_mode", "auto")
                putExtra("captcha_solve_method", "auto")
                putExtra("tunnel_mode", "speed")
            }

            withContext(Dispatchers.Main) {
                if (Build.VERSION.SDK_INT >= 26) {
                    context.startForegroundService(svcIntent)
                } else {
                    context.startService(svcIntent)
                }
            }
            return
        }

        val linkToSave = rawLink.trim()
        val provider = when {
            linkToSave.contains("telemost", ignoreCase = true) ||
                linkToSave.contains("ya.ru", ignoreCase = true) -> "yandex"
            else -> "vk"
        }
        val linkForTunnel = when (provider) {
            "yandex" -> YandexParser.normalizeLink(linkToSave).ifBlank { linkToSave }
            else -> parseVkHash(linkToSave).ifBlank { linkToSave }
        }
        val isValidLink = when (provider) {
            "yandex" -> VkHashParser.parseYandex(linkToSave).isNotBlank() ||
                linkToSave.contains("telemost", ignoreCase = true)
            else -> parseVkHash(linkToSave).isNotBlank() || VkHashParser.parse(linkToSave).isNotBlank()
        }
        if (!isValidLink) {
            withContext(Dispatchers.Main) {
                onStarting(false)
                Toast.makeText(context, "Неверная ссылка подключения", Toast.LENGTH_SHORT).show()
            }
            return
        }

        withContext(Dispatchers.IO) {
            store.save(
                peer = Peer,
                vkHashes = linkForTunnel,
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
            putExtra("vk_hashes", linkForTunnel)
            putExtra("secondary_vk_hash", "")
            putExtra("workers_per_hash", 18)
            putExtra("port", 9000)
            putExtra("sni", "")
            putExtra("connection_password", ServerConfig.PASSWORD)
            putExtra("protocol", "udp")
            putExtra("provider", provider)
            putExtra("captcha_mode", captchaMode)
            putExtra("captcha_solve_method", captchaSolveMethod)
            putExtra("tunnel_mode", "whitelist")
            putExtra("wg_port", serverWgPort)
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

@Composable
private fun TrafficBox(
    label: String,
    arrow: String,
    arrowColor: Color,
    valueMb: Float,
    speedMbs: Float,
    history: List<Float>,
    barColor: Color,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.defaultMinSize(minHeight = AdaptiveLayout.TrafficCardMinHeight),
        shape = SkyflowShapes.Card,
        color = SkyflowColors.GlassSurfaceElevated.copy(alpha = 0.6f),
        border = SkyflowBorders.Glass
    ) {
        Column(Modifier.padding(8.dp, 8.dp, 8.dp, 6.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Text(arrow, style = MaterialTheme.typography.labelMedium, color = arrowColor)
                Text(
                    label.uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = SkyflowColors.TextSecondary,
                    letterSpacing = 0.4.sp,
                    maxLines = 1,
                )
            }
            Spacer(Modifier.height(2.dp))
            Row(
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    "%.0f".format(valueMb),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = SkyflowColors.TextPrimary,
                )
                Text("МБ", style = MaterialTheme.typography.bodySmall, color = SkyflowColors.TextSecondary)
            }
            Text(
                "$arrow %.1f МБ/с".format(speedMbs),
                style = MaterialTheme.typography.bodySmall,
                color = if (speedMbs > 0f) arrowColor else SkyflowColors.TextMuted,
                maxLines = 1,
            )
            Spacer(Modifier.height(4.dp))
            val maxVal = history.maxOrNull()?.coerceAtLeast(1f) ?: 1f
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(16.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.Bottom
            ) {
                history.forEach { v ->
                    val frac = (v / maxVal).coerceIn(0.1f, 1f)
                    Box(
                        Modifier
                            .weight(1f)
                            .fillMaxHeight(frac)
                            .clip(RoundedCornerShape(topStart = 1.dp, topEnd = 1.dp))
                            .background(barColor.copy(alpha = 0.3f + 0.7f * frac))
                    )
                }
            }
        }
    }
}

/** Полная ссылка VK-звонка или Яндекс Телемост для передачи в туннель. */
private fun parseVkHash(input: String): String {
    val trimmed = input.trim()
    if (trimmed.isBlank()) return ""

    if (trimmed.contains("telemost", ignoreCase = true) ||
        trimmed.contains("ya.ru/telemost", ignoreCase = true)
    ) {
        return trimmed
    }

    if (trimmed.contains("call/join/", ignoreCase = true)) {
        return trimmed
    }

    return VkHashParser.parse(trimmed)
}

private fun formatRemaining(seconds: Long): String {
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return "%02d:%02d:%02d".format(h, m, s)
}

private suspend fun createVkCallLink(): String? {
    return withContext(Dispatchers.IO) {
        try {
            val name = listOf(
                "Андрей", "Мария", "Иван", "Елена", "Дмитрий",
                "Анна", "Сергей", "Наталья", "Алексей", "Ольга"
            ).random()

            val tokenUrl = java.net.URL(
                "https://api.vk.ru/method/calls.getAnonymousToken" +
                    "?v=5.275&client_id=6287487&name=$name"
            )
            val tokenConn = tokenUrl.openConnection() as java.net.HttpURLConnection
            tokenConn.connectTimeout = 8000
            tokenConn.readTimeout = 8000
            tokenConn.setRequestProperty("User-Agent", "Mozilla/5.0 (Android) Chrome/120.0.0.0")

            val tokenResp = tokenConn.inputStream.bufferedReader().readText()
            tokenConn.disconnect()

            val tokenJson = org.json.JSONObject(tokenResp)
            val token = tokenJson.optJSONObject("response")
                ?.optString("token") ?: return@withContext null

            val callUrl = java.net.URL(
                "https://api.vk.ru/method/calls.create" +
                    "?v=5.275&access_token=$token"
            )
            val callConn = callUrl.openConnection() as java.net.HttpURLConnection
            callConn.connectTimeout = 8000
            callConn.readTimeout = 8000
            callConn.setRequestProperty("User-Agent", "Mozilla/5.0 (Android) Chrome/120.0.0.0")

            val callResp = callConn.inputStream.bufferedReader().readText()
            callConn.disconnect()

            val callJson = org.json.JSONObject(callResp)
            callJson.optJSONObject("response")
                ?.optString("join_link")
        } catch (e: Exception) {
            null
        }
    }
}

private fun disconnectTunnel(context: Context) {
    try {
        context.startService(
            Intent(context, TunnelService::class.java).apply { action = "STOP" }
        )
    } catch (_: Exception) {
        TunnelManager.stop()
    }
}
