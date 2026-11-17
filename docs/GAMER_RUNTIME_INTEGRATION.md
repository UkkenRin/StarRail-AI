# Gamer/StarRail-AI 可复用组件接线（首轮集成）

> 2026-11-17。**真正接了两条数据/决策链，但尚未实现安卓自主点击。** 不能把通过 mock 测试等同于设备实测。

## 1. 安卓本地画面 → ChatGPT OAuth 视觉分析（实际 App 代码）

- 复用原版 `LocalCaptureFrames` 的用户授权 MediaProjection 画面（内存中，最长边 640px），在现有 `ManualVisionPanel` 冻结当前选中的**一张**图片。
- 二次弹窗确认后，保留现有 `ManualVisionQuotaSession` 的进程 3 次/冷却 10s、拍摄时间不超过 2 分钟、用户可取消、不自动上传。
- 如选「ChatGPT（账号授权）」且账号已有方案权限，使用当前可用 `oauthModel`，以 Responses `input_text + input_image`（JPEG data URL, detail=low）调用 `ChatGptOAuth.analyzeFrame`；`store=false`、`stream=true`，等待 `response.completed`。
- 严格限制 640px、500KiB，禁止重定向转发 Token/截图，不保存画面或回复。复用 `manualVisionSystemPrompt`、`parseManualVisionProposal`、`restrictGamerSuggestion` 返回 scene/observations/next_step，Gamer 动作**仅可选择只读展示**。
- 使用 API Key 的既有手动视觉功能保持不变。未授权 OAuth 或该账号可用模型不支持图片时直接报错，**不偷用另一个 Provider**。
- **这不是直播画面接入模型，不是 OAuth 自动控制，也不是我（当前聊天）可直接访问平板。** OriginOS 上 OAuth 浏览器回调、截图采集、模型权限仍须安装后实测。

## 2. Gamer 稀疏思考 → 可信执行 → 新帧验证 → 记忆（Python 可调用运行环）

将既有 PR #18、#19、#20 中的 `GamerStepCoordinator`、`ReadOnlyGameScalingPreview`、`SparseThinkGate` 整合在同一分支。新增：

- `starrail_core/gamer_runtime.py` 的 `GamerAgentRuntime.run_one` **真的会调用注入的模型与可信设备 Port**（前提是调用方提供可用模型 Port、独立的 FrameProof、独占 lease、可信 LocalBinding 与设备执行器）。它并非只有检查协议字符串。
- `starrail_core/verified_memory.py` 的 `VerifiedGameMemory`：最近 5 条**已验证成功**的转移、简短上下文、按目标+游戏版本+场景+局部 8×8 画面相似度匹配的动作复用。已建议但尚未执行/成功验证的动作不准写入记忆；游戏更新可全部失效。经验复用仍要经过本地目标定位、OS 锁、输入租约和动作后复核。
- 对正常重复画面、M7A 已运行流程、已验证缓存方案不产生新模型请求；未知局面在用户授权、真实焦点、画面新鲜、预算允许时才考虑请求模型；同一失败画面不会无限重试。
- `TrustedGamePort` 四个动作 `resolve/dispatch/observe_after/monotonic_ms` 是平台实现的契约。必须由 Android/Windows **单独**落实 OS 输入锁、真实包名+焦点、当前设备显示/触控目标、屏幕尺寸映射、系统停止键，不可信模型不能创建 FrameProof、LocalBinding 或随机 input lease。
- `ModelPort` 只接收许可的图像和至多 1200 字符的**已验证**短历史。可由通用 VLM、Python GameScaling 客户端或将来 Gamer 权重的适配器实现。
- `tests/test_gamer_runtime.py` 用 fake model + fake trusted OS Port 验证：首次推理→发送一次动作→真实后帧验证→记录经验；下次同场景复用不再调用模型；1000 次 routine 不请求；无授权/失焦/无映射/无后置事件拒绝；模型未执行动作不会进入记忆。

## 3. 尚缺什么，为什么没有“除了模型都完成”

**仍未实现或不能声称可用**：

1. 安卓 `LocalCaptureFrames` 的可信实时目标帧转成 Python `FrameProof`、`ThinkSignal`；本次视觉通路为用户手动选择的历史帧，**不能用于自动点击**。
2. Android/Windows 真实 `TrustedGamePort` 的手势执行与独立系统焦点验证。现有 M7A `DeviceService` 控制的是独立后台虚拟游戏屏，并不是任意实体屏的安全代理；当前项目只实现 Python 端可注入接口。
3. 自动模型决策对局连续运行、安卓 OAuth 视觉模型自动调用、长期持久存储和 Gamer 式实时服务 KV-cache。
4. 官方 GameScaling 0.8B/2B/9B/27B 权重是**不同于 Gamer-RL** 的公开 CPT 模型；`ReadOnlyGameScalingPreview` 仍默认一次一帧、并清空尚未执行的动作历史。本地 `VerifiedGameMemory` 已提供真实历史来源，但**尚未接入官方 Policy 的持续状态和实际安卓执行**。
5. 官方 Gamer Streaming-vLLM 仍为 Coming soon。未发布的部分不能宣称被复用。

## 4. 实际下一步

优先在 Android 真实游戏里核实 MediaProjection 画面、OAuth 一帧图像回传，随后把现有 M7A 设备控制**限定为已经验证的游戏会话**，补可靠的焦点/尺寸/触控绑定，使 `GamerAgentRuntime` 真正能够从本机画面执行**一次低风险可逆操作**并自动验证。再扩展长期任务和按任务预算。

参考：[Gamer](https://github.com/RainBowLuoCS/Gamer)、[GameScaling](https://github.com/THUSI-Lab/GameScaling)；官方源为各自 Apache-2.0，本站继续遵守 M7A GPL-3.0 与来源声明。
