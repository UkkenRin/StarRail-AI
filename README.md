# StarRail AI

跨平台《崩坏：星穹铁道》AI 助手实验项目。

> **开发初期 / 非官方客户端。** 本项目以 [March7thAssistant](https://github.com/moesnow/March7thAssistant) 的开源实现为参考和运行底座，目标是把 PC 自动化、Android 后台控制、账号理解及多模型 AI 决策逐步结合。**当前还不是能自主通关的 AI 玩家。**

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

当前 MVP：只读「AI 顾问」页面。支持 OpenAI/DeepSeek/通义/自定义 Chat Completions API Key；另增加 **ChatGPT OAuth 的安卓实验实现**（动态客户端注册、PKCE、loopback 回调、ID Token 验证、Keystore 凭据存储、模型列表、Responses API 流式请求）。OAuth 客户端代码曾在 [Actions #43893504654](https://github.com/yu-x-an/StarRail-AI/actions/runs/43893504654) **编译成功**；但未完成**真机登录与模型请求测试**，使用者仍可能遇到授权、系统浏览器回调或额度限制。当前 API Key 仍仅存页面内存。

**这里没有自动扫描账号、没有前台实体屏控制、也没有 AI 自主战斗。** 新增独立包名 `top.starrailai` 的构建与安装仍须单独验证。详见 [OAuth 状态及测试说明](docs/OAUTH.md)。

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
