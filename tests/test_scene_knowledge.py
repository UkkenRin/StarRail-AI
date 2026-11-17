"""Regression tests for the pinned M7A screen corpus and cross-platform routes."""
import json
from pathlib import Path
import sys
import unittest

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "scripts"))

from build_scene_knowledge import build_catalog
from starrail_core.scene_knowledge import load_catalog, SceneCatalog


class SceneKnowledgeTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.catalog = load_catalog()

    def test_pinned_61_pc_20_touch_and_union(self):
        scenes = self.catalog.scenes
        self.assertEqual(len(scenes), 68)
        self.assertEqual(sum(s.pc is not None for s in scenes), 61)
        self.assertEqual(sum(s.touch is not None for s in scenes), 20)
        self.assertEqual(sum(len(s.pc.destinations) for s in scenes if s.pc), 116)
        self.assertEqual(sum(len(s.touch.destinations) for s in scenes if s.touch), 33)
        self.assertEqual(self.catalog.upstream_ref, "v2026.11.19")

    def test_pc_only_challenges_and_android_only_resource_screens(self):
        self.assertIsNotNone(self.catalog.get("apocalyptic").pc)
        self.assertIsNone(self.catalog.get("apocalyptic").touch)
        self.assertIsNone(self.catalog.get("calyx").pc)
        self.assertIsNotNone(self.catalog.get("calyx").touch)

    def test_platform_specific_aliases(self):
        self.assertEqual(self.catalog.get("incoming_message").pc.name, "新消息通知")
        self.assertEqual(self.catalog.get("incoming_message").touch.name, "短信")
        self.assertTrue(any(x.id == "incoming_message" for x in self.catalog.search("短信")))
        self.assertEqual(self.catalog.get("material").touch.name, "材料合成")

    def test_graph_is_platform_separated(self):
        self.assertEqual(self.catalog.shortest_route("main", "menu", "pc"), ("main", "menu"))
        self.assertEqual(self.catalog.shortest_route("main", "menu", "touch"), ("main", "menu"))
        self.assertIsNone(self.catalog.shortest_route("main", "apocalyptic", "touch"))
        with self.assertRaises(ValueError):
            self.catalog.shortest_route("main", "menu", "linux")

    def test_context_is_labelled_manual_and_never_executable(self):
        info = self.catalog.prompt_context("apocalyptic")
        self.assertIn("用户手动选定", info)
        self.assertIn("未经屏幕/图像确认", info)
        self.assertIn("Android 触屏：此版本场景配置未收录", info)
        self.assertNotIn("auto.click_element", info)
        self.assertNotIn("press_key", info)
        self.assertEqual(self.catalog.prompt_context("unknown_id"), "")
        full = (ROOT / "data/game_scenes.json").read_text(encoding="utf-8")
        self.assertNotIn("actions_list", full)
        self.assertNotIn("auto.", full)
        self.assertNotIn(".png", full)

    def test_strict_schema_and_references(self):
        with self.assertRaises(ValueError):
            SceneCatalog({"schema_version": 9, "source": {"ref": "v1"}, "scenes": []})
        raw = json.loads((ROOT / "data/game_scenes.json").read_text(encoding="utf-8"))
        raw["scenes"][0]["pc"]["destinations"].append("made_up")
        with self.assertRaises(ValueError):
            SceneCatalog(raw)

    def test_exporter_strips_actions_and_handles_platform_alias(self):
        pc = [
            {"id": "main", "name": "主页", "image_path": "secret.png",
             "actions": [{"target_screen": "menu", "actions_list": ["dangerous.action()"]}]},
            {"id": "menu", "name": "菜单", "actions": []},
        ]
        touch = [
            {"id": "main", "name": "触屏首页", "actions": [{"target_screen": "menu"}]},
            {"id": "menu", "name": "触屏菜单", "actions": []},
        ]
        data = build_catalog(pc, touch)
        self.assertEqual(data["scenes"][0]["pc"]["destinations"], ["menu"])
        self.assertEqual(data["scenes"][0]["touch"]["name"], "触屏首页")
        self.assertNotIn("dangerous.action()", json.dumps(data))
        self.assertNotIn("secret.png", json.dumps(data))
        broken = [dict(pc[0], actions=[{"target_screen": "does_not_exist"}]), pc[1]]
        with self.assertRaises(ValueError):
            build_catalog(broken, touch)


if __name__ == "__main__":
    unittest.main()
