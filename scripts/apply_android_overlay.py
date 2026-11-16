#!/usr/bin/env python3
"""Safely apply this repository's minimal AI advisor to the checked-out M7A source.

Idempotent; fails if upstream layout or expected anchors changed.
This does NOT enable foreground touchscreen automation.
"""
from __future__ import annotations
import argparse
from pathlib import Path
import shutil

ROOT = Path(__file__).resolve().parents[1]
OVERLAY = ROOT / "android_overlay"


def replace_once(text: str, before: str, after: str) -> str:
    if after in text:
        return text
    if text.count(before) != 1:
        raise RuntimeError(f"Upstream layout changed: expected exactly one anchor: {before!r}")
    return text.replace(before, after, 1)


def apply(upstream: Path):
    app = upstream / "Android/app/src/main/kotlin/top/m7a/ui/App.kt"
    strings = upstream / "Android/app/src/main/res/values/strings.xml"
    advisor = upstream / "Android/app/src/main/kotlin/top/m7a/ui/AiAdvisorScreen.kt"
    if not (app.is_file() and strings.is_file()):
        raise FileNotFoundError("Expected M7A Android source tree")
    code = app.read_text(encoding="utf-8")
    code = replace_once(code,
        "    LOGS(R.string.tab_logs, M7Icons.Logs),",
        "    LOGS(R.string.tab_logs, M7Icons.Logs),\n    AI(R.string.tab_ai, M7Icons.Game),")
    code = replace_once(code,
        "                            Tab.LOGS -> LogsScreen(state, logs, actions)",
        "                            Tab.LOGS -> LogsScreen(state, logs, actions)\n                            Tab.AI -> AiAdvisorScreen()")
    s = strings.read_text(encoding="utf-8")
    if '<string name="tab_ai">' not in s:
        s = replace_once(s, "</resources>", '    <string name="tab_ai">AI 顾问</string>\n</resources>')
    source = OVERLAY / "app/src/main/kotlin/top/m7a/ui/AiAdvisorScreen.kt"
    if not source.is_file():
        raise FileNotFoundError(source)
    app.write_text(code, encoding="utf-8")
    strings.write_text(s, encoding="utf-8")
    shutil.copyfile(source, advisor)


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("upstream", type=Path)
    args = parser.parse_args()
    apply(args.upstream.resolve())
    print("Applied read-only AI advisor overlay")
