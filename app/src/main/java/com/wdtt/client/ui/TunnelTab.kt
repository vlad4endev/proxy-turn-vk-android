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
import androidx.compose.ui.graphics.graphicsLayer

private val ColorIdle = Color(0xFF3D3B6A)
private val ColorConnecting = Color(0xFFF59E0B)
private val ColorConnected = Color(0xFF4ADE80)

private val Peer = "${ServerConfig.HOST}:${ServerConfig.PORT}"

private data class ServiceStatus(
    val name: String,
    val emoji: String,
    val iconBg: Color,
    val host: String,
    val pingMs: Int = -1,
)

enum class LinkProvider { VK, YANDEX, UNKNOWN }
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
    val stats by TunnelManager.stats.collectAsStateWithLifecycle()

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
            ServiceStatus("YouTube",   "▶", Color(0xFF1A0F0F), "youtube.com"),
            ServiceStatus("Telegram",  "✈", Color(0xFF0F1A2A), "t.me"),
            ServiceStatus("Instagram", "📷", Color(0xFF1A0F1A), "instagram.com"),
            ServiceStatus("WhatsApp",  "💬", Color(0xFF0A1A0F), "whatsapp.com"),
            ServiceStatus("TikTok",    "🎵", Color(0xFF0F0F1A), "tiktok.com"),
        ))
    }
    var linkProvider by remember { mutableStateOf(LinkProvider.UNKNOWN) }
    var linkStatus by remember { mutableStateOf(LinkStatus.IDLE) }

    fun detectProvider(link: String): LinkProvider = when {
        link.contains("vk.com/call/join") ||
        link.contains("vk.me/call/join") ||
        link.contains("m.vk.com/call/join") -> LinkProvider.VK
        link.contains("telemost.yandex.ru") ||
        link.contains("ya.ru/telemost") -> LinkProvider.YANDEX
        else -> LinkProvider.UNKNOWN
    }

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

    val isConnected = tunnelRunning && activeWorkers > 0
    val isConnecting = isStarting || (tunnelRunning && activeWorkers <= 0)
    val vkHash = remember(vkLink) { parseVkHash(vkLink) }
    val powerEnabled = tunnelRunning || isStarting ||
        (vkLink.isNotBlank() && vkHash.isNotBlank())

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
        linkRemainingSeconds <= 0 -> Color(0xFFF87171)
        linkRemainingSeconds <= 3600 -> Color(0xFFF59E0B)
        linkRemainingSeconds <= 6 * 3600 -> Color(0xFFD4D4D8)
        else -> Color(0xFF4ADE80)
    }
    val timerBarColor = when {
        linkRemainingSeconds <= 0 -> Color(0xFFF87171)
        linkRemainingSeconds <= 3600 -> Color(0xFFF59E0B)
        linkRemainingSeconds <= 6 * 3600 -> Color(0xFF6366F1)
        else -> Color(0xFF4ADE80)
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
    val autoBtnBorderColor = when {
        linkRemainingSeconds <= 0 && vkLink.isNotBlank() -> Color(0xFF7F1D1D)
        linkRemainingSeconds <= 3600 && vkLink.isNotBlank() -> Color(0xFF78350F)
        vkLink.isNotBlank() -> Color(0xFF166534)
        else -> Color(0xFF2A2850)
    }
    val autoBtnText = when {
        isCreatingLink -> "Создание..."
        linkRemainingSeconds <= 0 && vkLink.isNotBlank() -> "Ссылка истекла · создать новую"
        linkRemainingSeconds in 1..3600 && vkLink.isNotBlank() -> "Ссылка скоро истечёт · обновить"
        vkLink.isNotBlank() -> "Создана · обновить"
        else -> "⚡ Создать автоматически"
    }
    val autoBtnTextColor = when {
        isCreatingLink -> Color(0xFF6366F1)
        linkRemainingSeconds <= 0 && vkLink.isNotBlank() -> Color(0xFFF87171)
        linkRemainingSeconds in 1..3600 && vkLink.isNotBlank() -> Color(0xFFF59E0B)
        vkLink.isNotBlank() -> Color(0xFF4ADE80)
        else -> Color(0xFF818CF8)
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
        } else if (vkLink.isBlank()) {
            Toast.makeText(context, "Вставьте код доступа", Toast.LENGTH_SHORT).show()
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
            .verticalScroll(scrollState)
            .padding(horizontal = 24.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        Spacer(Modifier.height(20.dp))

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
            val fieldBorderColor = when {
                linkStatus == LinkStatus.ACTIVE -> Color(0xFF166534)
                linkStatus == LinkStatus.DEAD -> Color(0xFF7F1D1D)
                linkStatus == LinkStatus.CHECKING -> Color(0xFF4F46E5)
                linkProvider == LinkProvider.VK -> Color(0xFF1E3A5F)
                linkProvider == LinkProvider.YANDEX -> Color(0xFF78350F)
                else -> Color(0xFF2A2850)
            }
            val fieldGlow = when (linkStatus) {
                LinkStatus.CHECKING -> Color(0xFF6366F1).copy(alpha = 0.08f)
                LinkStatus.ACTIVE -> Color(0xFF4ADE80).copy(alpha = 0.05f)
                else -> Color.Transparent
            }
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .bringIntoViewRequester(linkFieldBringIntoView),
                shape = RoundedCornerShape(14.dp),
                color = Color(0xFF13112A),
                border = BorderStroke(1.dp, fieldBorderColor)
            ) {
                Column(
                    modifier = Modifier
                        .background(fieldGlow)
                        .padding(horizontal = 14.dp, vertical = 11.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "Код доступа",
                            fontSize = 8.sp, color = Color(0xFF6B7280),
                            letterSpacing = 0.6.sp,
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
                                        tagBg = Color(0x263B82F6)
                                        tagBorder = Color(0x4D3B82F6)
                                        tagColor = Color(0xFF60A5FA)
                                        tagText = "VK"
                                    }
                                    LinkProvider.YANDEX -> {
                                        tagBg = Color(0x26F59E0B)
                                        tagBorder = Color(0x4DF59E0B)
                                        tagColor = Color(0xFFFBBF24)
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
                                    shape = RoundedCornerShape(8.dp),
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
                                            fontSize = 8.5.sp, fontWeight = FontWeight.Bold,
                                            color = tagColor
                                        )
                                    }
                                }
                            }
                            when (linkStatus) {
                                LinkStatus.CHECKING -> {
                                    Surface(
                                        shape = RoundedCornerShape(8.dp),
                                        color = Color(0x206366F1),
                                        border = BorderStroke(0.5.dp, Color(0x406366F1))
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                                        ) {
                                            CircularProgressIndicator(
                                                modifier = Modifier.size(6.dp),
                                                color = Color(0xFF818CF8),
                                                strokeWidth = 1.dp
                                            )
                                            Text(
                                                "Проверка", fontSize = 8.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                color = Color(0xFF818CF8)
                                            )
                                        }
                                    }
                                }
                                LinkStatus.ACTIVE -> {
                                    Surface(
                                        shape = RoundedCornerShape(8.dp),
                                        color = Color(0x204ADE80),
                                        border = BorderStroke(0.5.dp, Color(0x404ADE80))
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
                                                    .background(Color(0xFF4ADE80))
                                            )
                                            Text(
                                                "Активна", fontSize = 8.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                color = Color(0xFF4ADE80)
                                            )
                                        }
                                    }
                                }
                                LinkStatus.DEAD -> {
                                    Surface(
                                        shape = RoundedCornerShape(8.dp),
                                        color = Color(0x20F87171),
                                        border = BorderStroke(0.5.dp, Color(0x40F87171))
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
                                                    .background(Color(0xFFF87171))
                                            )
                                            Text(
                                                "Недоступна", fontSize = 8.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                color = Color(0xFFF87171)
                                            )
                                        }
                                    }
                                }
                                LinkStatus.NO_NETWORK -> {
                                    Surface(
                                        shape = RoundedCornerShape(8.dp),
                                        color = Color(0x20F59E0B),
                                        border = BorderStroke(0.5.dp, Color(0x40F59E0B))
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
                                                    .background(Color(0xFFF59E0B))
                                            )
                                            Text(
                                                "Не проверена", fontSize = 8.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                color = Color(0xFFF59E0B)
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
                        textStyle = TextStyle(
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            color = when (linkProvider) {
                                LinkProvider.YANDEX -> Color(0xFFFCD34D)
                                LinkProvider.VK -> Color(0xFFA5B4FC)
                                else -> Color(0xFF6B7280)
                            }
                        ),
                        decorationBox = { inner ->
                            if (vkLink.isEmpty()) {
                                Text(
                                    "vk.com/call/join/... или telemost.yandex.ru/j/...",
                                    fontSize = 11.sp, color = Color(0xFF3F3F5A)
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
                            HorizontalDivider(color = Color(0xFF1E1C3A), thickness = 0.5.dp)
                            Spacer(Modifier.height(5.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(timerIcon, fontSize = 10.sp)
                                Text(
                                    timerLabel,
                                    fontSize = 7.sp,
                                    color = if (linkRemainingSeconds <= 3600) timerColor
                                    else Color(0xFF6B7280)
                                )
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(3.dp)
                                        .clip(RoundedCornerShape(2.dp))
                                        .background(Color(0xFF1E1C3A))
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
                                    shape = RoundedCornerShape(10.dp),
                                    color = Color(0xFF13112A),
                                    border = BorderStroke(
                                        0.5.dp,
                                        if (isCreatingLink) Color(0xFF6366F1) else autoBtnBorderColor
                                    )
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        if (isCreatingLink) {
                                            CircularProgressIndicator(
                                                modifier = Modifier.size(10.dp),
                                                color = Color(0xFF6366F1),
                                                strokeWidth = 1.5.dp
                                            )
                                        }
                                        Text(
                                            autoBtnText,
                                            fontSize = 11.sp,
                                            color = autoBtnTextColor,
                                            fontWeight = FontWeight.Medium
                                        )
                                    }
                                }
                            }
                            if (autoLinkError.isNotBlank()) {
                                Text(
                                    text = autoLinkError,
                                    fontSize = 10.sp,
                                    color = Color(0xFFF59E0B),
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(7.dp))
                    HorizontalDivider(color = Color(0xFF1E1C3A), thickness = 0.5.dp)
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
                                    fontSize = 7.5.sp, color = Color(0xFFF87171),
                                    modifier = Modifier.weight(1f)
                                )
                            }
                            linkStatus == LinkStatus.NO_NETWORK -> {
                                Text(
                                    "Нет подключения для проверки",
                                    fontSize = 7.5.sp, color = Color(0xFFF59E0B),
                                    modifier = Modifier.weight(1f)
                                )
                            }
                            parsedHash.isNotBlank() -> {
                                Row(
                                    modifier = Modifier.weight(1f),
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text("Хеш:", fontSize = 7.5.sp, color = Color(0xFF6B7280))
                                    Text(
                                        parsedHash,
                                        fontSize = 7.5.sp,
                                        fontFamily = FontFamily.Monospace,
                                        color = if (linkProvider == LinkProvider.YANDEX)
                                            Color(0xFFFBBF24) else Color(0xFF818CF8),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f)
                                    )
                                    if (linkStatus == LinkStatus.ACTIVE) {
                                        Text("✓ Готова", fontSize = 7.sp, color = Color(0xFF4ADE80))
                                    }
                                }
                            }
                            vkLink.isBlank() -> {
                                Text(
                                    "Вставьте ссылку для проверки",
                                    fontSize = 7.5.sp, color = Color(0xFF3F3F5A),
                                    modifier = Modifier.weight(1f)
                                )
                            }
                            else -> {
                                Text(
                                    "Формат не распознан",
                                    fontSize = 7.5.sp, color = Color(0xFFF87171),
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                        if (vkLink.isBlank()) {
                            val clipboardManager = LocalClipboardManager.current
                            Surface(
                                shape = RoundedCornerShape(5.dp),
                                color = Color(0xFF1E1C3A),
                                border = BorderStroke(0.5.dp, Color(0xFF2A2850)),
                                modifier = Modifier.clickable {
                                    clipboardManager.getText()?.text?.let { vkLink = it }
                                }
                            ) {
                                Text(
                                    "Вставить",
                                    fontSize = 7.5.sp, color = Color(0xFF6B7280),
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
            isConnected = isConnected
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
                    shape = RoundedCornerShape(11.dp),
                    color = Color(0xFF13112A),
                    border = BorderStroke(0.5.dp, Color(0xFF1E1C3A))
                ) {
                    Column {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 10.dp, vertical = 7.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "ТРАФИК", fontSize = 8.sp, color = Color(0xFF6B7280),
                                letterSpacing = 0.6.sp
                            )
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(3.dp)
                            ) {
                                Box(
                                    Modifier
                                        .size(4.dp)
                                        .clip(CircleShape)
                                        .background(Color(0xFF4ADE80))
                                        .graphicsLayer { alpha = liveDotAlpha }
                                )
                                Text(
                                    "Live", fontSize = 7.5.sp, color = Color(0xFF4ADE80),
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                        HorizontalDivider(color = Color(0xFF1E1C3A), thickness = 0.5.dp)
                        Row(
                            modifier = Modifier.padding(8.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            TrafficBox(
                                label = "Загружено",
                                arrow = "↓",
                                arrowColor = Color(0xFF60A5FA),
                                valueMb = downMb,
                                speedMbs = downSpeedMbs,
                                history = downHistory,
                                barColor = Color(0xFF3B82F6),
                                modifier = Modifier.weight(1f)
                            )
                            TrafficBox(
                                label = "Отдано",
                                arrow = "↑",
                                arrowColor = Color(0xFF818CF8),
                                valueMb = upMb,
                                speedMbs = upSpeedMbs,
                                history = upHistory,
                                barColor = Color(0xFF6366F1),
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }

                // ── Services Block ─────────────────────────────────────────
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(11.dp),
                    color = Color(0xFF13112A),
                    border = BorderStroke(0.5.dp, Color(0xFF1E1C3A))
                ) {
                    Column {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { servicesExpanded = !servicesExpanded }
                                .padding(horizontal = 10.dp, vertical = 9.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    "СЕРВИСЫ", fontSize = 8.sp, color = Color(0xFF6B7280),
                                    letterSpacing = 0.6.sp
                                )
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    services.forEach { svc ->
                                        val dotColor = when {
                                            svc.pingMs == -1 -> Color(0xFF6366F1)
                                            svc.pingMs == -2 -> Color(0xFFF87171)
                                            svc.pingMs > 300 -> Color(0xFFF59E0B)
                                            else -> Color(0xFF4ADE80)
                                        }
                                        Box(Modifier.size(4.dp).clip(CircleShape).background(dotColor))
                                    }
                                }
                                val okCount = services.count { it.pingMs > 0 && it.pingMs <= 300 }
                                val totalDone = services.count { it.pingMs != -1 }
                                if (totalDone > 0) {
                                    Text(
                                        "$okCount/${services.size} ОК",
                                        fontSize = 7.5.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (okCount == services.size) Color(0xFF4ADE80)
                                        else Color(0xFFF59E0B)
                                    )
                                }
                            }
                            Text(
                                "▾", fontSize = 12.sp, color = Color(0xFF4B5563),
                                modifier = Modifier.graphicsLayer { rotationX = chevronRotation }
                            )
                        }

                        AnimatedVisibility(
                            visible = servicesExpanded,
                            enter = expandVertically(tween(250)) + fadeIn(tween(250)),
                            exit = shrinkVertically(tween(200)) + fadeOut(tween(150))
                        ) {
                            Column {
                                HorizontalDivider(color = Color(0xFF1E1C3A), thickness = 0.5.dp)
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
                                                .clip(RoundedCornerShape(6.dp))
                                                .background(svc.iconBg),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(svc.emoji, fontSize = 11.sp)
                                        }
                                        Text(
                                            svc.name,
                                            fontSize = 8.5.sp,
                                            fontWeight = FontWeight.Medium,
                                            color = Color(0xFFD4D4D8),
                                            modifier = Modifier.weight(1f)
                                        )
                                        when {
                                            svc.pingMs == -1 -> {
                                                CircularProgressIndicator(
                                                    modifier = Modifier.size(8.dp),
                                                    color = Color(0xFF818CF8),
                                                    strokeWidth = 1.dp
                                                )
                                            }
                                            svc.pingMs == -2 -> {
                                                Box(
                                                    Modifier.size(5.dp).clip(CircleShape)
                                                        .background(Color(0xFFF87171))
                                                )
                                                Text(
                                                    "Недоступен", fontSize = 7.5.sp,
                                                    color = Color(0xFFF87171),
                                                    fontWeight = FontWeight.SemiBold
                                                )
                                            }
                                            svc.pingMs > 300 -> {
                                                Text(
                                                    "${svc.pingMs}мс", fontSize = 7.sp,
                                                    color = Color(0xFF4B5563)
                                                )
                                                Box(
                                                    Modifier.size(5.dp).clip(CircleShape)
                                                        .background(Color(0xFFF59E0B))
                                                )
                                                Text(
                                                    "Мед.", fontSize = 7.5.sp,
                                                    color = Color(0xFFF59E0B),
                                                    fontWeight = FontWeight.SemiBold
                                                )
                                            }
                                            else -> {
                                                Text(
                                                    "${svc.pingMs}мс", fontSize = 7.sp,
                                                    color = Color(0xFF4B5563)
                                                )
                                                Box(
                                                    Modifier.size(5.dp).clip(CircleShape)
                                                        .background(Color(0xFF4ADE80))
                                                )
                                                Text(
                                                    "ОК", fontSize = 7.5.sp,
                                                    color = Color(0xFF4ADE80),
                                                    fontWeight = FontWeight.SemiBold
                                                )
                                            }
                                        }
                                    }
                                    if (idx < services.size - 1) {
                                        HorizontalDivider(
                                            modifier = Modifier.padding(horizontal = 10.dp),
                                            color = Color(0xFF1E1C3A),
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
            .size(96.dp)
            .alpha(if (enabled) 1f else 0.38f)
            .clip(CircleShape)
            .clickable(
                enabled = enabled,
                interactionSource = interactionSource,
                indication = ripple(bounded = true, radius = 48.dp),
                onClick = onClick
            )
            .drawBehind {
                val r = size.minDimension / 2f
                val ringStroke = 1.5.dp.toPx()
                val ringRadius = r - ringStroke / 2f
                drawCircle(ringColor, radius = ringRadius, style = Stroke(ringStroke))
                if (tunnelRunning && isConnecting) {
                    rotate(spinAngle) {
                        drawArc(
                            color = ringColor,
                            startAngle = 0f,
                            sweepAngle = arcSweep,
                            useCenter = false,
                            topLeft = Offset(ringStroke / 2f, ringStroke / 2f),
                            size = Size(size.width - ringStroke, size.height - ringStroke),
                            style = Stroke(ringStroke, cap = StrokeCap.Round)
                        )
                    }
                }
                val iconSize = 36.dp.toPx()
                val iconLeft = (size.width - iconSize) / 2f
                val iconTop = (size.height - iconSize) / 2f
                val cx = size.width / 2f
                val sw = 1.8.dp.toPx()
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
            fontSize = 18.sp,
            fontWeight = FontWeight.Medium
        )
        if (isConnected) {
            Text(
                "Соединение защищено",
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                fontSize = 12.sp
            )
        }
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
        !tunnelRunning -> "Облачный релей · ожидание"
        isConnecting -> "Облачный релей · подключение..."
        isConnected -> "Облачный релей · активен"
        else -> "Облачный релей · готов"
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Canvas(Modifier.size(6.dp)) { drawCircle(dotColor) }
        Text(statusText, style = MaterialTheme.typography.labelSmall, color = dotColor)
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

        val linkToSave = rawLink.trim()
        val provider = when {
            linkToSave.contains("telemost", ignoreCase = true) ||
                linkToSave.contains("ya.ru", ignoreCase = true) -> "yandex"
            else -> "vk"
        }
        val linkForTunnel = when (provider) {
            "yandex" -> linkToSave
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
                Toast.makeText(context, "Неверный код доступа", Toast.LENGTH_SHORT).show()
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
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        color = Color(0xFF0D0B1A),
        border = BorderStroke(0.5.dp, Color(0xFF1E1C3A))
    ) {
        Column(Modifier.padding(6.dp, 6.dp, 6.dp, 4.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Text(arrow, fontSize = 9.sp, color = arrowColor)
                Text(
                    label.uppercase(), fontSize = 6.5.sp,
                    color = Color(0xFF6B7280), letterSpacing = 0.4.sp
                )
            }
            Spacer(Modifier.height(2.dp))
            Row(
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    "%.0f".format(valueMb), fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold, color = Color(0xFFD4D4D8)
                )
                Text("МБ", fontSize = 8.sp, color = Color(0xFF6B7280))
            }
            Text(
                "$arrow %.1f МБ/с".format(speedMbs),
                fontSize = 7.sp,
                color = if (speedMbs > 0f) arrowColor else Color(0xFF4B5563)
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
    if (trimmed.contains("vk.com/call/join/")) return trimmed
    if (trimmed.contains("telemost", ignoreCase = true) ||
        trimmed.contains("ya.ru/telemost", ignoreCase = true)
    ) {
        return trimmed
    }
    return ""
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
    TunnelManager.stop()
    try {
        context.startService(
            Intent(context, TunnelService::class.java).apply { action = "STOP" }
        )
    } catch (_: Exception) {
    }
}
