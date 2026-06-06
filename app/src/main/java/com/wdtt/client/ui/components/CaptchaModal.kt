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
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wdtt.client.SettingsStore
import com.wdtt.client.TunnelManager

private const val CAPTCHA_PROXY_URL = "http://127.0.0.1:8765"
private val CAPTCHA_RETRY_DELAYS_MS = longArrayOf(500L, 1_000L, 2_000L)

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
    if (!isVisible) return

    val context = LocalContext.current
    val store = remember { SettingsStore(context) }
    val storedUserAgent by store.userAgent.collectAsStateWithLifecycle(initialValue = "")
    val userAgent = storedUserAgent.ifBlank { DEFAULT_USER_AGENT }
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    var isLoading by remember { mutableStateOf(true) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var retryAttempt by remember { mutableIntStateOf(0) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }

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
            loadError = "Повтор загрузки ($retryAttempt/${CAPTCHA_RETRY_DELAYS_MS.size})…"
        }
        mainHandler.postDelayed({
            webViewRef?.loadUrl(CAPTCHA_PROXY_URL)
        }, delayMs)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.7f))
            .clickable(enabled = false) {}
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .padding(horizontal = 24.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(MaterialTheme.colorScheme.surface)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Подтверждение",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = "Закрыть")
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                "VK запросил проверку. Пройдите капчу " +
                    "и подключение продолжится автоматически.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(12.dp))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 350.dp, max = 500.dp)
                    .clip(RoundedCornerShape(12.dp))
            ) {
                AndroidView(
                    factory = { ctx ->
                        WebView(ctx).apply {
                            webViewRef = this
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
                                    val description = error?.description?.toString() ?: "Не удалось открыть страницу"
                                    scheduleRetry(description)
                                }

                                override fun onReceivedHttpError(
                                    view: WebView?,
                                    request: WebResourceRequest?,
                                    errorResponse: WebResourceResponse?
                                ) {
                                    super.onReceivedHttpError(view, request, errorResponse)
                                    if (request?.isForMainFrame != true) return
                                    val code = errorResponse?.statusCode ?: 0
                                    scheduleRetry("HTTP $code")
                                }

                                override fun onReceivedSslError(
                                    view: WebView,
                                    handler: android.webkit.SslErrorHandler,
                                    error: android.net.http.SslError
                                ) {
                                    val url = error.url ?: ""
                                    if (
                                        url.contains("127.0.0.1") ||
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

                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .size(48.dp),
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                loadError?.let { errorText ->
                    if (!isLoading) {
                        Text(
                            text = errorText,
                            modifier = Modifier
                                .align(Alignment.Center)
                                .padding(16.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                "🔒 Данные остаются на устройстве",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
