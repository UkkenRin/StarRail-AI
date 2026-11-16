# Architecture and safety boundaries

We maintain an upstream + overlay build until core control interfaces stabilize.

## Shared abstractions

- FrameSource / GameObserver: continuous **local in-memory screen/video frame stream**, available separately on Android and Windows; vision AI can analyze selected frames **without mandatory OCR**. Optional local OCR only for exact numeric UI extraction. Remote video/frame submission must be user-authorized and budget-gated. See [LIVE_VISION.md](LIVE_VISION.md).
- AccountProfile: character, light cone, equipped relic, and provenance/last-updated. Do not infer unobserved inventory.
- SceneKnowledge: generated read-only, source-tagged M7A scene labels and **separate PC/touch navigation graphs** (`data/game_scenes.json`); no raw Python automation code. Windows/Linux via `starrail_core.scene_knowledge`; Android via `SceneCatalog.kt`. Current scene selected **manually**, not inferred from pixels, and included only on explicit model request. See [SCENE_KNOWLEDGE.md](SCENE_KNOWLEDGE.md).
- AiProvider: ChatGPT OAuth (Android prototype submitted, not device-verified), API-key provider (initial Android MVP), multimodal provider (future). All tokens must stay off Git.
- VisualEventGate: local motion/state-change signatures, event filtering, focus and freshness checks, model call cooldown and request budget; core proof of concept in `starrail_core/live_vision.py` with no capture or cloud integration yet.
- VisionAgent / Planner: receives selected keyframes or supported video input, game state, user preferences, and action capability list. Outputs typed decisions plus confidence, not arbitrary shell commands.
- ActionGuard: validates current game package/display/scale, confidence, cooldown, and action allowlist. No model-authored raw shell commands; irreversible actions require explicit user consent.
- GameExecutor: Windows native input, Android Shizuku virtual display, future Android physical display. Backend boundaries are explicit and tests are platform-specific.

## Android MVP v0.5 (code / CI only)

Overlay contributes a text-based read-only advisor using HTTPS OpenAI-compatible Chat Completions plus an experimental native ChatGPT OAuth option. ChatGPT plan requests use official Responses API (`store:false`, `stream:true`) and a dynamically registered public OAuth client. Credentials are encrypted with Android Keystore. OAuth was compiled in an earlier CI build but has **not** been authenticated or tested on device. Account/observation are typed into the UI; there is no screenshot upload, autonomous action, account scan, PC challenge mode or foreground game control. This deliberately avoids unsafe assumptions about the foreground screen.

## Platform matrix

| Capability | Windows upstream | Android upstream | This repo now |
| --- | --- | --- | --- |
| Daily routines | implemented | implemented | inherited via upstream |
| High-difficulty challenge | implemented (PC UI) | not implemented in touch profile | pending |
| Character export | implemented (PC UI) | not wired | pending |
| Background virtual screen | N/A | implemented | inherited |
| Foreground physical screen | N/A | not available | research, NOT enabled |
| Account-aware model suggestions | via agent integration | unavailable | manual text prototype |
| AI real-time combat control | not implemented | not implemented | not implemented |

## License and provenance

Upstream March7thAssistant is GPL-3.0 licensed. Retain upstream notices when producing modified binaries and provide corresponding source. This repository's overlay is GPL-3.0-or-later; its distribution must observe upstream GPL-3.0 compatibility.
