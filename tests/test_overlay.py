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
            manifest = root / "Android/app/src/main/AndroidManifest.xml"
            app.parent.mkdir(parents=True)
            strings.parent.mkdir(parents=True)
            gradle.parent.mkdir(parents=True, exist_ok=True)
            manifest.parent.mkdir(parents=True, exist_ok=True)
            manifest.write_text("""<manifest xmlns:android="http://schemas.android.com/apk/res/android">\n    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_SPECIAL_USE" />\n    <application>\n        <service\n            android:name=".TaskRunnerService" android:exported="false" />\n    </application>\n</manifest>""", encoding="utf-8")
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
            self.assertTrue((app.parent / "SceneCatalog.kt").is_file())
            self.assertTrue((root / "Android/app/src/test/kotlin/top/m7a/ui/SceneCatalogTest.kt").is_file())
            packaged = root / "Android/app/src/main/assets/starrail_ai_scenes.json"
            self.assertTrue(packaged.is_file())
            self.assertIn('"schema_version": 1', packaged.read_text(encoding="utf-8"))
            self.assertIn('"ref": "v2026.11.17"', packaged.read_text(encoding="utf-8"))
            self.assertIn("org.json:json:", gradle.read_text())
            self.assertIn("top.starrailai", gradle.read_text())
            self.assertIn("android.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION", manifest.read_text())
            self.assertIn("android.permission.PACKAGE_USAGE_STATS", manifest.read_text())
            self.assertIn('android:foregroundServiceType="mediaProjection"', manifest.read_text())
            self.assertTrue((app.parent / "StarRailCaptureService.kt").is_file())
            self.assertTrue((app.parent / "LocalCaptureFrames.kt").is_file())
            self.assertTrue((app.parent / "CaptureGeometry.kt").is_file())
            self.assertTrue((app.parent / "FrameObservation.kt").is_file())
            self.assertTrue((app.parent / "AndroidForegroundProbe.kt").is_file())
            self.assertTrue((app.parent / "GamerProtocol.kt").is_file())
            self.assertTrue((app.parent / "GamerProtocolPreviewPanel.kt").is_file())
            self.assertTrue((app.parent / "GamerVisionProtocol.kt").is_file())
            self.assertTrue((app.parent / "GamerVisionCloudClient.kt").is_file())
            self.assertTrue((app.parent / "GamerVisionProposalPanel.kt").is_file())
            self.assertTrue((root / "Android/app/src/test/kotlin/top/m7a/ui/GamerVisionProtocolTest.kt").is_file())
            self.assertTrue((root / "Android/app/src/test/kotlin/top/m7a/ui/GamerProtocolTest.kt").is_file())
            self.assertTrue((root / "Android/app/src/test/kotlin/top/m7a/ui/FrameObservationTest.kt").is_file())
            self.assertTrue((root / "Android/app/src/test/kotlin/top/m7a/ui/CaptureGeometryTest.kt").is_file())
            manifest_first = manifest.read_text(encoding="utf-8")
            apply(root)
            self.assertEqual(first, app.read_text(encoding="utf-8"))
            self.assertEqual(manifest_first, manifest.read_text(encoding="utf-8"))

    def test_unknown_upstream_refused(self):
        with TemporaryDirectory() as tmp:
            root = Path(tmp)
            app = root / "Android/app/src/main/kotlin/top/m7a/ui/App.kt"
            strings = root / "Android/app/src/main/res/values/strings.xml"
            gradle = root / "Android/app/build.gradle"
            manifest = root / "Android/app/src/main/AndroidManifest.xml"
            app.parent.mkdir(parents=True)
            strings.parent.mkdir(parents=True)
            gradle.parent.mkdir(parents=True, exist_ok=True)
            manifest.parent.mkdir(parents=True, exist_ok=True)
            manifest.write_text("""<manifest xmlns:android="http://schemas.android.com/apk/res/android">\n    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_SPECIAL_USE" />\n    <application>\n        <service\n            android:name=".TaskRunnerService" android:exported="false" />\n    </application>\n</manifest>""", encoding="utf-8")
            app.write_text("unexpected upstream")
            strings.write_text('<resources><string name="app_name">三月七小助手</string></resources>', encoding="utf-8")
            gradle.write_text("applicationId = 'top.m7a'\n    testImplementation 'junit:junit:4.13.2'")
            with self.assertRaises(RuntimeError):
                apply(root)
            self.assertEqual(app.read_text(encoding="utf-8"), "unexpected upstream")


if __name__ == "__main__":
    unittest.main()
