"""Cross-platform, read-only adapter for March7thAssistant PC characters.json v1.

This module does not communicate with games or model providers. A source profile is not
a complete inventory: it only records observed characters and their equipped items.
"""
from __future__ import annotations

from dataclasses import dataclass
import json
from pathlib import Path
from typing import Any, Mapping

SOURCE = "march7th-characters-v1"
MAX_BYTES = 4 * 1024 * 1024


@dataclass(frozen=True)
class Character:
    name: str
    path: str
    level: int
    max_level: int
    eidolon: int
    light_cone: Mapping[str, Any] | None
    equipped_relics: tuple[Mapping[str, Any], ...]
    skills: tuple[Mapping[str, Any], ...]
    warnings: tuple[str, ...]


@dataclass(frozen=True)
class AccountSnapshot:
    source: str
    exported_at: str
    complete: bool
    failed_count: int
    characters: tuple[Character, ...]
    inventory_complete: bool = False

    @property
    def warnings(self) -> tuple[str, ...]:
        return tuple(warning for c in self.characters for warning in c.warnings)

    def summary(self) -> str:
        header = (
            f"来源：{self.source}；导出时间：{self.exported_at}；"
            f"角色数：{len(self.characters)}；导出完整：{self.complete}；"
            f"读取失败记录：{self.failed_count}；完整背包：未知。"
        )
        lines = [header, "仅包含扫描到的角色和当时已装备的光锥、遗器；缺失不代表未拥有。"]
        for ch in self.characters:
            cone = ch.light_cone
            cone_info = (f"{cone.get('name', '未知')} Lv.{cone.get('level', '未知')}"
                         if cone is not None else "未装备或读取失败")
            lines.append(
                f"{ch.name}／{ch.path} Lv.{ch.level}/{ch.max_level} "
                f"星魂{ch.eidolon} 光锥:{cone_info} "
                f"已装备遗器{len(ch.equipped_relics)}件；"
                f"识别警告:{'；'.join(ch.warnings) or '无'}"
            )
        if not self.complete or self.failed_count or self.warnings:
            lines.append("此档案不完整或含识别警告；不得推断未收录的角色/装备不存在。")
        return "\n".join(lines)


def parse_m7a_export(raw: str | bytes | Mapping[str, Any]) -> AccountSnapshot:
    if isinstance(raw, bytes):
        if len(raw) > MAX_BYTES:
            raise ValueError("account export exceeds 4 MB")
        document = json.loads(raw.decode("utf-8"))
    elif isinstance(raw, str):
        if len(raw.encode("utf-8")) > MAX_BYTES:
            raise ValueError("account export exceeds 4 MB")
        document = json.loads(raw)
    else:
        document = raw

    if not isinstance(document, dict) or document.get("schema_version") != 1:
        raise ValueError("requires March7thAssistant characters.json schema_version=1")
    chars = document.get("characters")
    if not isinstance(chars, list) or not 1 <= len(chars) <= 300:
        raise ValueError("invalid character collection")
    if type(document.get("complete")) is not bool:
        raise ValueError("missing/invalid complete marker")
    failed = document.get("failed", [])
    if not isinstance(failed, list):
        raise ValueError("invalid failed-record collection")
    records: list[Character] = []
    for index, row in enumerate(chars):
        if not isinstance(row, dict):
            raise ValueError(f"character {index} must be an object")
        name, path = row.get("name"), row.get("path")
        level, max_level, eidolon = row.get("level"), row.get("max_level"), row.get("eidolon")
        if not isinstance(name, str) or not name.strip() or not isinstance(path, str) or not path.strip():
            raise ValueError(f"character {index} missing name/path")
        if any(type(x) is not int for x in (level, max_level, eidolon)) or not (
            1 <= level <= max_level <= 100 and 0 <= eidolon <= 6
        ):
            raise ValueError(f"invalid level/eidolon for {name}")
        cone = row.get("light_cone")
        if cone is not None and not isinstance(cone, dict):
            raise ValueError(f"invalid light cone for {name}")
        relics = row.get("relics")
        if relics is None:
            relics = {}
        if not isinstance(relics, dict):
            raise ValueError(f"invalid relic container for {name}")
        pieces = relics.get("pieces", [])
        skills = row.get("skills", [])
        warnings = row.get("warnings", [])
        if not isinstance(pieces, list) or not isinstance(skills, list) or not isinstance(warnings, list):
            raise ValueError(f"invalid details for {name}")
        if any(not isinstance(p, dict) for p in pieces + skills) or any(not isinstance(w, str) for w in warnings):
            raise ValueError(f"invalid entries for {name}")
        records.append(Character(
            name=name.strip(), path=path.strip(), level=level,
            max_level=max_level, eidolon=eidolon,
            light_cone=cone, equipped_relics=tuple(pieces),
            skills=tuple(skills), warnings=tuple(warnings),
        ))
    return AccountSnapshot(
        source=SOURCE,
        exported_at=str(document.get("exported_at", "未提供"))[:60],
        complete=document["complete"],
        failed_count=len(failed),
        characters=tuple(records),
    )


def load_m7a_export(path: str | Path) -> AccountSnapshot:
    p = Path(path)
    if p.stat().st_size > MAX_BYTES:
        raise ValueError("account export exceeds 4 MB")
    return parse_m7a_export(p.read_bytes())
