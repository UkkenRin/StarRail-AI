# 路线图 / ROADMAP

> **状态定义**：☐ 未开始；◐ 开发中；☑ 源码已提交；✅ CI 通过；📱 真实设备已验证。打勾不代表可在用户设备上使用。

## 顶层验收目标：真正的 AI-first 玩家（2026-11-17 方向纠偏）

**产品不是三月七脚本控制面板，也不是 Gamer 动作协议展示器。** 即使没有 Gamer-RL 权重，也应能通过可替换的通用视觉模型逐步实现自主观察、目标规划、操作、反馈和纠错。Gamer 模型未来可用时仅更换模型适配器，不更换核心状态/执行框架。

**下一阶段唯一的 P0 产品里程碑：** 在授权的 Android 设备上，从**实际本地游戏帧**开始，AI 对**当前真实画面**给出下一步意图；经本地确认目标与焦点后，调用**既有 M7A 设备服务**执行一个低风险可逆操作；随后由新的游戏画面**自动判断结果**，失败就停止。先做一轮完整闭环（可真机/模拟逐层验收），再增加连续任务。**截至本次修改这条闭环仍未实现，也未通过真机验收。**

优先级按**最终游戏能力**而不是文件/协议数量排序：
1. 复用 M7A DeviceService 的截图与触控接口，打通 AI 自主流程的可授权连接；Android 实体屏需独立核验权限/包名/坐标，不能因为后台虚拟屏工作就默认安全。
2. 建立独立于任何模型的 Goal / GameState / ModelAdapter / Action / Outcome 核心协议，支持**通用 VLM、GameScaling、未来 Gamer**，不把 Gamer 200ms 键鼠文本当唯一内部动作。
3. 让 AI 能不经 M7A 脚本做一次安全操作并看到反馈；M7A 是可调用的成熟技能库，不是 AI 自主能力的上限，也**不能固定优先**。
4. 对已知、稳定、经验证的本地动作/脚本执行不重复推理；新目标/失败/未知界面才触发稀疏思考，并保留可验证的短期状态和压缩记忆。
5. 第一条真实闭环验收后，再依次扩展导航/对话/日常/回合战斗/高难策略。优先测真实任务成功率、误操作率、每任务 Token 和设备延迟，而不是只看 CI 通过率。

**当前限制：** 现有各类纯 Python 守卫、只读 Gamer 解析器和可选官方 GameScaling 客户端均是基础模块，未连成真正的控制链路；不得写成“已经实现自主游戏”。

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

## 当前 Gamer 关键组件接线（PR #21 已合并，仍需实机接入）

- ☑ 在同一分支内接上已独立审查的 Gamer 单步协议与反馈、官方 GameScaling **可选只读 Python 客户端**、稀疏推理门控；新增可注入的 `GamerAgentRuntime` 与仅记录**已验证动作**的 `VerifiedGameMemory`，模拟 OS Port 的闭环测试验证成功后可不再重新请求模型。
- ☑ Android 将已有 MediaProjection 手动选择帧 → ChatGPT OAuth Responses `input_image` → 只读场景分析接通（同一张图二次确认，每进程 3 次/冷却 10 秒）；需 Android CI 和 OAuth 真机图片模型实测。
- ☐ 将此运行器接到安卓可信 FrameProof + 真正 Shizuku 手势执行和新帧后置验证；现阶段**没有自动点击、没有后台跑 Gamer**。见 [Gamer 接线说明](GAMER_RUNTIME_INTEGRATION.md)。

## Android 真实设备接口与受限 AI 语义动作（PR #22 待合并）

- ☑ 已提交 Shizuku `DeviceAccess` 真实探测接口：不启动 M7A 脚本，独立认证设备服务、只监控 watchdog 与 Android 任务/显示器/锁屏状态；并发 M7A 输入占用时拒绝。
- ☑ 已提交可调用的安卓受限一次性单步：用户单独确认后，必须让本机实时截图、主界面图标模板、当前任务 UID/屏幕尺寸等全部匹配，才通过设备服务向星铁 UID 投递一次打开手机菜单的触摸；新画面不能确认就停止，不重试。
- ☑ OAuth/API Key 单帧视觉分析新增 `safe_action=open_game_menu` 语义提案（其余意图不在白名单），可由用户二次确认后交给**本地重新验证的**安卓单步执行器；模型没有原始坐标或任意设备执行权。
- ☐ 必须通过 Android 构建及 iQOO Pad5 Pro/OriginOS 真机实测，不能用 CI 证明实际点击成功；还需接上 `GamerAgentRuntime` 的后台连续观察与实际 OS 输入、状态记忆，才能叫“AI 自主玩游戏”。见 [执行器说明](ANDROID_TRUSTED_DEVICE_PORT.md)。

## P2.8：AI-first 自主游戏控制（替代“只是启动脚本”的错误定位）

- ☑ 已提交 [AI 主驾驶与脚本接管设计](AUTONOMOUS_GAME_AGENT.md)：AI 可直接接管游戏，也可调用 M7A 后在失败时暂停脚本、自己操作并验证；目标是几乎全游戏自主执行，M7A 仅为工具。**目标和当前交付能力严格区分**。
- ☑ 新增 [Gamer/GameScaling 动作协议只读适配](GAMER_ADAPTER.md)：Python 共享核心严格解析 Gamer 的 200ms/6 组键鼠动作格式，Android「AI 顾问」新增本地解析预览，附 Python/Kotlin 测试。未接入模型权重、触控执行器或实时游戏操控，需 CI 和真机核验。
- ☑ **Gamer 风格稀疏推理门控（纯 Python 离线原型）**：`starrail_core/sparse_thinking.py` 对当前情境适用的已验证 M7A 技能或可复用的本地计划不重复请求模型，只在新目标、未知画面、脚本故障、验证异常等关键事件产生云推理候选；复用 `LiveVisionGate` 作为独立预算许可，重复帧不反复推理，单上下文限制重试。**尚未连接 M7A/真实观察/模型，不等于已经减少实际 Token 支出**。详见 [TOKEN_BUDGET.md](TOKEN_BUDGET.md)。
- ☑ 已提交 `starrail_core/agent_handoff.py` 独占输入权协议原型：包含 AI-first / M7A 暂停确认 / 限时接管租约 / 动作许可 / 失败与复位，配套纯 Python 测试；**尚无真实画面观察、LLM 规划、平台执行器或 APK 集成**。
- ☑ [安卓手动视觉 AI 单帧分析试验](MANUAL_VISION_AI.md)：用户二次确认上传**弹窗中固定的同一张截图**，2 分钟内、单进程最多 3 次、冷却 10 秒，发送低分辨率 JPEG 到 HTTPS 视觉 API。默认返回场景与下一步建议；额外明确勾选时可生成严格解析的 Gamer 200ms **只读**动作提案。禁止 HTTP 重定向转发截图/Key。**需 CI 与真机验收；没有实时控制或 Gamer 专用模型。**
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
