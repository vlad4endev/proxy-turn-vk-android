package com.wdtt.client

/**
 * Нормализация ссылки VK-звонка / хеша (синхронно с go_client/normalizeVKJoinHash).
 */
object VkHashParser {

    private val HASH_RE = Regex("""^[A-Za-z0-9_\-]{16,}$""")
    private val JOIN_RE = Regex("""call/join/([A-Za-z0-9_\-]+)""", RegexOption.IGNORE_CASE)

    fun parse(input: String): String {
        var s = input.trim().trim('<', '>', '"', '\'')
        if (s.isBlank()) return ""

        val lower = s.lowercase()

        val urlPrefixes = listOf(
            "https://vk.com/call/join/",
            "http://vk.com/call/join/",
            "https://m.vk.com/call/join/",
            "http://m.vk.com/call/join/",
            "https://vk.ru/call/join/",
            "http://vk.ru/call/join/",
            "m.vk.com/call/join/",
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
        return if (s.matches(HASH_RE)) s else ""
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
        return parsed.isBlank() || parsed.length < 16
    }

    /** Для полей ввода: полный parse или только снятие префикса URL (чтобы не мешать набору). */
    fun normalizeForInput(input: String): String {
        val parsed = parse(input)
        if (parsed.isNotBlank()) return parsed
        return cleanTail(
            input.trim().trim('<', '>', '"', '\'')
                .let { s ->
                    val lower = s.lowercase()
                    val urlPrefixes = listOf(
                        "https://vk.com/call/join/",
                        "http://vk.com/call/join/",
                        "https://m.vk.com/call/join/",
                        "http://m.vk.com/call/join/",
                        "m.vk.com/call/join/",
                        "vk.com/call/join/"
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
