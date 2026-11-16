from pathlib import Path
from tempfile import TemporaryDirectory
import sys
import unittest
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "scripts"))
from apply_android_overlay import apply, replace_once


class TestOverlay(unittest.TestCase):
    def test_replace_once(self):
        self.assertEqual(replace_once("abc", "a", "AX"), "AXbc")
        self.assertEqual(replace_once("AXbc", "a", "AX"), "AXbc")
        with self.assertRaises(RuntimeError):
            replace_once("bbb", "a", "AX")

    def test_apply_and_idempotent(self):
        with TemporaryDirectory() as tmp:
            root = Path(tmp)
            app = root / "Android/app/src/main/kotlin/top/m7a/ui/App.kt"
            strings = root / "Android/app/src/main/res/values/strings.xml"
            gradle = root / "Android/app/build.gradle"
            app.parent.mkdir(parents=True)
            strings.parent.mkdir(parents=True)
            gradle.parent.mkdir(parents=True, exist_ok=True)
            app.write_text("    LOGS(R.string.tab_logs, M7Icons.Logs),\n" +
                           "                            Tab.LOGS -> LogsScreen(state, logs, actions)\n")
            strings.write_text('<resources>\n    <string name="app_name">三月七小助手</string>\n</resources>', encoding="utf-8")
            gradle.write_text("applicationId = 'top.m7a'\n    testImplementation 'junit:junit:4.13.2'")
            apply(root)
            first = app.read_text(encoding="utf-8")
            self.assertIn("Tab.AI -> AiAdvisorScreen()", first)
            self.assertIn('name="tab_ai"', strings.read_text(encoding="utf-8"))
            self.assertTrue((app.parent / "AiAdvisorScreen.kt").is_file())
            self.assertTrue((app.parent / "ChatGptOAuth.kt").is_file())
            self.assertTrue((app.parent / "AccountProfile.kt").is_file())
            self.assertTrue((root / "Android/app/src/test/kotlin/top/m7a/ui/AccountProfileTest.kt").is_file())
            self.assertIn("org.json:json:", gradle.read_text())
            self.assertIn("top.starrailai", gradle.read_text())
            apply(root)
            self.assertEqual(first, app.read_text(encoding="utf-8"))

    def test_unknown_upstream_refused(self):
        with TemporaryDirectory() as tmp:
            root = Path(tmp)
            app = root / "Android/app/src/main/kotlin/top/m7a/ui/App.kt"
            strings = root / "Android/app/src/main/res/values/strings.xml"
            gradle = root / "Android/app/build.gradle"
            app.parent.mkdir(parents=True)
            strings.parent.mkdir(parents=True)
            gradle.parent.mkdir(parents=True, exist_ok=True)
            app.write_text("unexpected upstream")
            strings.write_text('<resources><string name="app_name">三月七小助手</string></resources>', encoding="utf-8")
            gradle.write_text("applicationId = 'top.m7a'\n    testImplementation 'junit:junit:4.13.2'")
            with self.assertRaises(RuntimeError):
                apply(root)
            self.assertEqual(app.read_text(encoding="utf-8"), "unexpected upstream")


if __name__ == "__main__":
    unittest.main()
