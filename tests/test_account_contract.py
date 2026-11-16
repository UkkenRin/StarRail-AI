"""PC exporter v1 contract tests, independent from game runtime."""
import json
import unittest

from starrail_core.account_profile import parse_m7a_export


def fixture():
    return {
        "schema_version": 1,
        "exported_at": "2026-11-17T08:00:00+08:00",
        "complete": True,
        "failed": [],
        "characters": [{
            "name": "三月七", "path": "存护", "level": 80, "max_level": 80,
            "eidolon": 6, "warnings": [],
            "light_cone": {"name": "余生的第一天", "level": 80, "superimposition": 5},
            "relics": {"sets": [{"name": "戍卫风雪的铁卫", "pieces": 1}],
                       "pieces": [{"slot": "头部", "set": "戍卫风雪的铁卫",
                                   "level": 15, "main_stat": {"stat": "hp", "value": 705},
                                   "sub_stats": []}]},
            "skills": [{"name": "战技", "level": 10}],
        }],
    }


class AccountContractTests(unittest.TestCase):
    def test_valid_export_preserves_equipped_only(self):
        account = parse_m7a_export(json.dumps(fixture()).encode("utf-8"))
        self.assertTrue(account.complete)
        self.assertFalse(account.inventory_complete)
        self.assertEqual(account.source, "march7th-characters-v1")
        self.assertEqual(account.characters[0].name, "三月七")
        self.assertEqual(account.characters[0].light_cone["name"], "余生的第一天")
        self.assertEqual(len(account.characters[0].equipped_relics), 1)
        self.assertIn("完整背包：未知", account.summary())

    def test_partial_and_failed_are_not_missing_inventory(self):
        data = fixture()
        data["complete"] = False
        data["failed"] = [{"name": "未知角色", "error": "OCR失败"}]
        data["characters"][0]["warnings"] = ["属性不完整"]
        account = parse_m7a_export(data)
        self.assertFalse(account.complete)
        self.assertEqual(account.failed_count, 1)
        self.assertEqual(account.warnings, ("属性不完整",))
        self.assertIn("不得推断", account.summary())

    def test_invalid_schema_refused(self):
        data = fixture()
        data["schema_version"] = 2
        with self.assertRaises(ValueError):
            parse_m7a_export(data)

    def test_wrong_level_and_eidolon_rejected(self):
        for level, eidolon in [(101, 6), (80, 7), (0, 0), (80, True)]:
            data = fixture()
            data["characters"][0]["level"] = level
            data["characters"][0]["eidolon"] = eidolon
            with self.assertRaises(ValueError):
                parse_m7a_export(data)

    def test_empty_list_and_invalid_complete_rejected(self):
        for modification in ({"characters": []}, {"complete": "yes"}):
            data = fixture()
            data.update(modification)
            with self.assertRaises(ValueError):
                parse_m7a_export(data)

    def test_size_limit(self):
        with self.assertRaises(ValueError):
            parse_m7a_export(b" " * (4 * 1024 * 1024 + 1))


if __name__ == "__main__":
    unittest.main()
