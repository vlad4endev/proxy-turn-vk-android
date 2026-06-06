package com.wdtt.client

import org.junit.Assert.assertEquals
import org.junit.Test

class VkHashParserTest {

    @Test
    fun parse_vkRuCallJoinLink() {
        val hash = "fzicZ66ECu4E4sA4RlRoKAqBL7SWEP8Zh2xKnVS8wFo"
        assertEquals(hash, VkHashParser.parse("https://vk.ru/call/join/$hash"))
    }

    @Test
    fun parse_vkComCallJoinLink() {
        assertEquals("AbCdEfGhIjKl", VkHashParser.parse("https://vk.com/call/join/AbCdEfGhIjKl"))
    }

    @Test
    fun parse_plainHash() {
        assertEquals("AbCdEfGhIjKl", VkHashParser.parse("AbCdEfGhIjKl"))
    }
}
