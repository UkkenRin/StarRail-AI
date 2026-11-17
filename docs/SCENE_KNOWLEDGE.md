# 星铁场景知识库 v1：PC / Android 共享（2026-11-17）

## 已实现并通过 CI 验证（尚未真机测试）

[实现 PR #6](https://github.com/UkkenRin/StarRail-AI/pull/6) 已合并；[Windows/Linux 单元测试](https://github.com/UkkenRin/StarRail-AI/actions/runs/43980970224) 与 [Android 编译、JUnit、APK 产物](https://github.com/UkkenRin/StarRail-AI/actions/runs/43980970062) 均成功。已直接检查 APK 内含 `assets/starrail_ai_scenes.json`，其中 68 个场景及 PC/安卓数量正确。

- 从固定上游 March7thAssistant `v2026.11.19` 提取两套界面导航：
  - **PC (`pc_1080`)：61 个场景、116 条直接导航关系**，来源 `assets/config/screens.json`。
  - **Android (`touch_720`)：20 个场景、33 条直接导航关系**，来源 `assets/touch_720/screens.json`。
  - 合并为 **68 个唯一 ID**，保留同一 ID 的平台别名（例如 `incoming_message`：PC「新消息通知」、Android「短信」）。
- 可重现生成脚本 `scripts/build_scene_knowledge.py` 从**已检出的固定上游仓库**重新生成 `data/game_scenes.json`；CI 对生成结果做差异检查，防止资料漂移。
- 每条场景仅保留 `id`、`name`、平台收录状态与**下一层目标场景 ID**；**明确去除**全部原版 Python `actions_list`、点击坐标、截图素材路径、按键、OCR 命令和超时脚本。它是知识，不是执行器。
- Windows/Linux 的 `starrail_core.scene_knowledge` 支持搜索、按平台寻找最短导航路线与生成有来源的 AI 提示上下文。
- Android APK 将这份静态 JSON 打包到 App 私有 assets 中；「AI 顾问」可以离线搜索并手动选择场景，列出 PC/安卓的直接导航目标。用户点击「让 AI 分析」时，才将选中的**单一场景摘要**连同手动描述交给所选模型（ChatGPT OAuth / OpenAI / DeepSeek / Qwen / Custom）。
- 未选择场景时，不添加任何虚假的“当前画面知识”；界面中明确标注“用户手动选择，未经游戏截图确认”。

## 数据结构

```json
{
  "schema_version": 1,
  "source": {"repo": "moesnow/March7thAssistant", "ref": "v2026.11.19"},
  "scenes": [
    {
      "id": "main", "name": "主界面",
      "pc": {"name": "主界面", "destinations": ["menu"]},
      "touch": {"name": "主界面", "destinations": ["menu"]}
    }
  ]
}
```

此处示例仅展示部分目标，完整受控记录以 `data/game_scenes.json` 为准。**PC 图中的导航边不代表 Android 也能使用**；Android 未收录的场景仍可作为 PC 玩法认知，但不能生成安卓触摸命令。

## 能力边界

- 尚未从 iQOO Pad5 Pro / Windows 游戏窗口实时辨认当前场景；**需要用户手动选择**。下一阶段由本地截图匹配 / Live Vision 自动选择 Scene ID，优先利用已知界面，未知就明确返回 unknown。
- 场景的「可以跳去哪里」不代表「按钮具体是什么用途、战斗应该如何决策」；角色/敌人机制也不在 screens.json 内。不能把静态导航关系包装成完整的游戏攻略知识库。
- 不能把原版动作脚本不经审查交给 GPT 执行；游戏控制必须通过目标应用校验、用户许可和 ActionGuard 的受限动作协议。
- **无需先测试 ChatGPT OAuth**，离线场景选择可独立运行。等用户有空连接 GPT 后，模型才会实际接收选中的场景摘要。
- Android 嵌入知识库不会自动增加 AI 的视觉、OCR、游戏自动战斗能力，也不会默默向云端传任何截图。

## 手动验收步骤（iQOO Pad5 Pro）

1. 安装最新构建成功的 StarRail AI 测试 APK，打开「AI 顾问」。
2. 查找「末日」，选择「末日幻影」，检查页面显示 **仅 PC 收录**，安卓未收录。
3. 搜索「短信」，选择「新消息通知」同一 ID，检查两个平台均收录。
4. 搜索「手机菜单」，选择后可看到 PC 和安卓不同的导航入口。
5. 不接入 GPT 也可以离线查看这些数据；若已接入模型，手动写问题并点「让 AI 分析」，确认 AI 提到**手动选择、不是屏幕实测**，且不会声称帮你点击了游戏。

## 后续

- 将 Scene ID 作为 Live Vision 的受限分类输出，使用本地模板匹配快速认出已知界面。
- 对 PC/安卓游戏 HUD、队伍、装备、挑战页面积累新的具备来源的语义数据，不凭空补完。
- 扩展 [Live Vision](LIVE_VISION.md) 和 [Windows-MCP 评估](WINDOWS_MCP_EVALUATION.md) 的端侧观察层；自动操作前必须另行通过设备安全验证。
