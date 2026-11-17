# Gamer / GameScaling 动作协议：StarRail-AI 适配实验

> 状态：**兼容协议的独立解析器 + Android 本机只读解析界面已提交源码**；以 CI 运行结果为准，尚未真机确认。**不是已接入 Gamer 模型，也不具备 AI 直接操作游戏能力。**

## 参考来源与代码边界

- Gamer 研究项目：https://github.com/RainBowLuoCS/Gamer
- GameScaling 实际 Python 推理客户端：https://github.com/THUSI-Lab/GameScaling
- 动作格式参考：Gamer README 的「Action Format」和 GameScaling 的 gamescaling/action.py、gamescaling/policy.py；项目各自仓库标注 Apache-2.0。我们以公开**协议格式为参考独立编写严格解析器**，没有复制 GameScaling 的 parser/推理客户端源码，也没有引入模型权重或新的运行时依赖。
- 当前 Gamer 的 streaming-vllm/readme.md 仍标记 **Coming soon**；演示中针对《星穹铁道》的专用微调/RL 权重不在公开仓库内。不要混淆 GameScaling 预训练公开权重、Gamer 实时推理引擎及其演示中的最终模型。

## 输入格式

模型输出可包含一个已结束的思考块，然后必须紧接完整的一段：

~~~text
<think>optional reasoning</think>
<|action_start|>X Y Z ; g1 ; g2 ; g3 ; g4 ; g5 ; g6<|action_end|>
~~~

- 每一段总长约 200 毫秒，恰好有 6 个约 33 毫秒的按键组；连续组中的同一按键表示持续按住。
- X/Y 为相对于屏幕宽高的千分比鼠标位移，限定 -1000 到 1000；Z 为滚轮格数，限定 -5 到 5。
- 每组 0～4 个大小写敏感的已知键鼠 token（如 W、LB、Space）；空组表示释放所有按键。
- 不接受省略标记、额外动作、缺失或额外分组、未知或重复按键、浮点/无限值、超界值、被截断的思考块和模糊的额外文本。

**本实现刻意比研究用的 GameScaling 解析器更严格**：原研究实现有实验性 lenient/fallback、坐标限幅、未知 token 丢弃等行为；我们面向真实用户设备的输入边界一律**拒绝整个模糊或超限的动作**。解析通过仅表示格式正确，不代表与当前场景、角色或平台兼容。

### 示例（离线，不执行）

~~~python
from starrail_core.gamer_protocol import parse_gamer_action, preview_gamer_action

text = "<think></think><|action_start|>0 0 0 ; W ; W ; W ; W ; W ; W<|action_end|>"
parsed = parse_gamer_action(text)
assert parsed.valid
assert parsed.chunk.duration_ms == 200
assert parsed.chunk.steps == (("W",),) * 6
assert preview_gamer_action(text)["executable"] is False
~~~

运行 python -m unittest discover -s tests -p test_gamer_protocol.py -v 可在没有 GPU/网络的情况下验证协议示例和拒绝案例。

## Android 页面怎么用

安卓「AI 顾问」新增 **「Gamer / GameScaling 动作协议 · 只读实验」**：

1. 打开该区域，默认已填入一个只含 W 的公开协议示例，或粘贴新的完整动作字符串。
2. 点击「仅解析动作，不执行」。
3. 页面显示六组键鼠动作、相对位移、滚轮值，或明确拒绝原因。**绝不会因此向游戏发送触摸/键鼠事件，或调用任意视觉/云模型**。

代码：starrail_core/gamer_protocol.py、android_overlay/app/src/main/kotlin/top/m7a/ui/GamerProtocol.kt、GamerProtocolPreviewPanel.kt。Python 和 Kotlin 都使用严谨的协议语义，并有各自的测试。Android 构建仍由固定 March7thAssistant 版本加 scripts/apply_android_overlay.py 补丁生成。

## 为什么暂时不允许直接操作

Gamer/GameScaling 输出的是**PC 键鼠意图**，不是可验证的 Android 控件坐标。直接把相对鼠标位移或 LB 改成平板屏幕触摸可能误触游戏以外的界面。Android 当前的「疑似游戏前台」仅为 Usage Access 提供的非可信提示。即使协议解析成功，也**没有真实的、可作为输入许可的焦点/可见性/受控触摸执行器**。

真实执行必须另行实现且逐项验收：

1. 根据游戏输入场景把动作转换成平台无关、高层**受验证目标**的提案，不把原始六组键鼠文本直接交给 ADB/Shell/无障碍触摸。
2. 在调用前由本地执行器重新核验游戏窗口、采样时间、用户授权和 AgentHandoff 独占输入租约；不允许 M7A 脚本与 AI 同时点击。
3. 用户随时停止、危险操作（抽卡/购买/消耗资源等）需要单独确认；执行后必须观察结果、超时停止。
4. 按需接入合法可用的视觉 Provider，并通过 LiveVisionGate 限制上传关键帧和预算。

## 用普通视觉 AI 临时代替 Streaming-vLLM（单帧原型）

**已提交 Android 代码，必须通过 Android CI、API 真实调用和 OriginOS 真机验收后才可称可用。**

- 在「AI 顾问」先通过系统 MediaProjection 获取本机画面（采集本身不需要网络）。
- 在同一页面选择支持图片的 Chat Completions API Provider、视觉模型、API Key；**不会因为选择 Provider 而自动上传**。不保证所有兼容 API 都支持图片；当前 ChatGPT OAuth 尚未独立验证多模态输入，暂不走此通路。
- 页面底部的「用视觉 AI 代替 Gamer 推理引擎」允许填写简短目标，并**明确确认每次要发的单张截图**。确认弹窗展示截图与 Provider；如果截图包含通知、账号、其他应用内容，必须取消。
- 一次只发送这张最多 640px 的 RAM 预览及目标，不发送账号导入文件、不连续录制上传、不自动重试；单次使用限制 10 秒冷却与本次应用会话最多 8 次**尝试**。截图距采集超过 120 秒时要求重采；该限制只用于人工确认的静态场景提议，**不是自动驾驶场景新鲜帧门槛**。
- 云端返回文本由已有的严格 `GamerProtocol.parse` 校验；多余文字、缺失动作标记、非法键鼠代码都会被拒绝。即便解析成功，也只显示六组 PC 键鼠建议，**不发送任何系统输入、不允许自动游玩**。
- 使用独立 `GamerVisionProtocol.kt`、`GamerVisionCloudClient.kt` 与 `GamerVisionProposalPanel.kt`；没有复制未公开 Gamer 推理引擎，也没有把 GameScaling 通用 vLLM 客户端直接搬到安卓。

这个实验可以检验**真实图片 → 通用视觉模型 → 200ms Gamer 格式提案**的可达性，不能据此推断通用模型有星铁操控训练经验或具备论文演示的 65ms 延迟。未来需要保留历史帧、动作后校验、真实焦点与可信的安卓手势目标、Script→Agent 独占交接及帧/任务预算，才能开始自动执行。

## 下一步

- [ ] 接入真实模型生成 Gamer 兼容格式的**离线提案**，先在模拟画面上核验语义（不是本地试玩时就调用云）。
- [ ] Windows 安全键鼠 executor 与 Android 受验证目标触屏 executor；先做明确限制的测试，不开放任意控制。
- [ ] 真机验收本机解析 UI 和原先屏幕采集/权限流程。
- [ ] 评测端侧推理/云端推理延迟和 Token 预算；不可假设论文中 H200 上的 65 ms 能在平板实现。

本功能仅受协议格式启发。**原作者、关联项目和许可证信息**请见 [CREDITS.md](../CREDITS.md)；没有获得任何官方合作或模型授权声明。
