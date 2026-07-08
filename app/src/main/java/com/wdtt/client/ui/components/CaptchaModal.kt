package com.wdtt.client.ui.components

import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.*
import androidx.compose.runtime.*
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
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wdtt.client.SettingsStore
import com.wdtt.client.TunnelManager
import com.wdtt.client.ui.SkyflowColors
import com.wdtt.client.ui.SkyflowShapes

// localhost (не 127.0.0.1), чтобы origin WebView совпадал с origin, который
// использует инъекция Go-прокси (localhost:8765) — иначе fetch/worker капчи
// уходят cross-origin и чекбокс не проходится.
private const val CAPTCHA_PROXY_URL = "http://localhost:8765"
private val CAPTCHA_RETRY_DELAYS_MS = longArrayOf(500L, 1_000L, 2_000L)

// Максимум ожидания прохождения капчи в модалке. Если за это время не решена —
// не морозим Go-солвер на 3 минуты, а сигналим TunnelManager (перезапуск/отказ).
private const val CAPTCHA_MODAL_TIMEOUT_MS = 100_000L

private const val DEFAULT_USER_AGENT =
    "Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

private val hideElementsJSCode = """
    (function() {
        document.addEventListener('click', function(e) {
            if (e.target.closest('.vkc__ModalCardBase-module__dismiss')) {
                if (window.WdttCaptchaHost && window.WdttCaptchaHost.onCancelAndStop) {
                    window.WdttCaptchaHost.onCancelAndStop();
                }
            }
        });

        const style = document.createElement('style');
        style.innerHTML = `
            .vkc__VisuallyHiddenModalOverlay-module__host,
            .vkc__ModalOverlay-module__host,
            .vkc__KaleidoscopeScreen-module__logoBlock,
            .vkc__KaleidoscopeScreen-module__captchaId,
            .vkc__SliderCaptcha-module__descriptionLink,
            .vkc__SliderCaptcha-module__changeTypeButton {
                display: none !important;
            }
            body, html, .vkc__ModalCard-module__host, .vkc__AppRoot-module__host, .vkui__root {
                background: transparent !important;
                box-shadow: none !important;
            }
            .vkc__ModalCardBase-module__container {
                background: #000000 !important;
                box-shadow: none !important;
            }
            .vkc__ModalCardBase-module__dismiss {
                color: #ef4444 !important;
                transform: scale(0.8) translateX(-12px) !important;
            }
            .vkc__ModalCardBase-module__dismiss svg {
                fill: #ef4444 !important;
            }
            .vkc__RefreshButton-module__text,
            .vkc__SliderCaptcha-module__description {
                color: #ffffff !important;
            }
            .vkc__SwipeButton-module__track {
                background-color: #ffffff !important;
            }
            .vkc__SwipeButton-module__track span {
                color: #0000FF !important;
            }
        `;
        document.head.appendChild(style);

        function checkDone() {
            var text = document.body ? document.body.textContent || '' : '';
            if (text.indexOf('Done') !== -1 || text.indexOf('✔') !== -1) {
                if (window.WdttCaptchaHost && window.WdttCaptchaHost.onCaptchaDone) {
                    window.WdttCaptchaHost.onCaptchaDone();
                }
            }
        }
        setInterval(checkDone, 500);
    })();
""".trimIndent()

@Composable
fun CaptchaModal(
    isVisible: Boolean,
    onDismiss: () -> Unit,
    onCaptchaSolved: () -> Unit,
) {
    val context = LocalContext.current
    val store = remember { SettingsStore(context) }
    val storedUserAgent by store.userAgent.collectAsStateWithLifecycle(initialValue = "")
    val userAgent = storedUserAgent.ifBlank { DEFAULT_USER_AGENT }
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    var isLoading by remember { mutableStateOf(true) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var retryAttempt by remember { mutableIntStateOf(0) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }

    // Анти-зависание: пока модалка видима, ведём таймер. Если капча не решена за
    // CAPTCHA_MODAL_TIMEOUT_MS, сообщаем TunnelManager (он перезапустит транспорт
    // или честно остановит с сообщением), вместо вечного зависания на Go-солвере.
    LaunchedEffect(isVisible) {
        if (!isVisible) return@LaunchedEffect
        kotlinx.coroutines.delay(CAPTCHA_MODAL_TIMEOUT_MS)
        TunnelManager.onCaptchaTimeout()
    }

    // Видимый отсчёт (только индикация — реальный таймаут ведётся выше отдельно).
    var secondsLeft by remember { mutableIntStateOf((CAPTCHA_MODAL_TIMEOUT_MS / 1000L).toInt()) }
    LaunchedEffect(isVisible) {
        if (!isVisible) return@LaunchedEffect
        secondsLeft = (CAPTCHA_MODAL_TIMEOUT_MS / 1000L).toInt()
        while (secondsLeft > 0) {
            kotlinx.coroutines.delay(1000L)
            secondsLeft -= 1
        }
    }

    fun scheduleRetry(errorDescription: String) {
        if (retryAttempt >= CAPTCHA_RETRY_DELAYS_MS.size) {
            mainHandler.post {
                isLoading = false
                loadError = errorDescription
            }
            return
        }
        val delayMs = CAPTCHA_RETRY_DELAYS_MS[retryAttempt]
        retryAttempt += 1
        mainHandler.post {
            isLoading = true
            loadError = "Повтор ($retryAttempt/${CAPTCHA_RETRY_DELAYS_MS.size})…"
        }
        mainHandler.postDelayed({ webViewRef?.loadUrl(CAPTCHA_PROXY_URL) }, delayMs)
    }

    // ── Dim background — only when visible, dismissible by tap ───────────────
    AnimatedVisibility(
        visible = isVisible,
        enter = androidx.compose.animation.fadeIn(tween(200)),
        exit  = androidx.compose.animation.fadeOut(tween(180))
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.45f))
                .clickable(enabled = true) { onDismiss() }
        )
    }

    // ── Bottom sheet card ─────────────────────────────────────────────────────
    AnimatedVisibility(
        visible = isVisible,
        enter = slideInVertically(
            animationSpec = tween(320),
            initialOffsetY = { it }
        ),
        exit  = slideOutVertically(
            animationSpec = tween(240),
            targetOffsetY = { it }
        ),
        modifier = Modifier.fillMaxSize()
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.BottomCenter
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                    .background(SkyflowColors.GlassSurfaceElevated)
                    .clickable(enabled = false) {}  // absorb taps so background-tap doesn't pass through
            ) {
                // ── Drag handle ───────────────────────────────────────────────
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp, bottom = 4.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(width = 36.dp, height = 4.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(SkyflowColors.TextMuted.copy(alpha = 0.35f))
                    )
                }

                // ── Header row ────────────────────────────────────────────────
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Status badge
                    val badgeColor = if (isLoading) SkyflowColors.Connecting
                        else if (loadError != null) SkyflowColors.ErrorColor
                        else SkyflowColors.Connected
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .clip(RoundedCornerShape(11.dp))
                            .background(badgeColor.copy(alpha = 0.16f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Default.VerifiedUser,
                            contentDescription = null,
                            tint = badgeColor,
                            modifier = Modifier.size(17.dp),
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "Быстрая проверка",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = SkyflowColors.TextPrimary
                        )
                        Text(
                            when {
                                isLoading && loadError != null -> loadError!!
                                isLoading -> "Загрузка капчи…"
                                loadError != null -> loadError!!
                                else -> "VK просит подтвердить, что вы не робот"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = SkyflowColors.TextMuted,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                        )
                    }
                    // Retry button (shown on error)
                    if (loadError != null && !isLoading) {
                        IconButton(
                            onClick = {
                                retryAttempt = 0
                                isLoading = true
                                loadError = null
                                webViewRef?.loadUrl(CAPTCHA_PROXY_URL)
                            },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                Icons.Default.Refresh,
                                contentDescription = "Повторить",
                                tint = SkyflowColors.AccentLight,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                    // Close button
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "Закрыть",
                            tint = SkyflowColors.TextMuted,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 20.dp),
                    color = SkyflowColors.Border.copy(alpha = 0.5f)
                )

                // ── Таймаут + запасной способ ────────────────────────────────
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Автопроверка · ${secondsLeft}с",
                        style = MaterialTheme.typography.labelSmall,
                        color = SkyflowColors.TextMuted,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        "Другой способ",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = SkyflowColors.AccentLight,
                        modifier = Modifier.clickable { TunnelManager.onCaptchaTimeout() },
                    )
                }

                // ── WebView area ──────────────────────────────────────────────
                // Выше 300dp: чекбокс-капча VK не влезала и клик мог не попадать.
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(460.dp)
                ) {
                    AndroidView(
                        factory = { ctx ->
                            WebView(ctx).apply {
                                webViewRef = this
                                // Cookies обязательны для VK-капчи (сессия/CSRF для
                                // captchaNotRobot.check). По умолчанию сторонние
                                // cookies в WebView выключены — включаем явно.
                                val cookieManager = android.webkit.CookieManager.getInstance()
                                cookieManager.setAcceptCookie(true)
                                cookieManager.setAcceptThirdPartyCookies(this, true)
                                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                                layoutParams = ViewGroup.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    ViewGroup.LayoutParams.MATCH_PARENT
                                )
                                settings.apply {
                                    javaScriptEnabled = true
                                    domStorageEnabled = true
                                    databaseEnabled = true
                                    mediaPlaybackRequiresUserGesture = false
                                    loadWithOverviewMode = true
                                    useWideViewPort = true
                                    blockNetworkLoads = false
                                    cacheMode = WebSettings.LOAD_DEFAULT
                                    userAgentString = userAgent
                                    mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                                }

                                addJavascriptInterface(object {
                                    @android.webkit.JavascriptInterface
                                    fun onCancelAndStop() {
                                        mainHandler.post {
                                            TunnelManager.stop()
                                            onDismiss()
                                        }
                                    }

                                    @android.webkit.JavascriptInterface
                                    fun onCaptchaDone() {
                                        mainHandler.post { onCaptchaSolved() }
                                    }
                                }, "WdttCaptchaHost")

                                webViewClient = object : WebViewClient() {
                                    override fun shouldInterceptRequest(
                                        view: WebView?,
                                        request: WebResourceRequest?
                                    ): WebResourceResponse? {
                                        val url = request?.url?.toString() ?: ""
                                        if (url.contains("local-captcha-result")) {
                                            mainHandler.post { onCaptchaSolved() }
                                        }
                                        return super.shouldInterceptRequest(view, request)
                                    }

                                    override fun onPageFinished(view: WebView?, url: String?) {
                                        super.onPageFinished(view, url)
                                        view?.evaluateJavascript(hideElementsJSCode, null)
                                        mainHandler.post {
                                            isLoading = false
                                            loadError = null
                                        }
                                        if (url?.contains("local-captcha-result") == true) {
                                            mainHandler.post { onCaptchaSolved() }
                                        }
                                    }

                                    override fun onReceivedError(
                                        view: WebView?,
                                        request: WebResourceRequest?,
                                        error: WebResourceError?
                                    ) {
                                        super.onReceivedError(view, request, error)
                                        if (request?.isForMainFrame != true) return
                                        scheduleRetry(
                                            error?.description?.toString() ?: "Ошибка загрузки"
                                        )
                                    }

                                    override fun onReceivedHttpError(
                                        view: WebView?,
                                        request: WebResourceRequest?,
                                        errorResponse: WebResourceResponse?
                                    ) {
                                        super.onReceivedHttpError(view, request, errorResponse)
                                        if (request?.isForMainFrame != true) return
                                        scheduleRetry("HTTP ${errorResponse?.statusCode ?: 0}")
                                    }

                                    override fun onReceivedSslError(
                                        view: WebView,
                                        handler: android.webkit.SslErrorHandler,
                                        error: android.net.http.SslError
                                    ) {
                                        val url = error.url ?: ""
                                        if (url.contains("127.0.0.1") ||
                                            url.contains("localhost") ||
                                            url.contains("vk.ru") ||
                                            url.contains("vk.com") ||
                                            url.contains("okcdn.ru")
                                        ) {
                                            handler.proceed()
                                        } else {
                                            handler.cancel()
                                        }
                                    }
                                }
                                webChromeClient = WebChromeClient()
                                loadUrl(CAPTCHA_PROXY_URL)
                            }
                        },
                        modifier = Modifier.fillMaxSize()
                    )

                    // Loading spinner
                    if (isLoading && loadError == null) {
                        CircularProgressIndicator(
                            modifier = Modifier
                                .align(Alignment.Center)
                                .size(36.dp),
                            strokeWidth = 2.5.dp,
                            color = SkyflowColors.AccentLight
                        )
                    }

                    // Error state (after all retries)
                    if (loadError != null && !isLoading) {
                        Column(
                            modifier = Modifier
                                .align(Alignment.Center)
                                .padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Text(
                                loadError!!,
                                style = MaterialTheme.typography.bodySmall,
                                color = SkyflowColors.ErrorColor,
                                textAlign = TextAlign.Center
                            )
                            OutlinedButton(
                                onClick = {
                                    retryAttempt = 0
                                    isLoading = true
                                    loadError = null
                                    webViewRef?.loadUrl(CAPTCHA_PROXY_URL)
                                },
                                colors = ButtonDefaults.outlinedButtonColors(
                                    contentColor = SkyflowColors.AccentLight
                                ),
                                border = androidx.compose.foundation.BorderStroke(
                                    1.dp, SkyflowColors.AccentLight.copy(alpha = 0.5f)
                                )
                            ) {
                                Text("Повторить", fontSize = 13.sp)
                            }
                        }
                    }
                }

                // Bottom safe area spacer
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}
