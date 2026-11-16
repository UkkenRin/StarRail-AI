"""No-OCR-gate for event-driven live-vision UI agents.

The screen recorder keeps running *locally*. It must create an 8x8 grayscale
signature from each frame without uploading full frames. This module decides
whether a cloud-vision *request is allowed*, not whether any action is safe.
It neither reads screenshots nor connects to any model/provider.

Consumers MUST recheck focused game, model credentials, user permission,
and ActionGuard before sending keyframes or performing touches.
"""
from __future__ import annotations

from dataclasses import dataclass
from typing import Literal

Event = Literal["none", "scene_change", "battle_turn", "unexpected", "manual"]
EVENTS = frozenset({"none", "scene_change", "battle_turn", "unexpected", "manual"})


@dataclass(frozen=True)
class FrameSignal:
    timestamp_ms: int
    fingerprint: bytes  # precisely 64 local thumbnail intensity samples, never the frame
    focused_game: bool
    event: Event = "none"


@dataclass(frozen=True)
class VisionBudget:
    cloud_enabled: bool = False
    max_requests_per_task: int = 8
    minimum_interval_ms: int = 10_000
    maximum_frame_age_ms: int = 2_000
    scene_change_threshold: float = 14.0  # mean absolute 8-bit thumbnail difference

    def __post_init__(self):
        if self.max_requests_per_task < 0:
            raise ValueError("negative cloud request budget")
        if self.minimum_interval_ms < 0 or self.maximum_frame_age_ms < 1:
            raise ValueError("invalid time limits")
        if not 0 <= self.scene_change_threshold <= 255:
            raise ValueError("invalid image difference threshold")


@dataclass(frozen=True)
class VisionDecision:
    send_to_model: bool
    reason: str
    used_requests: int


def fingerprint_distance(a: bytes, b: bytes) -> float:
    """Mean absolute difference between two tiny, local, 8x8 grayscale signatures."""
    if len(a) != 64 or len(b) != 64:
        raise ValueError("frame fingerprint must contain exactly 64 grayscale samples")
    return sum(abs(x - y) for x, y in zip(a, b)) / 64


class LiveVisionGate:
    """Per-task single-threaded decision gate; not an image uploader or timer.

    A permit is consumed when emitted, whether network operation later fails or
    succeeds. This avoids unlimited retry spam on error. Call reset() only when
    the user explicitly begins a NEW task.
    """

    def __init__(self, policy: VisionBudget | None = None):
        self.policy = policy or VisionBudget()
        self._used = 0
        self._last_permitted_time: int | None = None
        self._last_permitted_fingerprint: bytes | None = None

    def evaluate(self, frame: FrameSignal, *, now_ms: int) -> VisionDecision:
        if len(frame.fingerprint) != 64:
            raise ValueError("frame fingerprint must contain exactly 64 grayscale samples")
        if frame.event not in EVENTS:
            raise ValueError("unknown live vision event")
        if frame.timestamp_ms > now_ms or now_ms - frame.timestamp_ms > self.policy.maximum_frame_age_ms:
            return self._deny("stale_frame")
        if not frame.focused_game:
            return self._deny("game_not_focused")
        if not self.policy.cloud_enabled:
            return self._deny("no_explicit_cloud_consent")
        if frame.event == "none":
            return self._deny("no_relevant_event")
        if self._used >= self.policy.max_requests_per_task:
            return self._deny("task_budget_exhausted")
        if self._last_permitted_time is not None and now_ms - self._last_permitted_time < self.policy.minimum_interval_ms:
            return self._deny("cooldown")
        # An explicit user request overrides visual deduplication, not consent/cost.
        if frame.event == "scene_change" and self._last_permitted_fingerprint is not None:
            diff = fingerprint_distance(frame.fingerprint, self._last_permitted_fingerprint)
            if diff < self.policy.scene_change_threshold:
                return self._deny("near_duplicate")
        self._used += 1
        self._last_permitted_time = now_ms
        self._last_permitted_fingerprint = frame.fingerprint
        return VisionDecision(True, "keyframe_permitted", self._used)

    def _deny(self, reason: str) -> VisionDecision:
        return VisionDecision(False, reason, self._used)

    def reset_for_new_user_task(self) -> None:
        self._used = 0
        self._last_permitted_time = None
        self._last_permitted_fingerprint = None

    @property
    def requests_used(self) -> int:
        return self._used
