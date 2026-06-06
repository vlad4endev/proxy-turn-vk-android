package com.wdtt.client

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext
import java.io.File

import androidx.compose.runtime.Stable
import com.wdtt.client.xray.XrayHelper
import com.wdtt.client.xray.parseVlessUri

enum class ConnectionStage {
    IDLE,
    STARTING,
    VK_CREDS,
    VK_CAPTCHA,
    SERVER_DTLS,
    VPN_READY,
    FAILED
}

enum class TunnelMode {
    WHITELIST,
    SPEED;

    companion object {
        fun from(raw: String): TunnelMode = when (raw.lowercase()) {
            "speed" -> SPEED
            else -> WHITELIST
        }
    }
}

enum class LogCategory {
    SYSTEM,
    VK,
    CAPTCHA,
    TURN,
    WRAP,
    SERVER,
    VPN,
    STATS,
    NETWORK,
    DEPLOY,
    ERROR
}

@Stable
data class LogEntry(
    val key: String,
    val message: String,
    val count: Int = 1,
    val priority: Int = 99, // 0 - Creds, 1 - DTLS, 2 - Ready, 3 - Stats, 99 - Errors/Other
    val isError: Boolean = false,
    val category: LogCategory = LogCategory.SYSTEM,
    val timestampMs: Long = System.currentTimeMillis(),
    val lastSeenMs: Long = timestampMs
)

@Stable
data class TunnelConnectionSnapshot(
    val peer: String = "",
    val listen: String = "",
    val hashMode: String = "",
    val hashCount: Int = 0,
    val workers: Int = 0,
    val captchaMode: String = "",
    val captchaSolve: String = ""
)

object TunnelManager {
    // 100% защита от утечек: единый управляемый глобальный Scope
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Таймаут этапа авторизации Яндекс Телемост и текст ошибки для пользователя.
    private const val YANDEX_AUTH_TIMEOUT_MS = 30_000L
    private const val YANDEX_AUTH_TIMEOUT_MSG =
        "Не удалось подключиться к серверу Яндекс. Проверьте ссылку и попробуйте снова."

    private var process: Process? = null
    private var readerJob: Job? = null
    private var watchdogJob: Job? = null
    private var yandexAuthTimeoutJob: Job? = null
    @Volatile
    private var yandexCredsOk = false
    private var wgHelper: WireGuardHelper? = null
    private var xrayHelper: XrayHelper? = null
    @Volatile
    private var wireGuardStarted = false

    // Error counters for circuit breaker
    private var floodCount = 0
    private var mismatchCount = 0
    private var refusedCount = 0
    private var currentHashErrorCount = 0
    private var wrapAuthTimeoutCount = 0
    var processStartedAtMs = 0L
    /** Момент, когда Go-клиент выдал WireGuard-конфиг и мы начали поднимать VPN. */
    var wireGuardExpectedAtMs = 0L
    private var lastActiveAtMs = 0L
    private var activeHashIndex = 0 // 0: primary, 1: secondary
    private var currentParams: TunnelParams? = null
    private var lastContext: Context? = null
    private var forceRegenerateUA = false // принудительная перегенерация UA при ошибках
    private var currentLinkProvider = LinkProvider.UNKNOWN

    @Volatile
    var isLoggingEnabled = true

    @Volatile
    private var startInProgress = false

    @Volatile
    private var transportRestartInProgress = false

    val running = MutableStateFlow(false)
    val logs = MutableStateFlow<List<LogEntry>>(emptyList())
    val unreadErrorCount = MutableStateFlow(0)
    val config = MutableStateFlow<String?>(null)
    val stats = MutableStateFlow("Ожидание данных...")
    val activeWorkers = MutableStateFlow(0)
    val connectionStage = MutableStateFlow(ConnectionStage.IDLE)
    val connectionHint = MutableStateFlow("")
    val connectionSnapshot = MutableStateFlow(TunnelConnectionSnapshot())
    val connectedSince = MutableStateFlow(0L)
    val tunnelMode = MutableStateFlow(TunnelMode.WHITELIST)

    val cooldownActive = MutableStateFlow(false)
    private var cooldownJob: Job? = null

    private val _showCaptchaModal = MutableStateFlow(false)
    val showCaptchaModal: StateFlow<Boolean> = _showCaptchaModal

    fun showCaptchaModal() {
        _showCaptchaModal.value = true
    }

    fun dismissCaptchaModal() {
        _showCaptchaModal.value = false
    }

    fun onCaptchaSolved() {
        _showCaptchaModal.value = false
        updateLog("captcha_done", "[КАПЧА] Решена ✓", 5, false)
    }

    fun clearUnreadErrors() {
        unreadErrorCount.value = 0
    }

    // Добавляем лог с Деплоя
    fun addDeployErrorLog(message: String) {
        val hash = message.hashCode().toString()
        updateLog("deploy_err_$hash", "[ДЕПЛОЙ] $message", 99, true)
    }

    fun addDeploySuccessLog(message: String) {
        val hash = message.hashCode().toString() + System.currentTimeMillis()
        updateLog("deploy_ok_$hash", message, 2, false)
    }

    private fun categorizeLog(message: String, isError: Boolean): LogCategory = when {
        message.startsWith("[ДЕПЛОЙ]") -> LogCategory.DEPLOY
        message.startsWith("[ВК]") || message.contains("[VK", ignoreCase = true) -> LogCategory.VK
        message.startsWith("[ЯНДЕКС]") -> LogCategory.VK
        message.startsWith("[КАПЧА") -> LogCategory.CAPTCHA
        message.startsWith("[TURN]") -> LogCategory.TURN
        message.startsWith("[WRAP]") -> LogCategory.WRAP
        message.startsWith("[СЕРВЕР]") || message.startsWith("[КЛИЕНТ]") -> LogCategory.SERVER
        message.startsWith("[READY]") -> LogCategory.VPN
        message.startsWith("[СТАТИСТИКА]") -> LogCategory.STATS
        message.startsWith("[СЕТЬ]") || message.startsWith("[СТОП]") -> LogCategory.NETWORK
        isError -> LogCategory.ERROR
        else -> LogCategory.SYSTEM
    }

    private fun isVkCaptchaFlow(): Boolean = currentLinkProvider != LinkProvider.YANDEX

    private fun updateLog(key: String, message: String, priority: Int, isError: Boolean = false) {
        if (!isLoggingEnabled) return
        val category = categorizeLog(message, isError)
        val now = System.currentTimeMillis()
        if (isError) {
            val list = logs.value
            if (list.none { it.key == key }) {
                unreadErrorCount.value++
            }
        }
        logs.update { currentList ->
            val current = currentList.toMutableList()
            val index = current.indexOfFirst { it.key == key }

            if (index != -1) {
                val entry = current[index]
                current[index] = entry.copy(
                    count = entry.count + 1,
                    message = message,
                    priority = priority,
                    isError = isError,
                    category = category,
                    lastSeenMs = now
                )
            } else {
                current.add(LogEntry(key, message, 1, priority, isError, category, now, now))
            }

            // Сортировка: по приоритету (наименьший сверху), затем ошибки
            // Приоритеты: Основной=1, Капча=5, Готов=10, Статы=100, Ошибки=200
            val sorted = current.sortedWith(compareBy({ it.priority }, { if (it.isError) 1 else 0 }, { it.key }))

            // Лимит 100 записей
            if (sorted.size > 100) sorted.takeLast(100) else sorted
        }
    }

    /** Сервис можно останавливать только когда туннель реально выключен или упал. */
    fun shouldStopService(): Boolean = when (connectionStage.value) {
        ConnectionStage.IDLE, ConnectionStage.FAILED -> true
        else -> false
    }

    fun start(context: Context, params: TunnelParams, isSwitching: Boolean = false) {
        if (running.value && !isSwitching) return
        if (!isSwitching && startInProgress) return
        
        val appContext = context.applicationContext // Защита от Memory Leak
        
        if (!isSwitching) {
            startInProgress = true
            clearLogs()
            connectionStage.value = ConnectionStage.STARTING
            connectionHint.value = ""
            config.value = null
            stats.value = "Ожидание данных..."
            floodCount = 0
            mismatchCount = 0
            refusedCount = 0
            currentHashErrorCount = 0
            wrapAuthTimeoutCount = 0
            processStartedAtMs = 0L
            wireGuardExpectedAtMs = 0L
            wireGuardStarted = false
            yandexCredsOk = false
            connectedSince.value = 0L
            lastActiveAtMs = 0L
            activeHashIndex = 0
            _showCaptchaModal.value = false
            currentParams = params
            lastContext = appContext
            forceRegenerateUA = false
        }
        
        wgHelper = WireGuardHelper(appContext)
        xrayHelper = XrayHelper(appContext)
        val mode = TunnelMode.from(params.tunnelMode)
        tunnelMode.value = mode

        scope.launch {
            try {
                if (mode == TunnelMode.SPEED) {
                    startSpeedMode(appContext, params)
                    return@launch
                }
                startWhitelistMode(appContext, params, isSwitching)
            } catch (e: Exception) {
                updateLog("critical_start_error", "Критическая ошибка запуска: ${e.message}", 99, true)
                e.printStackTrace()
                running.value = false
                connectionStage.value = ConnectionStage.FAILED
                startInProgress = false
                transportRestartInProgress = false
            }
        }
    }

    private suspend fun startSpeedMode(context: Context, params: TunnelParams) {
        val vlessUri = ServerConfig.VLESS_URI
        val vlessCfg = try {
            parseVlessUri(vlessUri)
        } catch (e: Exception) {
            updateLog("vless_parse_error", "Ошибка: неверный VLESS URI — ${e.message}", 99, true)
            connectionStage.value = ConnectionStage.FAILED
            connectionHint.value = "Неверный VLESS URI в ServerConfig"
            running.value = false
            startInProgress = false
            return
        }

        val endpoint = "${vlessCfg.serverHost}:${vlessCfg.serverPort}"

        connectionSnapshot.value = TunnelConnectionSnapshot(
            peer = endpoint,
            listen = "127.0.0.1:${xrayHelper?.socksPort() ?: 10808}",
            hashMode = "Скоростной (Xray)",
            hashCount = 0,
            workers = 0,
            captchaMode = "—",
            captchaSolve = "—"
        )
        updateLog("path_start", "[ПУТЬ] Телефон → ${vlessCfg.serverHost}:${vlessCfg.serverPort} (VLESS/${vlessCfg.network.uppercase()})", 0)
        updateLog("server_peer", "[СЕРВЕР] ${vlessCfg.serverHost}:${vlessCfg.serverPort} — ${vlessCfg.remark}", 1)
        connectionStage.value = ConnectionStage.STARTING
        connectionHint.value = "Запуск Xray (VLESS/${vlessCfg.network.uppercase()})…"
        running.value = true
        startInProgress = false
        transportRestartInProgress = false

        try {
            val helper = xrayHelper ?: XrayHelper(context).also { xrayHelper = it }
            val configJson = helper.generateConfig(vlessCfg)
            config.value = configJson
            helper.start(configJson)
            connectionStage.value = ConnectionStage.VPN_READY
            connectionHint.value = ""
            markConnectedIfNeeded()
            updateLog("ready", "[READY] Скоростной режим — Xray прокси активен ✓ (SOCKS5 :${helper.socksPort()})", 2, false)
        } catch (e: Exception) {
            running.value = false
            connectionStage.value = ConnectionStage.FAILED
            connectionHint.value = "Не удалось запустить Xray ($endpoint)"
            updateLog("vpn_start_error", "Ошибка запуска Xray: ${e.readableMessage()}", 99, true)
        }
    }

    private suspend fun startWhitelistMode(context: Context, params: TunnelParams, isSwitching: Boolean) {
            try {
                val vkLink = if (activeHashIndex == 0) params.vkHashes else params.secondaryVkHash
                val linkProvider = YandexParser.detectProvider(vkLink).let { detected ->
                    if (detected != LinkProvider.UNKNOWN) detected
                    else when (params.provider.lowercase()) {
                        "yandex" -> LinkProvider.YANDEX
                        "vk" -> LinkProvider.VK
                        else -> LinkProvider.UNKNOWN
                    }
                }
                currentLinkProvider = linkProvider

                val normalizedLink = when (linkProvider) {
                    LinkProvider.YANDEX -> YandexParser.normalizeLink(vkLink).ifBlank { vkLink }
                    LinkProvider.VK -> VkHashParser.parse(vkLink).ifBlank { vkLink }
                    LinkProvider.UNKNOWN -> vkLink
                }

                val isValidLink = when (linkProvider) {
                    LinkProvider.VK -> normalizedLink.contains("call/join/", ignoreCase = true) ||
                        normalizedLink.matches(Regex("[A-Za-z0-9_\\-]{10,}"))
                    LinkProvider.YANDEX -> YandexParser.parse(vkLink).isNotBlank() ||
                        vkLink.contains("/j/", ignoreCase = true) ||
                        vkLink.contains("telemost", ignoreCase = true)
                    LinkProvider.UNKNOWN -> vkLink.matches(Regex("[A-Za-z0-9_\\-]{10,}"))
                }

                if (vkLink.isBlank() || !isValidLink) {
                    updateLog("hash_error", "Ошибка: неверная ссылка на звонок", 99, true)
                    connectionStage.value = ConnectionStage.FAILED
                    connectionHint.value = "Укажите ссылку vk.com/call/join/... или telemost.yandex.ru/j/..."
                    running.value = false
                    startInProgress = false
                    transportRestartInProgress = false
                    return
                }

                val hashCount = 1
                val totalWorkers = params.workersPerHash.coerceIn(1, 128)
                
                val hashMode = if (activeHashIndex == 0) "Основной" else "Запасной"
                connectionSnapshot.value = TunnelConnectionSnapshot(
                    peer = params.peer,
                    listen = "127.0.0.1:${params.port}",
                    hashMode = hashMode,
                    hashCount = hashCount,
                    workers = totalWorkers,
                    captchaMode = params.captchaMode,
                    captchaSolve = params.captchaSolveMethod
                )
                updateLog("config_info", "[$hashMode] Хешей=$hashCount, Потоков=$totalWorkers", 1)
                val pathLabel = if (linkProvider == LinkProvider.YANDEX) {
                    "[ПУТЬ] Телефон → Яндекс → TURN → VPS ${params.peer} → Защита"
                } else {
                    "[ПУТЬ] Телефон → VK → TURN → VPS ${params.peer} → Защита"
                }
                updateLog("path_start", pathLabel, 0)
                updateLog("server_peer", "[СЕРВЕР] VPS ${params.peer} (шифрование/WRAP после получения кредов)", 1)
                connectionStage.value = ConnectionStage.VK_CREDS
                connectionHint.value = if (linkProvider == LinkProvider.YANDEX) {
                    "Шаг 1/2: авторизация Яндекс Телемост…"
                } else {
                    "Шаг 1/2: авторизация VK…"
                }

                val binaryPath = context.applicationInfo.nativeLibraryDir + "/libclient.so"
                val binaryFile = File(binaryPath)
                
                if (!binaryFile.exists()) {
                    updateLog("binary_error", "Ошибка: Бинарный файл не найден", 99, true)
                    connectionStage.value = ConnectionStage.FAILED
                    startInProgress = false
                    transportRestartInProgress = false
                    return
                }

                val providerFlag = params.provider.ifBlank {
                    when (linkProvider) {
                        LinkProvider.YANDEX -> "yandex"
                        else -> "vk"
                    }
                }

                val cmd = mutableListOf(
                    binaryPath,
                    "-peer", params.peer,
                    "-link", normalizedLink,
                    "-listen", "127.0.0.1:${params.port}",
                    "-obf-profile", "rtpopus",
                    "-obf-key", ServerConfig.OBF_KEY,
                    "-provider", providerFlag,
                    "-n", totalWorkers.toString()
                )

                val pb = ProcessBuilder(cmd)
                pb.directory(context.filesDir)
                pb.redirectErrorStream(true)
                
                val env = pb.environment()
                env["LD_LIBRARY_PATH"] = context.applicationInfo.nativeLibraryDir

                process = pb.start()
                processStartedAtMs = System.currentTimeMillis()
                wrapAuthTimeoutCount = 0
                lastActiveAtMs = 0L
                running.value = true
                startInProgress = false
                transportRestartInProgress = false
                startLogReader()
                startWatchdog(context, params)
                if (linkProvider == LinkProvider.YANDEX) {
                    startYandexAuthTimeout()
                }

            } catch (e: Exception) {
                updateLog("critical_start_error", "Критическая ошибка запуска: ${e.message}", 99, true)
                e.printStackTrace()
                running.value = false
                connectionStage.value = ConnectionStage.FAILED
                startInProgress = false
                transportRestartInProgress = false
            }
    }

    private fun startLogReader() {
        readerJob = scope.launch {
            val reader = process?.inputStream?.bufferedReader() ?: return@launch
            var collectingConfig = false
            val configBuilder = StringBuilder()

            try {
                var lastResetTime = System.currentTimeMillis()

                reader.forEachLine { line ->
                    val now = System.currentTimeMillis()
                    if (now - lastResetTime > 60000) {
                        refusedCount = 0
                        floodCount = 0
                        mismatchCount = 0
                        currentHashErrorCount = 0
                        lastResetTime = now
                    }

                    val msgPrefixReplaced = line.replace(Regex("^\\d{4}/\\d{2}/\\d{2}\\s\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?\\s"), "")
                    val lineTrim = msgPrefixReplaced.trim()
                    mapGoLineToStage(lineTrim)

                    val isError = lineTrim.contains("Ошибка", true) || lineTrim.contains("error", true) || lineTrim.contains("FAIL", true) || lineTrim.contains("timeout", true) || lineTrim.contains("refused", true) || lineTrim.contains("FATAL_AUTH", true)

                    if (lineTrim.contains("FATAL_AUTH")) {
                        val isWrapHandshakeTimeout = lineTrim.contains("DTLS timeout", true) ||
                            lineTrim.contains("WRAP_AUTH_TIMEOUT", true)
                        if (isWrapHandshakeTimeout) {
                            if (activeWorkers.value > 0) {
                                wrapAuthTimeoutCount = 0
                                updateLog(
                                    "wrap_timeout_recovered",
                                    "[WRAP] Один поток не прошёл handshake, активных=${activeWorkers.value}; повторяем",
                                    50,
                                    true
                                )
                            } else {
                                wrapAuthTimeoutCount++
                                updateLog(
                                    "wrap_timeout_wait",
                                    "[WRAP] Handshake не подтвердился, проверяем пароль/сеть ($wrapAuthTimeoutCount)",
                                    50,
                                    true
                                )
                            }
                            return@forEachLine
                        }

                        val reason = when {
                            lineTrim.contains("неверный пароль") -> "Неверный пароль подключения"
                            lineTrim.contains("истёк") -> "Срок действия пароля истёк"
                            lineTrim.contains("другому устройству") -> "Пароль привязан к другому устройству"
                            else -> "Ошибка авторизации"
                        }
                        handleCriticalError("\uD83D\uDD12 $reason. Воркеры остановлены.")
                        return@forEachLine
                    }

                    if (lineTrim.contains("WRAP_AUTH_TIMEOUT", true)) {
                        if (activeWorkers.value > 0) {
                            wrapAuthTimeoutCount = 0
                            updateLog(
                                "wrap_timeout_recovered",
                                "[WRAP] Один поток не прошёл handshake, активных=${activeWorkers.value}; повторяем",
                                    50,
                                    true
                            )
                        } else {
                            wrapAuthTimeoutCount++
                            updateLog(
                                "wrap_timeout_wait",
                                "[WRAP] Handshake не подтвердился, проверяем пароль/сеть ($wrapAuthTimeoutCount)",
                                    50,
                                    true
                            )
                        }
                        return@forEachLine
                    }

                    if (isError) {
                        when {
                            lineTrim.contains("Flood control", true) -> {
                                floodCount++
                                if (floodCount >= 5) {
                                    handleCriticalError("Flood Control (ВК ограничил ваш IP). Попробуйте позже.")
                                    return@forEachLine
                                }
                            }
                            lineTrim.contains("ip mismatch", true) -> {
                                mismatchCount++
                                if (mismatchCount >= 5) {
                                    handleCriticalError("IP Mismatch (IP утерян). Попробуйте переподключиться.")
                                    return@forEachLine
                                }
                            }
                            lineTrim.contains("connection refused", true) || lineTrim.contains("timeout", true) -> {
                                refusedCount++
                                if (refusedCount >= 400) {
                                    handleCriticalError("Критическое отсутствие сети (400+ таймаутов). Отключение.")
                                    return@forEachLine
                                }
                            }
                            lineTrim.contains("9000") || lineTrim.contains("Call not found", true) -> {
                                currentHashErrorCount++
                                if (currentHashErrorCount >= 10) {
                                    handleHashError()
                                    return@forEachLine
                                }
                            }
                        }
                    }

                    if (lineTrim.contains("[СТАТИСТИКА]")) {
                        val msg = lineTrim.substringAfter("[СТАТИСТИКА]").trim()
                        stats.value = msg

                        val match = Regex("Активных:\\s*(\\d+)").find(msg)
                        if (match != null) {
                            val active = match.groupValues[1].toIntOrNull() ?: 0
                            activeWorkers.value = active
                            if (active > 0) {
                                lastActiveAtMs = now
                                wrapAuthTimeoutCount = 0
                                markConnectedIfNeeded()
                            }
                        }

                        updateLog("stats", "[СТАТИСТИКА] $msg", 3, false)
                        return@forEachLine
                    }

                    when {
                        isVkCaptchaFlow() && lineTrim.contains("[КАПЧА] AUTO:") -> {
                            var text = lineTrim.substringAfter("[КАПЧА] AUTO:").trim()
                            text = text.replace(Regex("\\s*\\([^)]+\\)\\s*"), " ").trim()

                            val isErr = text.contains("ошибка", true) ||
                                text.contains("timeout", true) ||
                                text.contains("не решил", true)
                            val stableKey = when {
                                text.contains("старт") -> "captcha_auto_1"
                                text.contains("Go v2") && text.contains("2 попыт") -> "captcha_auto_2"
                                text.contains("WBV Auto попытка") -> "captcha_auto_3"
                                text.contains("финальная") -> "captcha_auto_4"
                                text.contains("ручной WebView") -> "captcha_auto_5"
                                text.contains("решил") || text.contains("решила") -> "captcha_auto_done"
                                else -> "captcha_auto_${text.take(18).hashCode()}"
                            }
                            updateLog(stableKey, "[КАПЧА AUTO] $text", 5, isErr)
                        }

                        isVkCaptchaFlow() && lineTrim.contains("[КАПЧА] RJS:") -> {
                            var text = lineTrim.substringAfter("[КАПЧА] RJS:").trim()
                            text = text.replace(Regex("\\s*\\([^)]+\\)\\s*"), " ").trim()
                            
                            val stableKey = when {
                                text.contains("Загрузка") || text.contains("fetch") -> "captcha_rjs_1"
                                text.contains("PoW") -> "captcha_rjs_2"
                                text.contains("осматривает") || text.contains("человек") -> "captcha_rjs_3"
                                text.contains("captchaNotRobot") || text.contains("Отправка") -> "captcha_rjs_4"
                                text.contains("endSession") -> "captcha_rjs_5"
                                text.contains("решена") -> "captcha_rjs_6"
                                else -> "captcha_rjs_${text.take(15).hashCode()}"
                            }
                            updateLog(stableKey, "[КАПЧА RJS] $text", 5, false)
                        }

                        isVkCaptchaFlow() && lineTrim.contains("[КАПЧА] WBV:") -> {
                            var text = lineTrim.substringAfter("[КАПЧА] WBV:").trim()
                            text = text.replace(Regex("\\s*\\([^)]+\\)\\s*"), " ").trim()
                            
                            val isErr = text.contains("Ошибка")
                            val stableKey = when {
                                text.contains("Запрос") -> "captcha_wv_step_2"
                                text.contains("Токен") -> "captcha_wv_step_5"
                                isErr -> "captcha_wv_err"
                                else -> "captcha_wv_go_other"
                            }
                            updateLog(stableKey, "[КАПЧА WBV] $text", 5, isErr)
                        }

                        lineTrim.contains("Старт") || lineTrim.contains("Ожидайте") ||
                            (lineTrim.contains("[ГРУППА") && lineTrim.contains("Запрос кредов")) ->
                            updateLog("creds_start", "[ВК] Получение учётных данных VK…", 2, false)
                        lineTrim.contains("Креды получены") ->
                            updateLog("creds_lifetime", lineTrim, 2, false)
                        lineTrim.contains("[ГРУППА") && lineTrim.contains("Креды OK") -> {
                            val tail = lineTrim.substringAfter("Креды OK")
                                .trim().trimStart('—', '-').trim()
                            updateLog("creds_ok", "[ВК] Креды OK ✓${if (tail.isNotBlank()) " — $tail" else ""}", 2, false)
                        }
                        lineTrim.contains("Креды OK") || lineTrim.contains("Первые креды") ->
                            updateLog("creds_ok", "[ВК] Учётные данные VK получены ✓", 2, false)
                        lineTrim.contains("[ГРУППА") && lineTrim.contains("Успешный старт") ->
                            updateLog("group_relay_ok", "[TURN] Группа на relay, эстафета следующей…", 2, false)
                        lineTrim.contains("[ДИСП]") && lineTrim.contains("зарегистрирован") -> {
                            val m = Regex("""#(\d+)\s+зарегистрирован\s+\(всего:\s*(\d+)\)""").find(lineTrim)
                            val detail = if (m != null) {
                                "воркер #${m.groupValues[1]} на VPS (всего ${m.groupValues[2]})"
                            } else {
                                lineTrim.substringAfter("[ДИСП]").trim()
                            }
                            updateLog("disp_reg", "[СЕРВЕР] $detail", 1, false)
                        }
                        lineTrim.contains("[ГРУППА") && lineTrim.contains("Ошибка кредов") -> {
                            val detail = lineTrim.substringAfter("Ошибка кредов:", lineTrim).trim()
                            updateLog("vk_creds_fatal", "[ВК] $detail", 99, true)
                        }
                        lineTrim.contains("[STREAM") && lineTrim.contains("[VK Auth]") -> {
                            val text = lineTrim.substringAfter("[VK Auth]").trim().ifBlank { lineTrim }
                            val isVkErr = text.contains("Failed", true) ||
                                text.contains("error", true) ||
                                text.contains("Rate limit", true)
                            updateLog("vk_auth_${text.take(24).hashCode()}", "[ВК] $text", 2, isVkErr)
                        }
                        lineTrim.contains("[Yandex") && lineTrim.contains("TURN creds OK") -> {
                            // Аналог «Креды OK» для VK — успешная авторизация Яндекс.
                            yandexCredsOk = true
                            yandexAuthTimeoutJob?.cancel()
                            updateLog(
                                "creds_ok",
                                "[ЯНДЕКС] Авторизация Яндекс Телемост ✓ — TURN получен",
                                2,
                                false
                            )
                            if (connectionStage.value == ConnectionStage.VK_CREDS ||
                                connectionStage.value == ConnectionStage.STARTING
                            ) {
                                connectionStage.value = ConnectionStage.SERVER_DTLS
                                connectionHint.value = "Шаг 2/2: подключение к VPS (шифрование)…"
                            }
                        }
                        lineTrim.contains("[Yandex] timeout") -> {
                            handleCriticalError(YANDEX_AUTH_TIMEOUT_MSG)
                            return@forEachLine
                        }
                        lineTrim.contains("[STREAM") && lineTrim.contains("[Yandex") -> {
                            val text = lineTrim.substringAfter("[Yandex").trim().ifBlank { lineTrim }
                            val yandexErr = text.contains("timeout", true) ||
                                text.contains("captcha", true) ||
                                text.contains("ошибка", true) ||
                                text.contains("не выполнен", true) ||
                                text.contains("status=4", true) ||
                                text.contains("status=5", true)
                            updateLog("yandex_auth_${text.take(24).hashCode()}", "[ЯНДЕКС] $text", 2, yandexErr)
                        }
                        isVkCaptchaFlow() && (
                            lineTrim.contains("8765") ||
                            lineTrim.contains("Opening browser") ||
                            lineTrim.contains("manual-captcha", ignoreCase = true) ||
                            lineTrim.contains("proxy HTTP server")
                        ) -> {
                            _showCaptchaModal.value = true
                            updateLog(
                                "captcha_modal",
                                "[КАПЧА] Пройдите проверку в окне",
                                5,
                                false
                            )
                        }
                        isVkCaptchaFlow() && lineTrim.contains("Решаю VK Smart Captcha") ->
                            updateLog("captcha_start", "[КАПЧА] Решение капчи...", 5, false)
                        isVkCaptchaFlow() && lineTrim.contains("Smart Captcha решена") ->
                            updateLog("captcha_done", "[КАПЧА] Капча решена ✓", 5, false)
                        isVkCaptchaFlow() && (
                            lineTrim.contains("капча не решена") || lineTrim.contains("ошибка решения капчи")
                        ) ->
                            updateLog("captcha_failed", "[КАПЧА] Ошибка решения капчи", 5, true)
                        // Английские строки реального Go-бинаря (Схема A: Go решает сам).
                        // Специфичные матчеры идут раньше общего "[Captcha]".
                        isVkCaptchaFlow() && lineTrim.contains("received success token") -> {
                            _showCaptchaModal.value = false
                            updateLog("captcha_done", "[КАПЧА] Решена ✓", 5, false)
                        }
                        isVkCaptchaFlow() && lineTrim.contains("Auto captcha failed") ->
                            updateLog("captcha_auto_fail", "[КАПЧА] Авто не удалась, ручной режим", 5, false)
                        isVkCaptchaFlow() && lineTrim.contains("[Captcha]") -> {
                            connectionStage.value = ConnectionStage.VK_CAPTCHA
                            connectionHint.value = "VK запросил капчу — не закрывайте приложение"
                            updateLog("captcha_start", "[КАПЧА] Решение капчи...", 5, false)
                        }
                        lineTrim.contains("[WRAP]") -> {
                            val text = lineTrim.substringAfter("[WRAP]").trim()
                            updateLog("wrap_status", "[WRAP] $text", 1, false)
                        }
                        lineTrim.contains("[TURN]") -> {
                            val text = lineTrim.substringAfter("[TURN]").trim()
                            val turnError = text.contains("Ошибка", true) ||
                                text.contains("не удалось", true) ||
                                text.contains("неполный ответ", true)
                            updateLog("turn_${text.take(32).hashCode()}", "[TURN] $text", 2, turnError)
                        }
                        lineTrim.contains("Relay:") ||
                            (lineTrim.contains("[ВОРКЕР") && lineTrim.contains("[DTLS]") && lineTrim.contains("Handshake")) ->
                            updateLog("dtls_start", "[СЕРВЕР] Рукопожатие шифрования с VPS…", 1, false)
                        lineTrim.contains("DTLS ОК") ||
                            (lineTrim.contains("[ВОРКЕР") && lineTrim.contains("DTLS") && lineTrim.contains("установлено")) ->
                            updateLog("dtls_ok", "[СЕРВЕР] Защищённое соединение с VPS установлено ✓", 1, false)
                        lineTrim.contains("[КЛИЕНТ]") && (
                            lineTrim.contains("Пир:") || lineTrim.contains("Слушаю:") ||
                                lineTrim.contains("Воркеров:") || lineTrim.contains("Хешей:") ||
                                lineTrim.contains("Протокол:") || lineTrim.contains("WRAP:") ||
                                lineTrim.contains("Device ID:")
                            ) -> {
                            val stableKey = when {
                                lineTrim.contains("Пир:") -> "client_peer"
                                lineTrim.contains("Слушаю:") -> "client_listen"
                                lineTrim.contains("Воркеров:") -> "client_workers"
                                else -> "client_boot_${lineTrim.take(24).hashCode()}"
                            }
                            updateLog(stableKey, "[КЛИЕНТ] ${lineTrim.substringAfter("[КЛИЕНТ]").trim()}", 1, false)
                        }
                        lineTrim.contains("Активна ✓") -> {
                            updateLog("ready", "[READY] Туннель готов к работе ✓", 2, false)
                            launchWireGuardIfNeeded()
                        }
                        
                        isError -> {
                            val errorKey = when {
                                lineTrim.contains("lookup login.vk.ru", true) -> "err_vk_dns"
                                lineTrim.contains("connection refused") -> "err_conn_refused"
                                lineTrim.contains("timeout") -> "err_timeout"
                                lineTrim.contains("кредов") -> "err_creds"
                                lineTrim.contains("DTLS") -> "err_dtls"
                                else -> "general_error_" + lineTrim.take(15).hashCode()
                            }
                            val errorMessage = if (errorKey == "err_vk_dns") {
                                "[СЕТЬ] DNS до VK недоступен: login.vk.ru"
                            } else {
                                lineTrim
                            }
                            updateLog(errorKey, errorMessage, 99, true)
                        }
                    }

                    if (lineTrim.contains("[READY]", true) && !lineTrim.contains("Активна ✓")) {
                        launchWireGuardIfNeeded()
                    }

                    if (line.contains("╔") && line.contains("WireGuard")) {
                        collectingConfig = true
                        configBuilder.clear()
                        return@forEachLine
                    } else if (collectingConfig) {
                        if (line.contains("╚")) {
                            collectingConfig = false
                            val configStr = WireGuardHelper.ensureWireGuardMtu(configBuilder.toString().trim())
                            if (configStr.isNotEmpty()) {
                                config.value = configStr
                            }
                            launchWireGuardIfNeeded()
                        } else if (line.contains("║")) {
                            val content = line.replace("║", "").trim()
                            if (content.isNotEmpty()) {
                                configBuilder.appendLine(content)
                            }
                        }
                        return@forEachLine
                    }
                }
            } catch (e: Exception) {
                updateLog("sys_error", "Процесс остановлен: ${e.message}", -1, true)
            } finally {
                if (!transportRestartInProgress) {
                    running.value = false
                }
                process = null
            }
        }
    }

    private fun markConnectedIfNeeded() {
        if (connectedSince.value == 0L) {
            connectedSince.value = System.currentTimeMillis()
        }
    }

    private fun launchWireGuardIfNeeded() {
        if (wireGuardStarted || !running.value) return
        wireGuardStarted = true
        val configStr = WireGuardHelper.ensureWireGuardMtu(ServerConfig.WG_CONFIG.trim())
        config.value = configStr
        wireGuardExpectedAtMs = System.currentTimeMillis()
        markConnectedIfNeeded()
        scope.launch(Dispatchers.Main) {
            try {
                wgHelper?.startTunnel(configStr)
            } catch (e: Exception) {
                wireGuardStarted = false
                wireGuardExpectedAtMs = 0L
                updateLog("vpn_start_error", "Ошибка запуска VPN: ${e.readableMessage()}", 99, true)
            }
        }
    }

    private fun handleCriticalError(message: String) {
        updateLog("circuit_breaker", "[СТОП] $message", -1, true)
        stop()
    }

    private fun handleHashError() {
        val params = currentParams ?: return
        val context = lastContext ?: return

        currentHashErrorCount = 0
        forceRegenerateUA = true

        if (params.secondaryVkHash.isNotEmpty() && activeHashIndex == 0) {
            updateLog("hash_switch", "Основной хеш мертв. Переключение на запасной...", 50, true)
            activeHashIndex = 1
            stopOnlyProcess()
            start(context, params, isSwitching = true)
        } else {
            val msg = if (activeHashIndex == 1) "Запасной хеш тоже мертв. Отключение." else "Хеш умер, запасного нет. Отключение."
            handleCriticalError(msg)
        }
    }

    /**
     * Сторож этапа авторизации Яндекс Телемост: если за 30 секунд Go-клиент не
     * получил TURN-креды (нет строки «[Yandex] TURN creds OK»), показываем
     * понятную пользователю ошибку вместо вечного зависания на «Авторизации».
     */
    private fun startYandexAuthTimeout() {
        yandexAuthTimeoutJob?.cancel()
        yandexCredsOk = false
        yandexAuthTimeoutJob = scope.launch {
            delay(YANDEX_AUTH_TIMEOUT_MS)
            if (!isActive) return@launch
            if (!yandexCredsOk &&
                running.value &&
                activeWorkers.value <= 0 &&
                connectionStage.value != ConnectionStage.VPN_READY
            ) {
                handleCriticalError(YANDEX_AUTH_TIMEOUT_MSG)
            }
        }
    }

    private fun startWatchdog(context: Context, params: TunnelParams) {
        watchdogJob?.cancel()
        watchdogJob = scope.launch {
            var zeroWorkersSince = 0L
            while (isActive && running.value) {
                delay(30_000)
                if (!isActive || !running.value) break

                val proc = process
                if (proc == null || !proc.isAlive) {
                    restartClientSilently(context, params)
                    return@launch
                }

                if (
                    _showCaptchaModal.value ||
                    ManlCaptchaWebViewManager.isCaptchaPending ||
                    CaptchaWebViewActivityLauncher.isCaptchaPending
                ) {
                    zeroWorkersSince = 0L
                    continue
                }

                if (activeWorkers.value > 0) {
                    zeroWorkersSince = 0L
                    continue
                }

                // До первых активных воркеров (VK/TURN/DTLS) нулевое значение нормально.
                if (lastActiveAtMs == 0L) continue

                if (zeroWorkersSince == 0L) {
                    zeroWorkersSince = System.currentTimeMillis()
                    continue
                }

                if (System.currentTimeMillis() - zeroWorkersSince >= 60_000) {
                    restartClientSilently(context, params)
                    return@launch
                }
            }
        }
    }

    private fun restartClientSilently(context: Context, params: TunnelParams) {
        scheduleTransportRestart(context, params)
    }

    private fun scheduleTransportRestart(
        context: Context,
        params: TunnelParams,
        logMessage: String? = null
    ) {
        if (transportRestartInProgress) return
        transportRestartInProgress = true
        logMessage?.let { updateLog("network_restart", it, 50, false) }
        activeWorkers.value = 0
        killProcess()
        scope.launch {
            delay(1500)
            start(context, params, isSwitching = true)
        }
    }

    fun restartTransport() {
        if (tunnelMode.value == TunnelMode.SPEED) return
        val params = currentParams ?: return
        val context = lastContext ?: return
        scheduleTransportRestart(
            context,
            params,
            "[СЕТЬ] Перезапуск транспорта из-за смены сети..."
        )
    }

    fun pause() {
        if (!running.value || tunnelMode.value == TunnelMode.SPEED) return
        killProcess()
        activeWorkers.value = 0
    }

    fun resume() {
        if (tunnelMode.value == TunnelMode.SPEED) return
        val params = currentParams ?: return
        val context = lastContext ?: return
        scheduleTransportRestart(context, params)
    }

    private fun killProcess() {
        watchdogJob?.cancel()
        readerJob?.cancel()
        yandexAuthTimeoutJob?.cancel()
        val proc = process
        process = null
        if (proc != null) {
            try { proc.destroy() } catch (_: Exception) {}
            try { proc.waitFor(500, java.util.concurrent.TimeUnit.MILLISECONDS) } catch (_: Exception) {}
            if (proc.isAlive) {
                try { proc.destroyForcibly() } catch (_: Exception) {}
                try { proc.waitFor(1000, java.util.concurrent.TimeUnit.MILLISECONDS) } catch (_: Exception) {}
            }
        }
    }

    private fun stopOnlyProcess() {
        wireGuardStarted = false
        killProcess()
        running.value = false
    }

    private fun mapGoLineToStage(line: String) {
        when {
            (line.contains("[КАПЧА]", true) || line.contains("Captcha", true) ||
                line.contains("CAPTCHA", true)) && isVkCaptchaFlow() -> {
                connectionStage.value = ConnectionStage.VK_CAPTCHA
                connectionHint.value = "VK запросил капчу — не закрывайте приложение"
            }
            line.contains("[ГРУППА") && line.contains("Запрос кредов") ||
                line.contains("[VK Auth]", true) ||
                line.contains("[ВК]", true) && line.contains("Получение", true) -> {
                connectionStage.value = ConnectionStage.VK_CREDS
                connectionHint.value = "Шаг 1/2: авторизация VK…"
            }
            line.contains("[ГРУППА") && line.contains("Креды OK") -> {
                connectionStage.value = ConnectionStage.SERVER_DTLS
                connectionHint.value = "Шаг 2/2: подключение к VPS (шифрование)…"
            }
            line.contains("[Yandex") && line.contains("TURN creds OK") -> {
                connectionStage.value = ConnectionStage.SERVER_DTLS
                connectionHint.value = "Шаг 2/2: подключение к VPS (шифрование)…"
            }
            line.contains("[DTLS]", true) || line.contains("WRAP_AUTH", true) ||
                line.contains("[WRAP]", true) && line.contains("Handshake", true) -> {
                connectionStage.value = ConnectionStage.SERVER_DTLS
                connectionHint.value = "Шаг 2/2: VPS не отвечает — проверьте пароль и wdtt.service"
            }
            line.contains("[READY]", true) || line.contains("Активна ✓", true) -> {
                connectionStage.value = ConnectionStage.VPN_READY
                connectionHint.value = ""
                markConnectedIfNeeded()
            }
            line.contains("FATAL_AUTH", true) -> {
                connectionStage.value = ConnectionStage.FAILED
                connectionHint.value = "Пароль в APK ≠ пароль на VPS (deploy -password)"
            }
            line.contains("WRAP_AUTH_TIMEOUT", true) || line.contains("DTLS timeout", true) -> {
                if (activeWorkers.value <= 0) {
                    connectionHint.value = "VPS: неверный пароль, сервер выключен или UDP 56000 закрыт"
                }
            }
            line.contains("[ГРУППА") && line.contains("Ошибка кредов") -> {
                connectionStage.value = ConnectionStage.FAILED
                connectionHint.value = "Ссылка VK недействительна или звонок завершён"
            }
        }
    }

    fun stop() {
        startInProgress = false
        transportRestartInProgress = false
        scope.launch(Dispatchers.Main) {
            xrayHelper?.stop()
            wgHelper?.stopTunnel()
        }
        killProcess()
        running.value = false
        activeWorkers.value = 0
        wireGuardStarted = false
        wireGuardExpectedAtMs = 0L
        connectedSince.value = 0L
        connectionStage.value = ConnectionStage.IDLE
        connectionHint.value = ""
        connectionSnapshot.value = TunnelConnectionSnapshot()
        tunnelMode.value = TunnelMode.WHITELIST
        currentParams = null
        currentLinkProvider = LinkProvider.UNKNOWN
        _showCaptchaModal.value = false
        ManlCaptchaWebViewManager.cancelCaptcha()
        CaptchaWebViewActivityLauncher.dismiss()
    }

    suspend fun stopAndWait() {
        withContext(Dispatchers.Main) {
            xrayHelper?.stop()
            wgHelper?.stopTunnel()
        }
        withContext(Dispatchers.IO) {
            killProcess()
            running.value = false
            activeWorkers.value = 0
            wireGuardStarted = false
            connectedSince.value = 0L
            currentParams = null
            _showCaptchaModal.value = false
            ManlCaptchaWebViewManager.cancelCaptcha()
            CaptchaWebViewActivityLauncher.dismiss()
            repeat(30) {
                try {
                    java.net.ServerSocket(9000, 1, java.net.InetAddress.getByName("127.0.0.1")).use { it.close() }
                    return@withContext
                } catch (_: Exception) {
                    delay(100)
                }
            }
        }
    }

    fun reloadWireGuard() {
        if (running.value) {
            scope.launch {
                wgHelper?.reloadTunnel()
            }
        }
    }

    fun clearLogs() {
        logs.value = emptyList()
        if (!running.value) {
            activeWorkers.value = 0
        }
    }

    fun startCooldown(millis: Long) {
        cooldownJob?.cancel()
        cooldownActive.value = true
        cooldownJob = scope.launch(Dispatchers.Main) {
            delay(millis)
            cooldownActive.value = false
        }
    }

    private fun Throwable.readableMessage(): String {
        val text = message ?: localizedMessage
        return if (text.isNullOrBlank()) this::class.java.simpleName else "${this::class.java.simpleName}: $text"
    }
}

data class TunnelParams(
    val peer: String,
    val vkHashes: String,
    val secondaryVkHash: String = "",
    val workersPerHash: Int,
    val port: Int,
    val sni: String = "",
    val connectionPassword: String = "",
    val protocol: String = "udp",
    val captchaMode: String = "auto",
    val captchaSolveMethod: String = "auto",
    val provider: String = "vk",
    val tunnelMode: String = "whitelist",
    val wgPort: Int = 56001
)
