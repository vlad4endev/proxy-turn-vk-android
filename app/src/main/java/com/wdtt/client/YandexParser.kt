package com.wdtt.client

/**
 * Парсинг ссылок Яндекс Телемост и строгая проверка реальной капчи в HTML.
 * Не использует эвристики VK Smart Captcha (captcha/recaptcha в JS).
 */
object YandexParser {

    private val TELEMOST_JOIN_RE = Regex("""(?:telemost/j/|/j/)([A-Za-z0-9_\-]+)""", RegexOption.IGNORE_CASE)
    private val CHECKBOX_INPUT_RE = Regex(
        """<input[^>]+type\s*=\s*["']checkbox["']""",
        RegexOption.IGNORE_CASE
    )

    fun detectProvider(url: String): LinkProvider {
        val lower = url.trim().lowercase()
        return when {
            lower.contains("telemost.yandex") ||
                lower.contains("telemost/j/") ||
                lower.contains("ya.ru/telemost") ||
                lower.startsWith("yandex://telemost") -> LinkProvider.YANDEX
            lower.contains("vk.com") ||
                lower.contains("vk.me") ||
                lower.contains("vk.ru") ||
                lower.startsWith("vk://") -> LinkProvider.VK
            else -> LinkProvider.UNKNOWN
        }
    }

    /** Извлекает room hash из ссылки Телемост или возвращает пустую строку. */
    fun parse(input: String): String {
        var s = input.trim().trim('<', '>', '"', '\'')
        if (s.isBlank()) return ""
        val lower = s.lowercase()

        if (lower.startsWith("yandex://telemost")) {
            val path = s.substring("yandex://telemost".length).trimStart('/')
            TELEMOST_JOIN_RE.find("telemost/$path")?.groupValues?.get(1)?.let { return cleanTail(it) }
            if (path.lowercase().startsWith("j/")) return cleanTail(path.substring(2))
        }

        val prefixes = listOf(
            "https://telemost.yandex.ru/j/",
            "http://telemost.yandex.ru/j/",
            "telemost.yandex.ru/j/",
            "https://ya.ru/telemost/j/",
            "http://ya.ru/telemost/j/",
            "ya.ru/telemost/j/"
        )
        for (prefix in prefixes) {
            if (lower.startsWith(prefix)) return cleanTail(s.substring(prefix.length))
        }

        TELEMOST_JOIN_RE.find(s)?.groupValues?.get(1)?.let { return cleanTail(it) }

        return ""
    }

    /** Полная join-ссылка для Go-клиента (-link). */
    fun normalizeLink(input: String): String {
        val hash = parse(input)
        if (hash.isNotBlank()) return "https://telemost.yandex.ru/j/$hash"
        val trimmed = input.trim()
        return if (trimmed.contains("telemost", ignoreCase = true)) trimmed else ""
    }

    /**
     * Капча Яндекса — только явная форма (checkbox) или текст «Докажите, что вы не робот».
     * Слова captcha/recaptcha в JS/meta не считаются капчей.
     */
    fun isRealCaptchaChallenge(html: String): Boolean {
        if (html.isBlank()) return false
        if (html.contains("Докажите что вы не робот", ignoreCase = true) ||
            html.contains("Докажите, что вы не робот", ignoreCase = true)
        ) {
            return true
        }
        return CHECKBOX_INPUT_RE.containsMatchIn(html)
    }

    private fun cleanTail(s: String): String {
        var out = s.substringBefore('?').substringBefore('#').trim().trimEnd('/')
        if (out.startsWith(":")) out = out.removePrefix(":").trim()
        return out
    }
}
