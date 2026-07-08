package com.wdtt.client.ui

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wdtt.client.AccessManager
import com.wdtt.client.BillingConfig
import com.wdtt.client.SettingsStore
import com.wdtt.client.xray.SubscriptionParser
import com.wdtt.client.xray.SubscriptionResult
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private fun openTelegramBot(context: Context) {
    context.startActivity(
        Intent(Intent.ACTION_VIEW, Uri.parse("https://t.me/skypathvpn_bot"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    )
}

@Composable
fun SubscriptionSetupScreen(
    settingsStore: SettingsStore,
    onFinish: () -> Unit,
) {
    val context = LocalContext.current
    val scope   = rememberCoroutineScope()

    var url           by remember { mutableStateOf("") }
    var isLoading     by remember { mutableStateOf(false) }
    var result        by remember { mutableStateOf<SubscriptionResult?>(null) }
    var errorMsg      by remember { mutableStateOf<String?>(null) }

    val isValidUrl = url.trimStart().let {
        it.startsWith("https://") || it.startsWith("http://")
    }
    val canProceed = result != null && result!!.servers.isNotEmpty()

    // Вход в уже существующую подписку по subId / Telegram ID (через backend).
    var showLinkExisting by remember { mutableStateOf(false) }
    if (showLinkExisting) {
        LinkExistingSubscriptionScreen(
            store = settingsStore,
            onDone = onFinish,
            onBack = { showLinkExisting = false },
        )
        return
    }

    // ── Статус доступа (триал / платно / истёк) ────────────────────────────
    var subExpireAt  by remember { mutableLongStateOf(settingsStore.getSubExpireAt()) }
    var trialStartAt by remember { mutableLongStateOf(settingsStore.getTrialStartAt()) }
    var serverCount   by remember { mutableIntStateOf(settingsStore.loadServers().size) }
    var savedSubUrl   by remember { mutableStateOf(settingsStore.getSubscriptionUrl()) }
    var isRefreshing  by remember { mutableStateOf(false) }

    fun refreshAccessState() {
        subExpireAt  = settingsStore.getSubExpireAt()
        trialStartAt = settingsStore.getTrialStartAt()
        serverCount  = settingsStore.loadServers().size
        savedSubUrl  = settingsStore.getSubscriptionUrl()
    }

    val access = AccessManager(settingsStore)
    val nowSec = System.currentTimeMillis() / 1000L
    val accessState = access.current(nowSec)

    fun refreshExistingServers() {
        if (savedSubUrl.isBlank() || isRefreshing) return
        scope.launch {
            isRefreshing = true
            try {
                val r = SubscriptionParser.fetchSubscription(savedSubUrl)
                if (r.servers.isNotEmpty()) {
                    settingsStore.saveServers(r.servers)
                    settingsStore.saveSubExpireAt(r.expireAt)
                    settingsStore.saveSubTitle(r.title)
                    settingsStore.saveSubUpload(r.upload)
                    settingsStore.saveSubDownload(r.download)
                    settingsStore.saveSubTotal(r.total)
                    settingsStore.saveSubAnnounce(r.announce)
                    refreshAccessState()
                }
            } catch (_: Exception) {
                // тихий отказ — статус-карточка просто останется как есть
            }
            isRefreshing = false
        }
    }

    fun loadSubscription() {
        if (url.isBlank() || isLoading) return
        // Принимаем любую валидную http(s)-ссылку подписки (3X-UI/провайдер),
        // не только skypath.fun — иначе легитимные ссылки давали ложную ошибку.
        scope.launch {
            isLoading = true
            result    = null
            errorMsg  = null
            try {
                val r = SubscriptionParser.fetchSubscription(url.trim())
                if (r.servers.isEmpty()) {
                    errorMsg = "Серверы не найдены — проверьте ссылку"
                } else {
                    result = r
                    settingsStore.saveVlessInputMode("subscription")
                    settingsStore.saveSubscriptionUrl(url.trim())
                    settingsStore.saveServers(r.servers)
                    settingsStore.saveSubExpireAt(r.expireAt)
                    settingsStore.saveSubTitle(r.title)
                    settingsStore.saveSubUpload(r.upload)
                    settingsStore.saveSubDownload(r.download)
                    settingsStore.saveSubTotal(r.total)
                    settingsStore.saveSubAnnounce(r.announce)
                    refreshAccessState()
                }
            } catch (e: Exception) {
                errorMsg = "Не удалось загрузить: ${e.message?.take(80)}"
            }
            isLoading = false
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(SkyflowColors.Background, Color(0xFF0A0A18), SkyflowColors.Background)
                )
            ),
    ) {
        AccentGlow(
            modifier  = Modifier.size(340.dp).align(Alignment.TopCenter).padding(top = 60.dp),
            intensity = 0.45f,
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp),
        ) {
            // ── Назад ────────────────────────────────────────────────────────
            TextButton(onClick = onFinish, modifier = Modifier.padding(top = 4.dp)) {
                Icon(Icons.Default.ArrowBack, null, tint = SkyflowColors.TextSecondary, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Назад", color = SkyflowColors.TextSecondary, fontSize = 14.sp)
            }

            Spacer(Modifier.height(8.dp))
            Text(
                "Подписка",
                fontSize   = 24.sp,
                fontFamily = displayFontFamily,
                fontWeight = FontWeight.Bold,
                color      = SkyflowColors.TextPrimary,
            )
            Spacer(Modifier.height(16.dp))

            // ── Карточка статуса доступа ────────────────────────────────────
            AccessStatusCard(
                state          = accessState,
                serverCount    = serverCount,
                hasSubUrl      = savedSubUrl.isNotBlank(),
                isRefreshing   = isRefreshing,
                onBuy          = { openTelegramBot(context) },
                onRefreshServers = { refreshExistingServers() },
            )

            Spacer(Modifier.height(28.dp))
            HorizontalDivider(color = SkyflowColors.Border, thickness = 0.5.dp)
            Spacer(Modifier.height(20.dp))

            // ── Подключить другую ссылку ─────────────────────────────────────
            Text(
                "Подключить другую подписку",
                fontSize   = 16.sp,
                fontFamily = interFontFamily,
                fontWeight = FontWeight.SemiBold,
                color      = SkyflowColors.TextPrimary,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "Вставьте ссылку от провайдера — приложение загрузит серверы автоматически.",
                fontSize   = 13.sp,
                fontFamily = interFontFamily,
                color      = SkyflowColors.TextSecondary,
                lineHeight = 18.sp,
            )

            Spacer(Modifier.height(16.dp))

            OutlinedTextField(
                value         = url,
                onValueChange = {
                    url      = it
                    result   = null
                    errorMsg = null
                },
                label         = { Text("Ссылка подписки") },
                placeholder   = { Text("https://sub.example.com/...") },
                modifier      = Modifier.fillMaxWidth(),
                singleLine    = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                colors        = OutlinedTextFieldDefaults.colors(
                    focusedTextColor         = SkyflowColors.TextPrimary,
                    unfocusedTextColor       = SkyflowColors.TextPrimary,
                    focusedBorderColor       = SkyflowColors.Accent,
                    unfocusedBorderColor     = SkyflowColors.Border,
                    focusedLabelColor        = SkyflowColors.AccentLight,
                    unfocusedLabelColor      = SkyflowColors.TextMuted,
                    cursorColor              = SkyflowColors.Accent,
                    focusedPlaceholderColor  = SkyflowColors.Placeholder,
                    unfocusedPlaceholderColor= SkyflowColors.Placeholder,
                ),
                trailingIcon  = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier          = Modifier.padding(end = 4.dp)
                    ) {
                        IconButton(
                            onClick  = {
                                val cb   = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                val text = cb.primaryClip?.getItemAt(0)?.text?.toString() ?: ""
                                if (text.isNotBlank()) {
                                    url      = text.trim()
                                    result   = null
                                    errorMsg = null
                                }
                            },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                Icons.Default.ContentPaste,
                                null,
                                tint     = SkyflowColors.AccentLight,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        if (url.isNotBlank()) {
                            IconButton(
                                onClick  = { url = ""; result = null; errorMsg = null },
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(
                                    Icons.Default.Clear,
                                    null,
                                    tint     = SkyflowColors.TextMuted,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }
            )

            Spacer(Modifier.height(12.dp))

            Button(
                onClick  = ::loadSubscription,
                enabled  = isValidUrl && !isLoading,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape    = SkyflowShapes.Chip,
                colors   = ButtonDefaults.buttonColors(
                    containerColor = SkyflowColors.Accent,
                    contentColor   = SkyflowColors.OnAccent,
                    disabledContainerColor = SkyflowColors.Border,
                    disabledContentColor   = SkyflowColors.TextMuted
                )
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier    = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color       = SkyflowColors.OnAccent
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Загрузка...", fontWeight = FontWeight.SemiBold)
                } else {
                    Text("Загрузить серверы", fontWeight = FontWeight.SemiBold)
                }
            }

            // ── Вход в существующую подписку по subId / Telegram ID ───────────
            Spacer(Modifier.height(4.dp))
            TextButton(onClick = { showLinkExisting = true }, modifier = Modifier.fillMaxWidth()) {
                Text(
                    "У меня уже есть подписка — войти по ID",
                    color = SkyflowColors.AccentLight,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                )
            }

            // ── Result / error ────────────────────────────────────────────────
            AnimatedVisibility(
                visible = result != null || errorMsg != null,
                enter   = fadeIn(tween(250)) + expandVertically(tween(250)),
                exit    = fadeOut(tween(150)) + shrinkVertically(tween(150))
            ) {
                Column {
                    Spacer(Modifier.height(8.dp))
                    if (result != null) {
                        Surface(
                            shape    = SkyflowShapes.Card,
                            color    = SkyflowColors.Connected.copy(alpha = 0.08f),
                            border   = BorderStroke(1.dp, SkyflowColors.Connected.copy(alpha = 0.25f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier              = Modifier.padding(14.dp),
                                verticalAlignment     = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Icon(
                                    Icons.Default.CheckCircle,
                                    null,
                                    tint     = SkyflowColors.Connected,
                                    modifier = Modifier.size(20.dp)
                                )
                                Column {
                                    val title = result!!.title.ifBlank { "Подписка" }
                                    Text(
                                        title,
                                        fontFamily  = interFontFamily,
                                        fontWeight  = FontWeight.SemiBold,
                                        color       = SkyflowColors.Connected,
                                        fontSize    = 14.sp
                                    )
                                    Text(
                                        "Загружено ${result!!.servers.size} серверов",
                                        fontFamily = interFontFamily,
                                        fontSize   = 13.sp,
                                        color      = SkyflowColors.TextSecondary
                                    )
                                }
                            }
                        }
                    } else if (errorMsg != null) {
                        Surface(
                            shape    = SkyflowShapes.Card,
                            color    = SkyflowColors.ErrorColor.copy(alpha = 0.08f),
                            border   = BorderStroke(1.dp, SkyflowColors.ErrorColor.copy(alpha = 0.25f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier              = Modifier.padding(14.dp),
                                verticalAlignment     = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Icon(
                                    Icons.Default.Warning,
                                    null,
                                    tint     = SkyflowColors.ErrorColor,
                                    modifier = Modifier.size(20.dp)
                                )
                                Text(
                                    errorMsg!!,
                                    fontFamily = interFontFamily,
                                    fontSize   = 13.sp,
                                    color      = SkyflowColors.ErrorColor
                                )
                            }
                        }
                    }
                }
            }

            AnimatedVisibility(visible = canProceed) {
                Column {
                    Spacer(Modifier.height(16.dp))
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(54.dp)
                            .clip(SkyflowShapes.Button)
                            .clickable {
                                scope.launch {
                                    settingsStore.setSubscriptionSetupDone()
                                    onFinish()
                                }
                            },
                        shape    = SkyflowShapes.Button,
                        color    = Color.Transparent,
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(SkyflowGradients.Accent, SkyflowShapes.Button),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                "Готово",
                                fontSize   = 17.sp,
                                fontFamily = interFontFamily,
                                fontWeight = FontWeight.SemiBold,
                                color      = Color.White,
                                textAlign  = TextAlign.Center,
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

/** Карточка текущего статуса доступа: триал (счётчик дней), платная подписка, истёкший доступ. */
@Composable
private fun AccessStatusCard(
    state: com.wdtt.client.AccessState,
    serverCount: Int,
    hasSubUrl: Boolean,
    isRefreshing: Boolean,
    onBuy: () -> Unit,
    onRefreshServers: () -> Unit,
) {
    val isExpired = state is com.wdtt.client.AccessState.Expired
    val isTrial   = state is com.wdtt.client.AccessState.Trial
    val isPaid    = state is com.wdtt.client.AccessState.Paid

    val accentColor = when {
        isExpired -> SkyflowColors.ErrorColor
        isTrial   -> SkyflowColors.Connecting
        else      -> SkyflowColors.Accent
    }
    val badgeIcon = when {
        isExpired -> Icons.Default.ErrorOutline
        isTrial   -> Icons.Default.AccessTime
        else      -> Icons.Default.Star
    }
    val titleText = when {
        isExpired -> "Доступ закончился"
        isTrial   -> "Пробный период"
        else      -> "SKYFLOW Premium"
    }
    val subtitleText = when {
        isExpired -> "Оформите подписку, чтобы продолжить"
        isTrial   -> "Режим маскировки — бесплатно"
        else      -> "Оба режима · без ограничений"
    }
    val daysLeft = when (state) {
        is com.wdtt.client.AccessState.Trial -> state.daysLeft
        else -> 0
    }
    val expireAt = when (state) {
        is com.wdtt.client.AccessState.Trial -> state.expireAt
        is com.wdtt.client.AccessState.Paid  -> state.expireAt
        else -> 0L
    }
    val dateText = if (expireAt > 0L) {
        "до ${SimpleDateFormat("d MMM yyyy", Locale("ru")).format(Date(expireAt * 1000L))}"
    } else null

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape    = SkyflowShapes.Card,
        color    = SkyflowColors.GlassSurfaceElevated,
        border   = BorderStroke(1.dp, accentColor.copy(alpha = 0.3f)),
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .clip(SkyflowShapes.Chip)
                        .background(accentColor.copy(alpha = 0.16f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(badgeIcon, null, tint = accentColor, modifier = Modifier.size(22.dp))
                }
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        titleText,
                        fontFamily = displayFontFamily,
                        fontWeight = FontWeight.SemiBold,
                        fontSize   = 15.sp,
                        color      = SkyflowColors.TextPrimary,
                    )
                    Text(
                        subtitleText,
                        fontFamily = interFontFamily,
                        fontSize   = 12.sp,
                        color      = SkyflowColors.TextSecondary,
                    )
                }
            }

            if (isTrial || isPaid) {
                Spacer(Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    if (isTrial) {
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text(
                                "$daysLeft",
                                fontFamily = displayFontFamily,
                                fontWeight = FontWeight.Bold,
                                fontSize   = 34.sp,
                                color      = accentColor,
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                "дн.",
                                fontFamily = interFontFamily,
                                fontSize   = 14.sp,
                                color      = SkyflowColors.TextSecondary,
                                modifier   = Modifier.padding(bottom = 6.dp),
                            )
                        }
                    } else {
                        Text(
                            "Активна",
                            fontFamily = displayFontFamily,
                            fontWeight = FontWeight.Bold,
                            fontSize   = 20.sp,
                            color      = accentColor,
                        )
                    }
                    if (dateText != null) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.CalendarToday, null,
                                tint = SkyflowColors.TextMuted,
                                modifier = Modifier.size(13.dp),
                            )
                            Spacer(Modifier.width(5.dp))
                            Text(
                                dateText,
                                fontFamily = interFontFamily,
                                fontSize   = 12.sp,
                                color      = SkyflowColors.TextMuted,
                            )
                        }
                    }
                }
                if (isTrial) {
                    Spacer(Modifier.height(10.dp))
                    val progress = (1f - daysLeft.toFloat() / BillingConfig.TRIAL_DAYS.toFloat()).coerceIn(0f, 1f)
                    LinearProgressIndicator(
                        progress   = { progress },
                        modifier   = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp)),
                        color      = accentColor,
                        trackColor = SkyflowColors.Border,
                    )
                }
            }

            if (hasSubUrl) {
                Spacer(Modifier.height(14.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(SkyflowShapes.Chip)
                        .background(SkyflowColors.Surface)
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Серверов: $serverCount",
                        fontFamily = interFontFamily,
                        fontSize   = 12.5.sp,
                        color      = SkyflowColors.TextSecondary,
                        modifier   = Modifier.weight(1f),
                    )
                    if (isRefreshing) {
                        CircularProgressIndicator(
                            modifier    = Modifier.size(14.dp),
                            strokeWidth = 2.dp,
                            color       = SkyflowColors.AccentLight,
                        )
                    } else {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.clickable { onRefreshServers() },
                        ) {
                            Icon(
                                Icons.Default.Refresh, null,
                                tint = SkyflowColors.AccentLight,
                                modifier = Modifier.size(14.dp),
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                "Обновить",
                                fontFamily = interFontFamily,
                                fontWeight = FontWeight.SemiBold,
                                fontSize   = 12.sp,
                                color      = SkyflowColors.AccentLight,
                            )
                        }
                    }
                }
            }

            if (!isPaid) {
                Spacer(Modifier.height(14.dp))
                Button(
                    onClick  = onBuy,
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    shape    = SkyflowShapes.Chip,
                    colors   = ButtonDefaults.buttonColors(
                        containerColor = accentColor,
                        contentColor   = Color.White,
                    ),
                ) {
                    Icon(Icons.Default.Send, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (isExpired) "Купить · ${BillingConfig.SUB_PRICE_RUB} ₽"
                        else "Купить полный доступ · ${BillingConfig.SUB_PRICE_RUB} ₽",
                        fontFamily = interFontFamily,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            } else {
                Spacer(Modifier.height(14.dp))
                OutlinedButton(
                    onClick  = onBuy,
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    colors   = ButtonDefaults.outlinedButtonColors(contentColor = SkyflowColors.AccentLight),
                    border   = BorderStroke(1.dp, SkyflowColors.Accent),
                    shape    = SkyflowShapes.Chip,
                ) {
                    Text("Продлить подписку", fontFamily = interFontFamily, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}
