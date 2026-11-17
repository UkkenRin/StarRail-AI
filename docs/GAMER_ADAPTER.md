# Gamer / GameScaling 动作协议：StarRail-AI 适配实验

> **集成分支更新：** 这份文档描述原始**严格解析器与只读预览**的能力。后续整合代码见 [Gamer/StarRail-AI 组件接线](GAMER_RUNTIME_INTEGRATION.md)：`gamescaling_official.py` 可选导入上游真实 Python Policy，`gamer_runtime.py` 在有**可信本地 Port + 独占权限 + 新帧证据**时调用模型与执行器，`verified_memory.py` 根据已验证转移复用策略；安卓已增加 **OAuth 单张手动截图视觉分析**。这些功能仍不等于在 APK 上实现自动操作。

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

## 使用现有视觉 AI API 的先行方案

已开始开发 [安卓手动视觉 AI 单帧分析](MANUAL_VISION_AI.md)：用用户选择的视觉模型服务端承担单张截图理解与高层建议推理，无需自己部署 Streaming-vLLM。默认只返回场景文字；用户也可以**额外勾选**生成 Gamer 兼容的 200ms 键鼠动作**只读提案**，用已存在的严格解析器展示六组按键。两种方式均没有可执行触摸权限，也不承诺 200ms 实时游戏控制。需要用户主动上传许可和模型图像输入支持，安卓触摸执行器仍未实现。

## 下一步

- [ ] 接入真实模型生成 Gamer 兼容格式的**离线提案**，先在模拟画面上核验语义（不是本地试玩时就调用云）。
- [ ] Windows 安全键鼠 executor 与 Android 受验证目标触屏 executor；先做明确限制的测试，不开放任意控制。
- [ ] 真机验收本机解析 UI 和原先屏幕采集/权限流程。
- [ ] 评测端侧推理/云端推理延迟和 Token 预算；不可假设论文中 H200 上的 65 ms 能在平板实现。

本功能仅受协议格式启发。**原作者、关联项目和许可证信息**请见 [CREDITS.md](../CREDITS.md)；没有获得任何官方合作或模型授权声明。
