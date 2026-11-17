"""Gamer-inspired sparse *planning* gate; no game input or model calls.

Gamer (RainBowLuoCS/Gamer) uses sparse thinking for routine controls and
memory summaries for long tasks. Unlike Gamer's ~5-Hz dedicated inference
server, a per-step paid visual-API call is NOT appropriate for this project.

This module chooses when to ASK FOR REASONING, not when to execute actions or
authorize a screenshot upload. Existing M7A skills and locally verified plans
run without cloud planning. For any cloud suggestion, the caller MUST pass a
separate LiveVisionGate check, user consent and provider-level budget.

Do not populate the observations or verification flags with model self-reports.
"""
from __future__ import annotations

from collections import OrderedDict
from dataclasses import dataclass
from enum import Enum

from .live_vision import fingerprint_distance


class ThinkEvent(str, Enum):
    ROUTINE = "routine"
    NEW_GOAL = "new_goal"
    UNKNOWN_SCENE = "unknown_scene"
    SCRIPT_FAILED = "script_failed"
    UNEXPECTED = "unexpected"
    VERIFICATION_FAILED = "verification_failed"
    MANUAL = "manual"


class ThinkRoute(str, Enum):
    KEEP_LOCAL = "keep_local"
    USE_M7A = "use_m7a"
    USE_VERIFIED_PLAN = "use_verified_plan"
    CONSIDER_CLOUD_REASONING = "consider_cloud_reasoning"
    PAUSE = "pause"


@dataclass(frozen=True)
class ThinkSignal:
    """All inputs are local, observed / independently verified facts."""

    session_id: str
    goal_id: str
    scene_id: str
    fingerprint: bytes  # 8x8 luminance signature computed on device, no pixels
    event: ThinkEvent
    game_focused: bool
    frame_age_ms: int
    m7a_running: bool = False
    m7a_input_released: bool = False
    mapped_m7a_skill: bool = False
    # A known script ID alone is NOT evidence that it suits the current scene.
    # Trust these flags only when a local task/scene capability checker verifies them.
    m7a_skill_suitable_now: bool = False
    m7a_recent_failure: bool = False
    prefer_native_agent: bool = False
    verified_cached_plan: bool = False


@dataclass(frozen=True)
class ThinkDecision:
    route: ThinkRoute
    reason: str

    @property
    def reason_candidate(self) -> bool:
        # Not an API permit: LiveVisionGate must independently permit upload.
        return self.route is ThinkRoute.CONSIDER_CLOUD_REASONING


class SparseThinkGate:
    """Low-cost gate for *when* to reason; no actual planner/executor.

    Records only dispatched reasoning attempts, not every local frame. A model
    must not bypass input ownership checks or repeatedly ask about the same
    unresolved scene. An explicit NEW user task may reset this object.
    """

    _CONTEXT_LIMIT = 128
    _REASON_EVENTS = frozenset({
        ThinkEvent.NEW_GOAL, ThinkEvent.UNKNOWN_SCENE, ThinkEvent.SCRIPT_FAILED,
        ThinkEvent.UNEXPECTED, ThinkEvent.VERIFICATION_FAILED, ThinkEvent.MANUAL,
    })

    def __init__(
        self,
        *, max_frame_age_ms: int = 2_000,
        near_duplicate_threshold: float = 14.0,
        max_requests_per_context: int = 2,
    ) -> None:
        if (max_frame_age_ms < 1 or max_requests_per_context < 1
                or not 0 <= near_duplicate_threshold <= 255):
            raise ValueError("invalid sparse-thinking limits")
        self.max_frame_age_ms = max_frame_age_ms
        self.near_duplicate_threshold = near_duplicate_threshold
        self.max_requests_per_context = max_requests_per_context
        # Context key -> (last sent 8x8 signature, count of sent requests).
        self._requests: OrderedDict[
            tuple[str, str, str, ThinkEvent], tuple[bytes, int]
        ] = OrderedDict()

    @staticmethod
    def _key(signal: ThinkSignal) -> tuple[str, str, str, ThinkEvent]:
        return (signal.session_id, signal.goal_id, signal.scene_id, signal.event)

    def decide(self, signal: ThinkSignal) -> ThinkDecision:
        if (not isinstance(signal.event, ThinkEvent) or len(signal.fingerprint) != 64
                or not signal.session_id or not signal.goal_id):
            raise ValueError("invalid local decision signal")
        if not signal.game_focused or signal.frame_age_ms < 0 or (
            signal.frame_age_ms > self.max_frame_age_ms
        ):
            return ThinkDecision(ThinkRoute.PAUSE, "focus_missing_or_stale_frame")
        if signal.m7a_running:
            if signal.event in {ThinkEvent.SCRIPT_FAILED, ThinkEvent.UNEXPECTED,
                                ThinkEvent.VERIFICATION_FAILED, ThinkEvent.UNKNOWN_SCENE}:
                # Never reason-and-click while the original task still owns input.
                return ThinkDecision(ThinkRoute.PAUSE, "await_m7a_input_release")
            return ThinkDecision(ThinkRoute.KEEP_LOCAL, "m7a_skill_already_running")
        if signal.event is ThinkEvent.SCRIPT_FAILED and not signal.m7a_input_released:
            return ThinkDecision(ThinkRoute.PAUSE, "await_m7a_input_release")
        if signal.event in {ThinkEvent.ROUTINE, ThinkEvent.NEW_GOAL}:
            # Agent-first: reusable, locally VERIFIED plans are independent
            # capabilities, not second-class fallbacks to M7A.
            if signal.verified_cached_plan:
                return ThinkDecision(ThinkRoute.USE_VERIFIED_PLAN, "verified_local_plan")
            # M7A is an OPTIONAL skill, selected only when the locally verified
            # current situation matches its capabilities, it has not just failed,
            # and the user hasn't selected native agent control. Merely knowing
            # a script ID cannot dictate the agent's policy.
            if (signal.mapped_m7a_skill and signal.m7a_skill_suitable_now
                    and not signal.m7a_recent_failure
                    and not signal.prefer_native_agent):
                return ThinkDecision(ThinkRoute.USE_M7A, "locally_verified_m7a_skill")
        if signal.event is ThinkEvent.ROUTINE:
            return ThinkDecision(ThinkRoute.KEEP_LOCAL, "routine_no_new_evidence")
        if signal.event not in self._REASON_EVENTS:
            return ThinkDecision(ThinkRoute.KEEP_LOCAL, "no_reasoning_trigger")
        if not signal.scene_id:
            # Unknown scene MUST be explicit, not invented by model.
            if signal.event is not ThinkEvent.UNKNOWN_SCENE:
                return ThinkDecision(ThinkRoute.PAUSE, "missing_scene_identity")
        key = self._key(signal)
        previous = self._requests.get(key)
        if previous:
            previous_fingerprint, count = previous
            if count >= self.max_requests_per_context:
                return ThinkDecision(ThinkRoute.PAUSE, "context_reasoning_limit")
            if fingerprint_distance(previous_fingerprint, signal.fingerprint) < (
                self.near_duplicate_threshold
            ):
                return ThinkDecision(ThinkRoute.KEEP_LOCAL, "same_evidence_already_sent")
        return ThinkDecision(ThinkRoute.CONSIDER_CLOUD_REASONING, "new_reasoning_event")

    def record_cloud_request_dispatched(self, signal: ThinkSignal) -> None:
        """Call ONLY after LiveVisionGate permits a real provider request.

        This counts requests even if the provider later fails, preventing paid
        retry loops. A preview/candidate is not counted until dispatched.
        """
        if not self.decide(signal).reason_candidate:
            raise ValueError("no fresh permitted reasoning candidate")
        key = self._key(signal)
        previous = self._requests.get(key)
        count = 0 if previous is None else previous[1]
        self._requests[key] = (signal.fingerprint, count + 1)
        self._requests.move_to_end(key)
        if len(self._requests) > self._CONTEXT_LIMIT:
            self._requests.popitem(last=False)

    def reset_for_new_user_task(self) -> None:
        """Only an explicit new authorized task may clear this retry history."""
        self._requests.clear()
