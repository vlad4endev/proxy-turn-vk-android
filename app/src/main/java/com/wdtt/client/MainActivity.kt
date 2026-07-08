package com.wdtt.client

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.HapticFeedbackConstants
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.foundation.shape.RoundedCornerShape
import kotlinx.coroutines.launch
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.VpnKey
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.platform.LocalContext
import androidx.core.view.WindowCompat
import com.wdtt.client.ui.AppUpdateDialog
import com.wdtt.client.ui.AdaptiveContentHost
import com.wdtt.client.ui.LogsTab
import com.wdtt.client.ui.OnboardingScreen
import com.wdtt.client.ui.SettingsHubTab
import com.wdtt.client.ui.SetupPermissionsScreen
import com.wdtt.client.ui.SubscriptionSetupScreen
import com.wdtt.client.ui.ResponsiveLayout
import com.wdtt.client.ui.SkyflowColors
import com.wdtt.client.ui.SkyflowShapes
import com.wdtt.client.ui.TunnelTab
import com.wdtt.client.xray.SubscriptionParser
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.first
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

class MainActivity : ComponentActivity() {

    companion object {
        var activeActivities = 0
        var isForeground: Boolean
            get() = activeActivities > 0
            set(value) {}
    }

    override fun onStart() {
        super.onStart()
        activeActivities++
        ManlCaptchaWebViewManager.checkAndShowPendingCaptcha(this)
        CaptchaWebViewActivityLauncher.checkAndShowPendingCaptcha(this)
    }

    override fun onStop() {
        super.onStop()
        activeActivities--
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        WindowCompat.setDecorFitsSystemWindows(window, false)

        setContent {
            val settingsStore = remember { SettingsStore(this) }
            val themeMode by settingsStore.themeMode.collectAsStateWithLifecycle(initialValue = "system")
            val onboardingDone by settingsStore.onboardingDone.collectAsStateWithLifecycle(initialValue = false)
            val permissionsSetupDone by settingsStore.permissionsSetupDone.collectAsStateWithLifecycle(initialValue = false)
            val subscriptionSetupDone by settingsStore.subscriptionSetupDone.collectAsStateWithLifecycle(initialValue = false)
            val scope = rememberCoroutineScope()

            // Ждём первого реального значения из DataStore перед тем как рендерить экраны.
            // Без этого флага: на первом кадре initialValue=false → OnboardingScreen мигает,
            // затем DataStore отдаёт true → переключается на MainScreen.
            var settingsLoaded by remember { mutableStateOf(false) }
            LaunchedEffect(Unit) {
                // Три последовательных .first() — DataStore возвращает значение за ~1–5 мс.
                settingsStore.onboardingDone.first()
                settingsStore.permissionsSetupDone.first()
                settingsStore.subscriptionSetupDone.first()
                settingsLoaded = true
            }

            WDTTTheme(themeMode = themeMode) {
                if (!settingsLoaded) {
                    // DataStore ещё не загрузился — показываем пустой фон совпадающий
                    // с цветом splash screen. Длится < 10 мс, незаметно для пользователя.
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(SkyflowColors.Background)
                    )
                } else {
                    when {
                        !onboardingDone -> {
                            OnboardingScreen(
                                onFinish = {
                                    scope.launch { settingsStore.setOnboardingDone() }
                                }
                            )
                        }
                        !permissionsSetupDone -> {
                            SetupPermissionsScreen(
                                onFinish = {
                                    scope.launch { settingsStore.setPermissionsSetupDone() }
                                }
                            )
                        }
                        else -> {
                            // Первый вход: НЕ требуем подписку — стартуем 10-дневный пробный
                            // период и пускаем сразу в приложение. Экран подписки доступен
                            // внутри приложения (чип/кнопка), а не как обязательный гейт.
                            LaunchedEffect(Unit) {
                                if (settingsStore.getSubExpireAt() <= 0L) {
                                    AccessManager(settingsStore)
                                        .ensureTrialStarted(System.currentTimeMillis() / 1000L)

                                    // Пробный период: подставляем общие триал-ресурсы, но
                                    // только если у пользователя ещё нет своих — не перетираем
                                    // уже настроенную ссылку/подписку.
                                    if (settingsStore.wdttLink.first().isBlank() &&
                                        BillingConfig.TRIAL_VK_CALL_LINK.isNotBlank()
                                    ) {
                                        settingsStore.saveWdttLink(BillingConfig.TRIAL_VK_CALL_LINK)
                                    }
                                    if (settingsStore.loadServers().isEmpty() &&
                                        BillingConfig.TRIAL_SUB_URL.isNotBlank()
                                    ) {
                                        try {
                                            val result = SubscriptionParser.fetchSubscription(BillingConfig.TRIAL_SUB_URL)
                                            if (result.servers.isNotEmpty()) {
                                                SubscriptionLinker.saveSubscription(
                                                    settingsStore, BillingConfig.TRIAL_SUB_URL, result
                                                )
                                            }
                                        } catch (_: Exception) {
                                            // Нет сети на первом запуске — не блокируем вход,
                                            // «Скорость» останется недоступна до следующего раза.
                                        }
                                    }
                                }
                                if (!subscriptionSetupDone) settingsStore.setSubscriptionSetupDone()
                            }
                            MainScreen(
                                settingsStore = settingsStore,
                                themeMode = themeMode,
                                onThemeChange = { mode ->
                                    scope.launch { settingsStore.saveThemeMode(mode) }
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

// ═══ Навигация ═══

private data class NavItem(
    val id: Int,
    val label: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
)

private val navItems = listOf(
    NavItem(0, "Главная", Icons.Filled.VpnKey, Icons.Outlined.VpnKey),
    NavItem(3, "Активность", Icons.Filled.Terminal, Icons.Outlined.Terminal),
    NavItem(4, "Настройки", Icons.Filled.Settings, Icons.Outlined.Settings),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    settingsStore: SettingsStore,
    themeMode: String = "system",
    onThemeChange: (String) -> Unit = {},
) {
    val unreadErrors by TunnelManager.unreadErrorCount.collectAsStateWithLifecycle()
    val tunnelRunning by TunnelManager.running.collectAsStateWithLifecycle()
    val view = LocalView.current
    val context = LocalContext.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val activeProfile by settingsStore.activeProfile.collectAsStateWithLifecycle(initialValue = 0)
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    var dragTargetIndex by remember { mutableIntStateOf(-1) }
    var dragProgress by remember { mutableFloatStateOf(0f) }
    val updateCheckIntervalHours by settingsStore.updateCheckIntervalHours.collectAsStateWithLifecycle(
        initialValue = DEFAULT_UPDATE_CHECK_INTERVAL_HOURS
    )
    var pendingRelease by remember { mutableStateOf<AppReleaseInfo?>(null) }
    val currentVersion = remember { "v${BuildConfig.VERSION_NAME.removePrefix("v")}" }
    val safeBottomInset = with(density) { WindowInsets.safeDrawing.getBottom(density).toDp() }
    val imeBottomInset = with(density) { WindowInsets.ime.getBottom(density).toDp() }
    val isImeVisible = imeBottomInset > 0.dp
    val navOverlayReserve = safeBottomInset + 96.dp
    val contentBottomPadding = if (isImeVisible) imeBottomInset + 16.dp else navOverlayReserve

    val activeNavItems = navItems


    LaunchedEffect(selectedTab) {
        if (selectedTab == 1) selectedTab = 0
        if (selectedTab == 3) TunnelManager.clearUnreadErrors()
    }

    LaunchedEffect(updateCheckIntervalHours) {
        if (updateCheckIntervalHours == UPDATE_CHECK_NEVER) return@LaunchedEffect

        val intervalMillis = updateIntervalHoursToMillis(updateCheckIntervalHours)
            ?: updateIntervalHoursToMillis(DEFAULT_UPDATE_CHECK_INTERVAL_HOURS)
            ?: 12L * 60L * 60L * 1000L

        suspend fun runUpdateCheck(reason: String) {
            val checkedAt = System.currentTimeMillis()
            val release = fetchLatestReleaseInfo(currentVersion)
            settingsStore.saveUpdateState(
                lastCheckAt = checkedAt,
                latestVersion = release?.versionTag ?: "",
                error = if (release == null) "Не удалось проверить" else ""
            )

            if (release == null) {
                Log.w("WDTT", "[WARN] Update check: no release info, local=$currentVersion reason=$reason")
                return
            }

            val hasUpdate = isNewerVersion(currentVersion, release.versionTag)
            val postponeVer = settingsStore.updatePostponeVersion.first()
            val postponeUntil = settingsStore.updatePostponeUntil.first()
            val isPostponed = postponeVer == release.versionTag && checkedAt < postponeUntil
            Log.i(
                "WDTT",
                "Update check: local=$currentVersion remote=${release.versionTag} newer=$hasUpdate postponed=$isPostponed reason=$reason"
            )

            if (hasUpdate && !isPostponed) {
                settingsStore.saveUpdateDialogShown(release.versionTag, checkedAt)
                pendingRelease = release
            }
        }

        runUpdateCheck("startup")

        while (isActive) {
            val now = System.currentTimeMillis()
            val lastCheck = settingsStore.updateLastCheckAt.first()
            val nextCheckAt = lastCheck + intervalMillis
            val waitMs = (nextCheckAt - now).coerceAtLeast(intervalMillis)
            delay(waitMs)
            if (isActive) {
                runUpdateCheck("periodic")
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AppBackdrop(modifier = Modifier.matchParentSize())

        Scaffold(
            contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top),
            containerColor = Color.Transparent,
        ) { padding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .consumeWindowInsets(padding)
                    .pointerInput(selectedTab) {
                        var totalDrag = 0f
                        detectHorizontalDragGestures(
                            onDragStart = {
                                totalDrag = 0f
                                dragTargetIndex = -1
                                dragProgress = 0f
                            },
                            onDragCancel = {
                                dragTargetIndex = -1
                                dragProgress = 0f
                            },
                            onDragEnd = {
                                if (dragTargetIndex in activeNavItems.indices && dragProgress >= 0.5f) {
                                    selectedTab = activeNavItems[dragTargetIndex].id
                                    if (selectedTab == 3) TunnelManager.clearUnreadErrors()
                                }
                                dragTargetIndex = -1
                                dragProgress = 0f
                            }
                        ) { change, dragAmount ->
                            change.consume()
                            totalDrag += dragAmount
                            if (abs(totalDrag) < 12f) {
                                dragTargetIndex = -1
                                dragProgress = 0f
                                return@detectHorizontalDragGestures
                            }

                            val currentActiveIndex = activeNavItems.indexOfFirst { it.id == selectedTab }
                            val candidate = if (totalDrag < 0f) currentActiveIndex + 1 else currentActiveIndex - 1
                            if (candidate !in activeNavItems.indices) {
                                dragTargetIndex = -1
                                dragProgress = 0f
                                return@detectHorizontalDragGestures
                            }

                            dragTargetIndex = candidate
                            dragProgress = (abs(totalDrag) / 180f).coerceIn(0f, 1f)
                        }
                    }
            ) {
                AnimatedContent(
                    targetState = selectedTab,
                    transitionSpec = {
                        fadeIn(tween(300)) togetherWith fadeOut(tween(225))
                    },
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(bottom = contentBottomPadding),
                    label = "tab_content"
                ) { tab ->
                    AdaptiveContentHost {
                        when (tab) {
                            0 -> TunnelTab(onOpenSettings = { selectedTab = 4 })
                            3 -> LogsTab()
                            4 -> SettingsHubTab(
                                themeMode = themeMode,
                                onThemeChange = onThemeChange,
                                activeProfile = activeProfile,
                                onActiveProfileChange = { profile ->
                                    scope.launch { settingsStore.saveActiveProfile(profile) }
                                },
                                onUpdateFound = { release ->
                                    pendingRelease = release
                                },
                            )
                            else -> TunnelTab(onOpenSettings = { selectedTab = 4 })
                        }
                    }
                }

                if (!isImeVisible) {
                    ProxyNavigationBar(
                        navItems = activeNavItems,
                        selectedTab = selectedTab,
                        dragTargetIndex = dragTargetIndex,
                        dragProgress = dragProgress,
                        unreadErrors = unreadErrors,
                        tunnelRunning = tunnelRunning,
                        onTabSelected = { index ->
                            if (selectedTab != index) {
                                view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                selectedTab = index
                                if (index == 3) TunnelManager.clearUnreadErrors()
                            }
                            dragTargetIndex = -1
                            dragProgress = 0f
                        },
                        modifier = Modifier.align(Alignment.BottomCenter)
                    )
                }
            }
        }
    }

    pendingRelease?.let { release ->
        AppUpdateDialog(
            release = release,
            onPostpone = {
                pendingRelease = null
                Toast.makeText(context, "Обновление отложено на 24 часа.", Toast.LENGTH_SHORT).show()
                scope.launch {
                    val now = System.currentTimeMillis()
                    settingsStore.saveUpdatePostpone(
                        version = release.versionTag,
                        until = now + 24L * 60L * 60L * 1000L
                    )
                    settingsStore.saveUpdateDialogAction(
                        version = release.versionTag,
                        action = UPDATE_DIALOG_ACTION_POSTPONED,
                        actedAt = now
                    )
                }
            },
            onUpdate = {
                pendingRelease = null
                scope.launch {
                    settingsStore.saveUpdateDialogAction(
                        version = release.versionTag,
                        action = UPDATE_DIALOG_ACTION_UPDATE,
                        actedAt = System.currentTimeMillis()
                    )
                }
            }
        )
    }
}

@Composable
private fun ProxyNavigationBar(
    navItems: List<NavItem>,
    selectedTab: Int,
    dragTargetIndex: Int,
    dragProgress: Float,
    unreadErrors: Int,
    tunnelRunning: Boolean,
    onTabSelected: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val selectedColor = SkyflowColors.AccentLight
    val unselectedColor = SkyflowColors.TextMuted
    val shellColor = SkyflowColors.NavBg
    val shellBorder = SkyflowColors.NavBorder
    val indicatorColor = SkyflowColors.Accent.copy(alpha = 0.22f)
    val selectedVisualIndex = remember(selectedTab, navItems) {
        navItems.indexOfFirst { it.id == selectedTab }.coerceAtLeast(0)
    }
    val indicatorIndex = remember { Animatable(selectedVisualIndex.toFloat()) }
    val dragVisualIndex = indicatorIndex.value

    LaunchedEffect(selectedVisualIndex) {
        if (dragTargetIndex !in navItems.indices) {
            indicatorIndex.animateTo(
                targetValue = selectedVisualIndex.toFloat(),
                animationSpec = tween(
                    durationMillis = 720,
                    easing = CubicBezierEasing(0.2f, 0.9f, 0.24f, 1f)
                )
            )
        }
    }

    LaunchedEffect(selectedVisualIndex, dragTargetIndex, dragProgress) {
        if (dragTargetIndex in navItems.indices) {
            val target = selectedVisualIndex.toFloat() + (dragTargetIndex - selectedVisualIndex) * dragProgress
            indicatorIndex.snapTo(target)
        }
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))
            .padding(horizontal = 22.dp, vertical = 12.dp),
        contentAlignment = Alignment.BottomCenter,
    ) {
        val trackPadding = 8.dp
        val barMaxWidth = ResponsiveLayout.MaxNavBarWidth
        val barWidth = minOf(maxWidth, barMaxWidth)
        val itemWidth = (barWidth - trackPadding * 2) / navItems.size
        val indicatorOffset = trackPadding + itemWidth * dragVisualIndex

        Surface(
            shape = SkyflowShapes.NavBar,
            color = shellColor,
            border = BorderStroke(1.dp, shellBorder),
            tonalElevation = 0.dp,
            shadowElevation = 0.dp,
            modifier = Modifier.width(barWidth)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(68.dp)
            ) {
                Surface(
                    shape = SkyflowShapes.NavIndicator,
                    color = indicatorColor,
                    border = BorderStroke(0.5.dp, SkyflowColors.Accent.copy(alpha = 0.15f)),
                    modifier = Modifier
                        .offset(x = indicatorOffset)
                        .padding(vertical = 5.dp)
                        .width(itemWidth)
                        .fillMaxHeight()
                ) {}

                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = trackPadding, vertical = 6.dp)
                ) {
                    navItems.forEachIndexed { index, item ->
                        val emphasis = (1f - abs(index - dragVisualIndex)).coerceIn(0f, 1f)
                        val iconColor = lerp(unselectedColor, selectedColor, emphasis)

                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .defaultMinSize(minHeight = 48.dp)
                                .clip(SkyflowShapes.NavIndicator)
                                .clickable { onTabSelected(item.id) },
                            verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Box(contentAlignment = Alignment.TopEnd) {
                                Icon(
                                    imageVector = if (emphasis > 0.55f) item.selectedIcon else item.unselectedIcon,
                                    contentDescription = item.label,
                                    modifier = Modifier.size(22.dp),
                                    tint = iconColor
                                )
                                if (item.id == 3 && unreadErrors > 0) {
                                    Badge(
                                        containerColor = if (tunnelRunning) SkyflowColors.Accent else SkyflowColors.WarnColor,
                                        contentColor = SkyflowColors.OnAccent,
                                        modifier = Modifier.offset(x = 12.dp, y = (-8).dp)
                                    ) {
                                        Text("$unreadErrors")
                                    }
                                }
                            }
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = item.label,
                                fontSize = 10.sp,
                                fontWeight = if (emphasis > 0.55f) FontWeight.SemiBold else FontWeight.Medium,
                                color = iconColor,
                                maxLines = 1
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun openReleaseUrl(context: Context, url: String) {
    try {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addCategory(Intent.CATEGORY_BROWSABLE)
        }
        context.startActivity(intent)
    } catch (_: Exception) {
        Toast.makeText(context, "Не удалось открыть ссылку", Toast.LENGTH_SHORT).show()
    }
}

private fun android16OrbShape(points: Int, innerRatio: Float): Shape = GenericShape { size, _ ->
    val centerX = size.width / 2f
    val centerY = size.height / 2f
    val outerRadius = min(size.width, size.height) / 2f
    val innerRadius = outerRadius * innerRatio

    for (i in 0 until points * 2) {
        val angle = (-PI / 2.0) + (i * PI / points)
        val radius = if (i % 2 == 0) outerRadius else innerRadius
        val x = centerX + (radius * cos(angle)).toFloat()
        val y = centerY + (radius * sin(angle)).toFloat()
        if (i == 0) moveTo(x, y) else lineTo(x, y)
    }
    close()
}

private val Android16OrbLarge: Shape = android16OrbShape(points = 18, innerRatio = 0.90f)
private val Android16OrbMedium: Shape = android16OrbShape(points = 20, innerRatio = 0.92f)
private val Android16OrbSmall: Shape = android16OrbShape(points = 16, innerRatio = 0.88f)

@Composable
private fun AppBackdrop(modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val isDark = colors.background.luminance() < 0.22f
    val baseBrush = remember(isDark) {
        Brush.verticalGradient(
            colors = if (isDark) {
                listOf(
                    SkyflowColors.Background,
                    Color(0xFF0A0A16),
                    SkyflowColors.Background
                )
            } else {
                listOf(
                    lerp(colors.background, colors.surface, 0.78f),
                    colors.background,
                    lerp(colors.surfaceVariant, colors.background, 0.30f)
                )
            }
        )
    }
    val topOrbGlow = Brush.radialGradient(
        colors = listOf(
            SkyflowColors.Accent.copy(alpha = if (isDark) 0.14f else 0.10f),
            Color.Transparent
        )
    )
    val leftGlow = Brush.radialGradient(
        colors = listOf(
            SkyflowColors.AccentLight.copy(alpha = if (isDark) 0.10f else 0.08f),
            Color.Transparent
        )
    )
    val bottomGlow = Brush.radialGradient(
        colors = listOf(
            SkyflowColors.Connected.copy(alpha = if (isDark) 0.08f else 0.06f),
            Color.Transparent
        )
    )
    val lightOrbOutline = colors.outlineVariant.copy(alpha = 0.26f)

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(baseBrush)
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.TopStart)
                .offset(x = (-86).dp, y = (-126).dp)
                .size(280.dp)
                .clip(Android16OrbLarge)
                .background(topOrbGlow)
                .then(
                    if (isDark) Modifier else Modifier.border(1.dp, lightOrbOutline, Android16OrbLarge)
                )
        )
        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .offset(x = (-44).dp, y = 28.dp)
                .size(160.dp)
                .clip(Android16OrbSmall)
                .background(leftGlow)
                .then(
                    if (isDark) Modifier else Modifier.border(1.dp, lightOrbOutline.copy(alpha = 0.22f), Android16OrbSmall)
                )
        )
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .offset(x = 62.dp, y = (-208).dp)
                .size(220.dp)
                .clip(Android16OrbMedium)
                .background(bottomGlow)
                .then(
                    if (isDark) Modifier else Modifier.border(1.dp, lightOrbOutline.copy(alpha = 0.20f), Android16OrbMedium)
                )
        )
    }
}
