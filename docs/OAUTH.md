# ChatGPT OAuth / Sign in with ChatGPT

> 实验性功能。2026-11-16：安卓代码已加入仓库、曾通过一次 Actions 编译；尚未在真实设备上确认 OAuth 登录或模型调用。不能据此声称已可用或官方认证。

## 官方协议

本地开源应用可使用 OpenAI 的动态 OAuth 注册：
- https://developers.openai.com/siwc/token-sharing-open-source/sign-in
- https://developers.openai.com/siwc/token-sharing-open-source/models-and-inference
- https://developers.openai.com/siwc/token-sharing-open-source/profiles-and-sessions
- https://openai.com/policies/sign-in-with-chatgpt-terms/

第一次授权使用 client_id=dynamic_agent_client，回调返回实际 oaiapp_... 客户端 ID；后续重用该 ID。每个安装使用稳定 ext_agent_host_id。使用 PKCE S256 + 随机 state/nonce、127.0.0.1 loopback（路径 /auth/callback，不使用自定义 scheme），校验 ID token 的 JWKS 签名、issuer/audience/exp/nonce，并检查 chatgpt.tokens.use.direct 授权范围。

模型发现调用 GET https://api.openai.com/v1/models，取 models[].slug；推理调用 POST https://api.openai.com/v1/responses，设置 store=false、stream=true，消费 SSE 并等待 response.completed。**ChatGPT OAuth Token 不是普通 Chat Completions API Key**。

## 安卓实现（v0.5 实验原型）

源码路径：android_overlay/app/src/main/kotlin/top/m7a/ui/ChatGptOAuth.kt

1. 本机 loopback listener + 系统浏览器 + PKCE、state/nonce。
2. 动态注册与再授权，ID Token RS256 / OpenAI JWKS 校验。
3. Android Keystore AES-GCM 加密保存 Token 与稳定 Host ID。
4. 授权范围、令牌自动刷新、按账号列出模型。
5. Responses API 流式输出，必须看到完成事件。
6. 退出时尝试远程撤销 refresh token，并清理本机凭据。

**已在集成分支加入：** 在手动截图二次确认后，通过 ChatGPT OAuth Responses `input_image` 送一张最长边 640px 的 JPEG 给所选的支持视觉模型；沿用每进程 3 次、10 秒冷却、只读建议，不授权触摸。**未完成：** OAuth 视觉请求在真机/真实模型上验证、多账号切换、游戏实时自动视觉与 AI 自主触控。详情见 [GAMER_RUNTIME_INTEGRATION.md](GAMER_RUNTIME_INTEGRATION.md)。

## iQOO Pad5 Pro 验收步骤

1. 仅从最新**成功**的 GitHub Actions 下载包含独立应用 ID top.starrailai 的测试包。
2. 安装后进入 AI 顾问 → 选择“ChatGPT（账号授权）”。
3. 点击 Continue with ChatGPT，在系统浏览器完成授权并允许方案使用，然后返回应用。
4. 点击刷新可用模型，选定模型，发一条简单的文字问题。
5. 在 AI 顾问授权本机屏幕采集，拍到星铁后手动停止，**仅在检查预览没有个人敏感信息后**，选择一帧二次确认上传到 OAuth 视觉模型（只读）；模型不支持图片就报错。
6. 测试重新连接和断开登录；如报错，反馈**错误提示**，不要发送 Token、包含 code 的 URL、浏览器 Cookies 或密码。

## 待解决和防护

- OriginOS 可能在切换浏览器时暂停后台 socket 监听，必须实机验证。
- 身份登录成功不等于获得 chatgpt.tokens.use.direct；未授权时不得调用推理。
- 出现使用额度上限需跳转 ChatGPT Usage，而非默默使用其他计费路径。
- 依照当前官方规则，OAuth 请求只可用于用户明确授权的本地应用工作。
- 需要真实设备测试独立包名对 Shizuku 和 APK 更新的影响。
