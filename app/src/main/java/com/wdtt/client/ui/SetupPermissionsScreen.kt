package com.wdtt.client.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun SetupPermissionsScreen(onFinish: () -> Unit) {
    val context = LocalContext.current
    val steps = remember(context) { PermissionSetupHelper.buildSteps(context) }
    var currentStepIndex by remember { mutableIntStateOf(0) }
    val metrics = rememberScreenMetrics()
    val scrollState = rememberScrollState()

    fun goNext() {
        if (currentStepIndex < steps.lastIndex) {
            currentStepIndex++
        } else {
            onFinish()
        }
    }

    val runtimePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { goNext() }

    val settingsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { goNext() }

    LaunchedEffect(steps) {
        if (steps.isEmpty()) onFinish()
    }

    if (steps.isEmpty()) return

    val currentStep = steps[currentStepIndex]

    fun requestCurrentPermission() {
        when (val request = PermissionSetupHelper.createRequest(currentStep, context)) {
            is PermissionRequest.Runtime -> runtimePermissionLauncher.launch(request.permission)
            is PermissionRequest.Settings -> {
                try {
                    settingsLauncher.launch(request.intent)
                } catch (_: Exception) {
                    goNext()
                }
            }
            null -> goNext()
        }
    }

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
                repeat(steps.size) { index ->
                    val width by animateDpAsState(
                        targetValue = if (index == currentStepIndex) 32.dp else 10.dp,
                        animationSpec = tween(300),
                        label = "perm_dot_$index"
                    )
                    val color = when {
                        index < currentStepIndex -> SkyflowColors.Connected
                        index == currentStepIndex -> SkyflowColors.AccentLight
                        else -> SkyflowColors.Border
                    }
                    Box(
                        Modifier
                            .width(width)
                            .height(5.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(color)
                    )
                    if (index < steps.size - 1) Spacer(Modifier.width(6.dp))
                }
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(scrollState)
            ) {
                AnimatedContent(
                    targetState = currentStep,
                    transitionSpec = {
                        if (targetState.ordinal > initialState.ordinal) {
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
                    label = "permission_step"
                ) { step ->
                    PermissionStepPage(PermissionSetupHelper.stepContent(step))
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
                        .clickable { requestCurrentPermission() },
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
                            text = if (currentStepIndex == steps.lastIndex) "Разрешить и начать" else "Разрешить",
                            fontSize = 17.sp,
                            fontFamily = interFontFamily,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White,
                            textAlign = TextAlign.Center,
                        )
                    }
                }

                Text(
                    "Пропустить",
                    fontSize = 14.sp,
                    fontFamily = interFontFamily,
                    color = SkyflowColors.TextMuted,
                    modifier = Modifier
                        .clickable { goNext() }
                        .padding(vertical = 4.dp)
                )
            }
        }
    }
}

@Composable
private fun PermissionStepPage(content: PermissionStepContent) {
    val metrics = rememberScreenMetrics()
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        SetupPermissionCard(
            emoji = content.emoji,
            title = content.dialogTitle,
            body = content.dialogBody,
            allowLabel = content.allowLabel,
            hint = content.hint,
        )

        Spacer(Modifier.height(if (metrics.isCompactHeight) 20.dp else 32.dp))
        OnboardingTitle(content.pageTitle)
        Spacer(Modifier.height(if (metrics.isCompactHeight) 10.dp else 16.dp))
        OnboardingBody(content.pageBody)
    }
}
