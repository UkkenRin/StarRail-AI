# 路线图 / ROADMAP

> **状态定义**：☐ 未开始；◐ 开发中；☑ 源码已提交；✅ CI 通过；📱 真实设备已验证。打勾不代表可在用户设备上使用。

## P0：可持续构建与安装基础

- ☑ 创建跨平台仓库、GPL 许可、上游移植文档与 Android CI。
- ☑ 安卓只读多 Provider 顾问页面源码（输入账号摘要和问题，调用兼容 Chat Completions）。
- ✅ **Android Actions 构建通过，Debug APK 产物已上传**：2026-11-16 构建 [#43890667492](https://github.com/UkkenRin/StarRail-AI/actions/runs/43890667492)，产物名 `starrail-ai-android-debug`（仅 CI 验证，**尚未真机安装/测试**；当前产物保留至 2026-11-23）。
- ☑ 已提交独立 applicationId `top.starrailai`、应用名补丁及测试；**新 CI、安装/签名兼容和 Shizuku 操作尚待验收**。
- ☐ Android 真实设备安装、运行、模型 API 请求验证；记录日志（不含密钥）。
- ☐ 配置 Windows 代码与测试 CI，形成跨平台共享核心包。
- ☐ 保留上游版本 pin，增加升级兼容测试；定期核对安全与许可。

## P1：AI 账号理解（核心差异）

- ✅ 已实现 PC characters.json v1 导入、来源/完整性/失败标记、安卓 Keystore 加密档案和本地备注；[PR #2](https://github.com/UkkenRin/StarRail-AI/pull/2) 已合并。[Android/JUnit 构建](https://github.com/UkkenRin/StarRail-AI/actions/runs/43967027440) 与 [Windows/Linux Python CI](https://github.com/UkkenRin/StarRail-AI/actions/runs/43967640930) 已通过；**尚未真机验证，也不是安卓游戏内自动扫描**。[后续扫描任务 #3](https://github.com/UkkenRin/StarRail-AI/issues/3)。
- ☐ 移植并实测 PC 角色面板导出到 Android touch_720；识别角色、星魂、光锥、技能、已装备遗器。
- ☐ 对未装备遗器单独开发读取/导入，不能把已有导出当成完整背包。
- ☐ 整合 Fribbels 等可复用的遗器优化思想与合法的授权导入格式；配装建议可解释。
- ☐ 账号分析模型可用且回归测试：只用已拥有角色、避免捏造、不会自动花费培养资源。

## P2：显示器与控制

- ☑ 已提交 **安卓实体屏 MediaProjection 只读采集第一阶段**（[说明](ANDROID_LOCAL_CAPTURE.md)）：AI 页面发起系统授权，前台服务低频读取本机预览，页面/通知停止，保留最后一帧供用户核验；不会向 GPT 上传或发送游戏触控。**需 Android CI 和 iQOO Pad5 Pro 真机验证，暂不宣称完整的实体屏模式可用**。


- ◐ 已完成 [Android MCP / 无障碍方案评估](ANDROID_MCP_EVALUATION.md)，并开始实现实体屏 MediaProjection 只读帧采集；Accessibility 只读节点诊断、可信游戏焦点与真实设备验收仍待开发。

- ☐ 在 Android 增加显示模式设置：后台虚拟屏 / 前台实体屏；保持现有功能为默认。
- ◐ MediaProjection 用户授权 + 本地低频截图与屏幕尺寸缩放源码已提交，必须补实体屏焦点/包名校验、横竖屏真机映射及锁屏/错误包名即停；不得用于 AI 点击。
- ☑ [安卓本地帧诊断第二阶段](ANDROID_LOCAL_CAPTURE.md)：会话 ID、帧时间戳与过期策略、缩略图变化候选、系统共享内容可见性、可选 Usage Access 的**疑似前台应用**提示，均不授予 AI 输入权限；等待 CI 与真机验证。真实焦点确认、模型、动作执行器尚未实现。
- ☐ 前台输入动作白名单、动作后取帧校验、停止按钮、防误触系统应用。
- ☐ 真机比较 iQOO/OriginOS 后台限制、Shizuku 注入与服务恢复。
- ☐ 可选会话复用与释放测试：不泄露虚拟屏、不遗留静音/显示状态。

## P2.5：共享游戏场景知识

- ✅ 已合并并通过 CI 的 [场景知识库](SCENE_KNOWLEDGE.md)：从固定 M7A `v2026.11.17` 提取 61 PC、20 安卓命名场景（68 唯一 ID），仅保留界面标签和导航图，不执行原版脚本。[Windows/Linux 测试](https://github.com/UkkenRin/StarRail-AI/actions/runs/43980970224) 和 [Android/JUnit/APK 构建](https://github.com/UkkenRin/StarRail-AI/actions/runs/43980970062) 均通过。Windows/Linux 使用 Python 公共接口；安卓顾问支持离线查找和手动选择。**尚未真机测试，也不是自动识别画面。**
- ☐ 利用 Live Vision / 本地模板匹配自动把实际屏幕映射为已知场景 ID，置信度不够时返回 unknown。
- ☐ 将游戏敌人、技能、界面用途/按钮定义补成经过来源核对的场景知识，避免把静态导航图误当完整攻略。

## P2.8：AI-first 自主游戏控制（替代“只是启动脚本”的错误定位）

- ☑ 已提交 [AI 主驾驶与脚本接管设计](AUTONOMOUS_GAME_AGENT.md)：AI 可直接接管游戏，也可调用 M7A 后在失败时暂停脚本、自己操作并验证；目标是几乎全游戏自主执行，M7A 仅为工具。**目标和当前交付能力严格区分**。
- ☑ 已提交 `starrail_core/agent_handoff.py` 独占输入权协议原型：包含 AI-first / M7A 暂停确认 / 限时接管租约 / 动作许可 / 失败与复位，配套纯 Python 测试；**尚无真实画面观察、LLM 规划、平台执行器或 APK 集成**。
- ☐ Android/Windows FrameSource 和焦点检查，打通真实截图到视觉模型的场景理解。
- ☐ 模型输出的动态目标和操作计划，与 M7A/Android 任务可用性匹配；未知场景也能直接自主操作，不仅仅启动固定任务。
- ☐ 将输入权原型接到真正的 M7A 停止/释放信号、受限 Windows/Android GameExecutor 和操作后验证；异常支持 **脚本 → AI 接管 → 可选恢复脚本**。
- ☐ 能安全探索新界面，验证失败原因、限制试错次数，积累带游戏版本和设备条件的恢复经验。
- ☐ 以真实游戏玩法逐步验收：普通导航/日常 → 货币战争 → 高难 → 实际回合战斗；无法确认时停止，不承诺每个关卡都能自动完成。

## P3：PC 功能移植与 AI 决策

- ☐ 把 PC 深渊、模拟宇宙等任务逻辑拆成不依赖 pc_1080 的状态机，逐个适配 touch_720。
- ☐ 实现观察→规划→受限执行→验证闭环和动作审计日志。
- ☐ 末日幻影 AI 配队、战斗策略和失败复盘，分别提供离线/只读测试和设备验收。
- ☐ 保护消耗性操作：遗器分解/合成、燃料等必须用户明确确认。
- ☐ 多设备、多平台共享账号档案（需用户主动选择同步方式）。

## 独立的 AI 接入任务

- ☑ 只读 API Key provider 页面提交：OpenAI/DeepSeek/通义/自定义（未实测）。
- ☐ 校验模型请求的 HTTPS、日志隐私、错误与限流；增加 Keystore 安全存储和撤销功能。
- ✅ ChatGPT OAuth 实验源码已提交（PKCE/state/动态注册/回调/RS256 验签/Keystore/Responses SSE），提交 [995df6e](https://github.com/UkkenRin/StarRail-AI/commit/995df6e57d72694cef3b8833d8c16a0a808987b8) 的 [CI](https://github.com/UkkenRin/StarRail-AI/actions/runs/43893504654) **编译成功**；**尚未真机授权、模型选择、请求与续期验证**。
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
