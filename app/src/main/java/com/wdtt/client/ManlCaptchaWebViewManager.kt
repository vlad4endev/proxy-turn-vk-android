package com.wdtt.client

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.MotionEvent
import android.view.ViewGroup
import android.webkit.*
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.app.NotificationCompat
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.runtime.*
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicReference
import kotlin.random.Random

object ManlCaptchaWebViewManager {
    private const val TAG = "ManlCaptchaWV"
    private const val CAPTCHA_TIMEOUT_MS = 60_000L

    val captchaMutex = Mutex()
    val pendingResult = AtomicReference<CompletableDeferred<Result<String>>?>(null)
    var activeActivity: ManlCaptchaActivity? = null
    var pendingIntentToStart: Intent? = null
    var isCaptchaPending = false

    fun checkAndShowPendingCaptcha(context: Context) {
        val intent = pendingIntentToStart
        if (intent != null && activeActivity == null) {
            context.startActivity(intent)
        }
    }

    fun cancelCaptcha() {
        pendingResult.get()?.completeExceptionally(kotlin.coroutines.cancellation.CancellationException("Cancelled by system"))
    }

    private const val NOTIFICATION_ID = 9001
    private const val CHANNEL_ID = "captcha_channel"

    private fun showCaptchaNotification(context: Context, redirectUri: String) {
        if (MainActivity.isForeground) return
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Уведомления защиты (Капча)", NotificationManager.IMPORTANCE_HIGH
            )
            notificationManager.createNotificationChannel(channel)
        }
        val openIntent = Intent(context, ManlCaptchaActivity::class.java).apply {
            putExtra("redirectUri", redirectUri)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
        }
        val openPendingIntent = PendingIntent.getActivity(
            context, 0, openIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val cancelIntent = Intent(context, CaptchaCancelReceiver::class.java)
        val cancelPendingIntent = PendingIntent.getBroadcast(
            context, 1, cancelIntent, PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("Требуется подтверждение капчи")
            .setContentText("ВК запросил проверку безопасности. Нажмите для решения.")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(openPendingIntent)
            .setAutoCancel(true)
            .addAction(0, "Отменить и выключить", cancelPendingIntent)
            .build()
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    private fun clearCaptchaNotification(context: Context) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancel(NOTIFICATION_ID)
    }

    suspend fun solveCaptchaAsync(context: Context, redirectUri: String, sessionToken: String): String {
        return captchaMutex.withLock {
            isCaptchaPending = true
            val deferred = CompletableDeferred<Result<String>>()
            pendingResult.getAndSet(deferred)?.cancel()

            showCaptchaNotification(context, redirectUri)

            val intent = Intent(context, ManlCaptchaActivity::class.java).apply {
                putExtra("redirectUri", redirectUri)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
            }
            pendingIntentToStart = intent

            if (MainActivity.isForeground) {
                context.startActivity(intent)
            }

            try {
                withTimeout(CAPTCHA_TIMEOUT_MS) {
                    deferred.await().getOrThrow()
                }
            } finally {
                isCaptchaPending = false
                pendingResult.set(null)
                pendingIntentToStart = null
                clearCaptchaNotification(context)
                try {
                    activeActivity?.finish()
                } catch (e: Exception) {
                    Log.e(TAG, "Error finishing activity: ${e.message}")
                }
                activeActivity = null
            }
        }
    }

    fun notifyResult(result: Result<String>) {
        val deferred = pendingResult.getAndSet(null) ?: return
        if (!deferred.isCompleted) {
            deferred.complete(result)
        }
    }
}

/** Статус авто-решения капчи */
enum class AutoSolveStatus { Loading, AutoTrying, Manual, Done }

class ManlCaptchaActivity : ComponentActivity() {

    private val autoSolveStatus = MutableStateFlow(AutoSolveStatus.Loading)
    private var captchaWebViewRef: WebView? = null
    private var autoSolveTriggered = false

    // ── Перехватчик success_token из fetch/XHR ────────────────────────────────
    private val interceptorJSCode = """
        (function() {
            if (window.__wdtt_interceptor_installed) return;
            window.__wdtt_interceptor_installed = true;

            const origFetch = window.fetch;
            window.fetch = async function() {
                const args = arguments;
                const url = args[0] || '';
                if (typeof url === 'string' && url.includes('captchaNotRobot.check')) {
                    const response = await origFetch.apply(this, args);
                    const clone = response.clone();
                    try {
                        const data = await clone.json();
                        if (data.response && data.response.success_token) {
                            window.WdttCaptcha.onSuccess(data.response.success_token);
                        } else if (data.error) {
                            window.WdttCaptcha.onError(JSON.stringify(data.error));
                        }
                    } catch(e) {}
                    return response;
                }
                return origFetch.apply(this, args);
            };

            const origXHROpen = XMLHttpRequest.prototype.open;
            const origXHRSend = XMLHttpRequest.prototype.send;
            XMLHttpRequest.prototype.open = function(method, url) {
                this._wdtt_url = url;
                return origXHROpen.apply(this, arguments);
            };
            XMLHttpRequest.prototype.send = function() {
                const xhr = this;
                if (xhr._wdtt_url && xhr._wdtt_url.includes('captchaNotRobot.check')) {
                    xhr.addEventListener('load', function() {
                        try {
                            const data = JSON.parse(xhr.responseText);
                            if (data.response && data.response.success_token) {
                                window.WdttCaptcha.onSuccess(data.response.success_token);
                            } else if (data.error) {
                                window.WdttCaptcha.onError(JSON.stringify(data.error));
                            }
                        } catch(e) {}
                    });
                }
                return origXHRSend.apply(this, arguments);
            };
        })();
    """.trimIndent()

    // ── Стили: скрываем лишнее, оставляем только виджет капчи ────────────────
    private val hideElementsJSCode = """
        (function() {
            document.addEventListener('click', function(e) {
                if (e.target.closest('.vkc__ModalCardBase-module__dismiss')) {
                    window.WdttCaptcha.onCancelAndStop();
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
                .vkc__ModalCardBase-module__dismiss svg { fill: #ef4444 !important; }
                .vkc__RefreshButton-module__text, .vkc__SliderCaptcha-module__description { color: #ffffff !important; }
                .vkc__SwipeButton-module__track { background-color: #ffffff !important; }
                .vkc__SwipeButton-module__track span { color: #0000FF !important; }
            `;
            document.head.appendChild(style);
        })();
    """.trimIndent()

    // ── Поиск кликабельного элемента капчи (в CSS-пикселях * devicePixelRatio) ─
    private val findCaptchaElementJS = """
        (function() {
            const dpr = window.devicePixelRatio || 1;
            // Чекбокс ("Я не робот")
            const checkboxSels = [
                '.vkc__CheckboxCaptchaWidget-module__box',
                '.vkc__Checkbox-module__root',
                '[class*="CheckboxCaptcha"][class*="box"]',
                '[class*="not_robot"][class*="checkbox"]',
                '[class*="Checkbox"][class*="widget"]',
            ];
            for (const s of checkboxSels) {
                const el = document.querySelector(s);
                if (el) {
                    const r = el.getBoundingClientRect();
                    if (r.width > 0 && r.height > 0) {
                        return JSON.stringify({found:true, type:'checkbox',
                            x: (r.left + r.width/2) * dpr,
                            y: (r.top  + r.height/2) * dpr});
                    }
                }
            }
            // Слайдер ("Потяните вправо")
            const sliderSels = [
                '.vkc__SwipeButton-module__track',
                '[class*="SwipeButton"][class*="track"]',
                '[class*="SliderCaptcha"][class*="track"]',
            ];
            for (const s of sliderSels) {
                const el = document.querySelector(s);
                if (el) {
                    const r = el.getBoundingClientRect();
                    if (r.width > 10 && r.height > 0) {
                        // Начинаем с позиции ~15px от левого края трека
                        return JSON.stringify({found:true, type:'slider',
                            x: (r.left + 15) * dpr,
                            y: (r.top  + r.height/2) * dpr,
                            w: r.width * dpr});
                    }
                }
            }
            return JSON.stringify({found:false});
        })()
    """.trimIndent()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ManlCaptchaWebViewManager.activeActivity = this
        MainActivity.isForeground = true
        val redirectUri = intent.getStringExtra("redirectUri") ?: return finish()

        setContent {
            val solveStatus by autoSolveStatus.collectAsState()
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                Box(modifier = Modifier.fillMaxSize()) {

                    // WebView — всегда загружен (в т.ч. во время авто-решения)
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
                                    @Suppress("DEPRECATION")
                                    databaseEnabled = true
                                    mediaPlaybackRequiresUserGesture = false
                                    loadWithOverviewMode = true
                                    useWideViewPort = true
                                    blockNetworkLoads = false
                                    cacheMode = WebSettings.LOAD_DEFAULT
                                    userAgentString = "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
                                }

                                addJavascriptInterface(object {
                                    @JavascriptInterface
                                    fun onSuccess(token: String) {
                                        Log.d("ManlCaptchaWV", "Token received — auto=${autoSolveStatus.value}")
                                        autoSolveStatus.value = AutoSolveStatus.Done
                                        ManlCaptchaWebViewManager.notifyResult(Result.success(token))
                                        finish()
                                    }
                                    @JavascriptInterface
                                    fun onError(err: String) {
                                        Log.e("ManlCaptchaWV", "Error: $err")
                                        ManlCaptchaWebViewManager.notifyResult(Result.failure(Exception("VK Captcha error: $err")))
                                        finish()
                                    }
                                    @JavascriptInterface
                                    fun onCancelAndStop() {
                                        Log.d("ManlCaptchaWV", "User clicked VK Close — stopping tunnel")
                                        TunnelManager.stop()
                                        ManlCaptchaWebViewManager.notifyResult(Result.failure(Exception("Cancelled and stopped by user")))
                                        finish()
                                    }
                                }, "WdttCaptcha")

                                webViewClient = object : WebViewClient() {
                                    override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                                        super.onPageStarted(view, url, favicon)
                                        view?.evaluateJavascript(interceptorJSCode, null)
                                    }
                                    override fun onPageFinished(view: WebView?, url: String?) {
                                        super.onPageFinished(view, url)
                                        view?.evaluateJavascript(interceptorJSCode, null)
                                        view?.evaluateJavascript(hideElementsJSCode, null)

                                        // Запускаем авто-решение один раз
                                        if (!autoSolveTriggered) {
                                            autoSolveTriggered = true
                                            captchaWebViewRef = view as? WebView
                                            autoSolveStatus.value = AutoSolveStatus.AutoTrying
                                            lifecycleScope.launch {
                                                delay(1500L) // Ждём инициализации виджета
                                                launchAutoSolve(view as? WebView ?: return@launch)
                                            }
                                        }
                                    }
                                }
                                webChromeClient = WebChromeClient()
                                loadUrl(redirectUri)
                                captchaWebViewRef = this
                            }
                        }
                    )

                    // ── Overlay: авто-решение / загрузка ─────────────────────
                    AnimatedVisibility(
                        visible = solveStatus == AutoSolveStatus.Loading || solveStatus == AutoSolveStatus.AutoTrying,
                        enter = fadeIn(),
                        exit = fadeOut()
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color(0xFF0D0D1E)),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(48.dp),
                                    color = Color(0xFF6366F1),
                                    strokeWidth = 3.dp
                                )
                                Text(
                                    if (solveStatus == AutoSolveStatus.Loading) "Загружаем защиту ВК…"
                                    else "Пробуем решить автоматически…",
                                    color = Color.White,
                                    fontSize = 15.sp,
                                    textAlign = TextAlign.Center
                                )
                                if (solveStatus == AutoSolveStatus.AutoTrying) {
                                    TextButton(
                                        onClick = { autoSolveStatus.value = AutoSolveStatus.Manual },
                                        modifier = Modifier.padding(top = 4.dp)
                                    ) {
                                        Text(
                                            "Решить вручную",
                                            color = Color(0xFF6366F1),
                                            fontSize = 13.sp
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // ── Авто-решение: находим элемент через JS → кликаем MotionEvent ─────────

    private fun launchAutoSolve(webView: WebView) {
        var retries = 0
        fun tryNext() {
            if (autoSolveStatus.value != AutoSolveStatus.AutoTrying) return
            webView.evaluateJavascript(findCaptchaElementJS) { rawResult ->
                if (autoSolveStatus.value != AutoSolveStatus.AutoTrying) return@evaluateJavascript
                try {
                    val json = JSONObject(
                        rawResult.trim().removeSurrounding("\"").replace("\\\"", "\"")
                    )
                    val found = json.optBoolean("found", false)
                    if (!found) {
                        // Элемент ещё не отрисован — повторяем каждую секунду (до 5 раз)
                        retries++
                        if (retries >= 5) {
                            Log.d("AutoCaptcha", "Element not found after $retries retries — showing manual")
                            autoSolveStatus.value = AutoSolveStatus.Manual
                            return@evaluateJavascript
                        }
                        lifecycleScope.launch {
                            delay(1000)
                            tryNext()
                        }
                        return@evaluateJavascript
                    }

                    val type = json.optString("type", "checkbox")
                    val x = json.optDouble("x", 0.0).toFloat()
                    val y = json.optDouble("y", 0.0).toFloat()
                    Log.d("AutoCaptcha", "Element found: type=$type x=$x y=$y")

                    lifecycleScope.launch {
                        // Небольшая случайная задержка перед кликом (как у живого пользователя)
                        delay(300L + Random.nextLong(400))

                        if (type == "slider") {
                            val w = json.optDouble("w", 200.0).toFloat()
                            simulateSwipe(webView, x, y, w)
                        } else {
                            simulateTap(webView, x, y)
                        }

                        // Если за 5 секунд success_token не пришёл → показываем вручную
                        delay(5_000)
                        if (autoSolveStatus.value == AutoSolveStatus.AutoTrying) {
                            Log.d("AutoCaptcha", "Auto-solve timed out — switching to manual")
                            autoSolveStatus.value = AutoSolveStatus.Manual
                        }
                    }
                } catch (e: Exception) {
                    Log.w("AutoCaptcha", "JSON parse error: $e raw=$rawResult")
                    autoSolveStatus.value = AutoSolveStatus.Manual
                }
            }
        }
        tryNext()
    }

    /**
     * Симулирует реальный tap через MotionEvent.
     * Создаёт нативное касание → WebView передаёт в JS как isTrusted=true.
     */
    private fun simulateTap(view: WebView, x: Float, y: Float) {
        val downTime = SystemClock.uptimeMillis()
        val holdMs = 60L + Random.nextLong(40) // 60–100ms — как у реального пальца
        val up = MotionEvent.obtain(downTime, downTime + holdMs, MotionEvent.ACTION_UP, x, y, 0)
        val down = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, x, y, 0)
        view.dispatchTouchEvent(down)
        view.dispatchTouchEvent(up)
        down.recycle()
        up.recycle()
        Log.d("AutoCaptcha", "Tap dispatched at ($x, $y)")
    }

    /**
     * Симулирует реальный свайп для слайдера.
     * Плавное движение с ease-in-out кривой и лёгким вертикальным дрейфом.
     */
    private fun simulateSwipe(view: WebView, startX: Float, y: Float, trackWidth: Float) {
        val endX = startX + trackWidth * 0.90f   // до 90% длины трека
        val downTime = SystemClock.uptimeMillis()
        val totalMs = 500L + Random.nextLong(200) // 500–700ms
        val steps = 30

        view.dispatchTouchEvent(MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, startX, y, 0))

        for (i in 1..steps) {
            val t = i.toFloat() / steps
            // Ease-in-out: медленно → быстро → медленно
            val eased = t * t * (3 - 2 * t)
            val cx = startX + (endX - startX) * eased
            val jitter = (Random.nextFloat() - 0.5f) * 2f // ±1px вертикальный дрейф
            val eventTime = downTime + (totalMs * t).toLong()
            view.dispatchTouchEvent(MotionEvent.obtain(downTime, eventTime, MotionEvent.ACTION_MOVE, cx, y + jitter, 0))
        }

        val upTime = downTime + totalMs + 20
        view.dispatchTouchEvent(MotionEvent.obtain(downTime, upTime, MotionEvent.ACTION_UP, endX, y, 0))
        Log.d("AutoCaptcha", "Swipe dispatched from $startX to $endX (track=$trackWidth)")
    }

    override fun onDestroy() {
        super.onDestroy()
        MainActivity.isForeground = false
        if (ManlCaptchaWebViewManager.activeActivity === this) {
            ManlCaptchaWebViewManager.activeActivity = null
        }
    }
}

class CaptchaCancelReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        TunnelManager.stop()
        ManlCaptchaWebViewManager.activeActivity?.finish()
        val notifMgr = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notifMgr.cancel(9001)
    }
}
