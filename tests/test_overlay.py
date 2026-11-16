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
            app.parent.mkdir(parents=True)
            strings.parent.mkdir(parents=True)
            app.write_text("    LOGS(R.string.tab_logs, M7Icons.Logs),\n" +
                           "                            Tab.LOGS -> LogsScreen(state, logs, actions)\n")
            strings.write_text("<resources>\n</resources>")
            apply(root)
            first = app.read_text()
            self.assertIn("Tab.AI -> AiAdvisorScreen()", first)
            self.assertIn('name="tab_ai"', strings.read_text())
            self.assertTrue((app.parent / "AiAdvisorScreen.kt").is_file())
            apply(root)
            self.assertEqual(first, app.read_text())

    def test_unknown_upstream_refused(self):
        with TemporaryDirectory() as tmp:
            root = Path(tmp)
            app = root / "Android/app/src/main/kotlin/top/m7a/ui/App.kt"
            strings = root / "Android/app/src/main/res/values/strings.xml"
            app.parent.mkdir(parents=True)
            strings.parent.mkdir(parents=True)
            app.write_text("unexpected upstream")
            strings.write_text("<resources></resources>")
            with self.assertRaises(RuntimeError):
                apply(root)
            self.assertEqual(app.read_text(), "unexpected upstream")


if __name__ == "__main__":
    unittest.main()
