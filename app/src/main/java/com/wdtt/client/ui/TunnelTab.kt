package com.wdtt.client.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
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
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.outlined.VisibilityOff
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
import com.wdtt.client.xray.SubscriptionParser
import com.wdtt.client.xray.VlessServer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
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
    var modesExpanded by rememberSaveable { mutableStateOf(false) }
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

    // ── Subscription / VLESS gate ─────────────────────────────────────────────
    val isSubMode = remember { store.getVlessInputMode() == "subscription" }
    var subExpireAt  by remember { mutableLongStateOf(store.getSubExpireAt()) }
    var hasServers   by remember { mutableStateOf(store.loadServers().isNotEmpty()) }
    var trialStartAt by remember { mutableLongStateOf(store.getTrialStartAt()) }
    val isSubExpired by remember {
        derivedStateOf {
            subExpireAt > 0L && subExpireAt < System.currentTimeMillis() / 1000L
        }
    }
    // Доступ: платная подписка (subExpireAt) ИЛИ локальный пробный период (trialStartAt).
    val isPaidActive by remember { derivedStateOf { subExpireAt > System.currentTimeMillis() / 1000L } }
    val trialDaysLeft by remember {
        derivedStateOf {
            val now = System.currentTimeMillis() / 1000L
            val end = trialStartAt + com.wdtt.client.BillingConfig.TRIAL_DAYS * 86_400L
            if (trialStartAt > 0L && end > now) (((end - now) + 86_399L) / 86_400L).toInt() else 0
        }
    }
    val isTrialActive by remember { derivedStateOf { !isPaidActive && trialDaysLeft > 0 } }
    val accessExpired by remember { derivedStateOf { !isPaidActive && !isTrialActive } }
    // «Скорость» = VLESS: нужны серверы и не истёкшая подписка.
    val speedAvailable by remember { derivedStateOf { hasServers && !isSubExpired } }
    // «Маскировка» (VK→TURN→WG) работает при любом активном доступе — VLESS не нужен.
    val maskingAvailable by remember { derivedStateOf { !accessExpired } }
    val isVlessBlocked by remember { derivedStateOf { !hasServers || isSubExpired } }
    // Re-read every 30s — auto-unlocks after payment + subscription refresh
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000L)
            subExpireAt  = store.getSubExpireAt()
            hasServers   = store.loadServers().isNotEmpty()
            trialStartAt = store.getTrialStartAt()
        }
    }

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

    // WHITELIST: connected when workers > 0 (stats) OR stage == VPN_READY (Go client said ready / WireGuard up).
    // SPEED:     connected only when stage == VPN_READY (TUN active).
    val isConnected = if (isSpeedMode) {
        tunnelRunning && connectionStage == ConnectionStage.VPN_READY
    } else {
        tunnelRunning && (activeWorkers > 0 || connectionStage == ConnectionStage.VPN_READY)
    }
    val isConnecting = if (isSpeedMode) {
        isStarting || (tunnelRunning && connectionStage != ConnectionStage.VPN_READY && connectionStage != ConnectionStage.FAILED)
    } else {
        isStarting || (tunnelRunning && activeWorkers <= 0 && connectionStage != ConnectionStage.VPN_READY && connectionStage != ConnectionStage.FAILED)
    }
    val vkHash = remember(vkLink) { parseVkHash(vkLink) }
    val powerEnabled = tunnelRunning || isStarting ||
        (isSpeedMode && speedAvailable) ||
        (!isSpeedMode && maskingAvailable && vkLink.isNotBlank() && vkHash.isNotBlank())

    LaunchedEffect(tunnelRunning, activeWorkers, connectionStage, isSpeedMode) {
        when {
            !tunnelRunning -> isStarting = false
            isSpeedMode && connectionStage == ConnectionStage.VPN_READY -> isStarting = false
            !isSpeedMode && activeWorkers > 0 -> isStarting = false
            !isSpeedMode && connectionStage == ConnectionStage.VPN_READY -> isStarting = false
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

    // Таймер действия ссылки убран (был косметический 24-часовой отсчёт).
    val autoBtnText = when {
        isCreatingLink -> "Создание..."
        vkLink.isNotBlank() -> "Создана · обновить"
        else -> "⚡ Создать автоматически"
    }
    val autoBtnTextColor = when {
        isCreatingLink -> SkyflowColors.Accent
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
        // Ждём 3с, чтобы VPN-маршруты успели подняться перед первым пингом
        delay(3000L)
        while (true) {
            services = services.map { it.copy(pingMs = -1) }
            val snapshot = services.toList()
            snapshot.forEachIndexed { i, svc ->
                launch(Dispatchers.IO) {
                    val start = System.currentTimeMillis()
                    val ping = try {
                        val url = java.net.URL("https://${svc.host}")
                        val conn = url.openConnection() as java.net.HttpURLConnection
                        conn.connectTimeout = 5000
                        conn.readTimeout = 5000
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
            // Перепроверяем каждые 30 секунд, пока подключено
            delay(30_000L)
        }
    }

    LaunchedEffect(stats) {
        if (!isConnected) return@LaunchedEffect
        // total_backend-to-dtls = rx = получено с сервера = DOWNLOAD
        // total_dtls-to-backend = tx = отправлено на сервер = UPLOAD
        val downBytes = Regex("""total_backend-to-dtls[:\s]+(\d+)""").find(stats)
            ?.groupValues?.get(1)?.toLongOrNull() ?: return@LaunchedEffect
        val upBytes = Regex("""total_dtls-to-backend[:\s]+(\d+)""").find(stats)
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

    val openBuy: () -> Unit = {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse("https://t.me/skypathvpn_bot"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    val onPowerClick: () -> Unit = {
        if (!tunnelRunning && !isStarting && isSpeedMode && !speedAvailable) {
            // «Скорость» требует подписку/серверы (в пробном периоде недоступна без VLESS).
            Toast.makeText(context, "Скорость доступна по подписке — оформите или войдите по ID", Toast.LENGTH_LONG).show()
        } else if (!tunnelRunning && !isStarting && !isSpeedMode && accessExpired) {
            Toast.makeText(context, "Пробный период закончился — оформите подписку", Toast.LENGTH_LONG).show()
            openBuy()
        } else if (tunnelRunning || isStarting) {
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
            expanded = modesExpanded,
            onExpandedChange = { modesExpanded = it },
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
        )

        // ── Статус пробного периода ───────────────────────────────────────
        AnimatedVisibility(
            visible = isTrialActive && !tunnelRunning && !isStarting,
            enter   = fadeIn(tween(300)) + expandVertically(tween(300)),
            exit    = fadeOut(tween(200)) + shrinkVertically(tween(200))
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape    = SkyflowShapes.Chip,
                color    = SkyflowColors.AccentMuted,
                border   = BorderStroke(0.5.dp, SkyflowColors.Accent.copy(alpha = 0.3f))
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        "Пробный период · осталось $trialDaysLeft дн.",
                        style = MaterialTheme.typography.bodySmall,
                        color = SkyflowColors.TextPrimary,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        "Оформить",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = SkyflowColors.AccentLight,
                        modifier = Modifier.clickable { openBuy() }
                    )
                }
            }
        }

        // ── VLESS / subscription gate banner ──────────────────────────────
        AnimatedVisibility(
            visible = !tunnelRunning && !isStarting && ((isSpeedMode && !speedAvailable) || accessExpired),
            enter   = fadeIn(tween(300)) + expandVertically(tween(300)),
            exit    = fadeOut(tween(200)) + shrinkVertically(tween(200))
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape    = SkyflowShapes.Card,
                color    = SkyflowColors.ErrorColor.copy(alpha = 0.08f),
                border   = BorderStroke(1.dp, SkyflowColors.ErrorColor.copy(alpha = 0.30f))
            ) {
                Column(
                    modifier              = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    horizontalAlignment   = Alignment.CenterHorizontally,
                    verticalArrangement   = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        verticalAlignment     = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(if (accessExpired) "⛔" else "🔒", fontSize = 18.sp)
                        Column {
                            Text(
                                if (accessExpired) "Пробный период закончился"
                                else               "Скорость — по подписке",
                                style      = MaterialTheme.typography.titleSmall,
                                color      = SkyflowColors.ErrorColor,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                if (accessExpired) "Оформите подписку, чтобы продолжить"
                                else               "В пробном периоде работает Маскировка. Для Скорости оформите подписку или войдите по ID.",
                                style = MaterialTheme.typography.bodySmall,
                                color = SkyflowColors.TextSecondary
                            )
                        }
                    }
                    if (accessExpired) {
                        Button(
                            onClick  = {
                                context.startActivity(
                                    Intent(Intent.ACTION_VIEW, Uri.parse("https://t.me/skypathvpn_bot"))
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                )
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors   = ButtonDefaults.buttonColors(
                                containerColor = SkyflowColors.Accent,
                                contentColor   = SkyflowColors.OnAccent
                            ),
                            shape    = SkyflowShapes.Chip
                        ) {
                            Text("✈  Оплатить через Telegram", fontWeight = FontWeight.SemiBold)
                        }
                    } else {
                        OutlinedButton(
                            onClick  = { showServersScreen = true },
                            modifier = Modifier.fillMaxWidth(),
                            colors   = ButtonDefaults.outlinedButtonColors(
                                contentColor = SkyflowColors.AccentLight
                            ),
                            border   = BorderStroke(1.dp, SkyflowColors.Accent),
                            shape    = SkyflowShapes.Chip
                        ) {
                            Text("Настроить подписку →", fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }

        // ── Subscription widget (Speed: full, Whitelist: compact status chip) ──
        AnimatedVisibility(
            visible = !tunnelRunning && !isStarting && isSpeedMode,
            enter   = fadeIn(tween(300)) + expandVertically(tween(300)),
            exit    = fadeOut(tween(200)) + shrinkVertically(tween(200))
        ) {
            SubscriptionWidget(
                store        = store,
                refreshKey   = showServersScreen,
                onRefresh    = {
                    subExpireAt = store.getSubExpireAt()
                    hasServers  = store.loadServers().isNotEmpty()
                },
                onOpenServers = { showServersScreen = true },
            )
        }
        AnimatedVisibility(
            visible = !tunnelRunning && !isStarting && !isSpeedMode && hasServers,
            enter   = fadeIn(tween(300)) + expandVertically(tween(300)),
            exit    = fadeOut(tween(200)) + shrinkVertically(tween(200))
        ) {
            SubStatusChip(
                store      = store,
                expireAt   = subExpireAt,
                refreshKey = showServersScreen,
                onClick    = { showServersScreen = true },
            )
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
                            vkLink = newValue.trim()
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
                                    Text("Код:", fontSize = readableSp(12f), color = SkyflowColors.TextSecondary)
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

        ConnectedServerInfo(
            isConnected = isConnected,
            isSpeedMode = isSpeedMode,
            speedServer = activeSpeedServer,
            routingModeLabel = if (isSpeedMode && isConnected) {
                com.wdtt.client.xray.XrayRoutingMode.fromKey(activeRoutingModeKey).label
            } else null
        )

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

        // CaptchaModal manages its own bottom-sheet animations (dim fade + sheet slide).
        // Do NOT wrap in outer AnimatedVisibility — it conflicts with the inner ones.
        CaptchaModal(
            isVisible = showCaptcha,
            onDismiss = { TunnelManager.dismissCaptchaModal() },
            onCaptchaSolved = { TunnelManager.onCaptchaSolved() }
        )

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
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onModeChange: (String) -> Unit,
    onAutoSwitchChange: (Boolean) -> Unit,
) {
    val isSpeed = selectedMode == "speed"
    val hint = when {
        !enabled -> "Режим меняется до подключения"
        manualOverride && autoSwitchEnabled -> "Выбрано вручную · авто вернётся при смене сети"
        autoSwitchEnabled && networkTransport == NetworkTransport.WIFI -> "Авто · Wi‑Fi → скорость"
        autoSwitchEnabled && networkTransport == NetworkTransport.CELLULAR -> "Авто · LTE → маскировка"
        autoSwitchEnabled -> "Авто · по типу сети"
        isSpeed -> "Максимально быстрое соединение"
        else -> "Незаметно · обходит блокировки"
    }
    val hintColor = if (manualOverride && autoSwitchEnabled) SkyflowColors.WarnColor else SkyflowColors.TextMuted

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (enabled || autoSwitchEnabled) 1f else 0.6f),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // Компактный сегментированный переключатель режимов (всегда на виду).
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = SkyflowShapes.Card,
            color = SkyflowColors.GlassSurface,
            border = SkyflowBorders.Glass,
        ) {
            Row(
                modifier = Modifier.padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                ModePill(
                    label = "Маскировка",
                    icon = Icons.Outlined.VisibilityOff,
                    selected = !isSpeed,
                    enabled = enabled,
                    accentColor = SkyflowColors.Accent,
                    modifier = Modifier.weight(1f),
                    onClick = { onModeChange("whitelist") },
                )
                ModePill(
                    label = "Скорость",
                    icon = Icons.Filled.Bolt,
                    selected = isSpeed,
                    enabled = enabled,
                    accentColor = SkyflowColors.Connected,
                    modifier = Modifier.weight(1f),
                    onClick = { onModeChange("speed") },
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                hint,
                fontSize = readableSp(11f),
                color = hintColor,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "Авто",
                fontSize = readableSp(11f),
                color = if (autoSwitchEnabled) SkyflowColors.AccentLight else SkyflowColors.TextMuted,
            )
            Spacer(Modifier.width(6.dp))
            Switch(
                checked = autoSwitchEnabled,
                onCheckedChange = onAutoSwitchChange,
                modifier = Modifier.height(24.dp),
                colors = SwitchDefaults.colors(
                    checkedThumbColor = SkyflowColors.Connected,
                    checkedTrackColor = SkyflowColors.Connected.copy(alpha = 0.35f),
                    uncheckedThumbColor = SkyflowColors.TextMuted,
                    uncheckedTrackColor = SkyflowColors.Border,
                ),
            )
        }
    }
}

@Composable
private fun ModePill(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    selected: Boolean,
    enabled: Boolean,
    accentColor: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val bg by animateColorAsState(
        if (selected) accentColor else Color.Transparent,
        label = "mode_pill_bg"
    )
    val fg = if (selected) SkyflowColors.OnAccent else SkyflowColors.TextSecondary
    Surface(
        modifier = modifier.height(46.dp),
        shape = SkyflowShapes.Chip,
        color = bg,
        onClick = { if (enabled) onClick() },
        enabled = enabled,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = fg,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                label,
                fontSize = readableSp(13f),
                fontWeight = FontWeight.SemiBold,
                color = fg,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
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
) {
    val color = when {
        !tunnelRunning -> SkyflowColors.Idle
        isConnecting -> SkyflowColors.Connecting
        else -> SkyflowColors.Connected
    }
    val text = when {
        !tunnelRunning -> "Отключено"
        isConnecting -> "Подключаюсь…"
        else -> {
            val h = elapsedSec / 3600
            val m = (elapsedSec % 3600) / 60
            val s = elapsedSec % 60
            if (h > 0) "Защищено · %02d:%02d:%02d".format(h, m, s)
            else "Защищено · %02d:%02d".format(m, s)
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
            Text(
                "Соединение защищено",
                style = MaterialTheme.typography.bodySmall,
                color = SkyflowColors.TextSecondary,
            )
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
private fun ConnectedServerInfo(
    isConnected: Boolean,
    isSpeedMode: Boolean,
    speedServer: com.wdtt.client.xray.VlessServer?,
    routingModeLabel: String?
) {
    AnimatedVisibility(
        visible = isConnected,
        enter = fadeIn(tween(300)) + expandVertically(tween(300)),
        exit = fadeOut(tween(200)) + shrinkVertically(tween(200))
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = SkyflowShapes.Chip,
            color = SkyflowColors.GlassSurface,
            border = SkyflowBorders.Glass
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(
                    Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(SkyflowColors.Connected)
                )
                Column(modifier = Modifier.weight(1f)) {
                    val serverName = if (isSpeedMode && speedServer != null) {
                        speedServer.name
                    } else {
                        "Защищённый туннель"
                    }
                    Text(
                        serverName,
                        fontSize = readableSp(12f),
                        fontWeight = FontWeight.SemiBold,
                        color = SkyflowColors.TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    val subtitle = if (isSpeedMode && speedServer != null) {
                        buildString {
                            append("VLESS")
                            if (speedServer.type != "tcp") append(" · ${speedServer.type.uppercase()}")
                            when (speedServer.security) {
                                "tls" -> append(" · TLS")
                                "reality" -> append(" · Reality")
                            }
                            if (!routingModeLabel.isNullOrBlank()) append(" · $routingModeLabel")
                        }
                    } else {
                        "Защищённый туннель"
                    }
                    Text(
                        subtitle,
                        fontSize = readableSp(10f),
                        color = SkyflowColors.TextMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (isSpeedMode && speedServer != null && speedServer.latency > 0) {
                    Surface(
                        shape = SkyflowShapes.LogEntry,
                        color = SkyflowColors.Connected.copy(alpha = 0.12f),
                        border = BorderStroke(0.5.dp, SkyflowColors.Connected.copy(alpha = 0.3f))
                    ) {
                        Text(
                            "${speedServer.latency}ms",
                            fontSize = readableSp(10f),
                            fontWeight = FontWeight.SemiBold,
                            color = SkyflowColors.Connected,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
            }
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
            // SPEED mode drives traffic through Xray VLESS — the WireGuard "peer" field
            // is not relevant here, so skip that check entirely.
            // VLESS URI resolution and validation happen inside TunnelManager.startSpeedMode().
            val svcIntent = Intent(context, TunnelService::class.java).apply {
                action = "START"
                putExtra("peer", "")           // not used in SPEED mode
                putExtra("vk_hashes", "")
                putExtra("secondary_vk_hash", "")
                putExtra("workers_per_hash", 0)
                putExtra("port", 0)
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

// ═══ Subscription widget ═══════════════════════════════════════════════════

@Composable
private fun SubscriptionWidget(
    store: SettingsStore,
    refreshKey: Any,                    // changes when ServersScreen closes → reload
    onRefresh: () -> Unit,              // tell parent to re-read hasServers / expiry
    onOpenServers: () -> Unit,
) {
    val context          = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val scope            = rememberCoroutineScope()

    var servers       by remember(refreshKey) { mutableStateOf(store.loadServers()) }
    var selectedIndex by remember(refreshKey) { mutableIntStateOf(store.getSelectedServerIndex()) }
    var subTitle      by remember(refreshKey) { mutableStateOf(store.getSubTitle()) }
    var subUpload     by remember(refreshKey) { mutableLongStateOf(store.getSubUpload()) }
    var subDownload   by remember(refreshKey) { mutableLongStateOf(store.getSubDownload()) }
    var subTotal      by remember(refreshKey) { mutableLongStateOf(store.getSubTotal()) }
    var subExpireAt   by remember(refreshKey) { mutableLongStateOf(store.getSubExpireAt()) }
    var isAdding      by remember { mutableStateOf(false) }
    var addError      by remember { mutableStateOf<String?>(null) }

    fun addFromClipboard() {
        val text = clipboardManager.getText()?.toString()?.trim() ?: ""
        val isUrl = text.startsWith("https://") || text.startsWith("http://")
        if (!isUrl) { addError = "В буфере нет ссылки подписки"; return }
        scope.launch {
            isAdding  = true
            addError  = null
            try {
                val result = SubscriptionParser.fetchSubscription(text)
                if (result.servers.isEmpty()) {
                    addError = "Серверы не найдены — проверьте ссылку"
                } else {
                    store.saveVlessInputMode("subscription")
                    store.saveSubscriptionUrl(text)
                    store.saveServers(result.servers)
                    store.saveSubExpireAt(result.expireAt)
                    store.saveSubTitle(result.title)
                    store.saveSubUpload(result.upload)
                    store.saveSubDownload(result.download)
                    store.saveSubTotal(result.total)
                    store.saveSubAnnounce(result.announce)
                    store.saveSelectedServerIndex(0)
                    servers       = result.servers
                    selectedIndex = 0
                    subTitle      = result.title
                    subUpload     = result.upload
                    subDownload   = result.download
                    subTotal      = result.total
                    subExpireAt   = result.expireAt
                    onRefresh()
                }
            } catch (e: Exception) {
                addError = "Не удалось загрузить: ${e.message?.take(60)}"
            }
            isAdding = false
        }
    }

    Surface(
        shape    = SkyflowShapes.Card,
        color    = SkyflowColors.GlassSurface,
        border   = SkyflowBorders.Glass,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column {
            if (servers.isEmpty()) {
                // ── No subscription: Add button ──────────────────────────
                Column(
                    modifier              = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalAlignment   = Alignment.CenterHorizontally,
                    verticalArrangement   = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        "VLESS-ПОДПИСКА",
                        style = SkyflowTextStyles.labelUppercase,
                        color = SkyflowColors.TextMuted
                    )
                    addError?.let { err ->
                        Text(
                            err,
                            style = MaterialTheme.typography.bodySmall,
                            color = SkyflowColors.ErrorColor
                        )
                    }
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .clip(SkyflowShapes.Chip)
                            .clickable(enabled = !isAdding, onClick = ::addFromClipboard),
                        shape = SkyflowShapes.Chip,
                        color = Color.Transparent,
                    ) {
                        Box(
                            modifier         = Modifier
                                .fillMaxSize()
                                .background(
                                    if (!isAdding) SkyflowGradients.Accent
                                    else androidx.compose.ui.graphics.Brush.linearGradient(
                                        listOf(SkyflowColors.Border, SkyflowColors.Border)
                                    ),
                                    SkyflowShapes.Chip
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            if (isAdding) {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment     = Alignment.CenterVertically
                                ) {
                                    CircularProgressIndicator(
                                        modifier    = Modifier.size(16.dp),
                                        strokeWidth = 2.dp,
                                        color       = SkyflowColors.TextMuted
                                    )
                                    Text(
                                        "Загрузка...",
                                        fontSize   = 15.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color      = SkyflowColors.TextMuted
                                    )
                                }
                            } else {
                                Text(
                                    "+ Добавить подписку",
                                    fontSize   = 15.sp,
                                    fontFamily = interFontFamily,
                                    fontWeight = FontWeight.SemiBold,
                                    color      = Color.White
                                )
                            }
                        }
                    }
                }
            } else {
                // ── Subscription info header ─────────────────────────────
                SubWidgetHeader(
                    title     = subTitle,
                    upload    = subUpload,
                    download  = subDownload,
                    total     = subTotal,
                    expireAt  = subExpireAt,
                    onManage  = onOpenServers,
                )

                HorizontalDivider(color = SkyflowColors.Border)

                // ── Servers label + count ────────────────────────────────
                Row(
                    modifier              = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment     = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        "СЕРВЕРЫ",
                        style = SkyflowTextStyles.labelUppercase,
                        color = SkyflowColors.TextMuted
                    )
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = SkyflowColors.AccentMuted
                    ) {
                        Text(
                            "${servers.size}",
                            modifier   = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                            style      = MaterialTheme.typography.labelSmall,
                            color      = SkyflowColors.AccentLight,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                // ── Server rows ──────────────────────────────────────────
                servers.forEachIndexed { idx, server ->
                    if (idx > 0) {
                        HorizontalDivider(
                            color    = SkyflowColors.Border.copy(alpha = 0.5f),
                            modifier = Modifier.padding(start = 42.dp)
                        )
                    }
                    SubWidgetServerRow(
                        server     = server,
                        isSelected = idx == selectedIndex,
                        onClick    = {
                            selectedIndex = idx
                            scope.launch {
                                store.saveSelectedServerIndex(idx)
                                val updated = servers.mapIndexed { i, s -> s.copy(isSelected = i == idx) }
                                servers = updated
                                store.saveServers(updated)
                            }
                        }
                    )
                }

                Spacer(Modifier.height(4.dp))
            }
        }
    }
}

@Composable
private fun SubWidgetHeader(
    title: String,
    upload: Long,
    download: Long,
    total: Long,
    expireAt: Long,
    onManage: () -> Unit,
) {
    val nowSec   = System.currentTimeMillis() / 1000L
    val isActive = expireAt == 0L || expireAt > nowSec
    val used     = upload + download
    val daysLeft = if (expireAt > 0L && isActive) TimeUnit.SECONDS.toDays(expireAt - nowSec) else Long.MAX_VALUE

    Column(
        modifier            = Modifier
            .fillMaxWidth()
            .clickable { onManage() }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        // Title + status badge
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text       = title.ifBlank { "Подписка" },
                style      = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color      = SkyflowColors.TextPrimary,
                maxLines   = 1,
                overflow   = TextOverflow.Ellipsis,
                modifier   = Modifier.weight(1f)
            )
            Spacer(Modifier.width(8.dp))
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = if (isActive) SkyflowColors.Connected.copy(alpha = 0.12f)
                        else SkyflowColors.ErrorColor.copy(alpha = 0.12f)
            ) {
                Row(
                    modifier              = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                    verticalAlignment     = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Box(
                        Modifier
                            .size(5.dp)
                            .background(
                                if (isActive) SkyflowColors.Connected else SkyflowColors.ErrorColor,
                                CircleShape
                            )
                    )
                    Text(
                        if (isActive) "Активна" else "Истекла",
                        style      = MaterialTheme.typography.labelSmall,
                        color      = if (isActive) SkyflowColors.Connected else SkyflowColors.ErrorColor,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
            Spacer(Modifier.width(6.dp))
            Icon(
                Icons.Default.ChevronRight,
                contentDescription = null,
                tint     = SkyflowColors.TextMuted,
                modifier = Modifier.size(16.dp)
            )
        }

        // Traffic + expiry in one compact row
        Row(
            modifier              = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment     = Alignment.CenterVertically
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "↑ ${subFormatBytes(upload)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = SkyflowColors.TextSecondary
                )
                Text(
                    "↓ ${subFormatBytes(download)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = SkyflowColors.TextSecondary
                )
                val totalLabel = if (total > 0L) {
                    val p = (used.toFloat() / total).coerceIn(0f, 1f)
                    "/ ${subFormatBytes(total)}"
                } else "/ ∞"
                val totalColor = if (total > 0L && (used.toFloat() / total) > 0.9f)
                    SkyflowColors.ErrorColor else SkyflowColors.TextMuted
                Text(totalLabel, style = MaterialTheme.typography.bodySmall, color = totalColor)
            }
            Text(
                text = when {
                    expireAt == 0L -> "Бессрочно"
                    !isActive      -> "Истекла"
                    daysLeft < 1   -> "Сегодня"
                    daysLeft < 30  -> "ещё ${daysLeft}д"
                    else           -> SimpleDateFormat("d MMM", Locale("ru")).format(Date(expireAt * 1000L))
                },
                style      = MaterialTheme.typography.labelSmall,
                color      = when {
                    expireAt == 0L -> SkyflowColors.Connected
                    !isActive      -> SkyflowColors.ErrorColor
                    daysLeft < 7   -> SkyflowColors.ErrorColor
                    daysLeft < 30  -> SkyflowColors.WarnColor
                    else           -> SkyflowColors.TextMuted
                },
                fontWeight = FontWeight.SemiBold
            )
        }

        // Progress bar (when total > 0)
        if (total > 0L) {
            val progress = (used.toFloat() / total).coerceIn(0f, 1f)
            LinearProgressIndicator(
                progress   = { progress },
                modifier   = Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)),
                color      = when {
                    progress >= 0.9f -> SkyflowColors.ErrorColor
                    progress >= 0.7f -> SkyflowColors.WarnColor
                    else             -> SkyflowColors.Accent
                },
                trackColor = SkyflowColors.Border
            )
        }
    }
}

@Composable
private fun SubWidgetServerRow(
    server: VlessServer,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier          = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(start = 8.dp, end = 14.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(
            selected = isSelected,
            onClick  = onClick,
            colors   = RadioButtonDefaults.colors(
                selectedColor   = SkyflowColors.Accent,
                unselectedColor = SkyflowColors.TextMuted
            ),
            modifier = Modifier.size(32.dp)
        )
        Spacer(Modifier.width(6.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text       = server.name,
                style      = MaterialTheme.typography.bodyMedium,
                color      = if (isSelected) SkyflowColors.TextPrimary else SkyflowColors.TextSecondary,
                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                maxLines   = 1,
                overflow   = TextOverflow.Ellipsis
            )
            Text(
                text  = "${server.host}:${server.port}",
                style = MaterialTheme.typography.bodySmall,
                color = SkyflowColors.TextMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.width(8.dp))
        // Protocol badge (most relevant info)
        val badgeLabel = when {
            server.security.equals("reality", ignoreCase = true) -> "REALITY"
            server.security.equals("tls", ignoreCase = true)     -> "TLS"
            server.type.equals("ws", ignoreCase = true)          -> "WS"
            server.type.equals("grpc", ignoreCase = true)        -> "gRPC"
            else                                                  -> "TCP"
        }
        Surface(
            shape = RoundedCornerShape(4.dp),
            color = if (isSelected) SkyflowColors.AccentMuted else SkyflowColors.GlassSurfaceElevated
        ) {
            Text(
                text     = badgeLabel,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                style    = MaterialTheme.typography.labelSmall,
                color    = if (isSelected) SkyflowColors.AccentLight else SkyflowColors.TextMuted,
                fontSize = 10.sp
            )
        }
    }
}

private fun subFormatBytes(bytes: Long): String {
    if (bytes <= 0L) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    val idx   = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt().coerceIn(0, units.lastIndex)
    val value = bytes / Math.pow(1024.0, idx.toDouble())
    return if (idx == 0) "$bytes B" else "%.1f %s".format(value, units[idx])
}

// ─── Compact subscription status chip (Whitelist mode only) ───────────────────
@Composable
private fun SubStatusChip(
    store:      SettingsStore,
    expireAt:   Long,    // kept in sync by parent's 30s timer
    refreshKey: Any,     // changes when ServersScreen closes → re-read title
    onClick:    () -> Unit,
) {
    val title = remember(refreshKey) { store.getSubTitle() }

    val nowSec   = System.currentTimeMillis() / 1000L
    val isActive = expireAt == 0L || expireAt > nowSec
    val daysLeft = if (expireAt > 0L && isActive) TimeUnit.SECONDS.toDays(expireAt - nowSec) else Long.MAX_VALUE

    val statusColor = if (isActive) SkyflowColors.Connected else SkyflowColors.ErrorColor
    val expiryText  = when {
        expireAt == 0L -> "∞"
        !isActive      -> "Истекла"
        daysLeft < 1   -> "Сегодня"
        daysLeft < 30  -> "ещё ${daysLeft}д"
        else           -> SimpleDateFormat("d MMM", Locale("ru")).format(Date(expireAt * 1000L))
    }
    val expiryColor = when {
        expireAt == 0L -> SkyflowColors.TextMuted
        !isActive      -> SkyflowColors.ErrorColor
        daysLeft < 7   -> SkyflowColors.ErrorColor
        daysLeft < 30  -> SkyflowColors.WarnColor
        else           -> SkyflowColors.TextMuted
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        shape  = SkyflowShapes.Chip,
        color  = SkyflowColors.GlassSurface,
        border = BorderStroke(0.5.dp, statusColor.copy(alpha = 0.22f))
    ) {
        Row(
            modifier              = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment     = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Status dot
            Box(
                Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(statusColor)
            )
            // Subscription name
            Text(
                title.ifBlank { "Подписка" },
                style      = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
                color      = SkyflowColors.TextPrimary,
                maxLines   = 1,
                overflow   = TextOverflow.Ellipsis,
                modifier   = Modifier.weight(1f)
            )
            // Expiry / days left
            Text(
                expiryText,
                style      = MaterialTheme.typography.labelSmall,
                color      = expiryColor,
                fontWeight = FontWeight.SemiBold
            )
            // Chevron arrow
            Icon(
                Icons.Default.ChevronRight,
                contentDescription = null,
                tint     = SkyflowColors.TextMuted,
                modifier = Modifier.size(14.dp)
            )
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
