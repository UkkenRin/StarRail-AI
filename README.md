# StarRail AI

跨平台《崩坏：星穹铁道》AI 助手实验项目。

> **开发初期 / 非官方客户端。** 本项目以 [March7thAssistant](https://github.com/moesnow/March7thAssistant) 的开源实现为参考和运行底座，目标是把 PC 自动化、Android 后台控制、账号理解及多模型 AI 决策逐步结合。**当前还不是能自主通关的 AI 玩家。**

## 致谢与开源来源

**诚挚感谢 [三月七小助手（March7thAssistant）](https://github.com/moesnow/March7thAssistant) 的发起者 moesnow 和所有贡献者。** 原项目历经长期开发维护，已实现成熟的画面识别、自动化任务、安卓/Windows 适配与 AI Skill。我们尊重他们投入的时间与劳动，**不会将这些成果当成自己的原创**。

**目前 StarRail-AI 的安卓 APK 是基于三月七小助手 `v2026.11.17` 源码修改构建的，并非从零独立开发。** 我们新增的 AI 功能、知识库和接管原型应与上游已有实现明确区分。项目采用 GPL-3.0；重新分发修改版本时需要满足相应的版权声明、许可和对应源码提供义务。本项目独立开发，**并非三月七小助手官方版本，也不代表获得原作者的认可或背书。**

详见 **[完整致谢、代码来源与许可证说明](CREDITS.md)**。

## 安卓真实画面采集（第一阶段）

安卓端新增了基于 **系统 MediaProjection 授权** 的**本地只读屏幕预览**实验源码：在「AI 顾问」页申请权限、采样当前屏幕至本机内存、查看最后一帧，并能从通知或页面停止。它不启动第二份游戏，不上传画面给模型，也不注入触摸。与原版后台虚拟屏任务保持隔离。

**这不等于 AI 已经能够观察并自动操作游戏**：目前无可靠的游戏焦点校验、VLM 调用、自动点击或故障接管。此能力需要通过 Android CI 和 iQOO/OriginOS 真机授权、旋转、停止测试后才能标记可用。见 [安卓本地画面采集说明](docs/ANDROID_LOCAL_CAPTURE.md)。



**AI 是主驾驶，三月七小助手只是技能工具箱。** 用户希望 AI 在明确授权后直接观察、理解、规划并操控星铁；当已有自动化脚本无法识别新界面、操作失误或卡住时，AI 能在脚本真正停下后**接管游戏输入、尝试解决、验证结果**，并决定恢复原脚本还是继续独立操作。也支持不调用脚本的 **AI-first 模式**。详见 [自主游戏智能体目标与验收](docs/AUTONOMOUS_GAME_AGENT.md)。

目前只有 `starrail_core/agent_handoff.py` 的**输入权交接/限时许可 Python 协议原型**与测试：**没有接入 Android/Windows 真实屏幕、模型控制、自动故障修复或可用的自主代打 APK**。要实现上述能力，还需要视觉观察、Agent Planner、受限执行器和真机端到端测试。

## 已接入源码：跨平台场景知识库

基于固定版本上游 M7A 的 PC **61** 个场景、触屏 **20** 个场景，生成 **68** 个去重场景 ID 和平台独立导航关系。知识库在 [data/game_scenes.json](data/game_scenes.json)，Python 共用接口在 [starrail_core/scene_knowledge.py](starrail_core/scene_knowledge.py)。安卓「AI 顾问」可**离线搜索并手动选场景**；之后连接模型时，仅按需发送选中场景的可读摘要。**不是实时识别游戏画面，不会自动操作，不会上传源代码脚本。** 详情：[场景知识库](docs/SCENE_KNOWLEDGE.md)。

## 新方向：Live Vision（直接视觉理解，不强制 OCR）

最新决策：**让 Android/Windows 在本地持续采集游戏画面流；只在必要的关键事件，把画面直接交给支持视觉的 AI 分析**，无需强制 OCR。持续本地采集不等于持续上传云视频；模型费用仍然受关键帧次数、分辨率和 Provider 规则影响。现有稳定的 OCR 任务保留，作为精确读取属性等可选方案。

架构和验收： [Live Vision 设计](docs/LIVE_VISION.md) · [省 Token 与云视觉上传预算](docs/TOKEN_BUDGET.md)。

**当前应用没有实际前台录屏或 AI 实时视觉操控功能**。共享的纯 Python `LiveVisionGate` 仅负责低成本帧变化/事件预算门控，尚未连接 Android / Windows 屏幕采集驱动。

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

当前 MVP：只读「AI 顾问」页面。支持 OpenAI/DeepSeek/通义/自定义 Chat Completions API Key；另增加 **ChatGPT OAuth 的安卓实验实现**（动态客户端注册、PKCE、loopback 回调、ID Token 验证、Keystore 凭据存储、模型列表、Responses API 流式请求）。OAuth 客户端代码曾在 [Actions #43893504654](https://github.com/UkkenRin/StarRail-AI/actions/runs/43893504654) **编译成功**；但未完成**真机登录与模型请求测试**，使用者仍可能遇到授权、系统浏览器回调或额度限制。当前 API Key 仍仅存页面内存。

**这里没有自动扫描账号、没有前台实体屏控制、也没有 AI 自主战斗。** 新增独立包名 `top.starrailai` 的构建与安装仍须单独验证。详见 [OAuth 状态及测试说明](docs/OAUTH.md)。

## 正在开发：真实账号档案导入

[账号档案功能与使用边界](docs/ACCOUNT_PROFILE.md)：在 Android「AI 顾问」页面导入 PC 版 `characters.json` 或在平板上直接保存账号备注。支持本地 Keystore 加密保存、只发送摘要给所选 AI、读取失败标记、用户主动删除。**这是导入而不是 Android 游戏自动扫描**；这项功能已合并进主分支，Android/JUnit 与 Windows/Linux 账号契约测试均通过；**还未在 iQOO Pad5 Pro 上做真机测试**。[实现记录 PR #2](https://github.com/UkkenRin/StarRail-AI/pull/2)、[安卓构建](https://github.com/UkkenRin/StarRail-AI/actions/runs/43967027440)。

## 构建

在 GitHub 仓库的 **Actions → Build Android experimental APK → Run workflow** 启动，成功后从本次运行页面下载 `starrail-ai-android-debug`。无需 PC。首次构建可能较久，需保持 Actions 可用并由 GitHub 提供网络资源。

**重要：** 实验 APK 已在源码层改为独立应用 ID（`top.starrailai`），但必须先通过新 CI 才能安装测试。不要使用历史旧 APK 来验证独立安装，且避免与官方三月七小助手同时接管游戏。安装与运行可能受游戏条款及账号风险约束。

## 开发约定与需求

- [AI 开发交接说明（AGENTS.md）](AGENTS.md)：后续接手项目时必须阅读，记录产品方向、操作安全和交付要求。
- [用户需求与决策](docs/PRODUCT_REQUIREMENTS.md)：账号理解优先、非固定按键、跨平台、按需截图和多 AI API。
- [分阶段路线图](docs/ROADMAP.md)：具体任务、完成标准和未验证状态。

## 资料

- [架构与安全边界](docs/ARCHITECTURE.md)
- [PC 源码移植调查](docs/PORTING.md)
- [上游 GPL-3.0 许可证](https://github.com/moesnow/March7thAssistant/blob/main/LICENSE)

本仓库不收录用户密钥、Token、真实账号数据或已打包的第三方资源。使用或分发修改后的上游组件时遵守其 GPL-3.0 及其他依赖的许可证。
