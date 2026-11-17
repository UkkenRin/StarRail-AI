package top.m7a.ui

import android.graphics.Bitmap
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import org.json.JSONArray
import org.json.JSONObject

/** An AI's READ-ONLY scene report. A proposed Gamer action is never executable. */
internal data class ManualVisionProposal(
    val scene: String,
    val observations: String,
    val nextStep: String,
    val gamerAction: String,
    val gameActionParse: GamerParseResult?,
)

/** Parse text only; does not run any action, even a syntactically valid chunk. */
internal fun parseManualVisionProposal(text: String): ManualVisionProposal {
    require(text.length in 1..12_000) { "AI 返回内容为空或过长" }
    val raw = text.trim()
    // Allow providers that wrap JSON in a conventional single fenced block.
    val payload = if (raw.startsWith("&#96;&#96;&#96;")) raw else raw
    val trimmed = if (payload.startsWith("\u0060\u0060\u0060")) {
        val firstLine = payload.indexOf('\n')
        require(firstLine in 3..12 && payload.endsWith("\u0060\u0060\u0060")) {
            "AI 未返回完整的 JSON"
        }
        payload.substring(firstLine + 1, payload.length - 3).trim()
    } else payload
    val json = try {
        JSONObject(trimmed)
    } catch (_: Exception) {
        error("AI 未返回可解析的 JSON，请使用支持图像输入的模型")
    }
    val scene = json.optString("scene").trim().take(150)
    val observations = json.optString("observations").trim().take(1500)
    val nextStep = json.optString("next_step").trim().take(900)
    val gamerAction = json.optString("gamer_action").trim().take(1400)
    require(scene.isNotEmpty() && observations.isNotEmpty() && nextStep.isNotEmpty()) {
        "AI 缺少场景、画面证据或下一步建议"
    }
    return ManualVisionProposal(
        scene = scene,
        observations = observations,
        nextStep = nextStep,
        gamerAction = gamerAction,
        gameActionParse = if (gamerAction.isEmpty()) null else GamerProtocol.parse(gamerAction),
    )
}

/**
 * Performs precisely one user-confirmed HTTPS request with the in-memory frame.
 * Caller MUST enforce human confirmation, rate limit and foreground/cloud policy.
 * This bypasses automatic LiveVisionGate on purpose: a frozen preview is for
 * explicit MANUAL read-only explanation, never autonomous observation or input.
 */
internal fun queryManualVisionProposal(
    provider: Provider,
    customBase: String,
    apiKey: String,
    model: String,
    bitmap: Bitmap,
): ManualVisionProposal {
    require(provider != Provider.CHATGPT) { "此实验仅支持视觉 API Key，不支持 ChatGPT 账号授权" }
    require(apiKey.isNotBlank() && model.isNotBlank()) { "请填写视觉模型 ID 和 API Key" }
    require(model.length <= 150 && apiKey.length <= 4096) { "模型或 Key 过长" }
    val base = (if (provider == Provider.CUSTOM) customBase else provider.endpoint).trimEnd('/')
    val uri = URI(base)
    require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null &&
        uri.query == null && uri.fragment == null) { "必须使用合法 HTTPS 视觉 API 地址" }

    require(bitmap.width > 0 && bitmap.height > 0 &&
        bitmap.width <= 640 && bitmap.height <= 640) { "只允许发送最长边 640 像素的本地预览" }
    val jpeg = ByteArrayOutputStream().use { out ->
        require(bitmap.compress(Bitmap.CompressFormat.JPEG, 72, out)) { "图片编码失败" }
        out.toByteArray()
    }
    require(jpeg.size in 1..512_000) { "单张图片大小超过 500 KB 限制" }
    val encoded = Base64.encodeToString(jpeg, Base64.NO_WRAP)

    val systemPrompt = """
        你是《崩坏：星穹铁道》的只读画面观察员。你只看到用户手动选择的一张历史截图，
        可能不是游戏、可能已过期，不能推测任何点击已发生，也不能判断安全的实时触摸目标。
        根据画面可见证据说明可能的场景和下一步建议，不确定时直接说明不确定，
        不建议购买、抽卡、分解资源等高风险行为。
        只返回 JSON 对象（不要 Markdown），严格包含：
        {"scene":"简短场景或未知","observations":"实际可见的画面依据",
         "next_step":"只读的下一步操作建议，非已执行操作","gamer_action":""}
        gamer_action 必须始终为空字符串。本应用仅支持静态只读分析，
        不会从模型建议执行游戏触摸。不要输出额外字段。
    """.trimIndent()

    val messages = JSONArray()
        .put(JSONObject().put("role", "system").put("content", systemPrompt))
        .put(JSONObject().put("role", "user").put("content", JSONArray()
            .put(JSONObject().put("type", "text").put("text",
                "请分析这张手动选中的历史截图；不允许自主执行任何操作。"))
            .put(JSONObject().put("type", "image_url").put("image_url",
                JSONObject().put("url", "data:image/jpeg;base64,$encoded")))))
    val body = JSONObject()
        .put("model", model.trim())
        .put("messages", messages)
        .put("stream", false)
        .put("max_tokens", 700)
    val connection = (URL("$base/chat/completions").openConnection() as HttpsURLConnection).apply {
        requestMethod = "POST"
        doOutput = true
        connectTimeout = 15_000
        readTimeout = 70_000
        setRequestProperty("Content-Type", "application/json; charset=utf-8")
        setRequestProperty("Authorization", "Bearer " + apiKey.trim())
    }

    try {
        connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
        val code = connection.responseCode
        if (code !in 200..299) error("视觉 API HTTP $code；请确认所选模型支持图片和现有额度")
        // Bounded response; do not log raw frames, keys, or model output.
        val response = connection.inputStream.bufferedReader(Charsets.UTF_8).use {
            val buffer = CharArray(8192)
            val out = StringBuilder()
            while (out.length < 256_000) {
                val n = it.read(buffer, 0, minOf(buffer.size, 256_000 - out.length))
                if (n < 0) break
                out.append(buffer, 0, n)
            }
            out.toString()
        }
        val answer = JSONObject(response).getJSONArray("choices").getJSONObject(0)
            .getJSONObject("message").getString("content")
        return parseManualVisionProposal(answer)
    } finally {
        connection.disconnect()
    }
}
