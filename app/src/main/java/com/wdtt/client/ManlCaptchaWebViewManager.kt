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
import android.view.InputDevice
import android.view.MotionEvent
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.*
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.app.NotificationCompat
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.runtime.*
import androidx.lifecycle.lifecycleScope
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
    // 3 minutes: auto-solve takes ~36 s, then user needs time to notice and tap manually.
    private const val CAPTCHA_TIMEOUT_MS = 180_000L

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
        pendingResult.get()?.completeExceptionally(
            kotlin.coroutines.cancellation.CancellationException("Cancelled by system")
        )
    }

    private const val NOTIFICATION_ID = 9001
    private const val CHANNEL_ID = "captcha_channel"

    private fun showCaptchaNotification(context: Context, redirectUri: String) {
        if (MainActivity.isForeground) return
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Уведомления защиты (Капча)", NotificationManager.IMPORTANCE_HIGH)
            )
        }
        val openIntent = Intent(context, ManlCaptchaActivity::class.java).apply {
            putExtra("redirectUri", redirectUri)
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
            )
        }
        val cancelIntent = Intent(context, CaptchaCancelReceiver::class.java)
        nm.notify(
            NOTIFICATION_ID,
            NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle("Требуется подтверждение капчи")
                .setContentText("ВК запросил проверку безопасности. Нажмите для решения.")
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setContentIntent(
                    PendingIntent.getActivity(
                        context, 0, openIntent,
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                    )
                )
                .setAutoCancel(true)
                .addAction(
                    0, "Отменить и выключить",
                    PendingIntent.getBroadcast(
                        context, 1, cancelIntent, PendingIntent.FLAG_IMMUTABLE
                    )
                )
                .build()
        )
    }

    fun clearCaptchaNotification(context: Context) {
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .cancel(NOTIFICATION_ID)
    }

    suspend fun solveCaptchaAsync(context: Context, redirectUri: String, sessionToken: String): String {
        return captchaMutex.withLock {
            isCaptchaPending = true
            val deferred = CompletableDeferred<Result<String>>()
            pendingResult.getAndSet(deferred)?.cancel()

            showCaptchaNotification(context, redirectUri)

            val intent = Intent(context, ManlCaptchaActivity::class.java).apply {
                putExtra("redirectUri", redirectUri)
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
                )
            }
            pendingIntentToStart = intent
            if (MainActivity.isForeground) context.startActivity(intent)

            try {
                withTimeout(CAPTCHA_TIMEOUT_MS) {
                    deferred.await().getOrThrow()
                }
            } finally {
                isCaptchaPending = false
                pendingResult.set(null)
                pendingIntentToStart = null
                clearCaptchaNotification(context)
                try { activeActivity?.finish() } catch (_: Exception) {}
                activeActivity = null
            }
        }
    }

    fun notifyResult(result: Result<String>) {
        val deferred = pendingResult.getAndSet(null) ?: return
        if (!deferred.isCompleted) deferred.complete(result)
    }
}

/** Статус авто-решения: Idle — ещё не пробовали, Trying — пробуем, Done — готово */
enum class AutoSolveStatus { Idle, Trying, Done }

class ManlCaptchaActivity : ComponentActivity() {

    private val autoSolveStatus = MutableStateFlow(AutoSolveStatus.Idle)
    private var autoSolveTriggered = false

    // ── Перехватчик: ловит success_token из fetch/XHR ──────────────────────────
    private val interceptorJSCode = """
        (function() {
            if (window.__wdtt_interceptor_v2) return;
            window.__wdtt_interceptor_v2 = true;

            function onResult(data) {
                try {
                    if (data && data.response && data.response.success_token) {
                        window.WdttCaptcha.onSuccess(data.response.success_token);
                    } else if (data && data.error) {
                        window.WdttCaptcha.onError(JSON.stringify(data.error));
                    }
                } catch(e) { console.log('WdttCaptcha callback error: ' + e); }
            }

            const origFetch = window.fetch;
            window.fetch = async function() {
                const url = (arguments[0] || '').toString();
                if (url.includes('captchaNotRobot.check')) {
                    const resp = await origFetch.apply(this, arguments);
                    try { onResult(await resp.clone().json()); } catch(e) {}
                    return resp;
                }
                return origFetch.apply(this, arguments);
            };

            const origOpen = XMLHttpRequest.prototype.open;
            const origSend = XMLHttpRequest.prototype.send;
            XMLHttpRequest.prototype.open = function(m, url) {
                this._wdttUrl = url || '';
                return origOpen.apply(this, arguments);
            };
            XMLHttpRequest.prototype.send = function() {
                if (this._wdttUrl.includes('captchaNotRobot.check')) {
                    this.addEventListener('load', () => {
                        try { onResult(JSON.parse(this.responseText)); } catch(e) {}
                    });
                }
                return origSend.apply(this, arguments);
            };
        })();
    """.trimIndent()

    // ── Стили: скрываем лишнее, применяем тёмную тему ──────────────────────────
    private val hideElementsJSCode = """
        (function() {
            if (window.__wdtt_styles) return;
            window.__wdtt_styles = true;
            document.addEventListener('click', function(e) {
                if (e.target.closest('.vkc__ModalCardBase-module__dismiss')) {
                    window.WdttCaptcha.onCancelAndStop();
                }
            });
            const s = document.createElement('style');
            s.innerHTML = `
                .vkc__VisuallyHiddenModalOverlay-module__host,
                .vkc__ModalOverlay-module__host,
                .vkc__KaleidoscopeScreen-module__logoBlock,
                .vkc__KaleidoscopeScreen-module__captchaId,
                .vkc__SliderCaptcha-module__descriptionLink,
                .vkc__SliderCaptcha-module__changeTypeButton { display: none !important; }
                body, html { background: transparent !important; }
                .vkc__ModalCardBase-module__container { background: #0d0d1e !important; }
                .vkc__ModalCardBase-module__dismiss { color: #ef4444 !important; }
                .vkc__ModalCardBase-module__dismiss svg { fill: #ef4444 !important; }
            `;
            document.head.appendChild(s);
        })();
    """.trimIndent()

    // Ищет интерактивный элемент капчи в основном документе И во всех доступных iframe.
    // Возвращает объект { found, type, x, y, w?, sel, inFrame }
    // x/y — в CSS-пикселях относительно viewport WebView (без умножения на dpr,
    // т.к. dispatchTouchEvent ожидает View-координаты: css * dpr делаем на Kotlin-стороне).
    private val findCaptchaElementJS = """
        (function() {
            const cbSels = [
                '[role="checkbox"]',
                'input[type="checkbox"]',
                '.vkc__CheckboxCaptchaWidget-module__box',
                '.vkc__Checkbox-module__root',
                '[class*="CheckboxCaptcha"]',
                '[class*="checkboxCaptcha"]',
                '[class*="not_robot"]',
            ];
            const slSels = [
                '[role="slider"]',
                '.vkc__SwipeButton-module__track',
                '[class*="SwipeButton"][class*="track"]',
                '[class*="swipe"][class*="track"]',
                '[class*="SliderCaptcha"][class*="track"]',
            ];

            function findInDoc(doc, frameOffsetX, frameOffsetY) {
                for (const s of cbSels) {
                    try {
                        const el = doc.querySelector(s);
                        if (!el) continue;
                        const r = el.getBoundingClientRect();
                        if (r.width > 0 && r.height > 0) {
                            return { found: true, type: 'checkbox',
                                x: frameOffsetX + r.left + r.width / 2,
                                y: frameOffsetY + r.top  + r.height / 2,
                                sel: s };
                        }
                    } catch(e) {}
                }
                for (const s of slSels) {
                    try {
                        const el = doc.querySelector(s);
                        if (!el) continue;
                        const r = el.getBoundingClientRect();
                        if (r.width > 40 && r.height > 0) {
                            return { found: true, type: 'slider',
                                x: frameOffsetX + r.left + 14,
                                y: frameOffsetY + r.top  + r.height / 2,
                                w: r.width,
                                sel: s };
                        }
                    } catch(e) {}
                }
                return null;
            }

            // Главный документ
            const main = findInDoc(document, 0, 0);
            if (main) return Object.assign(main, { inFrame: false });

            // Поиск во всех доступных iframe
            const frames = document.querySelectorAll('iframe');
            for (const frame of frames) {
                try {
                    const doc = frame.contentDocument;
                    if (!doc) continue;
                    const fr = frame.getBoundingClientRect();
                    const res = findInDoc(doc, fr.left, fr.top);
                    if (res) return Object.assign(res, { inFrame: true });
                } catch(e) {} // cross-origin — пропускаем
            }
            return { found: false };
        })()
    """.trimIndent()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ManlCaptchaWebViewManager.activeActivity = this
        MainActivity.isForeground = true
        val redirectUri = intent.getStringExtra("redirectUri") ?: return finish()

        // Начинаем прозрачными — пользователь ничего не видит во время авто-решения.
        // Когда авто-решение провалится, окно станет видимым для ручного ввода.
        setWindowMode(transparent = true)

        // Следим за статусом авто-решения и переключаем прозрачность
        lifecycleScope.launch {
            autoSolveStatus.collect { status ->
                setWindowMode(transparent = (status == AutoSolveStatus.Trying))
            }
        }

        setContent {
            val solveStatus by autoSolveStatus.collectAsState()
            val showManual = solveStatus == AutoSolveStatus.Idle

            val bgColor by animateColorAsState(
                targetValue = if (showManual) Color(0xFF0D0D1E) else Color.Transparent,
                animationSpec = tween(300),
                label = "bg"
            )

            MaterialTheme(colorScheme = darkColorScheme()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(bgColor)
                ) {
                    // WebView — ВСЕГДА загружен в фоне (нужен для interceptor и MotionEvent).
                    // Invisible во время авто-решения, visible для ручного.
                    AndroidView(
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer { alpha = if (showManual) 1f else 0f },
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
                                    userAgentString = "Mozilla/5.0 (Linux; Android 14; Pixel 8) " +
                                        "AppleWebKit/537.36 (KHTML, like Gecko) " +
                                        "Chrome/126.0.0.0 Mobile Safari/537.36"
                                }

                                addJavascriptInterface(object {
                                    @JavascriptInterface
                                    fun onSuccess(token: String) {
                                        Log.d("ManlCaptchaWV", "✓ Token received (status=${autoSolveStatus.value})")
                                        autoSolveStatus.value = AutoSolveStatus.Done
                                        ManlCaptchaWebViewManager.notifyResult(Result.success(token))
                                        runOnUiThread { finish() }
                                    }
                                    @JavascriptInterface
                                    fun onError(err: String) {
                                        Log.e("ManlCaptchaWV", "✗ Error: $err")
                                        ManlCaptchaWebViewManager.notifyResult(
                                            Result.failure(Exception("VK Captcha error: $err"))
                                        )
                                        runOnUiThread { finish() }
                                    }
                                    @JavascriptInterface
                                    fun onCancelAndStop() {
                                        Log.d("ManlCaptchaWV", "User cancelled — stopping tunnel")
                                        TunnelManager.stop()
                                        ManlCaptchaWebViewManager.notifyResult(
                                            Result.failure(Exception("Cancelled and stopped by user"))
                                        )
                                        runOnUiThread { finish() }
                                    }
                                }, "WdttCaptcha")

                                webViewClient = object : WebViewClient() {
                                    override fun onPageStarted(
                                        view: WebView?, url: String?,
                                        favicon: android.graphics.Bitmap?
                                    ) {
                                        super.onPageStarted(view, url, favicon)
                                        view?.evaluateJavascript(interceptorJSCode, null)
                                    }

                                    override fun onPageFinished(view: WebView?, url: String?) {
                                        super.onPageFinished(view, url)
                                        view?.evaluateJavascript(interceptorJSCode, null)
                                        view?.evaluateJavascript(hideElementsJSCode, null)

                                        if (!autoSolveTriggered && view != null) {
                                            autoSolveTriggered = true
                                            lifecycleScope.launch {
                                                tryAutoSolve(view)
                                            }
                                        }
                                    }
                                }
                                webChromeClient = WebChromeClient()
                                loadUrl(redirectUri)
                            }
                        }
                    )

                    // Кнопка закрытия — только когда виден ручной режим
                    if (showManual) {
                        Text(
                            "Решите капчу вручную ↑",
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(bottom = 24.dp),
                            color = com.wdtt.client.ui.SkyflowColors.AccentLight,
                            fontSize = 13.sp
                        )
                    }
                }
            }
        }
    }

    // Управляет прозрачностью окна: transparent=true → пользователь ничего не видит,
    // тачи сквозь — transparent=false → тёмное окно с WebView для ручного решения.
    private fun setWindowMode(transparent: Boolean) {
        if (transparent) {
            window.addFlags(
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            )
        } else {
            window.clearFlags(
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            )
        }
    }

    // ── Авто-решение ────────────────────────────────────────────────────────────
    //
    // PoW (Proof of Work) VK считает 5–15 секунд в браузере.
    // Поэтому мы не делаем один клик — мы повторяем попытки каждые 3 секунды
    // на протяжении 35 секунд. Как только PoW завершится и клик будет принят,
    // interceptor поймает success_token и мы выйдем.

    private suspend fun tryAutoSolve(webView: WebView) {
        delay(2500L)  // минимальная пауза для инициализации виджета
        autoSolveStatus.value = AutoSolveStatus.Trying

        val dpr = webView.resources.displayMetrics.density

        // Каждые 3 секунды пробуем нажать на элемент (до 12 попыток = 36 сек)
        for (attempt in 1..12) {
            if (autoSolveStatus.value == AutoSolveStatus.Done) return

            Log.d("AutoCaptcha", "=== Attempt $attempt ===")

            val raw = evaluateJsSync(webView, findCaptchaElementJS)
            if (raw == null) {
                Log.w("AutoCaptcha", "JS eval returned null")
                delay(3000L)
                continue
            }

            val json = parseJsObject(raw)
            val found = json?.optBoolean("found", false) ?: false

            if (!found) {
                Log.d("AutoCaptcha", "Element not visible yet (raw=$raw)")
                delay(3000L)
                continue
            }

            val type = json!!.optString("type", "checkbox")
            // JS вернул CSS-пиксели, умножаем на dpr для View-координат
            val x = (json.optDouble("x", 0.0) * dpr).toFloat()
            val y = (json.optDouble("y", 0.0) * dpr).toFloat()
            val sel = json.optString("sel", "?")
            val inFrame = json.optBoolean("inFrame", false)
            Log.d("AutoCaptcha", "Found $type at css→px ($x,$y) via [$sel] inFrame=$inFrame")

            // Небольшой рандомный пре-делэй — имитация человека
            delay(150L + Random.nextLong(250))

            if (type == "slider") {
                val wCss = json.optDouble("w", 200.0)
                val wPx = (wCss * dpr).toFloat()
                simulateSwipe(webView, x, y, wPx)
            } else {
                simulateTap(webView, x, y)
            }

            // Ждём ответ от VK — если PoW был готов, токен придёт за 1–3 сек
            delay(3000L)
            if (autoSolveStatus.value == AutoSolveStatus.Done) return

            // Не пришёл — PoW ещё не готов, пробуем снова
            Log.d("AutoCaptcha", "No token after tap, retrying...")
        }

        Log.w("AutoCaptcha", "Auto-solve gave up after 12 attempts — user must solve manually")
        if (autoSolveStatus.value == AutoSolveStatus.Trying) {
            autoSolveStatus.value = AutoSolveStatus.Idle
        }
    }

    /** Вызывает evaluateJavascript и возвращает raw-строку из колбэка */
    private suspend fun evaluateJsSync(webView: WebView, js: String): String? {
        val deferred = CompletableDeferred<String?>()
        webView.evaluateJavascript(js) { value -> deferred.complete(value) }
        return try { deferred.await() } catch (_: Exception) { null }
    }

    /**
     * Парсит результат evaluateJavascript.
     * WebView возвращает JS-объект как JSON-строку.
     * Для объектов: rawResult = "{\"found\":true,...}" (уже валидный JSON)
     * Для строк: rawResult = "\"...\""
     */
    private fun parseJsObject(rawResult: String): JSONObject? {
        val s = rawResult.trim()
        return try {
            when {
                s.startsWith("{") -> JSONObject(s)
                s.startsWith("\"") -> {
                    // Строка в кавычках — убираем кавычки и анэскейпим
                    val unquoted = s.removeSurrounding("\"")
                        .replace("\\\"", "\"")
                        .replace("\\\\", "\\")
                    JSONObject(unquoted)
                }
                else -> null
            }
        } catch (e: Exception) {
            Log.w("AutoCaptcha", "JSON parse fail: $e | raw=$s")
            null
        }
    }

    // ── Нативный тап через MotionEvent (isTrusted=true в JS) ──────────────────

    private fun simulateTap(view: WebView, x: Float, y: Float) {
        val props = arrayOf(MotionEvent.PointerProperties().also {
            it.id = 0
            it.toolType = MotionEvent.TOOL_TYPE_FINGER
        })
        val coords = arrayOf(MotionEvent.PointerCoords().also {
            it.x = x; it.y = y; it.pressure = 1f; it.size = 1f
        })

        val downTime = SystemClock.uptimeMillis()
        val holdMs = 60L + Random.nextLong(50)

        fun event(action: Int, dt: Long) = MotionEvent.obtain(
            downTime, downTime + dt, action,
            1, props, coords,
            0, 0, 1f, 1f, 0, 0,
            InputDevice.SOURCE_TOUCHSCREEN, 0
        )

        view.dispatchTouchEvent(event(MotionEvent.ACTION_DOWN, 0)).also {
            Log.d("AutoCaptcha", "ACTION_DOWN dispatched at ($x,$y): $it")
        }
        view.dispatchTouchEvent(event(MotionEvent.ACTION_UP, holdMs)).also {
            Log.d("AutoCaptcha", "ACTION_UP dispatched: $it")
        }
    }

    // ── Нативный свайп (с реальными задержками между событиями) ──────────────

    private suspend fun simulateSwipe(view: WebView, startX: Float, y: Float, trackWidth: Float) {
        val endX = startX + trackWidth * 0.88f
        val steps = 25
        val stepDelayMs = 18L  // ~25 событий × 18мс ≈ 450мс реального свайпа

        val props = arrayOf(MotionEvent.PointerProperties().also {
            it.id = 0
            it.toolType = MotionEvent.TOOL_TYPE_FINGER
        })

        var downTime = SystemClock.uptimeMillis()

        fun coords(cx: Float, cy: Float) = arrayOf(MotionEvent.PointerCoords().also {
            it.x = cx; it.y = cy; it.pressure = 1f; it.size = 1f
        })
        fun event(action: Int, cx: Float, cy: Float, eventTime: Long) = MotionEvent.obtain(
            downTime, eventTime, action,
            1, props, coords(cx, cy),
            0, 0, 1f, 1f, 0, 0,
            InputDevice.SOURCE_TOUCHSCREEN, 0
        )

        // ACTION_DOWN
        downTime = SystemClock.uptimeMillis()
        view.dispatchTouchEvent(event(MotionEvent.ACTION_DOWN, startX, y, downTime))
        Log.d("AutoCaptcha", "Swipe ACTION_DOWN ($startX, $y)")

        // ACTION_MOVE — с реальными задержками для корректного velocity tracker
        for (i in 1..steps) {
            delay(stepDelayMs)
            val t = i.toFloat() / steps
            val eased = t * t * (3 - 2 * t)  // ease-in-out
            val cx = startX + (endX - startX) * eased
            val jitter = (Random.nextFloat() - 0.5f) * 1.5f
            val eventTime = SystemClock.uptimeMillis()
            view.dispatchTouchEvent(event(MotionEvent.ACTION_MOVE, cx, y + jitter, eventTime))
        }

        // ACTION_UP
        delay(stepDelayMs)
        val upTime = SystemClock.uptimeMillis()
        view.dispatchTouchEvent(event(MotionEvent.ACTION_UP, endX, y, upTime))
        Log.d("AutoCaptcha", "Swipe ACTION_UP ($endX, $y), total=${upTime - downTime}ms")
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
        ManlCaptchaWebViewManager.clearCaptchaNotification(context)
    }
}
