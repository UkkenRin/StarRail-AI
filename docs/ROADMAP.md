# 路线图 / ROADMAP

> **状态定义**：☐ 未开始；◐ 开发中；☑ 源码已提交；✅ CI 通过；📱 真实设备已验证。打勾不代表可在用户设备上使用。

## P0：可持续构建与安装基础

- ☑ 创建跨平台仓库、GPL 许可、上游移植文档与 Android CI。
- ☑ 安卓只读多 Provider 顾问页面源码（输入账号摘要和问题，调用兼容 Chat Completions）。
- ✅ **Android Actions 构建通过，Debug APK 产物已上传**：2026-11-16 构建 [#43890667492](https://github.com/yu-x-an/StarRail-AI/actions/runs/43890667492)，产物名 `starrail-ai-android-debug`（仅 CI 验证，**尚未真机安装/测试**；当前产物保留至 2026-11-23）。
- ☑ 已提交独立 applicationId `top.starrailai`、应用名补丁及测试；**新 CI、安装/签名兼容和 Shizuku 操作尚待验收**。
- ☐ Android 真实设备安装、运行、模型 API 请求验证；记录日志（不含密钥）。
- ☐ 配置 Windows 代码与测试 CI，形成跨平台共享核心包。
- ☐ 保留上游版本 pin，增加升级兼容测试；定期核对安全与许可。

## P1：AI 账号理解（核心差异）

- ✅ 已实现 PC characters.json v1 导入、来源/完整性/失败标记、安卓 Keystore 加密档案和本地备注；[PR #2](https://github.com/yu-x-an/StarRail-AI/pull/2) 已合并。[Android/JUnit 构建](https://github.com/yu-x-an/StarRail-AI/actions/runs/43967027440) 与 [Windows/Linux Python CI](https://github.com/yu-x-an/StarRail-AI/actions/runs/43967640930) 已通过；**尚未真机验证，也不是安卓游戏内自动扫描**。[后续扫描任务 #3](https://github.com/yu-x-an/StarRail-AI/issues/3)。
- ☐ 移植并实测 PC 角色面板导出到 Android touch_720；识别角色、星魂、光锥、技能、已装备遗器。
- ☐ 对未装备遗器单独开发读取/导入，不能把已有导出当成完整背包。
- ☐ 整合 Fribbels 等可复用的遗器优化思想与合法的授权导入格式；配装建议可解释。
- ☐ 账号分析模型可用且回归测试：只用已拥有角色、避免捏造、不会自动花费培养资源。

## P2：显示器与控制

- ☐ 在 Android 增加显示模式设置：后台虚拟屏 / 前台实体屏；保持现有功能为默认。
- ☐ 实体屏截图技术选型和横屏映射；焦点必须为星铁，锁屏/错误包名即停。
- ☐ 前台输入动作白名单、动作后取帧校验、停止按钮、防误触系统应用。
- ☐ 真机比较 iQOO/OriginOS 后台限制、Shizuku 注入与服务恢复。
- ☐ 可选会话复用与释放测试：不泄露虚拟屏、不遗留静音/显示状态。

## P3：PC 功能移植与 AI 决策

- ☐ 把 PC 深渊、模拟宇宙等任务逻辑拆成不依赖 pc_1080 的状态机，逐个适配 touch_720。
- ☐ 实现观察→规划→受限执行→验证闭环和动作审计日志。
- ☐ 末日幻影 AI 配队、战斗策略和失败复盘，分别提供离线/只读测试和设备验收。
- ☐ 保护消耗性操作：遗器分解/合成、燃料等必须用户明确确认。
- ☐ 多设备、多平台共享账号档案（需用户主动选择同步方式）。

## 独立的 AI 接入任务

- ☑ 只读 API Key provider 页面提交：OpenAI/DeepSeek/通义/自定义（未实测）。
- ☐ 校验模型请求的 HTTPS、日志隐私、错误与限流；增加 Keystore 安全存储和撤销功能。
- ✅ ChatGPT OAuth 实验源码已提交（PKCE/state/动态注册/回调/RS256 验签/Keystore/Responses SSE），提交 [995df6e](https://github.com/yu-x-an/StarRail-AI/commit/995df6e57d72694cef3b8833d8c16a0a808987b8) 的 [CI](https://github.com/yu-x-an/StarRail-AI/actions/runs/43893504654) **编译成功**；**尚未真机授权、模型选择、请求与续期验证**。
- ◐ **Live Vision 视觉智能体**：以本地持续画面流为基础，视觉模型直接分析关键画面，OCR 变为可选组件；独立 Android/Windows Capture 驱动、屏幕焦点保护、结构化行动接口待实现。已经新增纯 Python 帧事件预算门控和测试，**不代表 APK 已具备录屏或自动操控能力**。详见 [LIVE_VISION.md](LIVE_VISION.md)。
- ☐ **低 Token 架构**：持续本地观察、不持续上传云端视频；实现事件触发、策略缓存、关键帧裁剪、调用冷却与去重、每任务/每日预算限额、用量统计和停止机制。详见 [TOKEN_BUDGET.md](TOKEN_BUDGET.md)。
- ☐ 按需截图：用户授权与可见提示、局部裁剪、频率限制、调用统计与费用提醒。
- ☐ AI 配对机制知识须有来源及版本识别，不把过期关卡策略当成当期规则。

## 最近开发接续顺序

1. 核对独立包名 + OAuth 最新构建是否通过，验证 APK 产物存在。
2. 在 iQOO Pad5 Pro 真机分别测试 OAuth 登录回调、模型发现、推理、断开及 401/额度失败；不把 Token 传到公开仓库。
3. 测试其他 API 供应商接口（不在仓库保存密钥）。
4. 完成 Kotlin/JUnit 和跨平台 Python 账号数据契约测试；然后移植安卓 touch_720 自动扫描。
5. 优先实现跨平台 Live Vision 的 `FrameSource`、Android 本地屏幕流/MediaProjection 与 Windows Capture；先只读视觉理解，再研究受限操作。OCR 仅用于选配的数值读取。
6. 后续再推进 PC 高难移植和 AI 控制。

**注意**：这些是开发计划而非完成承诺；需要每次会话明确触发后才能继续实际开发，GitHub Actions 可自动构建已提交的改动。
