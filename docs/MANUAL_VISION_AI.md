# 手动视觉 AI 试验：使用现有模型代替自建 Gamer 推理服务器

> 状态：Android 实现源码已提交，需 CI 编译与 iQOO Pad5 Pro / OriginOS 真机验收。**仅单张截图、明确确认、只读分析；并非 Gamer RL 权重、游戏控制或实时自动化。**

## 思路与边界

Gamer 公开仓库目前公开论文、动作/提示词格式与演示；`streaming-vllm` 目录仍为 Coming soon。相关 `THUSI-Lab/GameScaling` 项目有实际 Python 客户端、格式解析和预训练权重，但没有公开 Gamer 在《崩坏：星穹铁道》演示的专用 SFT/RL 权重。我们不复制或假冒 Gamer 模型。

**现成视觉 AI API 可以承担画面理解与高层规划**，由 API 服务方负责模型推理部署；不是 1:1 替代 Gamer 专用 KV-cache、分块流式输出和 65ms 级实时控制。常规视觉模型也不能直接继承专用游戏操作能力。

第一阶段先走 Android 本地 Bitmap → 用户明确选一帧 → JPEG ≤640px / ≤500KB → HTTPS 视觉 Chat Completions API → 场景+可见证据+只读下一步建议。这套链路不使用 OCR，不启动游戏触屏，完全不触碰 Shizuku/M7A 自动化。

## 安全约束与预算

1. **默认不上传**。截图平时仅在设备 RAM 中，须用户点击「选择当前最后一帧，准备分析」并在二次弹窗选「同意上传这一张」。
2. 不需要用户电脑、不保存 API Key、不在 Git/日志/文件写入图片或模型原始内容；只在 UI 内存里显示返回的 JSON。自定义 HTTPS 端点由用户主动填写，不要使用不可信地址。
3. 每个 App 进程最多 3 次手动请求，两次至少间隔 10 秒；消耗次数发生在网络请求之前，失败仍计数，防止意外重试风暴。
4. 只有最后采样时间距现在 **2 分钟内** 的本机预览允许手动上传。这可能是**已经停止采集的历史帧**，只允许解释画面，**不能作为实时游戏焦点证据或触屏依据**。
5. 只提供通过 API Key 访问且明确支持图片的 OpenAI / 通义 / 自定义兼容服务等。部分文字模型（例如默认 DeepSeek 模型）可能拒绝图像输入；ChatGPT 账号授权 **尚不支持此图像通路**，ChatGPT 订阅不等于任意 API 模型额度。
6. 模型输出要求 JSON `scene`, `observations`, `next_step`, `gamer_action`。**默认关闭 Gamer 动作提案**（必须为空）。可选勾选「Gamer 格式动作提案」后才允许通用视觉模型提供一段 200ms、六组 PC 键鼠**只读提案**；不确定时仍必须为空。返回内容通过严格 Gamer 协议解析，任何解析结果都不会实际触摸游戏。真正执行还需要可信焦点/控制租约/受限手势转换和真机验证。
7. 这条是**用户手动选图的单次诊断通路**。尚**未**连接自动 `LiveVisionGate`、端到端信任焦点、事件触发批量视觉、持续流推理或 GameExecutor。

注意：共享整个 Android 屏幕时可能采到通知、账号、支付界面或其他应用。上传前请观察预览、不要在屏幕上展示敏感资料。调用视觉 API 可能计费并由提供方按各自政策处理数据。

## 平板操作

1. 下载安装最新 StarRail-AI 实验 APK，从「AI 顾问」点击「授权并开始本地观察」。切换到星铁停留几秒，然后从录屏通知里停止采集。
2. 返回 AI 顾问确认最后一帧确实是要分析的画面。向下找到 AI 服务选择，使用**支持图片的 API Key 模型**（不支持图像的模型会返回模型/API 错误），仅输入自己的 API Key。
3. 点击「选择当前最后一帧，准备分析」，阅读二次确认后决定是否上传这一张。
4. 查看场景判断、可见依据与建议。App **不会**执行建议；失败时可提供错误提示截图（请隐藏 API Key 和个人资料）。

## 实现

- Android：`ManualVisionProposal.kt` 负责单次 JPEG+HTTPS 图像消息、限长 JSON 解析，不保存或自动执行；`ManualVisionQuota.kt` 是进程级最大 3 次与 10 秒冷却；`ManualVisionPanel.kt` 实现二次明确同意。
- `AiAdvisorScreen.kt` 复用已有 API Key Provider/模型设置，`scripts/apply_android_overlay.py` 复制源码和单元测试。
- `ManualVisionProposalTest.kt` 覆盖只读结果、Gamer 字符串解析、无效 JSON、限额与冷却；新 UI 和网络提供方的真实调用仍需要设备端验证。

参考：https://github.com/RainBowLuoCS/Gamer 和 https://github.com/THUSI-Lab/GameScaling 。两者 Apache-2.0 许可证只适用各自公开代码，本仓库仍按已有 GPL-3.0 约定维护。
