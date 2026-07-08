package com.wdtt.client.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wdtt.client.LinkResult
import com.wdtt.client.SettingsStore
import com.wdtt.client.SubscriptionLinker
import kotlinx.coroutines.launch

/**
 * Вход в уже существующую подписку по subId или Telegram ID.
 * Резолв и сохранение — в [SubscriptionLinker]; серверы грузятся существующим
 * SubscriptionParser, и приложение продолжает работать как с обычной подпиской.
 */
@Composable
fun LinkExistingSubscriptionScreen(
    store: SettingsStore,
    onDone: () -> Unit,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var type by remember { mutableStateOf(SubscriptionLinker.TYPE_SUBID) }
    var value by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val isSubId = type == SubscriptionLinker.TYPE_SUBID

    fun submit() {
        if (value.isBlank() || isLoading) return
        scope.launch {
            isLoading = true
            error = null
            when (val r = SubscriptionLinker.linkByIdentifier(store, type, value)) {
                is LinkResult.Success -> onDone()
                LinkResult.NotFound -> error = "Подписка не найдена. Проверьте значение."
                is LinkResult.Error -> error = r.message
            }
            isLoading = false
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(SkyflowColors.Background)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(horizontal = 24.dp, vertical = 18.dp),
        horizontalAlignment = Alignment.Start,
    ) {
        TextButton(onClick = onBack) {
            Text("‹ Назад", color = SkyflowColors.TextSecondary, fontSize = 14.sp)
        }

        Spacer(Modifier.height(8.dp))
        Text(
            "Войти в свою подписку",
            fontFamily = interFontFamily,
            fontWeight = FontWeight.Bold,
            fontSize = 22.sp,
            color = SkyflowColors.TextPrimary,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "Введите subId из ссылки подписки или ваш Telegram ID — серверы подтянутся автоматически.",
            fontFamily = interFontFamily,
            fontSize = 14.sp,
            color = SkyflowColors.TextSecondary,
            lineHeight = 20.sp,
        )

        Spacer(Modifier.height(20.dp))

        // ── Segmented type toggle ─────────────────────────────────────────────
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = SkyflowColors.GlassSurface,
            border = SkyflowBorders.Glass,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(modifier = Modifier.padding(4.dp)) {
                SegItem(
                    label = "subId",
                    selected = isSubId,
                    modifier = Modifier.weight(1f),
                ) { type = SubscriptionLinker.TYPE_SUBID; error = null }
                SegItem(
                    label = "Telegram ID",
                    selected = !isSubId,
                    modifier = Modifier.weight(1f),
                ) { type = SubscriptionLinker.TYPE_TELEGRAM; error = null }
            }
        }

        Spacer(Modifier.height(14.dp))

        OutlinedTextField(
            value = value,
            onValueChange = { value = it; error = null },
            label = { Text(if (isSubId) "subId" else "Telegram ID") },
            placeholder = { Text(if (isSubId) "например 3a1f…" else "например 123456789") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(
                keyboardType = if (isSubId) KeyboardType.Text else KeyboardType.Number
            ),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = SkyflowColors.TextPrimary,
                unfocusedTextColor = SkyflowColors.TextPrimary,
                focusedBorderColor = SkyflowColors.Accent,
                unfocusedBorderColor = SkyflowColors.Border,
                focusedLabelColor = SkyflowColors.AccentLight,
                unfocusedLabelColor = SkyflowColors.TextMuted,
                cursorColor = SkyflowColors.Accent,
                focusedPlaceholderColor = SkyflowColors.Placeholder,
                unfocusedPlaceholderColor = SkyflowColors.Placeholder,
            ),
        )

        Spacer(Modifier.height(14.dp))

        Button(
            onClick = ::submit,
            enabled = value.isNotBlank() && !isLoading,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            shape = SkyflowShapes.Chip,
            colors = ButtonDefaults.buttonColors(
                containerColor = SkyflowColors.Accent,
                contentColor = SkyflowColors.OnAccent,
                disabledContainerColor = SkyflowColors.Border,
                disabledContentColor = SkyflowColors.TextMuted,
            ),
        ) {
            if (isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = SkyflowColors.OnAccent,
                )
                Spacer(Modifier.width(8.dp))
                Text("Проверяю…", fontWeight = FontWeight.SemiBold)
            } else {
                Text("Подключить", fontWeight = FontWeight.SemiBold)
            }
        }

        AnimatedVisibility(
            visible = error != null,
            enter = fadeIn(tween(200)) + expandVertically(tween(200)),
            exit = fadeOut(tween(150)) + shrinkVertically(tween(150)),
        ) {
            Column {
                Spacer(Modifier.height(12.dp))
                Surface(
                    shape = SkyflowShapes.Card,
                    color = SkyflowColors.ErrorColor.copy(alpha = 0.08f),
                    border = BorderStroke(1.dp, SkyflowColors.ErrorColor.copy(alpha = 0.28f)),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        error.orEmpty(),
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                        color = SkyflowColors.ErrorColor,
                        fontSize = 13.sp,
                        textAlign = TextAlign.Start,
                    )
                }
            }
        }
    }
}

@Composable
private fun SegItem(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = if (selected) SkyflowColors.Accent else androidx.compose.ui.graphics.Color.Transparent,
        modifier = modifier
            .height(38.dp)
            .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                label,
                fontFamily = interFontFamily,
                fontWeight = FontWeight.SemiBold,
                fontSize = 13.sp,
                color = if (selected) SkyflowColors.OnAccent else SkyflowColors.TextSecondary,
            )
        }
    }
}
