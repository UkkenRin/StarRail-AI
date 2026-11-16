"""Cross-platform read-only scene knowledge derived from M7A's actual navigation graph.

This is not a screen classifier or control engine. Names/edges are reference data;
the selected scene has not been visually verified, and edge scripts are never loaded.
"""
from __future__ import annotations

from collections import deque
from dataclasses import dataclass
import json
from pathlib import Path
import re

PLATFORMS = frozenset({"pc", "touch"})
_ID = re.compile(r"^[a-z][a-z0-9_]{0,79}$")
DEFAULT_CATALOG_PATH = Path(__file__).resolve().parents[1] / "data/game_scenes.json"


@dataclass(frozen=True)
class PlatformScene:
    name: str
    destinations: tuple[str, ...]


@dataclass(frozen=True)
class Scene:
    id: str
    name: str
    pc: PlatformScene | None
    touch: PlatformScene | None

    @property
    def labels(self) -> tuple[str, ...]:
        return tuple(dict.fromkeys(n for n in
                                   (self.name, self.pc.name if self.pc else None,
                                    self.touch.name if self.touch else None) if n))


class SceneCatalog:
    def __init__(self, payload: dict):
        if not isinstance(payload, dict) or payload.get("schema_version") != 1:
            raise ValueError("unsupported scene catalog schema")
        source = payload.get("source")
        if not isinstance(source, dict) or not isinstance(source.get("ref"), str):
            raise ValueError("missing upstream provenance")
        entries = payload.get("scenes")
        if not isinstance(entries, list) or not (1 <= len(entries) <= 300):
            raise ValueError("invalid scenes list")
        scenes: dict[str, Scene] = {}
        for entry in entries:
            if not isinstance(entry, dict) or not isinstance(entry.get("id"), str) or not _ID.fullmatch(entry["id"]):
                raise ValueError("invalid scene entry")
            identifier = entry["id"]
            if identifier in scenes:
                raise ValueError("duplicate scene id")
            name = entry.get("name")
            if not isinstance(name, str) or not 1 <= len(name) <= 100:
                raise ValueError("invalid scene label")
            def platform(name: str) -> PlatformScene | None:
                body = entry.get(name)
                if body is None:
                    return None
                if not isinstance(body, dict) or not isinstance(body.get("name"), str):
                    raise ValueError("invalid platform scene")
                edges = body.get("destinations")
                if not isinstance(edges, list) or len(edges) > 300 or any(
                    not isinstance(x, str) or not _ID.fullmatch(x) for x in edges
                ):
                    raise ValueError("invalid navigation edges")
                if len(set(edges)) != len(edges):
                    raise ValueError("duplicated navigation edges")
                return PlatformScene(body["name"][:100], tuple(edges))
            pc, touch = platform("pc"), platform("touch")
            if pc is None and touch is None:
                raise ValueError("scene unavailable on both profiles")
            scenes[identifier] = Scene(identifier, name, pc, touch)
        for scene in scenes.values():
            for platform_name in PLATFORMS:
                version = getattr(scene, platform_name)
                if version is None:
                    continue
                for edge in version.destinations:
                    target = scenes.get(edge)
                    if target is None or getattr(target, platform_name) is None:
                        raise ValueError(f"nonexistent {platform_name} navigation edge")
        self._scenes = scenes
        self.upstream_ref = source["ref"]
        self.game = str(payload.get("game") or "崩坏：星穹铁道")

    @property
    def scenes(self) -> tuple[Scene, ...]:
        return tuple(self._scenes.values())

    def get(self, scene_id: str) -> Scene | None:
        return self._scenes.get(scene_id)

    def search(self, query: str, limit: int = 15) -> list[Scene]:
        if limit < 1:
            return []
        q = query.strip().casefold()
        return [s for s in self._scenes.values()
                if not q or q in s.id.casefold() or any(q in v.casefold() for v in s.labels)][:limit]

    def shortest_route(self, start: str, target: str, platform: str) -> tuple[str, ...] | None:
        """Only navigation graph names; NOT executable clicks."""
        if platform not in PLATFORMS:
            raise ValueError("platform must be pc or touch")
        if start not in self._scenes or target not in self._scenes:
            return None
        if getattr(self._scenes[start], platform) is None or getattr(self._scenes[target], platform) is None:
            return None
        queue = deque([(start,)])  # no executable action text
        seen = {start}
        while queue:
            route = queue.popleft()
            here = route[-1]
            if here == target:
                return route
            version = getattr(self._scenes[here], platform)
            for nxt in version.destinations:
                if nxt not in seen:
                    seen.add(nxt)
                    queue.append(route + (nxt,))
        return None

    def prompt_context(self, scene_id: str) -> str:
        scene = self.get(scene_id)
        if scene is None:
            return ""
        lines = [
            "【三月七小助手场景知识（只读参考）】",
            f"游戏：{self.game}",
            f"用户手动选定：{scene.name}（内部 ID：{scene.id}）；**未经屏幕/图像确认**。",
            f"来源：March7thAssistant {self.upstream_ref} 的命名场景与导航图；不含按钮脚本。",
        ]
        for platform, label in (("pc", "Windows/PC"), ("touch", "Android 触屏")):
            version = getattr(scene, platform)
            if version is None:
                lines.append(f"{label}：此版本场景配置未收录，不能假定该平台可导航。")
            else:
                names = [getattr(self._scenes[edge], platform).name
                         for edge in version.destinations]
                lines.append(f"{label}：配置名称 {version.name}；已知直接导航目标："
                             + ("、".join(names[:24]) if names else "无") + "。")
        lines.append("这是静态场景知识，不代表游戏当前真的在该界面，也不代表导航可在当前设备直接执行；"
                     "AI 只能建议，不能声称已识别屏幕、点击或完成任务。"
                     "实际角色、敌人和奖励情况未知，需另外获取画面或由用户提供。")
        return "\n".join(lines)


def load_catalog(path: str | Path = DEFAULT_CATALOG_PATH) -> SceneCatalog:
    path = Path(path)
    if path.stat().st_size > 512 * 1024:
        raise ValueError("scene catalog unexpectedly large")
    return SceneCatalog(json.loads(path.read_text(encoding="utf-8")))
