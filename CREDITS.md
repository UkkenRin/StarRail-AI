# 致谢与开源来源 / Credits and Open-Source Attribution

## 致谢三月七小助手

StarRail-AI 衷心感谢 **[March7thAssistant（三月七小助手）](https://github.com/moesnow/March7thAssistant)** 项目的发起者 **moesnow**、历届代码贡献者、测试人员、文档作者以及社区维护者。

三月七小助手的游戏画面识别、任务自动化、Windows 与 Android 运行能力、角色面板导出和 AI Agent Skill 等功能，凝聚了原项目长期的设计、开发、测试和维护投入。**这些成果属于原项目及相应贡献者，我们不会将其声称为 StarRail-AI 独立原创。**

StarRail-AI 希望在尊重原项目劳动成果的前提下，继续探索以 AI 为主驾驶的游戏理解、动态规划、故障接管和跨平台交互。感谢原项目选择开源，使后续研究与改进成为可能。

> **我们是独立的非官方实验项目，与 March7thAssistant 项目及《崩坏：星穹铁道》开发和发行方不存在隶属或官方合作关系。此致谢也不意味着原作者认可、支持或负责 StarRail-AI 的改动。**

## 复用范围与代码来源

- **上游项目**：[moesnow/March7thAssistant](https://github.com/moesnow/March7thAssistant)
- **上游许可证**：[GNU GPL v3](https://github.com/moesnow/March7thAssistant/blob/main/LICENSE)
- **当前 Android 构建基线**：固定的 [`v2026.11.19`](https://github.com/moesnow/March7thAssistant/tree/v2026.11.19)
- **我们自己的改动**：[UkkenRin/StarRail-AI](https://github.com/UkkenRin/StarRail-AI)；主要在 `android_overlay/`、`starrail_core/`、`scripts/`、`data/` 与 `docs/`

**当前 Android APK 并非从零自主实现的应用。** 构建时 GitHub Actions 下载固定版三月七小助手源码，再应用 StarRail-AI 的修改后编译。因此，APK 继承了原项目的重要代码及功能，StarRail-AI 自身新增的功能只是其中的一部分。可从 [Android 构建工作流](.github/workflows/android-debug.yml) 和 [补丁脚本](scripts/apply_android_overlay.py) 追溯准确来源。

共享游戏场景知识 `data/game_scenes.json` 根据三月七小助手固定版本的 PC/Android 场景配置生成；详细出处与限制见 [场景知识库说明](docs/SCENE_KNOWLEDGE.md)。

## Gamer / GameScaling 研究与协议参考致谢

感谢 **[Gamer 研究项目](https://github.com/RainBowLuoCS/Gamer)**（miHoYo Honkai AI R&D Team 和合作研究人员）以及 **[THUSI-Lab/GameScaling](https://github.com/THUSI-Lab/GameScaling)** 研究者公开游戏视觉—语言—动作协议、技术文档和推理客户端思路。两仓库明确标注 Apache License 2.0，具体声明见各自 LICENSE。

StarRail-AI 在 [协议实验文档](docs/GAMER_ADAPTER.md) 中记录对应动作格式来源，并**独立编写兼容协议的严格解析器**；没有复制/打包他们的代码、训练数据、权重或专用实时推理引擎，也不声称与原研究团队合作。未来如直接引入第三方源码、权重或模型，必须保留并遵守其适用许可证和使用限制，单独审核分发要求。



- StarRail-AI 仓库提供 [GPL-3.0 许可证全文](LICENSE)；保留上游和第三方组件的适用版权及许可证通知。
- 分发修改过的 APK 时，应按所适用许可证提供该版本**完整的对应源码**（包含相应的上游源码、修改内容、构建和安装所需脚本等，按 GPL 具体要求提供），并保留必要的版权及修改声明。**只提供一个仓库链接或本致谢页，不应被视为自动满足全部许可证义务**。
- 独立依赖与捆绑资源可能有其他许可要求；这里记录已核实的核心上游出处，不代表对整个 APK 的第三方依赖和素材完成全面合规审计。
- 如来源、版本、许可证或复用方式变化，需要同步维护本页及 README，不能悄悄删除原作者署名或许可证声明。

## English summary

StarRail-AI gratefully acknowledges **moesnow and all March7thAssistant contributors**. Our Android build currently modifies and builds upon the upstream March7thAssistant source code (`v2026.11.19`), rather than being an entirely independent reimplementation. The upstream project is licensed under GPL-3.0. We preserve attribution and distinguish upstream work from StarRail-AI's experimental additions. This independent project is **not affiliated with or endorsed by** March7thAssistant or the game publisher. Distribution of modified binaries requires compliance with applicable source-code and licensing obligations.
