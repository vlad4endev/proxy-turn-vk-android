package com.wdtt.client

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.app.NotificationCompat

object CaptchaWebViewActivityLauncher {
    private const val TAG = "CaptchaWebView"
    private const val CAPTCHA_PROXY_URL = "http://127.0.0.1:8765"
    private const val NOTIFICATION_ID = 9002
    private const val CHANNEL_ID = "captcha_proxy_channel"

    var activeActivity: CaptchaWebViewActivity? = null
    var pendingIntentToStart: Intent? = null

    @Volatile
    var isCaptchaPending = false

    fun checkAndShowPendingCaptcha(context: Context) {
        val intent = pendingIntentToStart ?: return
        if (activeActivity == null) {
            context.startActivity(intent)
        }
    }

    fun launch(context: Context) {
        context.startActivity(
            Intent(context, CaptchaWebViewActivity::class.java).apply {
                putExtra("captchaUrl", CAPTCHA_PROXY_URL)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )
    }

    fun openManualCaptcha(context: Context) {
        if (activeActivity != null || isCaptchaPending) return

        isCaptchaPending = true
        showCaptchaNotification(context)

        val intent = Intent(context, CaptchaWebViewActivity::class.java).apply {
            putExtra("captchaUrl", CAPTCHA_PROXY_URL)
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
            )
        }
        pendingIntentToStart = intent

        if (MainActivity.isForeground) {
            context.startActivity(intent)
        }
    }

    fun dismiss() {
        isCaptchaPending = false
        pendingIntentToStart = null
        try {
            activeActivity?.finish()
        } catch (e: Exception) {
            Log.e(TAG, "Error finishing activity: ${e.message}")
        }
        activeActivity = null
    }

    private fun showCaptchaNotification(context: Context) {
        if (MainActivity.isForeground) return

        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Уведомления защиты (Капча)",
                NotificationManager.IMPORTANCE_HIGH
            )
            notificationManager.createNotificationChannel(channel)
        }

        val openIntent = Intent(context, CaptchaWebViewActivity::class.java).apply {
            putExtra("captchaUrl", CAPTCHA_PROXY_URL)
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
            )
        }

        val openPendingIntent = PendingIntent.getActivity(
            context, 0, openIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("Требуется подтверждение капчи")
            .setContentText("ВК запросил проверку безопасности. Нажмите для решения.")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(openPendingIntent)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    fun clearCaptchaNotification(context: Context) {
        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancel(NOTIFICATION_ID)
    }
}

class CaptchaWebViewActivity : ComponentActivity() {
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CaptchaWebViewActivityLauncher.activeActivity = this
        CaptchaWebViewActivityLauncher.pendingIntentToStart = null
        MainActivity.isForeground = true

        val captchaUrl = intent.getStringExtra("captchaUrl") ?: "http://127.0.0.1:8765"

        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                Box(modifier = Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        var isLoading by rememberSaveable { mutableStateOf(true) }

                        Box {
                            Surface(
                                modifier = Modifier.fillMaxSize(),
                                color = Color.Transparent,
                                tonalElevation = 0.dp,
                                shadowElevation = 0.dp
                            ) {
                                AndroidView(
                                    modifier = Modifier.fillMaxSize(),
                                    factory = { ctx ->
                                        WebView(ctx).apply {
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
                                                userAgentString =
                                                    "Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
                                            }

                                            addJavascriptInterface(object {
                                                @android.webkit.JavascriptInterface
                                                fun onCancelAndStop() {
                                                    runOnUiThread {
                                                        TunnelManager.stop()
                                                        finishAfterCaptcha()
                                                    }
                                                }

                                                @android.webkit.JavascriptInterface
                                                fun onCaptchaDone() {
                                                    runOnUiThread { finishAfterCaptcha() }
                                                }
                                            }, "WdttCaptchaHost")

                                            webViewClient = object : WebViewClient() {
                                                override fun onPageFinished(view: WebView?, url: String?) {
                                                    super.onPageFinished(view, url)
                                                    view?.evaluateJavascript(hideElementsJSCode, null)
                                                    isLoading = false
                                                    if (url?.contains("local-captcha-result") == true) {
                                                        finishAfterCaptcha()
                                                    }
                                                }

                                                override fun onReceivedSslError(
                                                    view: WebView,
                                                    handler: android.webkit.SslErrorHandler,
                                                    error: android.net.http.SslError
                                                ) {
                                                    val url = error.url ?: ""
                                                    if (
                                                        url.contains("127.0.0.1") ||
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
                                            loadUrl(captchaUrl)
                                        }
                                    }
                                )
                            }

                            if (isLoading) {
                                CircularProgressIndicator(
                                    modifier = Modifier.align(Alignment.Center).size(48.dp),
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    private fun finishAfterCaptcha() {
        CaptchaWebViewActivityLauncher.clearCaptchaNotification(applicationContext)
        CaptchaWebViewActivityLauncher.isCaptchaPending = false
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        CaptchaWebViewActivityLauncher.clearCaptchaNotification(applicationContext)
        CaptchaWebViewActivityLauncher.isCaptchaPending = false
        if (CaptchaWebViewActivityLauncher.activeActivity === this) {
            CaptchaWebViewActivityLauncher.activeActivity = null
        }
    }
}
