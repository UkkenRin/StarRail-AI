package top.m7a.ui

import android.content.Context
import android.net.Uri
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigInteger
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.net.URLEncoder
import java.net.URL
import java.security.KeyFactory
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.RSAPublicKeySpec
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.net.ssl.HttpsURLConnection

/** Experimental open-source Sign in with ChatGPT client. No shared secrets or remote backend. */
internal class ChatGptOAuth(context: Context) {
    internal data class Model(val slug: String, val name: String)

    private val prefs = context.applicationContext.getSharedPreferences("starrail-siwc", Context.MODE_PRIVATE)
    private val hostId = prefs.getString("host-id", null) ?: "urn:uuid:${UUID.randomUUID()}".also {
        prefs.edit().putString("host-id", it).apply()
    }
    private val random = SecureRandom()

    fun accountLabel(): String = readCredentials()?.optString("email").orEmpty()
    fun hasPlanPermission(): Boolean =
        readCredentials()?.optString("scope")?.split(" ")?.contains(PLAN_SCOPE) == true

    suspend fun signIn(openBrowser: (String) -> Unit): String = withContext(Dispatchers.IO) {
        val previous = readCredentials()
        val existingClient = previous?.optString("client_id")?.takeIf { it.startsWith("oaiapp_") }
        val requestedClient = existingClient ?: "dynamic_agent_client"
        val state = randomString()
        val nonce = randomString()
        val verifier = randomString(64)
        val challenge = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
            MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
        )
        ServerSocket(0, 4, InetAddress.getByName("127.0.0.1")).use { listener ->
            val redirect = "http://127.0.0.1:${listener.localPort}/auth/callback"
            val query = linkedMapOf(
                "client_id" to requestedClient, "response_type" to "code", "redirect_uri" to redirect,
                "scope" to "openid profile email offline_access resource.invoke $PLAN_SCOPE",
                "resource" to API, "state" to state, "nonce" to nonce,
                "code_challenge_method" to "S256", "code_challenge" to challenge,
                "ext_agent_host_id" to hostId
            )
            if (existingClient == null) {
                query["agent_name_hint"] = "StarRail AI"
            } else {
                previous?.optString("id_token")?.takeIf { it.isNotBlank() }?.let { query["id_token_hint"] = it }
            }
            withContext(Dispatchers.Main) { openBrowser("$AUTH/api/accounts/authorize?" + form(query)) }
            val callback = awaitCallback(listener, state)
            val issuedClient = if (existingClient == null) {
                callback.optString("client_id").takeIf { it.startsWith("oaiapp_") }
                    ?: error("授权未返回有效的客户端 ID")
            } else {
                callback.optString("client_id").takeIf { it.isNotEmpty() }?.let {
                    require(it == existingClient) { "回调客户端 ID 不匹配" }
                }
                existingClient
            }
            val token = postForm("$AUTH/api/accounts/oauth/token", mapOf(
                "grant_type" to "authorization_code", "client_id" to issuedClient,
                "code" to callback.getString("code"), "code_verifier" to verifier,
                "redirect_uri" to redirect, "resource" to API
            ))
            val identity = verifyIdToken(token.getString("id_token"), issuedClient, nonce)
            if (existingClient != null) {
                require(previous?.optString("subject") == identity.getString("sub")) { "账号身份不匹配" }
            }
            val profile = JSONObject()
                .put("client_id", issuedClient)
                .put("subject", identity.getString("sub"))
                .put("email", identity.optString("email"))
                .put("id_token", token.getString("id_token"))
                .put("access_token", token.getString("access_token"))
                .put("refresh_token", token.getString("refresh_token"))
                .put("scope", token.optString("scope"))
                .put("expires_at", System.currentTimeMillis() + token.optLong("expires_in", 3600L) * 1000)
            saveCredentials(profile)
            if (hasPlanPermission()) "已连接 ChatGPT" else "身份登录成功，但尚未授权方案额度"
        }
    }

    suspend fun models(): List<Model> = withContext(Dispatchers.IO) {
        val body = getJson("$API/models", accessToken())
        val array = body.optJSONArray("models") ?: JSONArray()
        buildList {
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val slug = item.optString("slug")
                if (slug.isNotBlank() && item.optString("visibility", "list") == "list") {
                    add(Model(slug, item.optString("display_name").ifBlank { slug }))
                }
            }
        }
    }

    suspend fun respond(model: String, account: String, observation: String, question: String): String =
        withContext(Dispatchers.IO) {
            require(model.isNotBlank() && question.isNotBlank()) { "请选择模型并输入问题" }
            require(account.length + observation.length + question.length < 40000) { "输入太长" }
            val prompt = "你是星穹铁道账号顾问。只能根据已提供的账号和关卡信息建议配队；未知内容须标明未知。" +
                "你没有操作游戏的权限，不得声称已经自动扫描账号。\n账号：\n$account\n场景：\n$observation\n问题：\n$question"
            val body = JSONObject().put("model", model)
                .put("input", JSONArray().put(JSONObject().put("role", "user").put("content", prompt)))
                .put("store", false).put("stream", true)
            val conn = (URL("$API/responses").openConnection() as HttpsURLConnection).apply {
                requestMethod = "POST"; doOutput = true
                connectTimeout = 15000; readTimeout = 90000
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Accept", "text/event-stream")
                setRequestProperty("Authorization", "Bearer ${accessToken()}")
            }
            try {
                conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                require(conn.responseCode in 200..299) { "Responses API HTTP ${conn.responseCode}" }
                val answer = StringBuilder()
                var completed = false
                conn.inputStream.bufferedReader(Charsets.UTF_8).useLines { lines ->
                    for (line in lines) {
                        if (!line.startsWith("data: ")) continue
                        val data = line.substring(6)
                        if (data == "[DONE]") break
                        val event = try { JSONObject(data) } catch (_: Exception) { continue }
                        when (event.optString("type")) {
                            "response.output_text.delta" -> {
                                answer.append(event.optString("delta"))
                                require(answer.length < 100000) { "模型回复过长" }
                            }
                            "response.failed" -> error("模型请求失败：${event.optJSONObject("response")?.optJSONObject("error")?.optString("code")}")
                            "response.incomplete" -> error("模型回复不完整")
                            "response.completed" -> completed = true
                        }
                    }
                }
                require(completed) { "没有收到模型完成标记，可能中途断线" }
                answer.toString().ifEmpty { error("模型返回空内容") }
            } finally {
                conn.disconnect()
            }
        }

    suspend fun signOut(): String = withContext(Dispatchers.IO) {
        val profile = readCredentials()
        var revoked = false
        try {
            if (profile != null && profile.optString("refresh_token").isNotBlank()) {
                val endpoint = getJson("$AUTH/.well-known/openid-configuration").optString("revocation_endpoint")
                if (endpoint.startsWith("$AUTH/")) {
                    postForm(endpoint, mapOf(
                        "token" to profile.getString("refresh_token"),
                        "token_type_hint" to "refresh_token",
                        "client_id" to profile.getString("client_id")
                    ), emptyOk = true)
                    revoked = true
                }
            }
        } catch (_: Exception) {
            // Never leak credentials in logs. Local sign-out must work offline.
        } finally { prefs.edit().remove("credentials").apply() }
        if (revoked) "已断开连接" else "本机已退出；请在 ChatGPT 设置中确认撤销授权"
    }

    private fun awaitCallback(listener: ServerSocket, expectedState: String): JSONObject {
        val until = System.currentTimeMillis() + 120000
        while (System.currentTimeMillis() < until) {
            listener.soTimeout = (until - System.currentTimeMillis()).coerceAtLeast(1).toInt()
            val socket = try { listener.accept() } catch (_: SocketTimeoutException) { break }
            try {
                socket.soTimeout = 5000
                val input = socket.getInputStream().bufferedReader(Charsets.UTF_8)
                val parts = (input.readLine() ?: "").split(" ")
                var host = ""
                while (true) {
                    val header = input.readLine() ?: break
                    if (header.isEmpty()) break
                    if (header.startsWith("Host:", true)) host = header.substringAfter(":").trim()
                }
                val path = parts.getOrNull(1).orEmpty()
                if (parts.firstOrNull() != "GET" || host != "127.0.0.1:${listener.localPort}" ||
                    !path.startsWith("/auth/callback?")) {
                    sendPage(socket, 404, "Not found"); continue
                }
                val uri = Uri.parse("http://$host$path")
                if (!MessageDigest.isEqual(
                        uri.getQueryParameter("state").orEmpty().toByteArray(),
                        expectedState.toByteArray())) {
                    sendPage(socket, 403, "Invalid state")
                    error("OAuth state 校验失败")
                }
                if (uri.getQueryParameter("error") != null) {
                    sendPage(socket, 200, "Authorization cancelled. Return to StarRail AI.")
                    error("已取消授权")
                }
                val code = uri.getQueryParameter("code").orEmpty()
                require(code.isNotBlank()) { "OAuth 回调没有 code" }
                val result = JSONObject().put("code", code)
                uri.getQueryParameter("client_id")?.let { result.put("client_id", it) }
                sendPage(socket, 200, "Authorization received. Return to StarRail AI.")
                return result
            } finally { socket.close() }
        }
        error("OAuth 登录超时")
    }

    private fun sendPage(socket: java.net.Socket, status: Int, message: String) {
        val data = "<!doctype html><meta charset=\"utf-8\"><p>$message</p>".toByteArray(Charsets.UTF_8)
        val headers = "HTTP/1.1 $status OK\r\nContent-Type: text/html; charset=utf-8\r\n" +
            "Content-Length: ${data.size}\r\nConnection: close\r\nCache-Control: no-store\r\n\r\n"
        socket.getOutputStream().write(headers.toByteArray(Charsets.US_ASCII) + data)
        socket.getOutputStream().flush()
    }

    private fun verifyIdToken(jwt: String, clientId: String, nonce: String): JSONObject {
        val parts = jwt.split(".")
        require(parts.size == 3) { "ID Token 格式异常" }
        val decoder = java.util.Base64.getUrlDecoder()
        val header = JSONObject(String(decoder.decode(parts[0]), Charsets.UTF_8))
        require(header.optString("alg") == "RS256") { "不支持的身份签名算法" }
        val keys = getJson("$AUTH/.well-known/jwks.json").getJSONArray("keys")
        val jwk = (0 until keys.length()).map { keys.getJSONObject(it) }.firstOrNull {
            it.optString("kid") == header.optString("kid") && it.optString("kty") == "RSA"
        } ?: error("身份签名公钥缺失")
        val key = KeyFactory.getInstance("RSA").generatePublic(RSAPublicKeySpec(
            BigInteger(1, decoder.decode(jwk.getString("n"))),
            BigInteger(1, decoder.decode(jwk.getString("e")))
        ))
        val signature = Signature.getInstance("SHA256withRSA")
        signature.initVerify(key)
        signature.update("${parts[0]}.${parts[1]}".toByteArray(Charsets.US_ASCII))
        require(signature.verify(decoder.decode(parts[2]))) { "ID Token 签名无效" }
        val claims = JSONObject(String(decoder.decode(parts[1]), Charsets.UTF_8))
        val aud = claims.optJSONArray("aud")
        val match = if (aud == null) claims.optString("aud") == clientId
                    else (0 until aud.length()).any { aud.optString(it) == clientId }
        val now = System.currentTimeMillis() / 1000L
        require(claims.optString("iss") == AUTH && match && claims.optString("nonce") == nonce &&
            claims.optString("sub").isNotBlank() && claims.optLong("exp") > now - 5 &&
            claims.optLong("iat") in 1..(now + 60)) { "身份、有效期或 nonce 校验失败" }
        return claims
    }

    @Synchronized
    private fun accessToken(): String {
        var profile = readCredentials() ?: error("请先连接 ChatGPT")
        require(profile.optString("scope").split(" ").contains(PLAN_SCOPE)) { "没有授权方案使用权限" }
        if (profile.optLong("expires_at") < System.currentTimeMillis() + 120000L) {
            val result = postForm("$AUTH/api/accounts/oauth/token", mapOf(
                "grant_type" to "refresh_token",
                "client_id" to profile.getString("client_id"),
                "refresh_token" to profile.getString("refresh_token"),
                "resource" to API,
            ))
            profile = JSONObject(profile.toString())
                .put("access_token", result.getString("access_token"))
                .put("refresh_token", result.getString("refresh_token"))
                .put("scope", result.optString("scope", profile.getString("scope")))
                .put("expires_at", System.currentTimeMillis() + result.optLong("expires_in", 3600L) * 1000)
            saveCredentials(profile)
        }
        return profile.getString("access_token")
    }

    private fun getJson(url: String, bearer: String? = null) = requestJson(url, null, null, bearer)
    private fun postForm(url: String, data: Map<String, String>, emptyOk: Boolean = false) =
        requestJson(url, form(data).toByteArray(Charsets.UTF_8),
            "application/x-www-form-urlencoded", null, emptyOk)

    private fun requestJson(url: String, body: ByteArray?, mime: String?, bearer: String?,
                            emptyOk: Boolean = false): JSONObject {
        require(url.startsWith("$AUTH/") || url.startsWith("$API/")) { "非法请求目标" }
        val connection = (URL(url).openConnection() as HttpsURLConnection).apply {
            requestMethod = if (body == null) "GET" else "POST"
            doOutput = body != null
            connectTimeout = 15000; readTimeout = 30000
            if (mime != null) setRequestProperty("Content-Type", mime)
            if (bearer != null) setRequestProperty("Authorization", "Bearer $bearer")
        }
        return try {
            if (body != null) connection.outputStream.use { it.write(body) }
            val code = connection.responseCode
            require(code in 200..299) { "认证服务 HTTP $code" }
            val content = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText().take(1000000) }
            if (emptyOk && content.isBlank()) JSONObject() else JSONObject(content)
        } finally { connection.disconnect() }
    }

    private fun randomString(n: Int = 32) = ByteArray(n).also { random.nextBytes(it) }
        .let { java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(it) }

    private fun form(values: Map<String, String>): String = values.entries.joinToString("&") {
        URLEncoder.encode(it.key, "UTF-8") + "=" + URLEncoder.encode(it.value, "UTF-8")
    }

    private fun cryptKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val old = store.getKey(KEY_ALIAS, null)
        if (old != null) return old as SecretKey
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build())
        }.generateKey()
    }

    private fun saveCredentials(data: JSONObject) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, cryptKey())
        }
        val encrypted = cipher.iv + cipher.doFinal(data.toString().toByteArray(Charsets.UTF_8))
        prefs.edit().putString("credentials", Base64.encodeToString(encrypted, Base64.NO_WRAP)).apply()
    }

    private fun readCredentials(): JSONObject? {
        val encoded = prefs.getString("credentials", null) ?: return null
        return try {
            val bytes = Base64.decode(encoded, Base64.NO_WRAP)
            require(bytes.size > 28)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, cryptKey(),
                GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
            JSONObject(String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8))
        } catch (_: Exception) { null }
    }

    private companion object {
        const val AUTH = "https://auth.openai.com"
        const val API = "https://api.openai.com/v1"
        const val PLAN_SCOPE = "chatgpt.tokens.use.direct"
        const val KEY_ALIAS = "starrail-siwc-credentials"
    }
}
