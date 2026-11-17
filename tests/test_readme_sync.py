"""Tests for the PR README documentation check."""
from __future__ import annotations

import unittest

from scripts.check_readme_sync import check, requires_readme


class ReadmeSyncTests(unittest.TestCase):
    def test_android_features_require_readme(self):
        changed = {"android_overlay/app/src/main/kotlin/Game.kt"}
        self.assertTrue(requires_readme(changed))
        self.assertFalse(check(changed)[0])

    def test_core_and_packaging_require_readme(self):
        for path in (
            "starrail_core/gamer_runtime.py",
            "data/game_scenes.json",
            "scripts/apply_android_overlay.py",
            ".github/workflows/android-debug.yml",
        ):
            with self.subTest(path=path):
                self.assertFalse(check({path})[0])

    def test_feature_with_readme_passes(self):
        self.assertTrue(check({
            "android_overlay/app/src/main/kotlin/Game.kt", "README.md"
        })[0])

    def test_docs_and_tests_only_pass(self):
        for changed in (
            {"docs/ROADMAP.md"},
            {"tests/test_gamer_runtime.py"},
            {"AGENTS.md", ".github/pull_request_template.md"},
            {".github/workflows/core-tests.yml"},
        ):
            with self.subTest(changed=changed):
                self.assertTrue(check(changed)[0])

    def test_readme_only_passes(self):
        self.assertTrue(check({"README.md"})[0])


if __name__ == "__main__":
    unittest.main()
