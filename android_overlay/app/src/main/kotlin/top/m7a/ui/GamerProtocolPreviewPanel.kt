package top.m7a.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** User-initiated wire-format preview only. This composable NEVER sends game input. */
@Composable
internal fun GamerProtocolPreviewPanel() {
    var source by rememberSaveable {
        mutableStateOf("<think></think><|action_start|>0 0 0 ; W ; W ; W ; W ; W ; W<|action_end|>")
    }
    var inspected by rememberSaveable { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Gamer / GameScaling 动作协议 · 只读实验", style = MaterialTheme.typography.titleMedium)
        Text(
            "可以粘贴 Gamer 风格的模型动作字符串，看看能否解析成 200 毫秒、六组键鼠动作。" +
                "这不是 Gamer 模型，也不代表平板已经支持键鼠转换。此功能不会访问游戏、模型或网络，不会触摸屏幕。",
        )
        OutlinedTextField(
            value = source,
            onValueChange = { source = it; inspected = false },
            label = { Text("Gamer 动作字符串（仅本机解析）") },
            modifier = Modifier.fillMaxWidth(),
            maxLines = 4,
        )
        Button(onClick = { inspected = true }) { Text("仅解析动作，不执行") }
        if (inspected) {
            val result = GamerProtocol.parse(source)
            val chunk = result.chunk
            if (chunk == null) {
                Text("已拒绝不完整或不安全的动作格式：" + result.reason)
            } else {
                Text("解析成功，动作长度：200 毫秒 · 执行状态：禁止执行")
                Text("鼠标相对位移：X=" + chunk.mouseXPermille +
                    "‰、Y=" + chunk.mouseYPermille +
                    "‰；滚轮：" + chunk.scrollNotches)
                chunk.groups.forEachIndexed { index, keys ->
                    Text("第 " + (index + 1) + " 组（约 33 毫秒）： " +
                        if (keys.isEmpty()) "松开全部按键" else keys.joinToString(" + "))
                }
                Text("仅用于验证协议，未连接画面识别、游戏焦点校验和 Android 触摸执行器。")
            }
        }
    }
}
