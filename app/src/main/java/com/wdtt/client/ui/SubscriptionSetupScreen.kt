package com.wdtt.client.ui

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ContentPaste
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
import com.wdtt.client.SettingsStore
import com.wdtt.client.xray.SubscriptionParser
import com.wdtt.client.xray.SubscriptionResult
import kotlinx.coroutines.launch

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
    var isWrongDomain by remember { mutableStateOf(false) }

    val isValidUrl = url.trimStart().let {
        it.startsWith("https://") || it.startsWith("http://")
    }
    val canProceed = result != null && result!!.servers.isNotEmpty()

    fun loadSubscription() {
        if (url.isBlank() || isLoading) return
        // Разрешены только ссылки от skypath.fun
        val host = try { java.net.URL(url.trim()).host.lowercase() } catch (_: Exception) { "" }
        val isAllowed = host == "skypath.fun" || host.endsWith(".skypath.fun")
        if (!isAllowed) {
            isWrongDomain = true
            errorMsg = null
            return
        }
        isWrongDomain = false
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
        contentAlignment = Alignment.Center
    ) {
        AccentGlow(
            modifier  = Modifier.size(340.dp).align(Alignment.TopCenter).padding(top = 60.dp),
            intensity = 0.45f,
        )

        Column(
            modifier              = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = 28.dp),
            horizontalAlignment   = Alignment.CenterHorizontally,
            verticalArrangement   = Arrangement.spacedBy(0.dp)
        ) {

            // ── Logo ─────────────────────────────────────────────────────────
            Box(
                Modifier
                    .size(72.dp)
                    .clip(SkyflowShapes.Logo)
                    .background(SkyflowColors.Surface)
                    .border(SkyflowBorders.Logo, SkyflowShapes.Logo),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "S",
                    fontSize    = 30.sp,
                    fontFamily  = interFontFamily,
                    fontWeight  = FontWeight.Black,
                    color       = SkyflowColors.VkStripe
                )
            }

            Spacer(Modifier.height(20.dp))

            // ── Title ─────────────────────────────────────────────────────────
            Text(
                "Подключите подписку",
                fontSize    = 24.sp,
                fontFamily  = interFontFamily,
                fontWeight  = FontWeight.Bold,
                color       = SkyflowColors.TextPrimary,
                textAlign   = TextAlign.Center
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Вставьте ссылку от провайдера.\nПриложение загрузит серверы автоматически.",
                fontSize    = 15.sp,
                fontFamily  = interFontFamily,
                color       = SkyflowColors.TextSecondary,
                textAlign   = TextAlign.Center,
                lineHeight  = 22.sp
            )

            Spacer(Modifier.height(28.dp))

            // ── URL field ─────────────────────────────────────────────────────
            OutlinedTextField(
                value         = url,
                onValueChange = {
                    url          = it
                    result       = null
                    errorMsg     = null
                    isWrongDomain = false
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

            // ── Load button ───────────────────────────────────────────────────
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

            // ── Wrong-domain banner ───────────────────────────────────────────
            AnimatedVisibility(
                visible = isWrongDomain,
                enter   = fadeIn(tween(250)) + expandVertically(tween(250)),
                exit    = fadeOut(tween(150)) + shrinkVertically(tween(150))
            ) {
                Column {
                    Spacer(Modifier.height(12.dp))
                    Surface(
                        shape    = SkyflowShapes.Card,
                        color    = SkyflowColors.ErrorColor.copy(alpha = 0.08f),
                        border   = androidx.compose.foundation.BorderStroke(
                            1.dp, SkyflowColors.ErrorColor.copy(alpha = 0.28f)
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Row(
                                verticalAlignment     = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Text("⛔", fontSize = 20.sp)
                                Column {
                                    Text(
                                        "Это не подписка SKYFLOW VPN",
                                        fontFamily  = interFontFamily,
                                        fontWeight  = FontWeight.Bold,
                                        fontSize    = 14.sp,
                                        color       = SkyflowColors.ErrorColor
                                    )
                                    Text(
                                        "Для продолжения работы купите подписку",
                                        fontFamily = interFontFamily,
                                        fontSize   = 13.sp,
                                        color      = SkyflowColors.TextSecondary
                                    )
                                }
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Surface(
                                    shape  = SkyflowShapes.Chip,
                                    color  = SkyflowColors.Accent.copy(alpha = 0.12f),
                                    border = androidx.compose.foundation.BorderStroke(
                                        0.5.dp, SkyflowColors.Accent.copy(alpha = 0.3f)
                                    )
                                ) {
                                    Text(
                                        "${com.wdtt.client.BillingConfig.SUB_PRICE_RUB} ₽ / месяц",
                                        fontFamily  = interFontFamily,
                                        fontWeight  = FontWeight.Bold,
                                        fontSize    = 14.sp,
                                        color       = SkyflowColors.AccentLight,
                                        modifier    = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                                    )
                                }
                                Button(
                                    onClick = {
                                        val ctx = context
                                        ctx.startActivity(
                                            android.content.Intent(
                                                android.content.Intent.ACTION_VIEW,
                                                android.net.Uri.parse("https://t.me/skypathvpn_bot")
                                            ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                        )
                                    },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = SkyflowColors.Accent,
                                        contentColor   = SkyflowColors.OnAccent
                                    ),
                                    shape = SkyflowShapes.Chip
                                ) {
                                    Text("✈  Купить в Telegram", fontFamily = interFontFamily, fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }
                    }
                }
            }

            // ── Result / error ────────────────────────────────────────────────
            AnimatedVisibility(
                visible = result != null || errorMsg != null,
                enter   = fadeIn(tween(250)) + expandVertically(tween(250)),
                exit    = fadeOut(tween(150)) + shrinkVertically(tween(150))
            ) {
                Spacer(Modifier.height(12.dp))
                if (result != null) {
                    Surface(
                        shape    = SkyflowShapes.Card,
                        color    = SkyflowColors.Connected.copy(alpha = 0.08f),
                        border   = androidx.compose.foundation.BorderStroke(
                            1.dp, SkyflowColors.Connected.copy(alpha = 0.25f)
                        ),
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
                        border   = androidx.compose.foundation.BorderStroke(
                            1.dp, SkyflowColors.ErrorColor.copy(alpha = 0.25f)
                        ),
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

            Spacer(Modifier.height(20.dp))

            // ── Continue button ───────────────────────────────────────────────
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp)
                    .clip(SkyflowShapes.Button)
                    .clickable(enabled = canProceed) {
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
                        .background(
                            if (canProceed) SkyflowGradients.Accent
                            else Brush.linearGradient(listOf(SkyflowColors.Border, SkyflowColors.Border)),
                            SkyflowShapes.Button
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "Продолжить →",
                        fontSize   = 17.sp,
                        fontFamily = interFontFamily,
                        fontWeight = FontWeight.SemiBold,
                        color      = if (canProceed) Color.White else SkyflowColors.TextMuted,
                        textAlign  = TextAlign.Center,
                    )
                }
            }

            // ── Начать бесплатно (пробный период) ─────────────────────────────
            Spacer(Modifier.height(10.dp))
            TextButton(
                onClick = {
                    scope.launch {
                        AccessManager(settingsStore).ensureTrialStarted(System.currentTimeMillis() / 1000L)
                        settingsStore.setSubscriptionSetupDone()
                        onFinish()
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    "Начать бесплатно · ${com.wdtt.client.BillingConfig.TRIAL_DAYS} дней",
                    fontFamily = interFontFamily,
                    fontWeight = FontWeight.SemiBold,
                    fontSize   = 15.sp,
                    color      = SkyflowColors.AccentLight,
                )
            }
        }
    }
}
