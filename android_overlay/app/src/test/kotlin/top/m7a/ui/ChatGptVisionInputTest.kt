package top.m7a.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatGptVisionInputTest {
    @Test fun usesSingleImageAndNoToolOrActionExecution() {
        val input = buildChatGptVisionInput(
            "只读分析当前游戏画面并输出 JSON",
            "data:image/jpeg;base64,AAEC",
        )
        assertEquals(1, input.length())
        val content = input.getJSONObject(0).getJSONArray("content")
        assertEquals("user", input.getJSONObject(0).getString("role"))
        assertEquals("input_text", content.getJSONObject(0).getString("type"))
        assertEquals("input_image", content.getJSONObject(1).getString("type"))
        assertEquals("low", content.getJSONObject(1).getString("detail"))
        assertEquals("data:image/jpeg;base64,AAEC", content.getJSONObject(1).getString("image_url"))
        val serialized = input.toString()
        assertFalse(serialized.contains("tool_call"))
        assertFalse(serialized.contains("exec"))
    }

    @Test fun invalidImageSourcesAndLargePayloadsFailClosed() {
        val invalid = listOf(
            "https://other-server.invalid/image.jpg",
            "data:image/png;base64,AAEC",
            "data:image/jpeg;base64,../../secret",
            "data:image/jpeg;base64," + "a".repeat(700_000),
            "",
        )
        for (uri in invalid) {
            assertTrue(uri.take(35), runCatching { buildChatGptVisionInput("只读分析", uri) }.isFailure)
        }
        assertTrue(runCatching { buildChatGptVisionInput("", "data:image/jpeg;base64,AAEC") }.isFailure)
    }
}
