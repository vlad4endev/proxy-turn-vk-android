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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun OnboardingScreen(onFinish: () -> Unit) {
    var currentPage by remember { mutableStateOf(0) }
    val totalPages = 4

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0D0B1A))
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
        // Progress dots
        Row(
            horizontalArrangement = Arrangement.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 48.dp)
                .align(Alignment.TopCenter)
        ) {
            repeat(totalPages) { index ->
                val width by animateDpAsState(
                    targetValue = if (index == currentPage) 20.dp else 8.dp,
                    animationSpec = tween(300),
                    label = "dot_width_$index"
                )
                val color = when {
                    index < currentPage -> Color(0xFF4ADE80)
                    index == currentPage -> Color(0xFF6366F1)
                    else -> Color(0xFF1E1C3A)
                }
                Box(
                    Modifier
                        .width(width)
                        .height(3.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(color)
                )
                if (index < totalPages - 1) Spacer(Modifier.width(4.dp))
            }
        }

        // Page content
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.Center)
                .padding(top = 80.dp, bottom = 140.dp)
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
                label = "onboarding_page"
            ) { page ->
                when (page) {
                    0 -> OnboardingPage0()
                    1 -> OnboardingPage1()
                    2 -> OnboardingPage2()
                    3 -> OnboardingPage3()
                    else -> OnboardingPage0()
                }
            }
        }

        // Navigation buttons
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
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
                shape = RoundedCornerShape(24.dp),
                color = Color(0xFF6366F1)
            ) {
                Text(
                    text = when (currentPage) {
                        0 -> "Начать →"
                        totalPages - 1 -> "Готово — начать!"
                        else -> "Понятно →"
                    },
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(vertical = 14.dp)
                )
            }

            if (currentPage == 0) {
                Text(
                    "Уже умею · пропустить",
                    fontSize = 11.sp,
                    color = Color(0xFF4B5563),
                    modifier = Modifier.clickable { onFinish() }
                )
            }
        }
    }
}

@Composable
private fun OnboardingPage0() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(Modifier.size(120.dp), contentAlignment = Alignment.Center) {
            Box(
                Modifier
                    .size(120.dp)
                    .clip(CircleShape)
                    .border(1.dp, Color(0xFF6366F1).copy(alpha = 0.15f), CircleShape)
            )
            Box(
                Modifier
                    .size(90.dp)
                    .clip(CircleShape)
                    .border(1.dp, Color(0xFF6366F1).copy(alpha = 0.25f), CircleShape)
            )
            Box(
                Modifier
                    .size(60.dp)
                    .clip(CircleShape)
                    .border(1.dp, Color(0xFF6366F1).copy(alpha = 0.4f), CircleShape)
            )
            Box(
                Modifier
                    .size(52.dp)
                    .clip(RoundedCornerShape(13.dp))
                    .background(Color(0xFF0D0B1A))
                    .border(1.5.dp, Color(0xFF6366F1).copy(alpha = 0.5f), RoundedCornerShape(13.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text("S", fontSize = 28.sp, fontWeight = FontWeight.Black, color = Color(0xFF60A5FA))
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "SKYFLOW",
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            color = Color(0xFFF4F4F5),
            letterSpacing = 2.sp
        )
        Text(
            "M",
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            color = Color(0xFF6366F1),
            letterSpacing = 3.sp
        )
        Spacer(Modifier.height(32.dp))
        Text(
            "Добро пожаловать\nв SKYFLOW M",
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            color = Color(0xFFF4F4F5),
            textAlign = TextAlign.Center,
            lineHeight = 32.sp
        )
        Spacer(Modifier.height(12.dp))
        Text(
            "Мгновенный доступ к интернету без ограничений.\nНастройка займёт меньше минуты.",
            fontSize = 14.sp,
            color = Color(0xFF9CA3AF),
            textAlign = TextAlign.Center,
            lineHeight = 20.sp
        )
    }
}

@Composable
private fun OnboardingPage1() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = Color(0xFF13112A),
                border = BorderStroke(0.5.dp, Color(0xFF2A2850))
            ) {
                Column(
                    Modifier
                        .padding(10.dp)
                        .width(140.dp)
                ) {
                    Text("ВКонтакте → Звонки", fontSize = 8.sp, color = Color(0xFF6B7280))
                    Spacer(Modifier.height(6.dp))
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = Color(0xFF0D0B1A),
                        border = BorderStroke(0.5.dp, Color(0xFF2A2850))
                    ) {
                        Row(
                            Modifier
                                .padding(6.dp, 5.dp)
                                .fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("📞 Создать звонок", fontSize = 9.sp, color = Color(0xFFD4D4D8))
                            Text("→", fontSize = 9.sp, color = Color(0xFF6366F1))
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Скопировать ссылку ✓",
                        fontSize = 8.sp,
                        color = Color(0xFF4ADE80),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            Text("↓", fontSize = 18.sp, color = Color(0xFF4B5563))

            Surface(
                shape = RoundedCornerShape(10.dp),
                color = Color(0xFF13112A),
                border = BorderStroke(0.5.dp, Color(0xFF2A2850))
            ) {
                Column(
                    Modifier
                        .padding(10.dp)
                        .width(140.dp)
                ) {
                    Text(
                        "КОД ДОСТУПА",
                        fontSize = 7.sp,
                        color = Color(0xFF6366F1),
                        letterSpacing = 0.6.sp
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        "vk.com/call/join/xK9...",
                        fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace,
                        color = Color(0xFFA5B4FC)
                    )
                    Spacer(Modifier.height(5.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = Color(0x263B82F6),
                            border = BorderStroke(0.5.dp, Color(0x4D3B82F6))
                        ) {
                            Text(
                                "VK",
                                fontSize = 8.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF60A5FA),
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                        Text("● Активна", fontSize = 8.sp, color = Color(0xFF4ADE80))
                    }
                }
            }
        }

        Spacer(Modifier.height(28.dp))
        Text(
            "Создай звонок в VK\nи вставь ссылку",
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            color = Color(0xFFF4F4F5),
            textAlign = TextAlign.Center,
            lineHeight = 32.sp
        )
        Spacer(Modifier.height(12.dp))
        Text(
            "Открой VK → Звонки → Создать новый звонок.\nСкопируй ссылку и вставь в поле приложения.",
            fontSize = 14.sp,
            color = Color(0xFF9CA3AF),
            textAlign = TextAlign.Center,
            lineHeight = 20.sp
        )
    }
}

@Composable
private fun OnboardingPage2() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(Modifier.size(100.dp), contentAlignment = Alignment.Center) {
            Box(
                Modifier
                    .size(100.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF4ADE80).copy(alpha = 0.05f))
            )
            Box(
                Modifier
                    .size(80.dp)
                    .clip(CircleShape)
                    .border(1.dp, Color(0xFF4ADE80).copy(alpha = 0.15f), CircleShape)
            )
            Box(
                Modifier
                    .size(64.dp)
                    .clip(CircleShape)
                    .border(1.5.dp, Color(0xFF4ADE80), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.PowerSettingsNew,
                    contentDescription = null,
                    tint = Color(0xFF4ADE80),
                    modifier = Modifier.size(28.dp)
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(
            "Подключено ✓",
            fontSize = 11.sp,
            color = Color(0xFF4ADE80),
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("YouTube", "Instagram", "Telegram").forEach { name ->
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = Color(0xFF13112A),
                    border = BorderStroke(0.5.dp, Color(0xFF166534))
                ) {
                    Column(
                        Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(name, fontSize = 7.sp, color = Color(0xFF6B7280))
                        Text("ОК", fontSize = 8.sp, color = Color(0xFF4ADE80), fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
        Spacer(Modifier.height(28.dp))
        Text(
            "Нажми кнопку —\nи всё готово",
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            color = Color(0xFFF4F4F5),
            textAlign = TextAlign.Center,
            lineHeight = 32.sp
        )
        Spacer(Modifier.height(12.dp))
        Text(
            "Большая кнопка в центре запускает защиту.\nЗелёный — соединение активно, все сайты открыты.",
            fontSize = 14.sp,
            color = Color(0xFF9CA3AF),
            textAlign = TextAlign.Center,
            lineHeight = 20.sp
        )
    }
}

@Composable
private fun OnboardingPage3() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = Color(0xFF13112A),
            border = BorderStroke(0.5.dp, Color(0xFF1E1C3A))
        ) {
            Column(
                Modifier
                    .padding(16.dp)
                    .width(160.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("🔒", fontSize = 24.sp)
                Spacer(Modifier.height(6.dp))
                Text(
                    "SKYFLOW M хочет\nсоздать VPN",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFFD4D4D8),
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "Приложение будет\nперехватывать трафик",
                    fontSize = 8.sp,
                    color = Color(0xFF6B7280),
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(10.dp))
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = Color(0xFF6366F1),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        "✓ Разрешить",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text("Отмена", fontSize = 8.sp, color = Color(0xFF4B5563))
            }
        }
        Spacer(Modifier.height(8.dp))
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = Color(0x20F59E0B),
            border = BorderStroke(0.5.dp, Color(0x40F59E0B))
        ) {
            Text(
                "⚠ Нажми «Разрешить» — это нужно\nдля работы защиты",
                fontSize = 8.sp,
                color = Color(0xFFF59E0B),
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
            )
        }
        Spacer(Modifier.height(28.dp))
        Text(
            "Разреши создание\nзащищённого соединения",
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            color = Color(0xFFF4F4F5),
            textAlign = TextAlign.Center,
            lineHeight = 32.sp
        )
        Spacer(Modifier.height(12.dp))
        Text(
            "Android запросит разрешение один раз.\nЭто стандартная процедура — нажми «ОК».",
            fontSize = 14.sp,
            color = Color(0xFF9CA3AF),
            textAlign = TextAlign.Center,
            lineHeight = 20.sp
        )
    }
}
