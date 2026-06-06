package com.wdtt.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class YandexParserTest {

    @Test
    fun detectProvider_telemostUrl() {
        assertEquals(
            LinkProvider.YANDEX,
            YandexParser.detectProvider("https://telemost.yandex.ru/j/AbCdEfGhIj")
        )
    }

    @Test
    fun detectProvider_vkUrl() {
        assertEquals(
            LinkProvider.VK,
            YandexParser.detectProvider("https://vk.com/call/join/AbCdEfGhIj")
        )
    }

    @Test
    fun parse_extractsHash() {
        assertEquals(
            "AbCdEfGhIj",
            YandexParser.parse("https://telemost.yandex.ru/j/AbCdEfGhIj?foo=1")
        )
    }

    @Test
    fun isRealCaptchaChallenge_ignoresJsMentions() {
        val html = """
            <html><head><script>window.smartCaptcha = { recaptcha: true }</script></head>
            <body>Telemost conference</body></html>
        """.trimIndent()
        assertFalse(YandexParser.isRealCaptchaChallenge(html))
    }

    @Test
    fun isRealCaptchaChallenge_detectsRobotText() {
        assertTrue(
            YandexParser.isRealCaptchaChallenge("<div>Докажите, что вы не робот</div>")
        )
    }

    @Test
    fun isRealCaptchaChallenge_detectsCheckboxForm() {
        assertTrue(
            YandexParser.isRealCaptchaChallenge("""<form><input type="checkbox" name="robot"/></form>""")
        )
    }
}
