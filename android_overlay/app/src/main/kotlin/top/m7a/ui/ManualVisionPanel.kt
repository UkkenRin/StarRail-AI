package top.m7a.ui

import android.graphics.Bitmap
import android.os.SystemClock
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Deliberately MANUAL screenshot-to-vision-API preview, not an autonomous loop.
 * We allow a recently stopped session's frozen image, because this is not a
 * real-time game-control observation. Stale frames NEVER authorize input.
 */
@Composable
internal fun ManualVisionPanel(
    provider: Provider,
    customBase: String,
    apiKey: String,
    model: String,
    bitmap: Bitmap?,
    lastFrameElapsedMs: Long,
) {
    val scope = rememberCoroutineScope()
    var confirmSend by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("没有发送任何画面到云端") }
    var proposal by remember { mutableStateOf<ManualVisionProposal?>(null) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("视觉 AI：手动分析最后一帧（只读试验）",
            style = MaterialTheme.typography.titleMedium)
        Text("当前采集画面默认只存在本机内存。仅当你点击下方按钮并再次确认时，" +
            "才会将一张压缩后的截图发给选定的视觉 AI API。可能包含游戏账号、通知或其他应用内容。")
        Text("仅支持可以接收图片的 API Key 模型；不使用 ChatGPT 账号 OAuth。" +
            "每次应用进程最多 3 次请求，至少间隔 10 秒；图片和模型回答不写入文件。")
        if (provider == Provider.CHATGPT) {
            Text("请先在下方切换为支持图片的 API Key 服务。ChatGPT 订阅不等同于 API 访问。")
        }
        Button(
            enabled = !busy && bitmap != null && lastFrameElapsedMs > 0L &&
                provider != Provider.CHATGPT && apiKey.isNotBlank() && model.isNotBlank(),
            onClick = { confirmSend = true },
        ) { Text(if (busy) "正在请求视觉 AI……" else "选择当前最后一帧，准备分析") }

        if (confirmSend) {
            AlertDialog(
                onDismissRequest = { confirmSend = false },
                title = { Text("确认上传一张截图？") },
                text = { Text("将当前最后一帧的 JPEG 图片发送到 ${provider.title}，" +
                    "仅用于这一次只读场景分析。图片可能含个人信息，" +
                    "API 供应商可能按其规则存储或计费。不会自动连续发送，也不会点击游戏。") },
                confirmButton = {
                    TextButton(onClick = {
                        confirmSend = false
                        val frame = bitmap
                        val age = SystemClock.elapsedRealtime() - lastFrameElapsedMs
                        when {
                            frame == null -> status = "当前没有可用截图"
                            age !in 0L..120_000L -> status = "截图已超过两分钟或时间无效，请重新采集"
                            else -> {
                                val limit = ManualVisionQuotaSession.quota.consume(
                                    SystemClock.elapsedRealtime())
                                if (limit != null) {
                                    status = when (limit) {
                                        "cooldown" -> "操作过快：两次请求至少间隔 10 秒"
                                        "request_budget_exhausted" -> "已达到本次应用进程 3 次手动上传上限"
                                        else -> "请求计数器状态异常"
                                    }
                                } else {
                                    proposal = null
                                    busy = true
                                    status = "仅此一张图片正在发送到视觉 API"
                                    scope.launch {
                                        val result = withContext(Dispatchers.IO) {
                                            runCatching {
                                                queryManualVisionProposal(
                                                    provider, customBase, apiKey, model, frame)
                                            }
                                        }
                                        result.onSuccess {
                                            proposal = it
                                            status = "视觉 AI 已返回只读建议，不会执行任何操作"
                                        }.onFailure {
                                            status = "分析失败：" + (it.message ?: "网络或模型错误")
                                        }
                                        busy = false
                                    }
                                }
                            }
                        }
                    }) { Text("同意上传这一张") }
                },
                dismissButton = {
                    TextButton(onClick = { confirmSend = false }) { Text("不上传") }
                },
            )
        }

        Text(status, color = MaterialTheme.colorScheme.onSurfaceVariant)
        proposal?.let { result ->
            Text("场景判断：" + result.scene)
            Text("画面依据：" + result.observations)
            Text("下一步建议（未执行）：" + result.nextStep)
            if (result.gamerAction.isNotBlank()) {
                Text("可选 Gamer 动作提案（仅预览）：" + result.gamerAction.take(280))
                Text("严格解析结果：" + (result.gameActionParse?.reason ?: "未解析") +
                    "。即使格式正确也严禁执行。")
            }
        }
    }
}
