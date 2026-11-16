# Android-MCP / 无障碍直读方案评估（2026-11-17）

此文是设计与技术调查，**不代表安卓 APK 已接入 MCP、前台录屏或 AI 游戏控制**。用户重点需求：iQOO Pad5 Pro/OriginOS、无需 PC、不强制 OCR，能在前台游戏上以录屏式连续观察。

## 可参考的安卓开源项目

- [mimic](https://github.com/khimaros/mimic)：安卓 AccessibilityService + 本机 localhost MCP/REST/Intent，细粒度授权机制；最接近“在平板本地直接提供 AI 工具”，不需电脑作服务器。
- [DroidPilot](https://github.com/youichi-uda/droidpilot)：安卓无障碍原生 UI 树与手势，并通过 Wi-Fi 与 MCP 客户端通信；声称不需 ADB/OCR。
- [AndroidAgentBridge](https://github.com/pluginslab/android-agent-bridge)：安卓 Kotlin 无障碍服务 + LAN MCP 访问，支持 UI 节点查找、手势和用户授权的 MediaProjection 截图。
- [android-agent-mcp](https://github.com/baiamansama/android-agent-mcp)：设备内读取无障碍树，执行节点动作与动作后验证。

上述项目是参考而不是已直接兼容星铁/OriginOS 的证明；应独立检查许可证、权限和运行条件。

## 与 Windows-MCP 的关系

Windows-MCP 通过 UI Automation 读取 Windows 原生控件；Android 可以用 AccessibilityService / AccessibilityNodeInfo 直接读取系统暴露的节点文本、类型、contentDescription、边界和点击动作。**普通 App 可以无需 OCR/云视觉识别部分控件**。

但《崩坏：星穹铁道》战斗 UI 等为实时渲染，自绘游戏内容可能根本不暴露可用无障碍树；不能假定系统能直接读取血量、敌人、技能点和角色属性。在这种场景下仍然需要像素画面/Live Vision，虽然可以不采用 OCR 作为必经步骤。

## 计划：本机双通道

1. SemanticObserver：用户授权后读取当前窗口的无障碍树，优先识别系统界面/启动器/可访问弹窗。检测真正有用节点数量；空树不得捏造游戏状态。
2. FrameSource：MediaProjection 或经过验证的 Shizuku 截帧后端，连续在本地内存观察实体屏正在运行的游戏，不需要另启一个游戏虚拟屏。
3. VisionGate：已有的跨平台本地门控负责焦点/过期帧/重复画面/事件/额度/冷却，只在用户同意时允许上传必要关键帧。持续本地观看**不等于持续云端视频流**。
4. VisionAgent / Planner：支持视觉的模型直接理解帧，OCR 降级为需要精确数值时的可选能力。
5. ActionGuard + Executor：游戏包名与焦点核对后才允许白名单触控；不得开放任意 Shizuku Shell、跨应用触控或破坏性操作。

### 安卓系统特别说明

MediaProjection 将当前实体屏内容**镜像**到应用可读取的 Surface。它内部使用系统 VirtualDisplay 对象，但**不是三月七小助手为游戏新建的独立游戏运行显示器**。Android 14+ 捕获会话需新的用户授权；不能声称永久免提示或凭 Shizuku 必然绕过全部授权。

我们希望 AI 核心直接在 APK 内调用能力，因此在当前阶段**不需要先暴露 LAN/公网 MCP Server**。若将来支持外部 MCP 客户端，则默认 localhost、强授权、关闭开关和最小工具权限，绝不默认开放到公网。

## MVP 验收顺序

- [ ] iQOO Pad5 Pro 安卓只读无障碍树诊断，比较“系统设置 App”和“星铁游戏窗口”实际可用节点。
- [ ] 如果游戏节点无意义，则明确标记 semantic_unavailable，直接改走 Live Vision，绝不声称读取到未暴露的角色资料。
- [ ] 安卓前台本地画面流与安全停止按钮，验证画面方向/焦点/OriginOS后台限制。
- [ ] 用户主动点击“分析当前画面”：授权后调用支持视觉的模型，不强制 OCR。
- [ ] 逐步接入已有 VisionGate 预算限制和游戏内触控/结果验证。

## 参考来源

- [Android AccessibilityService API](https://developer.android.com/reference/android/accessibilityservice/AccessibilityService)
- [Android MediaProjection 官方文档](https://developer.android.com/media/grow/media-projection)
- [Windows-MCP 评估](WINDOWS_MCP_EVALUATION.md)
- [Live Vision 设计](LIVE_VISION.md)
