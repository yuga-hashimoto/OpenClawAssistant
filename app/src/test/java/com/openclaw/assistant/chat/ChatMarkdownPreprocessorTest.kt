package com.openclaw.assistant.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class ChatMarkdownPreprocessorTest {
    @Test
    fun testPreprocessing() {
        val raw = "Sender (untrusted metadata):\n```json\n{}\n```\n\n\nHello\r\nWorld"
        val processed = ChatMarkdownPreprocessor.preprocess(raw)
        assertEquals("Hello\nWorld", processed)
    }
}
