"""Build the platform-neutral Star Rail screen/navigation knowledge catalog.

Source: pinned upstream March7thAssistant scene JSON definitions.
We intentionally strip all Python action code, OCR commands, coordinates and image paths.
Nothing in the resulting JSON is an executable automation program.
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path
import re

REF = "v2026.11.19"
UPSTREAM_REPOSITORY = "moesnow/March7thAssistant"
PC_PATH = "assets/config/screens.json"
TOUCH_PATH = "assets/touch_720/screens.json"
ID_PATTERN = re.compile(r"^[a-z][a-z0-9_]{0,79}$")


def _validate(profile: object, label: str) -> list[dict]:
    if not isinstance(profile, list) or not (1 <= len(profile) <= 500):
        raise ValueError(f"{label}: invalid scene array")
    seen: set[str] = set()
    for scene in profile:
        if not isinstance(scene, dict):
            raise ValueError(f"{label}: scene not an object")
        identifier, name = scene.get("id"), scene.get("name")
        if not isinstance(identifier, str) or not ID_PATTERN.fullmatch(identifier):
            raise ValueError(f"{label}: invalid scene id")
        if not isinstance(name, str) or not (1 <= len(name) <= 100):
            raise ValueError(f"{label}: invalid screen name")
        if identifier in seen:
            raise ValueError(f"{label}: duplicate screen id {identifier}")
        seen.add(identifier)
        actions = scene.get("actions", [])
        if not isinstance(actions, list):
            raise ValueError(f"{label}: malformed actions")
        for action in actions:
            if not isinstance(action, dict) or not ID_PATTERN.fullmatch(str(action.get("target_screen", ""))):
                raise ValueError(f"{label}: invalid navigation edge")
    for scene in profile:
        for action in scene.get("actions", []):
            if action["target_screen"] not in seen:
                raise ValueError(f"{label}: navigation target not in same profile")
    return profile


def build_catalog(pc: object, touch: object, *, upstream_ref: str = REF) -> dict:
    """Extract labels and graph edges only; never copy upstream action code."""
    pc = _validate(pc, "pc_1080")
    touch = _validate(touch, "touch_720")
    by_id: dict[str, dict] = {}
    order: list[str] = []
    for platform, original in (("pc", pc), ("touch", touch)):
        for item in original:
            identifier = item["id"]
            if identifier not in by_id:
                by_id[identifier] = {"id": identifier, "name": item["name"], "pc": None, "touch": None}
                order.append(identifier)
            # Use first (PC) name as primary label, but retain platform aliases.
            targets = list(dict.fromkeys(action["target_screen"] for action in item.get("actions", [])))
            by_id[identifier][platform] = {
                "name": item["name"],
                "destinations": targets,
            }
    return {
        "schema_version": 1,
        "game": "崩坏：星穹铁道",
        "source": {
            "repo": UPSTREAM_REPOSITORY,
            "ref": upstream_ref,
            "pc": PC_PATH,
            "touch": TOUCH_PATH,
        },
        "scenes": [by_id[identifier] for identifier in order],
    }


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("upstream", type=Path, help=f"Local M7A {REF} checkout")
    parser.add_argument("--output", type=Path, default=Path("data/game_scenes.json"))
    args = parser.parse_args()
    source = args.upstream.resolve()
    pc = json.loads((source / PC_PATH).read_text(encoding="utf-8"))
    touch = json.loads((source / TOUCH_PATH).read_text(encoding="utf-8"))
    catalog = build_catalog(pc, touch)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(catalog, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"Exported {len(catalog['scenes'])} unique scene IDs to {args.output}")


if __name__ == "__main__":
    main()
