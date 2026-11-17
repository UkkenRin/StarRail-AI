"""Verified-only bounded recent history and reusable low-risk game strategy.

Only observed SUCCESS enters this memory; model proposals or attempted actions
are not results. Purely local RAM object: no screenshots, secrets or transcripts.
Never replace a fresh trusted target binding or re-verification with this cache.
"""
from __future__ import annotations

from collections import OrderedDict, deque
from dataclasses import dataclass

from .live_vision import fingerprint_distance


@dataclass(frozen=True)
class VerifiedMove:
    goal_id: str
    game_version: str
    before_scene: str
    after_scene: str
    action_wire: str
    observed_event: str
    verified_at_ms: int


@dataclass(frozen=True)
class CachedMove:
    wire: str
    fingerprint: bytes
    expected_event: str
    verified_at_ms: int


class VerifiedGameMemory:
    """Short history + same-scene/version cache with bounded evidence summaries."""

    def __init__(self, *, max_recent: int = 5, max_skills: int = 24,
                 maximum_signature_distance: float = 5.0):
        if max_recent < 1 or max_skills < 1 or not 0 <= maximum_signature_distance <= 255:
            raise ValueError("invalid memory limits")
        self.recent: deque[VerifiedMove] = deque(maxlen=max_recent)
        self._skills: OrderedDict[tuple[str, str, str], CachedMove] = OrderedDict()
        self.max_skills = max_skills
        self.maximum_signature_distance = maximum_signature_distance
        self._failures: deque[str] = deque(maxlen=3)

    @staticmethod
    def _key(goal_id: str, version: str, scene_id: str) -> tuple[str, str, str]:
        return (goal_id, version, scene_id)

    def remember_success(self, *, goal_id: str, game_version: str, before_scene: str,
                         after_scene: str, action_wire: str, fingerprint: bytes,
                         observed_event: str, verified_at_ms: int) -> None:
        if not all((goal_id, game_version, before_scene, after_scene, action_wire,
                    observed_event)) or len(fingerprint) != 64 or verified_at_ms < 0:
            raise ValueError("success requires complete locally verified evidence")
        move = VerifiedMove(goal_id, game_version, before_scene, after_scene,
                            action_wire, observed_event, verified_at_ms)
        self.recent.append(move)
        key = self._key(goal_id, game_version, before_scene)
        self._skills[key] = CachedMove(action_wire, bytes(fingerprint),
                                       observed_event, verified_at_ms)
        self._skills.move_to_end(key)
        if len(self._skills) > self.max_skills:
            self._skills.popitem(last=False)
        self._failures.clear()

    def find_verified_action(self, *, goal_id: str, game_version: str,
                             scene_id: str, fingerprint: bytes) -> str | None:
        if len(fingerprint) != 64:
            raise ValueError("fingerprint must contain 64 local samples")
        cached = self._skills.get(self._key(goal_id, game_version, scene_id))
        if cached is None or fingerprint_distance(cached.fingerprint, fingerprint) > (
                self.maximum_signature_distance):
            return None
        return cached.wire

    def record_observed_failure(self, reason: str) -> None:
        if not reason or len(reason) > 120:
            raise ValueError("invalid trusted failure code")
        self._failures.append(reason)

    def context_summary(self, *, max_chars: int = 1200) -> str:
        """Recent observed events only; no previous images or chain of thought."""
        if max_chars < 80:
            raise ValueError("context budget too small")
        entries = []
        for m in self.recent:
            entries.append(
                f"verified goal={m.goal_id[:48]!r} version={m.game_version[:32]!r} "
                f"scene={m.before_scene[:50]!r}->{m.after_scene[:50]!r} "
                f"event={m.observed_event[:50]!r} time={m.verified_at_ms}"
            )
        for fail in self._failures:
            entries.append(f"observed_failure={fail[:120]!r}")
        return "\n".join(entries[-8:])[-max_chars:]

    @property
    def verified_actions(self) -> int:
        return len(self.recent)

    def clear_on_game_version_change(self) -> None:
        self._skills.clear()
        self.recent.clear()
        self._failures.clear()
