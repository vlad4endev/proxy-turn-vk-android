package com.wdtt.client.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wdtt.client.ui.theme.ScreenMetrics

@Composable
fun OnboardingScreen(onFinish: () -> Unit) {
    var currentPage by remember { mutableStateOf(0) }
    val totalPages = 4
    val metrics = rememberScreenMetrics()
    val scrollState = rememberScrollState()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        SkyflowColors.Background,
                        Color(0xFF0A0A18),
                        SkyflowColors.Background,
                    )
                )
            )
            .pointerInput(currentPage) {
                detectHorizontalDragGestures { _, dragAmount ->
                    if (dragAmount < -50 && currentPage < totalPages - 1) {
                        currentPage++
                    } else if (dragAmount > 50 && currentPage > 0) {
                        currentPage--
                    }
                }
            }
    ) {
        AccentGlow(
            modifier = Modifier
                .size(metrics.onboardingGlowSize)
                .align(Alignment.TopCenter)
                .padding(top = if (metrics.isCompactHeight) 16.dp else 40.dp),
            intensity = 0.55f,
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
        ) {
            Row(
                horizontalArrangement = Arrangement.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .adaptiveContentWidth()
                    .align(Alignment.CenterHorizontally)
                    .padding(top = if (metrics.isCompactHeight) 12.dp else 24.dp)
            ) {
                repeat(totalPages) { index ->
                    val width by animateDpAsState(
                        targetValue = if (index == currentPage) 32.dp else 10.dp,
                        animationSpec = tween(300),
                        label = "dot_width_$index"
                    )
                    val color = when {
                        index < currentPage -> SkyflowColors.Connected
                        index == currentPage -> SkyflowColors.AccentLight
                        else -> SkyflowColors.Border
                    }
                    Box(
                        Modifier
                            .width(width)
                            .height(5.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(color)
                    )
                    if (index < totalPages - 1) Spacer(Modifier.width(6.dp))
                }
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(scrollState)
            ) {
                AnimatedContent(
                    targetState = currentPage,
                    transitionSpec = {
                        if (targetState > initialState) {
                            slideInHorizontally { it } + fadeIn() togetherWith
                                slideOutHorizontally { -it } + fadeOut()
                        } else {
                            slideInHorizontally { -it } + fadeIn() togetherWith
                                slideOutHorizontally { it } + fadeOut()
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .adaptiveContentWidth()
                        .align(Alignment.TopCenter)
                        .padding(
                            horizontal = metrics.contentHorizontalPadding,
                            vertical = if (metrics.isCompactHeight) 12.dp else 24.dp,
                        ),
                    label = "onboarding_page"
                ) { page ->
                    when (page) {
                        0 -> OnboardingPage0(metrics)
                        1 -> OnboardingPage1()
                        2 -> OnboardingPage2(metrics)
                        3 -> OnboardingPage3()
                        else -> OnboardingPage0(metrics)
                    }
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .adaptiveContentWidth()
                    .align(Alignment.CenterHorizontally)
                    .padding(horizontal = metrics.contentHorizontalPadding)
                    .padding(bottom = if (metrics.isCompactHeight) 16.dp else 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        if (currentPage < totalPages - 1) {
                            currentPage++
                        } else {
                            onFinish()
                        }
                    },
                shape = SkyflowShapes.Button,
                color = Color.Transparent,
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(SkyflowGradients.Accent, SkyflowShapes.Button)
                        .padding(vertical = 18.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = when (currentPage) {
                            0 -> "Начать"
                            totalPages - 1 -> "Готово — начать!"
                            else -> "Понятно"
                        },
                        fontSize = 17.sp,
                        fontFamily = interFontFamily,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White,
                        textAlign = TextAlign.Center,
                    )
                }
            }

            if (currentPage == 0) {
                Text(
                    "Уже умею · пропустить",
                    fontSize = 14.sp,
                    fontFamily = interFontFamily,
                    color = SkyflowColors.TextMuted,
                    modifier = Modifier
                        .clickable { onFinish() }
                        .padding(vertical = 4.dp)
                )
            }
            }
        }
    }
}

@Composable
internal fun OnboardingTitle(text: String) {
    Text(
        text = text,
        fontSize = 28.sp,
        fontFamily = interFontFamily,
        fontWeight = FontWeight.Bold,
        color = SkyflowColors.TextPrimary,
        textAlign = TextAlign.Center,
        lineHeight = 36.sp
    )
}

@Composable
internal fun OnboardingBody(text: String) {
    Text(
        text = text,
        fontSize = 16.sp,
        fontFamily = interFontFamily,
        fontWeight = FontWeight.Normal,
        color = SkyflowColors.TextSecondary,
        textAlign = TextAlign.Center,
        lineHeight = 24.sp
    )
}

@Composable
private fun OnboardingPage0(metrics: ScreenMetrics) {
    val artSize = metrics.onboardingArtSize
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(Modifier.size(artSize), contentAlignment = Alignment.Center) {
            AccentGlow(
                modifier = Modifier.size(artSize),
                intensity = 0.75f,
            )
            Box(
                Modifier
                    .size(artSize * 0.88f)
                    .clip(CircleShape)
                    .border(1.dp, SkyflowColors.Accent.copy(alpha = 0.12f), CircleShape)
            )
            Box(
                Modifier
                    .size(artSize * 0.67f)
                    .clip(CircleShape)
                    .border(1.dp, SkyflowColors.Accent.copy(alpha = 0.22f), CircleShape)
            )
            Box(
                Modifier
                    .size(artSize * 0.45f)
                    .clip(CircleShape)
                    .border(1.5.dp, SkyflowColors.Accent.copy(alpha = 0.35f), CircleShape)
            )
            Box(
                Modifier
                    .size(artSize * 0.38f)
                    .clip(SkyflowShapes.Logo)
                    .background(SkyflowColors.Surface)
                    .border(SkyflowBorders.Logo, SkyflowShapes.Logo),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "S",
                    fontSize = if (metrics.isCompactHeight) 26.sp else 32.sp,
                    fontFamily = interFontFamily,
                    fontWeight = FontWeight.Black,
                    color = SkyflowColors.VkStripe
                )
            }
        }

        Spacer(Modifier.height(if (metrics.isCompactHeight) 8.dp else 16.dp))
        Text(
            "SKYFLOW",
            fontSize = 22.sp,
            fontFamily = interFontFamily,
            fontWeight = FontWeight.Bold,
            color = SkyflowColors.TextPrimary,
            letterSpacing = 3.sp
        )
        Text(
            "M",
            fontSize = 15.sp,
            fontFamily = interFontFamily,
            fontWeight = FontWeight.Bold,
            color = SkyflowColors.AccentLight,
            letterSpacing = 4.sp
        )

        Spacer(Modifier.height(if (metrics.isCompactHeight) 20.dp else 36.dp))
        OnboardingTitle("Добро пожаловать\nв SKYFLOW M")
        Spacer(Modifier.height(if (metrics.isCompactHeight) 10.dp else 16.dp))
        OnboardingBody(
            "Мгновенный доступ к интернету без ограничений.\nНастройка займёт меньше минуты."
        )
    }
}

@Composable
private fun OnboardingPage1() {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(0.92f),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            GlassSurface(
                modifier = Modifier.fillMaxWidth(),
                shape = SkyflowShapes.Field,
                border = SkyflowBorders.Glass,
            ) {
                Column(Modifier.padding(horizontal = 18.dp, vertical = 16.dp)) {
                    Text(
                        "ВКонтакте → Звонки",
                        fontSize = 13.sp,
                        fontFamily = interFontFamily,
                        fontWeight = FontWeight.Medium,
                        color = SkyflowColors.TextMuted
                    )
                    Spacer(Modifier.height(10.dp))
                    Surface(
                        shape = SkyflowShapes.Chip,
                        color = SkyflowColors.Surface,
                        border = SkyflowBorders.Default
                    ) {
                        Row(
                            Modifier
                                .padding(horizontal = 14.dp, vertical = 12.dp)
                                .fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "📞  Создать звонок",
                                fontSize = 15.sp,
                                fontFamily = interFontFamily,
                                color = SkyflowColors.TextPrimary
                            )
                            Text(
                                "→",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                color = SkyflowColors.AccentLight
                            )
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "Скопировать ссылку ✓",
                        fontSize = 13.sp,
                        fontFamily = interFontFamily,
                        fontWeight = FontWeight.SemiBold,
                        color = SkyflowColors.Connected,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            Icon(
                Icons.Default.ArrowDownward,
                contentDescription = null,
                tint = SkyflowColors.TextMuted,
                modifier = Modifier.size(28.dp)
            )

            GlassSurface(
                modifier = Modifier.fillMaxWidth(),
                shape = SkyflowShapes.Field,
                border = SkyflowBorders.GlassAccent,
            ) {
                Column(Modifier.padding(horizontal = 18.dp, vertical = 16.dp)) {
                    Text(
                        "Ссылка подключения",
                        style = SkyflowTextStyles.labelUppercase,
                        fontSize = 12.sp,
                        color = SkyflowColors.AccentLight
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "vk.com/call/join/xK9...",
                        fontSize = 15.sp,
                        fontFamily = FontFamily.Monospace,
                        color = SkyflowColors.TextAccent
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Surface(
                            shape = SkyflowShapes.Chip,
                            color = SkyflowColors.VkStripe.copy(alpha = 0.15f),
                            border = BorderStroke(0.5.dp, SkyflowColors.VkStripe.copy(alpha = 0.35f))
                        ) {
                            Text(
                                "VK",
                                fontSize = 13.sp,
                                fontFamily = interFontFamily,
                                fontWeight = FontWeight.Bold,
                                color = SkyflowColors.VkStripe,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                            )
                        }
                        Text(
                            "● Активна",
                            fontSize = 13.sp,
                            fontFamily = interFontFamily,
                            fontWeight = FontWeight.Medium,
                            color = SkyflowColors.Connected
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(32.dp))
        OnboardingTitle("Создай звонок в VK\nи вставь ссылку")
        Spacer(Modifier.height(16.dp))
        OnboardingBody(
            "Открой VK → Звонки → Создать новый звонок.\nСкопируй ссылку и вставь в поле приложения."
        )
    }
}

@Composable
private fun OnboardingPage2(metrics: ScreenMetrics) {
    val artSize = if (metrics.isCompactHeight) 112.dp else 148.dp
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(Modifier.size(artSize), contentAlignment = Alignment.Center) {
            AccentGlow(
                modifier = Modifier.size(artSize),
                color = SkyflowColors.Connected.copy(alpha = 0.18f),
                intensity = 1f,
            )
            Box(
                Modifier
                    .size(artSize * 0.86f)
                    .clip(CircleShape)
                    .background(SkyflowColors.Connected.copy(alpha = 0.06f))
            )
            Box(
                Modifier
                    .size(artSize * 0.70f)
                    .clip(CircleShape)
                    .border(1.5.dp, SkyflowColors.Connected.copy(alpha = 0.2f), CircleShape)
            )
            Box(
                Modifier
                    .size(artSize * 0.57f)
                    .clip(CircleShape)
                    .border(2.dp, SkyflowColors.Connected, CircleShape)
                    .background(SkyflowColors.Surface.copy(alpha = 0.6f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.PowerSettingsNew,
                    contentDescription = null,
                    tint = SkyflowColors.Connected,
                    modifier = Modifier.size(if (metrics.isCompactHeight) 32.dp else 40.dp)
                )
            }
        }

        Spacer(Modifier.height(if (metrics.isCompactHeight) 8.dp else 16.dp))
        Text(
            "Подключено ✓",
            fontSize = 15.sp,
            fontFamily = interFontFamily,
            color = SkyflowColors.Connected,
            fontWeight = FontWeight.SemiBold
        )

        Spacer(Modifier.height(if (metrics.isCompactHeight) 8.dp else 16.dp))
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            listOf("YouTube", "Instagram", "Telegram").forEach { name ->
                GlassSurface(
                    shape = SkyflowShapes.Chip,
                    border = SkyflowBorders.Success,
                ) {
                    Column(
                        Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            name,
                            fontSize = 12.sp,
                            fontFamily = interFontFamily,
                            color = SkyflowColors.TextMuted
                        )
                        Text(
                            "ОК",
                            fontSize = 14.sp,
                            fontFamily = interFontFamily,
                            color = SkyflowColors.Connected,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(32.dp))
        OnboardingTitle("Нажми кнопку —\nи всё готово")
        Spacer(Modifier.height(16.dp))
        OnboardingBody(
            "Большая кнопка в центре запускает защиту.\nЗелёный — соединение активно, все сайты открыты."
        )
    }
}

@Composable
internal fun SetupPermissionCard(
    emoji: String,
    title: String,
    body: String,
    allowLabel: String,
    hint: String,
) {
    GlassSurface(
        modifier = Modifier.fillMaxWidth(0.88f),
        shape = SkyflowShapes.Card,
        border = SkyflowBorders.Glass,
        elevated = true,
    ) {
        Column(
            Modifier
                .padding(horizontal = 24.dp, vertical = 22.dp)
                .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(emoji, fontSize = 36.sp)
            Spacer(Modifier.height(12.dp))
            Text(
                title,
                fontSize = 16.sp,
                fontFamily = interFontFamily,
                fontWeight = FontWeight.SemiBold,
                color = SkyflowColors.TextPrimary,
                textAlign = TextAlign.Center,
                lineHeight = 22.sp
            )
            Spacer(Modifier.height(8.dp))
            Text(
                body,
                fontSize = 14.sp,
                fontFamily = interFontFamily,
                color = SkyflowColors.TextSecondary,
                textAlign = TextAlign.Center,
                lineHeight = 20.sp
            )
            Spacer(Modifier.height(18.dp))
            Surface(
                shape = SkyflowShapes.Chip,
                color = SkyflowColors.Accent,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    allowLabel,
                    fontSize = 15.sp,
                    fontFamily = interFontFamily,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(vertical = 14.dp)
                )
            }
            Spacer(Modifier.height(10.dp))
            Text(
                "Отмена",
                fontSize = 14.sp,
                fontFamily = interFontFamily,
                color = SkyflowColors.TextMuted
            )
        }
    }

    Spacer(Modifier.height(14.dp))
    Surface(
        shape = SkyflowShapes.Chip,
        color = SkyflowColors.WarnColor.copy(alpha = 0.12f),
        border = BorderStroke(0.5.dp, SkyflowColors.WarnColor.copy(alpha = 0.35f))
    ) {
        Text(
            hint,
            fontSize = 13.sp,
            fontFamily = interFontFamily,
            fontWeight = FontWeight.Medium,
            color = SkyflowColors.WarnColor,
            textAlign = TextAlign.Center,
            lineHeight = 18.sp,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
        )
    }
}

@Composable
private fun OnboardingPage3() {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        SetupPermissionCard(
            emoji = "🔒",
            title = "Разрешите SKYFLOW\nзащищать соединение",
            body = "Это нужно, чтобы включить\nзащищённое подключение",
            allowLabel = "Разрешить",
            hint = "Нажмите «Разрешить» — без этого\nзащита не заработает",
        )

        Spacer(Modifier.height(32.dp))
        OnboardingTitle("Разреши создание\nзащищённого соединения")
        Spacer(Modifier.height(16.dp))
        OnboardingBody(
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                "Android запросит разрешение один раз.\nЭто стандартная процедура — нажми «ОК»."
            } else {
                "Android покажет системный запрос.\nЭто стандартная процедура — нажми «ОК»."
            }
        )
    }
}
