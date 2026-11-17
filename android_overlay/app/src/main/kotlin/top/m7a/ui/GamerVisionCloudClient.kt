package top.m7a.ui

import android.graphics.Bitmap
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import org.json.JSONObject

/**
 * One-shot, explicitly confirmed frame upload using an API key supplied in UI memory.
 * NO automatic frame pump, background sends, retries, storage or game input.
 * Provider/model image support must be verified on the user's actual account.
 */
internal object GamerVisionCloudClient {
    fun endpoint(provider: Provider, customBase: String): String {
        require(provider != Provider.CHATGPT) {
            "当前 ChatGPT OAuth 实验接口未验证支持图片；请使用支持视觉的 API 模型"
        }
        val base = (if (provider == Provider.CUSTOM) customBase else provider.endpoint).trim().trimEnd('/')
        val uri = URI(base)
        require(uri.scheme.equals("https", ignoreCase = true) &&
            !uri.host.isNullOrBlank() && uri.userInfo == null &&
            uri.query == null && uri.fragment == null) {
            "视觉模型 API 必须使用有效 HTTPS 地址"
        }
        return base + "/chat/completions"
    }

    fun propose(
        provider: Provider,
        customBase: String,
        apiKey: String,
        model: String,
        objective: String,
        frame: Bitmap,
    ): String {
        require(apiKey.isNotBlank()) { "请填写视觉模型 API Key" }
        val target = endpoint(provider, customBase)
        require(frame.width in 1..1280 && frame.height in 1..1280) {
            "本次只允许上传低分辨率本地截图"
        }

        val bytes = ByteArrayOutputStream()
        require(frame.compress(Bitmap.CompressFormat.JPEG, 66, bytes)) {
            "截图 JPEG 编码失败"
        }
        val jpeg = bytes.toByteArray()
        require(jpeg.isNotEmpty() && jpeg.size <= GamerVisionProtocol.MAX_IMAGE_BYTES) {
            "图片大小超限"
        }
        val payload = GamerVisionProtocol.requestBody(
            model, objective, Base64.encodeToString(jpeg, Base64.NO_WRAP),
        )

        val conn = (URL(target).openConnection() as HttpsURLConnection).apply {
            requestMethod = "POST"
            instanceFollowRedirects = false // Never forward screenshot or credentials to another host.
            doOutput = true
            connectTimeout = 15_000
            readTimeout = 75_000
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Authorization", "Bearer " + apiKey.trim())
        }
        return try {
            conn.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
            val status = conn.responseCode
            require(status in 200..299) { "视觉 API 返回 HTTP $status；请检查模型是否支持图片及额度" }
            val buf = ByteArrayOutputStream()
            conn.inputStream.use { stream ->
                val chunk = ByteArray(4096)
                while (true) {
                    val length = stream.read(chunk)
                    if (length < 0) break
                    if (length > 0) {
                        require(buf.size() + length <= 128_000) { "视觉 API 响应过大" }
                        buf.write(chunk, 0, length)
                    }
                }
            }
            val body = JSONObject(buf.toString("UTF-8"))
            val result = body.getJSONArray("choices").getJSONObject(0)
                .getJSONObject("message").getString("content")
            require(result.isNotBlank()) { "模型没有返回动作提案" }
            result
        } finally {
            conn.disconnect()
        }
    }
}
