package top.m7a.ui

import org.json.JSONArray
import org.json.JSONObject

/**
 * Strict Responses API input shape for a single user-approved captured frame.
 * Pure JSON helper, testable without network and without ever storing images.
 */
internal fun buildChatGptVisionInput(systemPrompt: String, dataUrl: String): JSONArray {
    require(systemPrompt.isNotBlank() && systemPrompt.length <= 8_000) { "无效的画面分析说明" }
    require(dataUrl.startsWith("data:image/jpeg;base64,") &&
        dataUrl.length in 32..700_000 &&
        dataUrl.substringAfter("base64,").all { it.isLetterOrDigit() || it == '+' || it == '/' || it == '=' }
    ) { "不支持的截图格式" }

    return JSONArray().put(
        JSONObject().put("role", "user").put("content", JSONArray()
            .put(JSONObject().put("type", "input_text").put("text", systemPrompt))
            .put(JSONObject().put("type", "input_image")
                .put("image_url", dataUrl).put("detail", "low")))
    )
}
