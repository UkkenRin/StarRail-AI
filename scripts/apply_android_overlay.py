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
    gradle = upstream / "Android/app/build.gradle"
    advisor = upstream / "Android/app/src/main/kotlin/top/m7a/ui/AiAdvisorScreen.kt"
    if not (app.is_file() and strings.is_file() and gradle.is_file()):
        raise FileNotFoundError("Expected M7A Android source tree")
    code = app.read_text(encoding="utf-8")
    code = replace_once(code,
        "    LOGS(R.string.tab_logs, M7Icons.Logs),",
        "    LOGS(R.string.tab_logs, M7Icons.Logs),\n    AI(R.string.tab_ai, M7Icons.Game),")
    code = replace_once(code,
        "                            Tab.LOGS -> LogsScreen(state, logs, actions)",
        "                            Tab.LOGS -> LogsScreen(state, logs, actions)\n                            Tab.AI -> AiAdvisorScreen()")
    gradle_code = gradle.read_text(encoding="utf-8")
    gradle_code = replace_once(gradle_code, "applicationId = 'top.m7a'", "applicationId = 'top.starrailai'")
    gradle_code = replace_once(gradle_code,
        "    testImplementation 'junit:junit:4.13.2'",
        "    testImplementation 'junit:junit:4.13.2'\n    testImplementation 'org.json:json:20250517'")
    s = strings.read_text(encoding="utf-8")
    s = replace_once(s, '<string name="app_name">三月七小助手</string>',
                        '<string name="app_name">StarRail AI（实验版）</string>')
    if '<string name="tab_ai">' not in s:
        s = replace_once(s, "</resources>", '    <string name="tab_ai">AI 顾问</string>\n</resources>')
    source = OVERLAY / "app/src/main/kotlin/top/m7a/ui/AiAdvisorScreen.kt"
    if not source.is_file():
        raise FileNotFoundError(source)
    app.write_text(code, encoding="utf-8")
    strings.write_text(s, encoding="utf-8")
    gradle.write_text(gradle_code, encoding="utf-8")
    shutil.copyfile(source, advisor)
    oauth_source = OVERLAY / "app/src/main/kotlin/top/m7a/ui/ChatGptOAuth.kt"
    if not oauth_source.is_file():
        raise FileNotFoundError(oauth_source)
    shutil.copyfile(oauth_source, advisor.with_name("ChatGptOAuth.kt"))
    profile_source = OVERLAY / "app/src/main/kotlin/top/m7a/ui/AccountProfile.kt"
    if not profile_source.is_file():
        raise FileNotFoundError(profile_source)
    shutil.copyfile(profile_source, advisor.with_name("AccountProfile.kt"))
    profile_test = OVERLAY / "app/src/test/kotlin/top/m7a/ui/AccountProfileTest.kt"
    if not profile_test.is_file():
        raise FileNotFoundError(profile_test)
    test_output = upstream / "Android/app/src/test/kotlin/top/m7a/ui/AccountProfileTest.kt"
    test_output.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(profile_test, test_output)
    catalog_source = OVERLAY / "app/src/main/kotlin/top/m7a/ui/SceneCatalog.kt"
    catalog_test = OVERLAY / "app/src/test/kotlin/top/m7a/ui/SceneCatalogTest.kt"
    knowledge = ROOT / "data/game_scenes.json"
    for required in (catalog_source, catalog_test, knowledge):
        if not required.is_file():
            raise FileNotFoundError(required)
    shutil.copyfile(catalog_source, advisor.with_name("SceneCatalog.kt"))
    shutil.copyfile(catalog_test, test_output.with_name("SceneCatalogTest.kt"))
    asset_output = upstream / "Android/app/src/main/assets/starrail_ai_scenes.json"
    asset_output.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(knowledge, asset_output)


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("upstream", type=Path)
    args = parser.parse_args()
    apply(args.upstream.resolve())
    print("Applied read-only AI advisor overlay")
