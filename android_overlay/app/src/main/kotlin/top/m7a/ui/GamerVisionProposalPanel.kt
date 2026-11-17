package top.m7a.ui

import android.graphics.Bitmap
import android.os.SystemClock
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Explicit opt-in for ONE in-memory screenshot -> visual model -> strict Gamer proposal.
 * Never runs a game command. UI consent applies to this request ONLY.
 */
@Composable
internal fun GamerVisionProposalPanel(
    capture: LocalCaptureState,
    provider: Provider,
    customBase: String,
    apiKey: String,
    model: String,
) {
    val scope = rememberCoroutineScope()
    var objective by rememberSaveable {
        mutableStateOf("只判断当前截图是否需要等待；不能确认键鼠映射时输出空动作")
    }
    var pending by remember { mutableStateOf<Bitmap?>(null) }
    var pendingFrameTimeMs by remember { mutableStateOf(0L) }
    var busy by remember { mutableStateOf(false) }
    var attempts by remember { mutableStateOf(GamerVisionRequestQuota.attempts()) }
    var status by remember { mutableStateOf("尚未发送任何图片") }
    var proposal by remember { mutableStateOf<GamerActionChunk?>(null) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("用视觉 AI 代替 Gamer 推理引擎 · 单帧只读实验",
            style = MaterialTheme.typography.titleMedium)
        Text(
            "默认不上传。你主动确认后，仅将当前内存中的一张截图和下方目标发送给所选 API Provider，" +
                "要求生成 Gamer 六组键鼠动作提案；不发送账号档案、也不执行动作。截图可能包含其他应用或通知。",
        )
        Text("需要支持图片的 Chat Completions 模型，不能仅用纯文本模型；" +
            "当前实验版 ChatGPT OAuth 未验证图片输入，请先用 API Key。")
        OutlinedTextField(
            value = objective, onValueChange = { if (it.length <= 300) objective = it },
            label = { Text("只读观察目标（最长 300 字）") },
            modifier = Modifier.fillMaxWidth(), minLines = 2,
        )
        Text("本次应用会话已尝试：$attempts / ${GamerVisionProtocol.MAX_REQUESTS_PER_SESSION} 次；" +
            "每次间隔至少 10 秒。云端费用由所选模型和供应商决定。")

        Button(
            enabled = !busy && capture.preview != null,
            onClick = {
                val now = SystemClock.elapsedRealtime()
                val block = GamerVisionRequestQuota.peek(now, capture.lastFrameElapsedMs)
                status = when {
                    block != null -> block
                    provider == Provider.CHATGPT -> "ChatGPT OAuth 视觉接口尚未验证，当前不允许截图上传"
                    apiKey.isBlank() || model.isBlank() -> "请在上方先选择支持视觉的 API 模型并填写 API Key"
                    objective.isBlank() -> "请填写观察目标"
                    else -> {
                        pending = capture.preview
                        pendingFrameTimeMs = capture.lastFrameElapsedMs
                        "请确认是否只上传下面这一张截图"
                    }
                }
            },
        ) { Text(if (busy) "视觉模型请求中……" else "选择一帧并确认发送给 AI") }

        Text(status, color = MaterialTheme.colorScheme.onSurfaceVariant)
        proposal?.let { chunk ->
            Text("严格格式校验通过，但绝不执行", style = MaterialTheme.typography.titleSmall)
            Text("Gamer 200ms 提案 · 鼠标 X=${chunk.mouseXPermille}‰ / Y=${chunk.mouseYPermille}‰ / 滚轮=${chunk.scrollNotches}")
            chunk.groups.forEachIndexed { index, keys ->
                Text("第 ${index + 1} 组：${keys.joinToString(" + ").ifEmpty { "无按键" }}")
            }
            Text("这些仍是 PC 键鼠语义，不是已验证的安卓触摸坐标。")
        }
    }

    val snapshot = pending
    if (snapshot != null) {
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text("确认向视觉 AI 发送这一张截图？") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("发送给：${provider.title} / ${model.ifBlank { "未选模型" }}")
                    Text("将上传这一张截图与观察目标；可能包含游戏外的敏感信息。" +
                        "不会附带账号档案、自动录像或触控权限。")
                    Image(
                        bitmap = snapshot.asImageBitmap(),
                        contentDescription = "即将上传的截图",
                        modifier = Modifier.fillMaxWidth().heightIn(max = 220.dp),
                        contentScale = ContentScale.Fit,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    pending = null
                    val now = SystemClock.elapsedRealtime()
                    val block = GamerVisionRequestQuota.consume(now, pendingFrameTimeMs)
                    if (block != null) {
                        status = block
                    } else {
                        attempts = GamerVisionRequestQuota.attempts() // Already consumed atomically before network.
                        busy = true
                        proposal = null
                        status = "已确认上传单帧；等待视觉模型提案（不会点击游戏）"
                        scope.launch {
                            val result = withContext(Dispatchers.IO) {
                                runCatching {
                                    GamerVisionCloudClient.propose(
                                        provider, customBase, apiKey, model, objective, snapshot,
                                    )
                                }
                            }
                            result.onSuccess { reply ->
                                val parsed = GamerProtocol.parse(reply)
                                proposal = parsed.chunk
                                status = if (parsed.valid)
                                    "收到 Gamer 格式提案，解析通过，仅展示，不执行"
                                else "模型未返回严格 Gamer 协议：${parsed.reason}；已安全拒绝"
                            }.onFailure { e ->
                                status = "视觉请求失败：" + (e.message ?: "网络或模型不兼容").take(150)
                            }
                            busy = false
                        }
                    }
                }) { Text("确认发送一次") }
            },
            dismissButton = {
                TextButton(onClick = { pending = null }) { Text("取消，不上传") }
            },
        )
    }
}
