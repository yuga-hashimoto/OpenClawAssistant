package com.openclaw.assistant.chat

import org.junit.Test
import org.junit.Assert.assertEquals

class ChatMarkdownPreprocessorTest {
    @Test
    fun testTrimStart() {
        val s = "\n\n\nhello\nworld"
        assertEquals("hello\nworld", s.trimStart('\n'))
    }
}
