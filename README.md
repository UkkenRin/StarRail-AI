# StarRail AI

跨平台《崩坏：星穹铁道》AI 助手实验项目。

> **开发初期 / 非官方客户端。** 本项目基于 [March7thAssistant](https://github.com/moesnow/March7thAssistant) 的开源运行底座，目标是结合 PC 自动化、Android 后台控制、账号理解和多模型自主决策。**尚不是可自主通关或无人值守游玩的 AI 玩家；源码提交、CI 成功不等于 iQOO/OriginOS 真机验收。**

## 最新状态（2026-11-17）

> **上游版本更新**：Android 构建基线计划从 March7thAssistant `v2026.11.17` 升级为 **`v2026.11.19`**。上游 `v2026.11.18` 改进 Android 游戏后台静音、异常退出恢复及触屏任务；`v2026.11.19` 修复触屏开拓力补充等问题。**上游更换不等于解决 StarRail-AI 自己的 MediaProjection 2 帧卡住/共享停止问题，也不代表 iQOO Pad5 Pro 已通过真机复测**。版本升级须以 Android CI 构建通过为准。

| 能力 | 主分支 / 实验分支实际状态 |
| --- | --- |
| Android 实体屏采集 | `main` 已有 MediaProjection 本地只读采样及屏幕预览；**iQOO Pad5 Pro 真机反馈：画面停在约 2 帧，停止共享后疑似 App 进程重启，验收未通过**。正在测试默认屏幕全屏授权、停止流程异常防护和本地状态诊断修复 |
| ChatGPT OAuth | `main` 已加入登录、模型列表、文字分析与**手动选一张截图的视觉分析**；真实账号和图像模型请求尚未真机验收 |
| Gamer / GameScaling | [PR #21](https://github.com/UkkenRin/StarRail-AI/pull/21) **已合并**：200ms 动作协议、官方 GameScaling 可选 Python 只读接入、稀疏思考、受限单步验证接口和已验证经验缓存；**未接 Android 自动执行** |
| Android Shizuku 实际触摸 | [PR #22](https://github.com/UkkenRin/StarRail-AI/pull/22) **待合并**：增加任务只读检查、经人工二次确认的一次「打开手机菜单」安全操作与新画面验证；未经过平板实测，且不是连续自主运行 |
| Android 构建稳定性 | [PR #23](https://github.com/UkkenRin/StarRail-AI/pull/23) **待合并**：仅为 Gradle 插件间歇下载失败增加重试，不代表触控功能已在 `main` |
| 完整 AI 游戏闭环 | **尚未完成安卓端到端真机验收**；Python 模拟测试不能替代设备结果 |

当前最需要确认的是：**APK 能否安装、OAuth 视觉是否真的能看见星铁、实体屏与 Shizuku 是否可用**。之后再将已确认的安全触控接到连续 AI 决策和短期历史，避免模型重复工作。详见 [Gamer 运行器整合说明](docs/GAMER_RUNTIME_INTEGRATION.md)、[Android 真实设备接口](https://github.com/UkkenRin/StarRail-AI/blob/feat/android-trusted-device-port-20261117/docs/ANDROID_TRUSTED_DEVICE_PORT.md)。


> **当前设备问题（2026-11-17）**：iQOO Pad5 Pro / OriginOS 测试发现采集约 2 帧后停止更新、结束共享后疑似整个 App 重新启动。当前 main APK **不能被视为已通过屏幕采集验收**。本次修复尝试全屏 MediaProjection 授权、容错清理和无敏感数据的采集诊断；即使 CI 成功仍需重装后验证，不代表已经解决全部 OriginOS 保活问题。优先测试读取诊断文字，不应启用自动触控。

## Android APK 固定下载链接（自动更新文件）

**[下载 StarRail-AI 安卓 Debug APK](https://github.com/UkkenRin/StarRail-AI/releases/download/android-latest/StarRail-AI-debug.apk)** · [查看自动发布记录](https://github.com/UkkenRin/StarRail-AI/releases/tag/android-latest)

每次 **main 分支的 Android CI 构建成功**，GitHub Actions 才会更新这个固定地址；若构建失败，仍提供**上次成功的旧版本**。**固定链接当前不能用于验证尚未合并的 PR #22 Shizuku 单步功能。**

需要验证 PR #22 的，请打开 [PR #22 成功构建 #44062971351](https://github.com/UkkenRin/StarRail-AI/actions/runs/44062971351) → 页面底部 **Artifacts** → `starrail-ai-android-debug`（可能需要登录 GitHub）→ 下载 ZIP → 在平板解压后安装 APK。这是临时测试产物，2026-11-24 预计过期；如果手机浏览器下载异常，不要使用未经核实的第三方 APK；改用 GitHub Release 官方下载链接或已经过校验的 Actions 产物。不要误把 GitHub Actions 页面当作 APK 下载直链。

**注意：自动更新的是 GitHub 上供下载的文件，不是设备已安装的应用。** 当前使用临时 Debug 签名，各次 CI 构建可能无法直接覆盖安装；卸载旧版会清除应用内保存的数据。稳定签名和应用内更新检查属于后续任务。详细操作见 [安卓安装与验收说明](docs/ANDROID_INSTALL.md)。

## Gamer / GameScaling：已接的组件与未完成部分

**[动作协议](docs/GAMER_ADAPTER.md)** 兼容公开的 Gamer 200ms / 6 步键鼠格式，Android 顾问提供只读预览。**[PR #21](https://github.com/UkkenRin/StarRail-AI/pull/21) 已合并** `starrail_core/gamer_runtime.py`、`sparse_thinking.py`、`gamer_step_cycle.py`、`verified_memory.py` 和官方 GameScaling 客户端的**可选 Python 只读适配器**。

共享核心可以在**注入可信模型与设备端口的模拟测试**中完成「是否需要推理 → 语义动作预检 → 调用设备端口 → 新帧确认 → 写入已验证经验」。已经验证的同类局面可以复用动作而不再次请求模型；但模型提出的未执行动作不会进入成功记忆。**Android 当前没有将上述 Python 运行器连成连续游戏操控循环，也没有发布 Gamer-RL 权重或官方 Streaming-vLLM 实现。** 详见 [运行器整合记录](docs/GAMER_RUNTIME_INTEGRATION.md)。



**诚挚感谢 [三月七小助手（March7thAssistant）](https://github.com/moesnow/March7thAssistant) 的发起者 moesnow 和所有贡献者。** 原项目历经长期开发维护，已实现成熟的画面识别、自动化任务、安卓/Windows 适配与 AI Skill。我们尊重他们投入的时间与劳动，**不会将这些成果当成自己的原创**。

**目前 StarRail-AI 的安卓 APK 是基于三月七小助手 `v2026.11.19` 源码修改构建的，并非从零独立开发。** 我们新增的 AI 功能、知识库和接管原型应与上游已有实现明确区分。项目采用 GPL-3.0；重新分发修改版本时需要满足相应的版权声明、许可和对应源码提供义务。本项目独立开发，**并非三月七小助手官方版本，也不代表获得原作者的认可或背书。**

详见 **[完整致谢、代码来源与许可证说明](CREDITS.md)**。

## 安卓真实画面采集（第一阶段）

安卓端已有基于 **系统 MediaProjection 授权** 的本地只读屏幕采集：在「AI 顾问」页申请权限、采样真实屏幕至内存、查看最后一帧，可从通知或页面停止。**采集本身不会上传截图或触摸游戏**；只有用户在单帧分析页面另外选择截图并再次确认，才会发送给选定的视觉模型。它与 M7A 后台虚拟屏区分，不启动第二份游戏。

**尚未实现无人值守视觉操控，也未在 iQOO Pad5 Pro / OriginOS 验收。** MediaProjection 本机预览、截图上传、当前焦点验证和 Shizuku 输入是不同的授权与安全环节，不能相互代替。见 [本地画面采集说明](docs/ANDROID_LOCAL_CAPTURE.md)。

### PR #22：受限安卓游戏设备接口（尚未进入 main）

[PR #22](https://github.com/UkkenRin/StarRail-AI/pull/22) 增加 Shizuku 设备状态只读检查，以及一次**人工二次授权、仅允许在本机重新核实星铁真实画面和任务状态后**执行的低风险「打开游戏手机菜单」操作。AI 单帧分析可以提出白名单 `safe_action=open_game_menu`，**模型不能指定原始坐标**。执行后通过同一采集会话的新画面验证；校验失败会阻止或停止，不能擅自重试。实际效果受 OriginOS、显示器尺寸、模板匹配与系统输入接口支持影响，**尚未在真机确认执行成功**。

这个功能只属于 [PR #22 成功构建产物](https://github.com/UkkenRin/StarRail-AI/actions/runs/44062971351)，**不在当前固定地址下载的主分支稳定 APK 中**。详见 [Android 设备接口说明](https://github.com/UkkenRin/StarRail-AI/blob/feat/android-trusted-device-port-20261117/docs/ANDROID_TRUSTED_DEVICE_PORT.md)。



**AI 是主驾驶，三月七小助手只是技能工具箱。** 用户希望 AI 在明确授权后直接观察、理解、规划并操控星铁；当已有自动化脚本无法识别新界面、操作失误或卡住时，AI 能在脚本真正停下后**接管游戏输入、尝试解决、验证结果**，并决定恢复原脚本还是继续独立操作。也支持不调用脚本的 **AI-first 模式**。详见 [自主游戏智能体目标与验收](docs/AUTONOMOUS_GAME_AGENT.md)。

目前已不止 `agent_handoff.py`：共享核心拥有单步协调器、稀疏思考、视觉预算门控和只在**结果验证成功后**更新的短期经验缓存，Android 端拥有单帧 OCR-free 视觉分析。**但各部分还没有接通为可在平板上无人值守的完整循环**；PR #22 的受限触控只是一项需要用户单独确认的安全试验。要实现真正自主游戏仍需安卓连续 FrameSource、可信 GameExecutor、AI 任务管理与真机失败恢复。

## 已接入源码：跨平台场景知识库

基于固定版本上游 M7A 的 PC **61** 个场景、触屏 **20** 个场景，生成 **68** 个去重场景 ID 和平台独立导航关系。知识库在 [data/game_scenes.json](data/game_scenes.json)，Python 共用接口在 [starrail_core/scene_knowledge.py](starrail_core/scene_knowledge.py)。安卓「AI 顾问」可**离线搜索并手动选场景**；之后连接模型时，仅按需发送选中场景的可读摘要。**不是实时识别游戏画面，不会自动操作，不会上传源代码脚本。** 详情：[场景知识库](docs/SCENE_KNOWLEDGE.md)。

## 视觉 AI 手动看图（只读试验）

安卓端 [手动视觉 AI 单帧分析](docs/MANUAL_VISION_AI.md) 已支持**ChatGPT OAuth 已授权且具图像能力的模型**，或用户自己的 HTTPS API Key 视觉模型。用户先在本机采集一张图片，**二次确认后**才发送；默认不上传、单进程最多 3 次、至少间隔 10 秒，要求图片不超过 640px / 500KiB。返回场景、画面证据和下一步只读建议。**OAuth 已写入 App 代码并通过分支 CI，但真实账号的授权及视觉模型调用尚未真机验证。** 这不是连续云视频，也不会因此自动点击。PR #22 可选提出受限语义动作，但必须另外授权并通过本地校验。

## 新方向：Live Vision（直接视觉理解，不强制 OCR）

最新决策：**让 Android/Windows 在本地持续采集游戏画面流；只在必要的关键事件，把画面直接交给支持视觉的 AI 分析**，无需强制 OCR。持续本地采集不等于持续上传云视频；模型费用仍然受关键帧次数、分辨率和 Provider 规则影响。现有稳定的 OCR 任务保留，作为精确读取属性等可选方案。

架构和验收： [Live Vision 设计](docs/LIVE_VISION.md) · [省 Token 与云视觉上传预算](docs/TOKEN_BUDGET.md)。

**安卓实体屏采集与「手动选一帧 → OAuth/API Key 视觉模型」已连接**，但尚未真机验证，也未接入 `GamerAgentRuntime` 的**连续**自动截图、推理与输入循环。共享 `LiveVisionGate` 仍负责事件触发预算门控，并非上传器；Windows 实时驱动仍待实现。

## 项目方向

- **Windows**：尽量复用上游成熟的游戏任务、账号面板导出、OCR 和自动化接口；逐步增加 AI 分析及安全决策。
- **Android**：保留上游 Shizuku 虚拟屏模式，逐步研究 *前台实体屏*、会话复用及移动端的任务适配。
- **AI Core**：统一账号档案、模型接口、观察→决策→验证循环。默认仅分析；任何触屏操作必须在可验证的目标窗口和白名单动作下执行。
- **其他系统**：先支持账号分析；依赖特定平台的游戏控制能力另行适配，不保证所有平台均可自动操作。

## 当前 MVP

此仓库采用 **上游源码 + 本仓库补丁** 的构建方式，避免提前复制整份大型上游代码：
1. GitHub Actions 拉取固定版本的官方源码。
2. 应用本仓库 `android_overlay/` 下的实验性 AI UI 补丁。
3. 按上游 Android 构建步骤编译 **debug APK**，以 Workflow Artifact 形式提供。

当前 `main` Android MVP：账号档案导入、本地画面观察、**手动单帧视觉 AI（OAuth / API Key）**、只读 Gamer 动作解析和场景知识库。OAuth 采用动态客户端注册、PKCE、loopback 回调、ID Token 验证、Keystore 凭据存储与 Responses 流式请求，**但尚未经过 iQOO 平板真实登录和图像分析验收**；API Key 不保存到持久设置。支持情况受模型权限和账号额度限制。

**`main` 仍无 Android AI 自主战斗和无人值守点击。** PR #22 的实体屏单步执行测试在独立分支中，并非固定下载 APK 已有功能；包名 `top.starrailai`、签名兼容与 Shizuku 仍需真机验证。参见 [OAuth 测试](docs/OAUTH.md) 和 [Android 安装说明](docs/ANDROID_INSTALL.md)。

## 正在开发：真实账号档案导入

[账号档案功能与使用边界](docs/ACCOUNT_PROFILE.md)：在 Android「AI 顾问」页面导入 PC 版 `characters.json` 或在平板上直接保存账号备注。支持本地 Keystore 加密保存、只发送摘要给所选 AI、读取失败标记、用户主动删除。**这是导入而不是 Android 游戏自动扫描**；这项功能已合并进主分支，Android/JUnit 与 Windows/Linux 账号契约测试均通过；**还未在 iQOO Pad5 Pro 上做真机测试**。[实现记录 PR #2](https://github.com/UkkenRin/StarRail-AI/pull/2)、[安卓构建](https://github.com/UkkenRin/StarRail-AI/actions/runs/43967027440)。

## 构建

在 GitHub 仓库的 **Actions → Build Android experimental APK → Run workflow** 启动，成功后从本次运行页面的 **Artifacts** 下载 `starrail-ai-android-debug` ZIP，平板上解压并安装其中的 APK；Actions 产物**不是 APK 直链**，下载可能要求登录 GitHub，且会过期。

**要测试 Shizuku 受限真实单步，请选 PR #22 的 [成功构建 #44062971351](https://github.com/UkkenRin/StarRail-AI/actions/runs/44062971351)，不要选 README 顶部固定主分支 APK。** 首轮建议先测试安装、画面采集、OAuth 和「5 秒后只读检查」，不急于点击。源码中的应用 ID 为 `top.starrailai`，临时 Debug 签名可能阻止覆盖安装；卸载会清除应用数据。不要与原版 M7A 同时接管输入。自动化可能涉及游戏账号条款风险，不能保证不会触发限制。

## 开发约定与需求

- [AI 开发交接说明（AGENTS.md）](AGENTS.md)：后续接手项目时必须阅读，记录产品方向、操作安全和交付要求。
- [用户需求与决策](docs/PRODUCT_REQUIREMENTS.md)：账号理解优先、非固定按键、跨平台、按需截图和多 AI API。
- [分阶段路线图](docs/ROADMAP.md)：具体任务、完成标准和未验证状态。

## 资料

- [架构与安全边界](docs/ARCHITECTURE.md)
- [PC 源码移植调查](docs/PORTING.md)
- [上游 GPL-3.0 许可证](https://github.com/moesnow/March7thAssistant/blob/main/LICENSE)

本仓库不收录用户密钥、Token、真实账号数据或已打包的第三方资源。使用或分发修改后的上游组件时遵守其 GPL-3.0 及其他依赖的许可证。
