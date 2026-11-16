# PC → Android port map (verified against upstream source, 2026-11-16)

## Why the APK creates a virtual screen

Android/app/src/main/python/android_task.py always invokes controller.prepare_display() during task setup. For physical devices, module/game/adb.py::prepare_display sends create_display (1280×720, dpi 240). Android/device-service/.../VirtualScreen.java backs it with DisplayManager.createVirtualDisplay and an ImageReader for frames. Android's Python task entry points currently cover ROUTINE, DAILY, POWER.

## Critical restriction

utils/tasks.py restricts ADB and touch_720 to routine/daily/power and a few utility tasks. Desktop challenge code, e.g. tasks/challenge/apocalyptic.py, relies on pc_1080 crop regions and desktop navigation. Removing the whitelist alone is not a port. Windows AI integration in assets/docs/Agent.md and assets/agent/skills/march7th-assistant/SKILL.md is an agent controlling an executable and extracting characters.json; it does not imply real-time AI combat.

## Planned implementation phases

1. Establish reproducible Android CI build with one read-only AI screen (this repository initial milestone).
2. Extract pure account analysis and model provider interfaces for both Windows and Android. Add explicit export data version/provenance and errors.
3. Integrate PC character_export parser with touch-specific navigation and screenshot/OCR tests; no guessing missing inventory.
4. Add Android physical game surface mode only after implementing: foreground game package checks, safe screenshot provider for display 0, orientation/size mapping, no input outside game, stop-on-lock, and tests on iQOO/OriginOS.
5. Independently port PC game tasks to touch_720 with template assets and state transitions.
6. Enable strictly validated, reversible actions from typed AI planner; require confirmation for relic decomposition, synthesis or resource spending.

**Current behavior is unchanged**: running the normal tasks still creates a virtual screen. The experimental AI page does not control the game.
