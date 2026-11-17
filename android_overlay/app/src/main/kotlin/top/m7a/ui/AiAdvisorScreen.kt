package top.m7a.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.SystemClock
import android.provider.Settings
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.TextButton
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
    val captureState by LocalCaptureFrames.state.collectAsStateWithLifecycle()
    var captureRequestPending by remember { mutableStateOf(false) }
    val capturePicker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        captureRequestPending = false
        val grant = result.data
        if (result.resultCode == Activity.RESULT_OK && grant != null) {
            runCatching {
                context.startForegroundService(
                    Intent(context, StarRailCaptureService::class.java)
                        .setAction(StarRailCaptureService.ACTION_START)
                        .putExtra(StarRailCaptureService.EXTRA_RESULT, result.resultCode)
                        .putExtra(StarRailCaptureService.EXTRA_GRANT, grant)
                )
            }.onFailure { error ->
                LocalCaptureFrames.notice("启动画面观察服务失败：" + (error.message ?: "未知错误"))
            }
        } else {
            LocalCaptureFrames.notice("已取消系统屏幕共享授权")
        }
    }
    val chatgpt = remember(context) { ChatGptOAuth(context.applicationContext) }
    val profileStore = remember(context) { AccountProfileStore(context.applicationContext) }
    val sceneLoad = remember(context) { runCatching { SceneCatalog.load(context.applicationContext) } }
    val sceneCatalog = sceneLoad.getOrNull()
    var sceneQuery by rememberSaveable { mutableStateOf("") }
    var sceneMenu by remember { mutableStateOf(false) }
    var selectedSceneId by rememberSaveable { mutableStateOf("") }
    var importedProfile by remember { mutableStateOf(profileStore.load()) }
    var clearConfirm by remember { mutableStateOf(false) }
    var oauthModel by remember { mutableStateOf("") }
    var oauthModels by remember { mutableStateOf(emptyList<ChatGptOAuth.Model>()) }
    var oauthMenu by remember { mutableStateOf(false) }
    var oauthLabel by remember { mutableStateOf(chatgpt.accountLabel()) }
    var provider by remember { mutableStateOf(Provider.OPENAI) }
    var expanded by remember { mutableStateOf(false) }
    var model by remember { mutableStateOf(provider.defaultModel) }
    var apiKey by remember { mutableStateOf("") }
    var customBase by remember { mutableStateOf("") }
    var account by remember { mutableStateOf(profileStore.loadNotes()) }
    var observation by remember { mutableStateOf("") }
    var question by remember { mutableStateOf("请根据我的账号给出不浪费资源的配队和培养建议") }
    var answer by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("可使用支持 Chat Completions 的 AI API；暂不直接控制游戏") }
    var busy by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            busy = true
            status = "正在读取并验证账号文件……"
            scope.launch {
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        val raw = context.contentResolver.openInputStream(uri)?.use {
                            AccountProfileParser.readBounded(it)
                        } ?: error("无法读取该文件")
                        profileStore.import(raw)
                    }
                }
                result.onSuccess {
                    importedProfile = it
                    status = "已导入 " + it.characterCount + " 位角色，本地加密保存成功"
                }.onFailure { status = "导入失败：" + (it.message ?: "文件不符合格式") }
                busy = false
            }
        }
    }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("AI 账号顾问 · 实验版", style = MaterialTheme.typography.titleLarge)
        Text("真实屏幕画面 · 本地只读试验", style = MaterialTheme.typography.titleMedium)
        Text("点击后由 Android 系统请求共享权限。可以切到星铁，让本机采集画面；在系统通知中点「停止观察」后回到这里查看最后一帧。可能采到其他应用或敏感画面，请只在需要时授权。")
        Text("这是你的真实屏幕采集，不是三月七小助手的后台游戏虚拟屏。画面仅保存在本机内存，不写入文件、不上传云端，也不允许 AI 操作。")
        Button(enabled = !captureState.running && !captureRequestPending, onClick = {
            runCatching {
                captureRequestPending = true
                val manager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                capturePicker.launch(manager.createScreenCaptureIntent())
            }.onFailure { error ->
                captureRequestPending = false
                LocalCaptureFrames.notice("无法申请录屏授权：" + (error.message ?: "未知错误"))
            }
        }) { Text(if (captureRequestPending) "等待系统授权……" else "授权并开始本地观察") }
        if (captureState.running) {
            Button(onClick = {
                context.startService(Intent(context, StarRailCaptureService::class.java)
                    .setAction(StarRailCaptureService.ACTION_STOP))
            }) { Text("立即停止画面观察") }
        }
        Text(captureState.status, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("游戏前台提示（可选）：Android「使用情况访问」权限仅供诊断，不是可执行触控的焦点验证。没有授权也能继续采集本机预览。")
        Button(onClick = {
            runCatching {
                context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }.onFailure { error ->
                LocalCaptureFrames.notice("打不开系统使用情况授权页：" + (error.message ?: "未知错误"))
            }
        }) { Text("打开系统使用情况访问设置") }
        val foregroundLabel = when (captureState.foregroundHint) {
            ForegroundHint.PERMISSION_REQUIRED -> "尚未授予使用情况访问权限"
            ForegroundHint.SCREEN_LOCKED -> "屏幕已关闭或锁定"
            ForegroundHint.GAME_CANDIDATE -> "最近活动应用疑似星铁（未经窗口核验）"
            ForegroundHint.OTHER_APP -> "最近活动应用不是已识别的星铁包名"
            ForegroundHint.UNKNOWN -> "系统暂无法确定当前活动应用"
        }
        val visibilityLabel = when (captureState.projectionVisible) {
            true -> "系统报告共享内容可见"
            false -> "系统报告共享内容已不可见"
            null -> "系统尚未报告共享内容可见性"
        }
        Text("共享状态：" + visibilityLabel + "；前台提示：" + foregroundLabel)
        if (captureState.frames > 0) {
            val ageMs = (SystemClock.elapsedRealtime() - captureState.lastFrameElapsedMs).coerceAtLeast(0L)
            val readiness = FrameReadinessRules.evaluate(
                captureState.running, captureState.frames, captureState.lastFrameElapsedMs,
                SystemClock.elapsedRealtime(), captureState.projectionVisible,
                captureState.foregroundHint,
            )
            val readinessLabel = when (readiness) {
                FrameReadiness.NOT_CAPTURING -> "采集已停止"
                FrameReadiness.NO_FRAME -> "暂无新帧"
                FrameReadiness.STALE -> "已过期"
                FrameReadiness.CAPTURE_HIDDEN -> "共享区域不可见"
                FrameReadiness.GAME_NOT_CONFIRMED -> "游戏目标未确认"
                FrameReadiness.LOCAL_CANDIDATE -> "仅是本地候选画面，未核验游戏窗口"
            }
            Text("本机采样：${captureState.frames} 帧 · ${captureState.width}×${captureState.height}；" +
                "最近一帧约 ${ageMs / 1000} 秒前 · 画面变化候选 ${captureState.changeEvents} 次")
            Text("最新帧诊断：" + readinessLabel + "；此结果绝不授权云上传或自动点击。")
        }
        captureState.preview?.let { bitmap ->
            Image(bitmap = bitmap.asImageBitmap(), contentDescription = "本地采集画面预览",
                modifier = Modifier.fillMaxWidth().heightIn(max = 280.dp),
                contentScale = ContentScale.Fit)
            Button(onClick = { LocalCaptureFrames.clearPreview() }) { Text("清除本机预览") }
        }
        Text("只读分析；不会控制游戏。你的账号档案只加密保存在本机，只有点击“让 AI 分析”才会将本次分析所需文字发给所选 AI。")
        Text("账号档案（实验版）", style = MaterialTheme.typography.titleMedium)
        Text("可以导入 PC 三月七小助手导出的 characters.json；目前不支持直接扫描安卓游戏，也不能获取未装备的全部遗器。")
        Button(enabled = !busy, onClick = {
            picker.launch(arrayOf("application/json", "text/plain", "application/octet-stream", "*/*"))
        }) { Text("导入 characters.json 文件") }
        val p = importedProfile
        if (p != null) {
            Text("已保存：" + p.characterCount + " 位角色 · 导出时间 " + p.exportedAt)
            Text("完整：" + (if (p.complete) "是" else "否") +
                " · 失败：" + p.failedCount + " · 警告：" + p.warningCount)
            Button(enabled = !busy, onClick = { clearConfirm = true }) {
                Text("清除已导入档案")
            }
        }
        if (clearConfirm) {
            AlertDialog(
                onDismissRequest = { clearConfirm = false },
                title = { Text("删除账号档案？") },
                text = { Text("只删除本机导入的角色资料，不删除单独保存的备注。") },
                confirmButton = {
                    TextButton(onClick = {
                        profileStore.clearImported()
                        importedProfile = null
                        clearConfirm = false
                        status = "本地导入档案已删除"
                    }) { Text("删除") }
                },
                dismissButton = {
                    TextButton(onClick = { clearConfirm = false }) { Text("取消") }
                }
            )
        }
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
        OutlinedTextField(value = account, onValueChange = { if (it.length <= 12000) account = it },
            label = { Text("手动补充账号信息 / 培养偏好（可不填）") },
            minLines = 3, modifier = Modifier.fillMaxWidth())
        Button(enabled = !busy, onClick = {
            busy = true
            scope.launch {
                val result = withContext(Dispatchers.IO) {
                    runCatching { profileStore.saveNotes(account) }
                }
                result.onSuccess { status = "账号备注已在本机加密保存" }
                    .onFailure { status = "保存失败：" + (it.message ?: "未知错误") }
                busy = false
            }
        }) { Text("保存账号备注（本地）") }
        Text("游戏场景知识库（离线可用）", style = MaterialTheme.typography.titleMedium)
        if (sceneCatalog == null) {
            Text("未能读取场景知识库，请检查 APK 构建；不会发送伪造的游戏数据。")
        } else {
            Text("已收录 " + sceneCatalog.pcCount + " 个 PC 场景、" +
                sceneCatalog.touchCount + " 个安卓触屏场景（共 " +
                sceneCatalog.scenes.size + " 个不同 ID）。")
            Text("手动选场景是告诉 AI 你目前可能在哪里，不是自动检测；不需要先连接 GPT。")
            OutlinedTextField(
                value = sceneQuery,
                onValueChange = { sceneQuery = it; sceneMenu = true },
                label = { Text("搜索场景（例：末日、队伍、邮箱）") },
                modifier = Modifier.fillMaxWidth(), singleLine = true,
            )
            Button(onClick = { sceneMenu = true }) {
                Text("当前场景：" + (sceneCatalog.get(selectedSceneId)?.name ?: "不指定"))
            }
            DropdownMenu(expanded = sceneMenu, onDismissRequest = { sceneMenu = false }) {
                DropdownMenuItem(text = { Text("不指定场景") }, onClick = {
                    selectedSceneId = ""
                    sceneQuery = ""
                    sceneMenu = false
                })
                sceneCatalog.search(sceneQuery, limit = 12).forEach { item ->
                    val available = when {
                        item.pc != null && item.touch != null -> "PC/安卓"
                        item.pc != null -> "仅 PC"
                        else -> "仅安卓"
                    }
                    DropdownMenuItem(
                        text = { Text(item.name + " · " + available) },
                        onClick = {
                            selectedSceneId = item.id
                            sceneQuery = ""
                            sceneMenu = false
                        }
                    )
                }
            }
            sceneCatalog.get(selectedSceneId)?.let { scene ->
                Text("所选：" + scene.name + "（用户手选，未经截图确认）")
                val pcOptions = scene.pc?.destinations?.mapNotNull {
                    sceneCatalog.get(it)?.pc?.name
                }.orEmpty().take(12)
                val touchOptions = scene.touch?.destinations?.mapNotNull {
                    sceneCatalog.get(it)?.touch?.name
                }.orEmpty().take(12)
                Text("PC 已知入口：" +
                    (if (scene.pc == null) "未收录" else pcOptions.joinToString("、").ifBlank { "无" }))
                Text("安卓已知入口：" +
                    (if (scene.touch == null) "未收录" else touchOptions.joinToString("、").ifBlank { "无" }))
            }
            Text("这里只显示静态导航资料；不会自动读取或操作游戏，模型也不能执行原版 PC 点击脚本。")
        }
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
                        val knownAccount = listOfNotNull(
                            importedProfile?.promptSummary,
                            account.takeIf { it.isNotBlank() }?.let { "用户补充备注：\n" + it }
                        ).joinToString("\n\n")
                        val situation = listOfNotNull(
                            sceneCatalog?.contextFor(selectedSceneId)?.takeIf { it.isNotBlank() },
                            observation.takeIf { it.isNotBlank() }?.let {
                                "用户自行描述的当前情况（未通过游戏画面确认）：\n" + it
                            }
                        ).joinToString("\n\n")
                        if (provider == Provider.CHATGPT)
                            chatgpt.respond(oauthModel, knownAccount, situation, question)
                        else analyze(provider, customBase, apiKey, model, knownAccount, situation, question)
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
