# Windows-MCP 项目调查：可复用的语义化操作层（2026-11-17）

目标仓库：[CursorTouch/Windows-MCP](https://github.com/CursorTouch/Windows-MCP)，MIT License，Windows 10/11；当前 Python 3.14+。已检查上游 README 和以下源码：
- [Snapshot/Screenshot MCP 工具](https://github.com/CursorTouch/Windows-MCP/blob/main/src/windows_mcp/tools/snapshot.py)
- [Windows UIA/无障碍树](https://github.com/CursorTouch/Windows-MCP/blob/main/src/windows_mcp/tree/service.py)
- [输入工具](https://github.com/CursorTouch/Windows-MCP/blob/main/src/windows_mcp/tools/input.py)
- [DXGI/dxcam 截图后端](https://github.com/CursorTouch/Windows-MCP/blob/main/src/windows_mcp/desktop/screenshot.py)
- [MIT License](https://github.com/CursorTouch/Windows-MCP/blob/main/LICENSE.md)

## 原理（纠正常见误解）

Windows-MCP 不要求 AI 先对截图 OCR 才能操作**普通 Windows 控件**。其 Snapshot 能用 Windows UI Automation / 浏览器 DOM/IAccessible2 等接口拿到控件名称、类型、位置及状态，返回结构化树；模型据此触发 Click/Type/Scroll/WaitFor 等 MCP 工具。Snapshot 的 `use_vision` 可选（默认 False）；Screenshot 是单独的截图优先路径，会直接捕获画面。截图实现支持 DXGI/dxcam，并有其他后备方案。

**所以它“不需要 OCR/不需要视觉模型”并非“AI 永远无需观察屏幕像素”。** 遇到自绘、GPU 渲染或不公开无障碍节点的游戏画面，UIA 往往只看到顶层窗口，无法读出敌人弱点、战斗技能点、角色面板及图标。星铁是否暴露有意义的语义节点需要真实游戏窗口测试；不可预设 UIA 能覆盖其内部 HUD。Windows-MCP 提供的 0.2–0.5s 动作延迟也是项目说明中的一般值，不含我们的模型推理、游戏帧采集与判断开销，不能用作实际战斗延迟承诺。

## StarRail-AI 的技术决定：混合语义/视觉，不强制 OCR

```text
                              StarRail AI Core
                         (账号档案、Planner、Guard)
                                  |
                   ┌──────────────┴──────────────┐
             SemanticObserver               VisualObserver
              Windows UIA /                  Live FrameSource
              Android NodeInfo               Direct VLM
                   |                             |
           启动器/设置/弹窗              战斗 HUD/敌人/角色画面
                   └──────────────┬──────────────┘
                                  |
                         Typed GameActions
                      Windows input / Shizuku
```

- **Windows**：优先用 UIA 识别普通窗口、启动器、设置和系统对话框；星铁窗口内部不能保证有语义树，需要使用持续本地帧流 + 事件触发的多模态模型。使用 Windows-MCP 作为**可选参考/独立 MCP adapter**，不要把它的完整工具权限直接开放给游戏 Agent。
- **Android**：Android 没有 Windows UIA/comtypes/pywin32/dxcam；可以用 `AccessibilityService` + `AccessibilityNodeInfo` 读取**有意义的 Android 控件树**，但游戏自绘 SurfaceView 内部不一定暴露这些节点。不能直接“把 Windows-MCP 打包成 APK”；需要单独编写 Android SemanticObserver 和 MediaProjection/兼容 Shizuku FrameSource。
- **共有**：模型只读取语义摘要或视觉关键帧；真正执行由本地受限 ActionGuard 根据焦点进程/包名、显示区域、动作白名单、过期帧检查进行。
- **云端 Token**：读取 UIA/Accessibility 及本地连续采集都不消耗云端视觉 Token；只有发送模型请求时产生相应模型费用/额度。UIA 元素树发送到 LLM 仍消耗文本 Token，但通常比大幅图片更紧凑，实际节省量需测量。连续视频上传云模型不能因此变成免费。

## 平台限制、账户安全

Windows-MCP 包含 PowerShell/Registry/FileSystem 等高权限工具。StarRail-AI 只应选用捕获、窗口状态、经过验证的鼠标/键盘等**最小工具集合**，避免给模型任意 Shell、删除文件等权限。项目的控制权接管/用户优先机制可参考，但不能作为绝对安全保证。

Android MediaProjection 捕获前台游戏画面需要用户授权；Android 14+ 投屏每次会话需再次授权，不能使用旧 Token 反复启动。MediaProjection 内部调用 `createVirtualDisplay` 用于**镜像采集**，与上游 M7A 为游戏另开一个虚拟显示器并不相同。

## 推荐开发顺序

1. Windows 先做**只读** UIA 实验：打开星铁窗口，列出可访问节点，并保留截图对照，确定游戏内部到底有多少可读语义信息。
2. Android 做**只读** AccessibilityNodeInfo 实验，同样统计实际游戏节点、精度与持续性能。
3. 统一 `SemanticObserver` 数据合同：`platform`、`window/package`、`timestamp`、`interactive_nodes`、`confidence`、`source`；节点为空立即回退 Live Vision，不凭空生成信息。
4. 后续编排中先用本地语义树处理可见弹窗/系统操作；游戏渲染画面用 Live Vision 关键帧，OCR 仅用于关键精确数字。
5. 接入云视觉的用户授权、预算、去重、结果验证和停止机制后，才考虑 AI 执行；不能把通用 MCP 权限直接暴露给模型。

## 外部资料

- [Android AccessibilityNodeInfo 官方文档](https://developer.android.com/reference/android/view/accessibility/AccessibilityNodeInfo)
- [Android MediaProjection 官方文档](https://developer.android.com/media/grow/media-projection)

**本文件为研究与开发计划，不表示已将 Windows-MCP 安装、集成或在星铁真机/Windows 上测试通过。** 不复制其源码时不产生代码再许可问题；实际引用 MIT 代码需保留原 MIT 著作权及许可文本，并遵守 StarRail-AI 的 GPL-3.0 发行义务。
