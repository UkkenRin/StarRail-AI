# 安卓安装与真机验收（iQOO Pad5 Pro / OriginOS）

> 安装包为 CI 编译成功的 Debug 实验版，并非完整自主游戏 AI；仍需真机验证。用户不需要 PC、Root、Android Studio 或 API Key 就能完成本次截图测试。

## 固定下载地址

- **[Android APK 直接下载](https://github.com/UkkenRin/StarRail-AI/releases/download/android-latest/StarRail-AI-debug.apk)**（GitHub Release 的固定标签和固定文件名）
- [Release 说明与构建提交](https://github.com/UkkenRin/StarRail-AI/releases/tag/android-latest)
- [GitHub Actions 历史构建](https://github.com/UkkenRin/StarRail-AI/actions/workflows/android-debug.yml)

每当 **main 上相关代码发生变化且 Android CI 成功构建**时，自动发布器将替换固定文件名的 APK；构建失败不会覆盖上一版。更新了 GitHub 下载文件 **不等于** 自动更新平板上已经安装的应用，用户仍需手动下载安装。

**关于覆盖安装：** 当前 APK 是 Debug 签名，不同 Actions 运行可能生成不同签名，导致系统显示“安装失败/签名冲突”，并且构建使用实验版的 versionCode。**不要直接卸载含重要本地账号资料的旧版**，否则 App 私有数据及 Keystore 加密档案可能无法恢复。后续需引入安全保管的稳定发布签名、递增版本号，以及用户确认后的更新安装；不应把现在的固定链接描述成已有 APP 内自动升级。

## 第一次安装

1. 在 iQOO Pad5 Pro 的浏览器打开上方 APK 下载链接（首次发布完成后可用），下载 **StarRail-AI-debug.apk**。
2. 在系统文件管理器中点击 APK 安装；如 OriginOS 提示“允许此来源安装应用”，按系统提示给当前浏览器/文件管理器授予安装许可。仅从项目官方 GitHub Release 获取，不要使用陌生网盘或第三方转载。
3. 打开应用，找到 **AI 顾问** 选项卡。原版三月七小助手可以并存，但首次测试不要同时启动两个自动化任务。

## 测试真实画面（不用连接 GPT）

1. 在 AI 顾问页面点击「授权并开始本地观察」，在 Android 系统弹窗中同意共享所需画面。
2. 切换到星铁，停留在普通游戏主界面几秒钟。录屏可能包含通知、其他 App 等，请勿同时展示敏感资料。
3. 下拉通知栏，点击 StarRail AI 的「停止观察」。返回 AI 顾问，查看是否存在最后一帧、帧数、画面变化计数。
4. 可选：在 Android 的「使用情况访问」设置中为 StarRail AI 授权，查看系统提供的**疑似游戏前台**提示。这个信息不是可执行游戏触控的安全证明，未授权也可完成画面采集验收。
5. 点击「清除本机预览」，确认预览消失。可再次尝试系统授权、横屏切换和手动停止。

向开发助手反馈：**能安装？能弹录屏授权？能看到星铁画面？能从通知停止？** 如果失败，截取应用报错界面即可，不要发送账号密码、密钥或私人通知内容。

## iQOO Pad5 Pro / OriginOS 真机反馈：2 帧后不更新、停止共享后疑似重启

2026-11-17 已收到一次真实设备反馈：用户授权本地观察后，切到星铁再返回只看到约 **2 帧**；点击系统「停止共享」后整个 StarRail-AI 看起来重新启动。**这属于未通过的验收结果，不是功能已成功。** 两个现象不一定是同一原因，也无法只靠视觉表现区分 Activity 重建、进程被回收或原生崩溃。

修复方向（必须构建成功、重新安装并复测后才可判定）：

1. Android 14+ 主动使用系统 `MediaProjectionConfig.createConfigForDefaultDisplay()` 请求共享**整个默认实体显示器**，防止误选「仅共享 StarRail-AI」导致切出 App 后没有新帧。系统仍须逐次明确授权；共享期间请勿显示密码、私人通知或其他敏感内容。
2. 系统通知栏停止共享会触发 `MediaProjection.Callback.onStop()`，此时 token 已失效，清理动作不应再次强制调用 `stop()` 或让异常冒泡导致 App 崩溃；服务仍须撤销前台通知并停用。
3. 仅在本机 `SharedPreferences` 记录采集开始/结束、非敏感停止原因、累计采样次数和进程标记；**不保存图像、截图历史、访问令牌或账户资料**。AI 顾问会显示「本机采集诊断」；若上次进程中断，重新打开 App 仍可读出最小诊断。
4. 若再次出现 2 帧且未退出，请先看当前会话诊断、系统屏幕共享是否仍活动；然后通过系统控制停止共享，记录返回后显示的状态。**不要反复安装卸载，尤其有重要本地账号档案时**。

如果下次仍异常，请只反馈诊断文字和帧数，不需要发送系统日志、账号资料、OAuth Token 或包含私密画面的截图。此修复不等于 Shizuku 点击或 AI 自主游戏已经可用。

## 已实现与尚未实现

- 已经实现且 CI 验证：本机只读 MediaProjection 截帧/低频 RAM 预览，带会话时间戳、帧过期状态、变化采样、可选 Android Usage Access 诊断。
- 待设备验收：OriginOS 屏幕共享、旋转、恢复、服务停止、截图是否准确。
- 尚未实现：真正可信的游戏窗口焦点校验、模型实时视觉理解、游戏触控、AI 动态操作闭环、脚本失败后智能接管。
- 参考 [安卓本地采集](ANDROID_LOCAL_CAPTURE.md) 和 [路线图](ROADMAP.md)。

本仓库构建使用固定的 March7thAssistant `v2026.11.19` 源码与本仓库 Android overlay 构建，开发和分发需遵守上游 GPL-3.0 及依赖许可证。
