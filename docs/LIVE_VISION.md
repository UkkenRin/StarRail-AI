# StarRail-AI Live Vision — 跨平台实时视觉智能体（设计决策，2026-11-17）

> **方向已确定，尚未打通真实游戏画面流与云模型。** 当前 APK 不包含视频输入、自动视觉控制或全自动战斗。这里记录用户希望“像录屏一样持续看游戏、跳过 OCR 必经步骤”的需求，以及技术上的真实限制。

## 1. 核心决策：取消强制 OCR，不能取消画面采集

**视觉模型可以直接分析图像/视频帧，不必先 OCR 抽字。** 但现实中的 Computer Use 及 Live Video 仍需要接收屏幕像素或视频帧：跳过“存储截图文件、再调用 OCR”的显式步骤，不等于 GPU/模型完全不处理画面，也不等于零 Token。

我们采用 **Capture Stream → Local Change Detection → Vision Agent → Typed Plan → ActionGuard → Game Executor → Visual Verification**。

- 画面连续从屏幕设备采集进入**本地内存缓冲**，不需要保存每张 JPEG/PNG 文件；
- 先用廉价的本地缩略图差异、布局/状态变化、可选轻量视觉检测判断是否需要新的理解；
- 在用户授权且预算允许的条件下，仅向支持视觉/视频的模型发送必要的画面（可为关键帧或短视频片段；提供接口能力适配）；
- 模型输出受限制的高层战术/操作提案，**不直接获得 ADB shell 或任意触摸权限**；
- 执行后持续从本地画面流核对结果；必要时再次请求视觉模型，识别失败就停止。

**OCR 是可选插件，不再是 AI 理解画面的前置条件**。只有读取遗器的精准小数、复杂技能文字或核对属性时，才可以选择端侧 OCR 或图像局部高分辨率识别。保留已有 M7A OCR 以保护成熟日常功能。

## 2. 同一个大脑，两个屏幕捕获驱动

```text
                   ┌──────────────────────────────┐
                   │    StarRail AI Core           │
                   │ AccountProfile / VisionGate   │
                   │ VisionAgent / Planner / Guard │
                   └──────────────┬───────────────┘
                                  │
                    ┌─────────────┴─────────────┐
                    │                           │
            AndroidCapture                 WindowsCapture
          Shizuku / MediaProjection     Windows Graphics Capture /
          屏幕会话/焦点监控               Desktop Duplication
                    │                           │
           AndroidTouchExecutor             WindowsInputExecutor
             (受控输入)                     (受控输入)
```

### Android（iQOO Pad5 Pro / OriginOS）

1. 复用上游已存在的后台虚拟屏游戏会话，增设前台实体屏模式；**不能直接把 display_id 改为 0**，因为原预览帧依赖虚拟屏 ImageReader。
2. 前台可研究系统 MediaProjection 或合规的 Shizuku 采集接口；首选使用**用户授权的系统屏幕采集**。MediaProjection 会在内部为采集创建 VirtualDisplay，但不等于为游戏新开一个独立虚拟屏。
3. Android 14+ 每次 MediaProjection session 需要新的用户授权，旧令牌不能复用；须声明前台媒体投屏服务及权限，不可承诺永久免授权。
4. 必须确认前台包名确实是星铁、画面方向/坐标变换有效、用户没切到系统界面；否则停止发送输入。
5. 采集和视觉推理分离；中途设备锁屏/用户取消捕获/游戏不再聚焦，应丢弃缓存帧，停止 AI 操作。

### Windows

1. 首选 Windows Graphics Capture 或 Desktop Duplication 采集游戏窗口/屏幕内容。
2. 控制端尽量复用 M7A 的成熟 Windows 输入与任务引擎，但新 AI 动作经 ActionGuard 限制，确认窗口句柄/焦点后执行。
3. 整个 Live Vision 上层状态机与 Android 共享。PC 版本也不会单靠 Accessibility Tree 理解游戏：游戏 UI 通常是自绘渲染，而非普通表单控件。

## 3. 真正省 Token 的设计

本地持续录屏 **不等于** 云端持续视频上传。永远区分：
- `LOCAL_PREVIEW`：本地采样/画面变化检测（默认开启时不会消耗云 Token）；
- `CLOUD_KEYFRAME`：用户授权后按事件发送图像（有额度与冷却）；
- `CLOUD_VIDEO`：由支持实时视频的供应商接入；可配置短片段/低帧率/暂停上传并有用户预算；
- `LOCAL_VLM`：仅在设备支持且经性能实测时可选，本地模型调用无需云 Token，但消耗算力、电量、内存。

不论视频还是静态图像，云模型都需要处理视觉输入，持续视频有可能比事件触发方式贵得多。若每秒 1 帧持续 10 分钟，会有 **600 帧** 需要送入视频处理管线（只是帧数示意，不是 Token/价格估算）。费用依赖具体供应商、模型、分辨率、会话长度、计费规则和缓存。

采用**首选“智能视觉均衡”**：本地观察可连续运行，但只有回合开始、出现未知敌人机制、UI 发生重大变化或用户主动触发才申请云调用。已知动作和重复画面不重复上传。

新增纯 Python `LiveVisionGate`（`starrail_core/live_vision.py`）实现了**用户许可、焦点确认、事件门控、过期帧、去重、10s 冷却和每任务 8 次调用的示例预算**。它只是一个**被调用方使用的许可决策组件，不会截屏、上传视频或操控游戏**；数值为初始测试默认值，可依据端侧性能调整。Windows/Linux 单测应验证默认 0 次视觉上传和预算熔断。

## 4. 支持能力必须按 Provider 逐一判断

- OpenAI Computer Use 官方仍是截图和工具操作循环；不是不使用屏幕图像的“无截图模式”。
- UI-TARS Desktop 是视觉 GUI agent，官方仓库明确有 screenshot + visual recognition，不是无需图片/无需画面采集。
- Gemini Live API 有实时视频帧输入，其官方文档描述常见 1 FPS；不是每秒任意高帧率且零成本的游戏操作接口。
- 仅能调用 Chat Completions 的 API Key 并不意味着能流式看视频。现有 ChatGPT OAuth 也不自动授予 Live Video 接口能力：每个 Provider 必须确认其可用模型、授权、视频输入格式、费用、延迟及会话限制。
- 某些 App/网页可以直接读取 UI 语义树省图像，但《星穹铁道》这类自绘游戏界面通常不能依赖此技术替代视觉。

## 5. 工程验收及优先级

1. [ ] Android 可**在不打开第二个游戏显示器**的前提下取得前台游戏短时本地预览帧；提供清楚的用户授权与停止入口。Android 14+ 规避过期投屏 Token。
2. [ ] Windows 接入持续采集游戏窗口，不需要调用 OCR 才能得到视觉帧，提供可测试 mock FrameSource。
3. [ ] 统一画面流接口包含 `timestamp`、`width/height`、`rotation`、`displayId`、`targetPackage/window`、`source`、`frameAge` 与 `fingerprint`；强制过期检查。
4. [ ] 本地画面差异/事件检测在 1000 帧重复画面下保持**零次默认云上传**，并通过单元测试和性能测试。
5. [ ] 首个只读 VLM demo：用户明确点“分析当前游戏画面”，授权一次必要帧并让模型回答“当前是什么界面、我能做什么”，**此阶段不自动点游戏**。
6. [ ] 验证 AI 返回结构化局面 + 置信度，再研究只读战斗阶段识别与安全的动作计划。
7. [ ] 手机与 Windows 分别验证画面延迟、耗电、实时性、Token/费用；不允许未验证就宣称 AI 已能自动操作。
8. [ ] 上述通过后才逐项开放高频战斗操作（动作白名单、停止开关、资源消耗确认）。

## Windows-MCP 语义化观察层

已评估 [CursorTouch/Windows-MCP](https://github.com/CursorTouch/Windows-MCP) 的 UI Automation、Snapshot、Screenshot 和输入工具。普通 Windows 软件可依靠 UIA 的结构化节点减少截图与 OCR，Android 也可利用 AccessibilityNodeInfo 做对应的语义观察；**星铁游戏内部 HUD 不保证暴露语义节点**，仍必须保留 Live Vision 画面输入。详见 [Windows-MCP 项目调查](WINDOWS_MCP_EVALUATION.md)。

## 外部资料

- [OpenAI Computer Use 官方指南](https://developers.openai.com/api/docs/guides/tools-computer-use)
- [OpenAI Computer Use SDK 驱动规范](https://developers.openai.com/api/docs/guides/tools-computer-use-sdk-drivers)
- [UI-TARS Desktop](https://github.com/bytedance/UI-TARS-desktop)
- [Gemini Live 视频帧输入](https://docs.cloud.google.com/gemini-enterprise-agent-platform/models/live-api/send-audio-video-streams)
- [Android MediaProjection 系统授权](https://developer.android.com/media/grow/media-projection)
