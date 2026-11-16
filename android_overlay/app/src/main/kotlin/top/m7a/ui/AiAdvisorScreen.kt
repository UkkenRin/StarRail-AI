package top.m7a.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/** Read-only AI advisor v0.4. Never inject a game input event. */
internal enum class Provider(val title: String, val endpoint: String, val defaultModel: String) {
    CHATGPT("ChatGPT（账号授权）", "", ""),
    OPENAI("OpenAI", "https://api.openai.com/v1", "gpt-4.1-mini"),
    DEEPSEEK("DeepSeek", "https://api.deepseek.com/v1", "deepseek-chat"),
    QWEN("通义千问", "https://dashscope.aliyuncs.com/compatible-mode/v1", "qwen-plus"),
    CUSTOM("自定义兼容 API", "", "")
}

/** An isolated one-shot HTTPS chat completion; does not persist credentials. */
private fun analyze(
    provider: Provider,
    customBase: String,
    apiKey: String,
    model: String,
    account: String,
    observation: String,
    question: String,
): String {
    require(apiKey.isNotBlank() && model.isNotBlank()) { "请填写 API Key 和模型名称" }
    require(account.length + observation.length + question.length <= 40000) { "输入过长，请缩短账号资料" }
    val base = (if (provider == Provider.CUSTOM) customBase else provider.endpoint).trimEnd('/')
    val uri = java.net.URI(base)
    require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null &&
        uri.query == null && uri.fragment == null) { "API 地址必须是安全的 HTTPS 地址" }
    val messages = JSONArray()
        .put(JSONObject().put("role", "system").put("content", "你是星穹铁道账号分析顾问。只依据给定的账号资料和关卡信息作答。未知信息必须标注未知。" +
            "解释阵容建议与培养理由。你没有游戏控制权，不得声称已点击、换装或消耗资源。"))
        .put(JSONObject().put("role", "user").put("content", "账号档案：\n" + account +
            "\n关卡/当前界面：\n" + observation + "\n问题：\n" + question))
    val body = JSONObject().put("model", model.trim()).put("messages", messages).put("stream", false)
    val url = URL(base + "/chat/completions")
    val connection = (url.openConnection() as HttpsURLConnection).apply {
        requestMethod = "POST"
        doOutput = true
        connectTimeout = 15000
        readTimeout = 90000
        setRequestProperty("Content-Type", "application/json; charset=utf-8")
        setRequestProperty("Authorization", "Bearer " + apiKey.trim())
    }
    return try {
        connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
        val code = connection.responseCode
        if (code !in 200..299) error("API HTTP $code；检查模型、额度或密钥")
        val response = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText().take(2_000_000) }
        JSONObject(response).getJSONArray("choices").getJSONObject(0)
            .getJSONObject("message").getString("content").ifBlank { error("AI 没有返回文本") }
    } finally {
        connection.disconnect()
    }
}

@Composable
internal fun AiAdvisorScreen() {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val chatgpt = remember(context) { ChatGptOAuth(context.applicationContext) }
    var oauthModel by remember { mutableStateOf("") }
    var oauthModels by remember { mutableStateOf(emptyList<ChatGptOAuth.Model>()) }
    var oauthMenu by remember { mutableStateOf(false) }
    var oauthLabel by remember { mutableStateOf(chatgpt.accountLabel()) }
    var provider by remember { mutableStateOf(Provider.OPENAI) }
    var expanded by remember { mutableStateOf(false) }
    var model by remember { mutableStateOf(provider.defaultModel) }
    var apiKey by remember { mutableStateOf("") }
    var customBase by remember { mutableStateOf("") }
    var account by remember { mutableStateOf("") }
    var observation by remember { mutableStateOf("") }
    var question by remember { mutableStateOf("请根据我的账号给出不浪费资源的配队和培养建议") }
    var answer by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("可使用支持 Chat Completions 的 AI API；暂不直接控制游戏") }
    var busy by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("AI 账号顾问 · 实验版", style = MaterialTheme.typography.titleLarge)
        Text("只分析你主动输入的资料，不会控制星铁。ChatGPT 登录使用本机 OAuth；其他服务使用 API Key。")
        Button(onClick = { expanded = true }) { Text("AI 服务：" + provider.title) }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            Provider.entries.forEach { item ->
                DropdownMenuItem(text = { Text(item.title) }, onClick = {
                    provider = item
                    model = item.defaultModel
                    apiKey = ""
                    expanded = false
                })
            }
        }
        if (provider == Provider.CUSTOM) {
            OutlinedTextField(value = customBase, onValueChange = { customBase = it },
                label = { Text("HTTPS API 基础地址（不带 /chat/completions）") },
                modifier = Modifier.fillMaxWidth(), singleLine = true)
        }
        if (provider == Provider.CHATGPT) {
            Text(if (oauthLabel.isBlank()) "ChatGPT 尚未连接" else "已保存 ChatGPT 账号：" + oauthLabel)
            Text("登录会在浏览器中进行；需要在授权页单独同意使用 ChatGPT 方案额度。")
            Button(enabled = !busy, onClick = {
                busy = true
                status = "正在启动 ChatGPT 授权……"
                scope.launch {
                    val result = runCatching {
                        val message = chatgpt.signIn { url ->
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        }
                        oauthLabel = chatgpt.accountLabel()
                        if (chatgpt.hasPlanPermission()) {
                            oauthModels = chatgpt.models()
                            oauthModel = oauthModels.firstOrNull()?.slug.orEmpty()
                        }
                        message
                    }
                    result.onSuccess { status = it }
                        .onFailure { status = "登录失败：" + (it.message ?: "未知错误") }
                    busy = false
                }
            }) { Text("Continue with ChatGPT") }
            if (oauthLabel.isNotBlank()) {
                Button(enabled = !busy, onClick = {
                    busy = true
                    scope.launch {
                        val result = runCatching { chatgpt.models() }
                        result.onSuccess {
                            oauthModels = it
                            oauthModel = it.firstOrNull()?.slug.orEmpty()
                            status = "可用模型：" + it.size
                        }.onFailure { status = "读取模型失败：" + (it.message ?: "未知错误") }
                        busy = false
                    }
                }) { Text("刷新可用模型") }
                Button(enabled = !busy, onClick = {
                    busy = true
                    scope.launch {
                        val result = runCatching { chatgpt.signOut() }
                        result.onSuccess { status = it }.onFailure { status = "退出失败" }
                        oauthLabel = ""
                        oauthModels = emptyList()
                        oauthModel = ""
                        busy = false
                    }
                }) { Text("断开 ChatGPT") }
            }
            if (oauthModels.isNotEmpty()) {
                Button(onClick = { oauthMenu = true }) {
                    Text("模型：" + (oauthModels.firstOrNull { it.slug == oauthModel }?.name ?: oauthModel))
                }
                DropdownMenu(expanded = oauthMenu, onDismissRequest = { oauthMenu = false }) {
                    oauthModels.forEach { option ->
                        DropdownMenuItem(text = { Text(option.name) }, onClick = {
                            oauthModel = option.slug
                            oauthMenu = false
                        })
                    }
                }
            }
        } else {
            OutlinedTextField(value = model, onValueChange = { model = it },
                label = { Text("模型 ID") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            OutlinedTextField(value = apiKey, onValueChange = { apiKey = it },
                label = { Text("API Key（此版本不保存）") }, visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(), singleLine = true)
        }
        OutlinedTextField(value = account, onValueChange = { if (it.length <= 20000) account = it },
            label = { Text("你的角色 / 光锥 / 遗器 / 培养偏好") },
            minLines = 3, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = observation, onValueChange = { observation = it },
            label = { Text("关卡、敌人或游戏当前情况（可不填）") },
            minLines = 2, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = question, onValueChange = { question = it },
            label = { Text("让 AI 帮你做什么") },
            minLines = 2, modifier = Modifier.fillMaxWidth())
        Button(enabled = !busy && question.isNotBlank() &&
            (if (provider == Provider.CHATGPT) chatgpt.hasPlanPermission() && oauthModel.isNotBlank()
             else apiKey.isNotBlank() && model.isNotBlank()), onClick = {
            busy = true
            status = "AI 分析中……"
            scope.launch {
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        if (provider == Provider.CHATGPT) chatgpt.respond(oauthModel, account, observation, question)
                        else analyze(provider, customBase, apiKey, model, account, observation, question)
                    }
                }
                result.onSuccess { answer = it; status = "分析完成（仅发送文字，无截图）" }
                    .onFailure { status = "调用失败：" + (it.message ?: "未知错误") }
                busy = false
            }
        }) { Text(if (busy) "分析中……" else "让 AI 分析") }
        Text(status, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (answer.isNotBlank()) {
            Text("AI 建议", style = MaterialTheme.typography.titleMedium)
            Text(answer)
        }
    }
}
