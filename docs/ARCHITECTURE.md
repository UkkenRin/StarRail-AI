# Architecture and safety boundaries

We maintain an upstream + overlay build until core control interfaces stabilize.

## Shared abstractions

- GameObserver: local screenshots and OCR plus structured account state. Screenshot must be opt-in for cloud use. On-device OCR is preferred when sufficient.
- AccountProfile: character, light cone, equipped relic, and provenance/last-updated. Do not infer unobserved inventory.
- AiProvider: ChatGPT OAuth (future), API-key provider (initial Android MVP), multimodal provider (future). All tokens must stay off Git.
- Planner: receives game state, user preferences, and action capability list. Outputs a typed plan and human-readable rationale.
- ActionGuard: validates current game package/display/scale, confidence, cooldown, and action allowlist. No model-authored raw shell commands; irreversible actions require explicit user consent.
- GameExecutor: Windows native input, Android Shizuku virtual display, future Android physical display. Backend boundaries are explicit and tests are platform-specific.

## Android MVP v0.4

Overlay contributes only a text-based read-only advisor using HTTPS OpenAI-compatible Chat Completions. Account/observation are typed into the UI. API key remains in in-memory UI state. No screenshot upload, autonomous action, account scan, PC challenge mode or OAuth yet. This deliberately avoids unsafe assumptions about the foreground screen.

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
