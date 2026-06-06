package com.wdtt.client

enum class LinkProvider { VK, YANDEX, UNKNOWN }

/**
 * Нормализация ссылок VK-звонка и Яндекс Телемост.
 */
object VkHashParser {

    private val HASH_RE = Regex("""^[A-Za-z0-9_\-]{10,}$""")
    private val JOIN_RE = Regex("""(?:call/join/)([A-Za-z0-9_\-]+)""", RegexOption.IGNORE_CASE)
    private val TELEMOST_JOIN_RE = Regex("""(?:telemost/j/|/j/)([A-Za-z0-9_\-]+)""", RegexOption.IGNORE_CASE)

    // ── Provider detection ────────────────────────────────────────────────────

    fun detectProvider(input: String): LinkProvider {
        val lower = input.trim().lowercase()
        return when {
            lower.contains("telemost") || lower.contains("ya.ru/telemost") -> LinkProvider.YANDEX
            lower.contains("vk.com") || lower.contains("vk.me") ||
                lower.contains("vk.ru") || lower.startsWith("vk://") -> LinkProvider.VK
            else -> LinkProvider.UNKNOWN
        }
    }

    // ── Yandex Telemost parsing ───────────────────────────────────────────────

    fun parseYandex(input: String): String {
        var s = input.trim().trim('<', '>', '"', '\'')
        if (s.isBlank()) return ""
        val lower = s.lowercase()

        // yandex://telemost/j/HASH
        if (lower.startsWith("yandex://telemost")) {
            val path = s.substring("yandex://telemost".length).trimStart('/')
            TELEMOST_JOIN_RE.find("telemost/$path")?.groupValues?.get(1)?.let { return cleanTail(it) }
            if (path.lowercase().startsWith("j/")) return cleanTail(path.substring(2))
        }

        val yandexPrefixes = listOf(
            "https://telemost.yandex.ru/j/",
            "http://telemost.yandex.ru/j/",
            "telemost.yandex.ru/j/",
            "https://ya.ru/telemost/j/",
            "http://ya.ru/telemost/j/",
            "ya.ru/telemost/j/"
        )
        for (prefix in yandexPrefixes) {
            if (lower.startsWith(prefix)) return cleanTail(s.substring(prefix.length))
        }

        TELEMOST_JOIN_RE.find(s)?.groupValues?.get(1)?.let { return cleanTail(it) }

        return ""
    }

    fun parse(input: String): String {
        var s = input.trim().trim('<', '>', '"', '\'')
        if (s.isBlank()) return ""

        val lower = s.lowercase()

        // Explicit vk:// scheme: vk://call/join/HASH
        if (lower.startsWith("vk://")) {
            val path = s.substring("vk://".length)
            JOIN_RE.find(path)?.groupValues?.get(1)?.let { return cleanTail(it) }
        }

        val urlPrefixes = listOf(
            "https://vk.com/call/join/",
            "http://vk.com/call/join/",
            "https://m.vk.com/call/join/",
            "http://m.vk.com/call/join/",
            "https://vk.me/call/join/",
            "http://vk.me/call/join/",
            "https://vk.ru/call/join/",
            "http://vk.ru/call/join/",
            "m.vk.com/call/join/",
            "vk.me/call/join/",
            "vk.com/call/join/",
            "vk.ru/call/join/"
        )
        for (prefix in urlPrefixes) {
            if (lower.startsWith(prefix)) {
                s = s.substring(prefix.length)
                break
            }
        }

        JOIN_RE.find(s)?.groupValues?.get(1)?.let { return cleanTail(it) }

        val joinIdx = lower.indexOf("/call/join/")
        if (joinIdx >= 0) {
            s = s.substring(joinIdx + "/call/join/".length)
        }

        if (lower.startsWith("vk:")) {
            s = s.removePrefix("vk:").removePrefix("VK:")
        }

        // Обрезанная вставка: "https:" → ":hash" или "://..."
        while (s.startsWith(":")) {
            s = s.removePrefix(":").trim()
        }
        if (s.startsWith("//")) {
            s = s.removePrefix("//")
            val slash = s.indexOf('/')
            if (slash >= 0) s = s.substring(slash + 1)
        }

        if (lower == "http:" || lower == "https:" || lower == "http" || lower == "https") {
            return ""
        }
        if ((lower.startsWith("http://") || lower.startsWith("https://")) && !lower.contains("/call/join/")) {
            return ""
        }

        s = cleanTail(s)
        if (s.matches(HASH_RE)) return s

        // Plain hash: only [A-Za-z0-9_-] with no dots or slashes, 10+ chars
        val trimmed = input.trim().trim('<', '>', '"', '\'')
        if (trimmed.matches(HASH_RE)) return trimmed

        return ""
    }

    fun parseList(raw: String): List<String> {
        return raw.split(Regex("[,;\\s\\n]+"))
            .mapNotNull { h ->
                val p = parse(h)
                p.takeIf { it.isNotBlank() }
            }
            .distinct()
    }

    fun looksInvalid(input: String): Boolean {
        val trimmed = input.trim()
        if (trimmed.isBlank()) return false
        val parsed = parse(trimmed)
        return parsed.isBlank() || parsed.length < 10
    }

    /** Для полей ввода: полный parse (VK + Яндекс) или снятие префикса URL (чтобы не мешать набору). */
    fun normalizeForInput(input: String): String {
        val parsedVk = parse(input)
        if (parsedVk.isNotBlank()) return parsedVk
        val parsedYa = parseYandex(input)
        if (parsedYa.isNotBlank()) return parsedYa
        return cleanTail(
            input.trim().trim('<', '>', '"', '\'')
                .let { s ->
                    val lower = s.lowercase()
                    val urlPrefixes = listOf(
                        "https://vk.com/call/join/",
                        "http://vk.com/call/join/",
                        "https://m.vk.com/call/join/",
                        "http://m.vk.com/call/join/",
                        "https://vk.me/call/join/",
                        "http://vk.me/call/join/",
                        "m.vk.com/call/join/",
                        "vk.me/call/join/",
                        "vk.com/call/join/",
                        "https://telemost.yandex.ru/j/",
                        "http://telemost.yandex.ru/j/",
                        "telemost.yandex.ru/j/",
                        "https://ya.ru/telemost/j/",
                        "http://ya.ru/telemost/j/",
                        "ya.ru/telemost/j/"
                    )
                    var out = s
                    for (prefix in urlPrefixes) {
                        if (lower.startsWith(prefix)) {
                            out = s.substring(prefix.length)
                            break
                        }
                    }
                    out
                }
        )
    }

    private fun cleanTail(s: String): String {
        var out = s.substringBefore('?').substringBefore('#').trim().trimEnd('/')
        if (out.startsWith(":")) {
            out = out.removePrefix(":").trim()
        }
        return out
    }
}
