"""Gamer-style one-step integration using injected, trusted local ports.

This is an executable *platform-independent* coordinator (not an Android
device driver). It actually calls a supplied model/port, but only on evidence
and authorization. The port must own an OS input mutex and independently
confirm focus/display/control binding when dispatching a ticket.

No model may manufacture FrameProof, LocalBinding, verified events or an input
lease. Do not wire this to arbitrary shell commands or model-selected pixels.
"""
from __future__ import annotations

from dataclasses import dataclass
from typing import Any, Protocol

from .agent_handoff import AgentLease
from .gamer_protocol import parse_gamer_action
from .gamer_step_cycle import FrameProof, GamerStepCoordinator, LocalBinding, StepTicket
from .live_vision import FrameSignal, LiveVisionGate
from .sparse_thinking import SparseThinkGate, ThinkEvent, ThinkRoute, ThinkSignal
from .verified_memory import VerifiedGameMemory


class ModelPort(Protocol):
    """VLM/Gamer adapter: consumes only consented image + verified short history."""
    def propose(self, image: Any, *, history_summary: str) -> str: ...


class TrustedGamePort(Protocol):
    """Platform-specific security boundary; caller must implement OS checks."""
    def resolve(self, frame: FrameProof, canonical_wire: str) -> LocalBinding | None: ...
    def dispatch(self, ticket: StepTicket, source_frame: FrameProof, *, now_ms: int) -> bool: ...
    def observe_after(self) -> FrameProof: ...
    def monotonic_ms(self) -> int: ...


@dataclass(frozen=True)
class ObservedFrame:
    proof: FrameProof
    fingerprint: bytes  # Local 8x8 grayscale signature, never model provided
    image: Any
    event: ThinkEvent


@dataclass(frozen=True)
class RuntimeOutcome:
    stage: str
    reason: str
    cloud_requests_used: int
    verified_actions: int


class GamerAgentRuntime:
    """One invocation = at most one controlled action; no free-running cloud loop."""

    def __init__(self, *, capture_session_id: str, game_version: str,
                 coordinator: GamerStepCoordinator, lease: AgentLease,
                 vision_gate: LiveVisionGate, think_gate: SparseThinkGate,
                 memory: VerifiedGameMemory, model: ModelPort, port: TrustedGamePort):
        if not capture_session_id or not game_version or (
                coordinator.capture_session_id != capture_session_id):
            raise ValueError("invalid capture session or game version")
        self.capture_session_id = capture_session_id
        self.game_version = game_version
        self.coordinator = coordinator
        self.lease = lease
        self.vision_gate = vision_gate
        self.think_gate = think_gate
        self.memory = memory
        self.model = model
        self.port = port
        self._halted = False

    def run_one(self, frame: ObservedFrame, *, now_ms: int, goal_id: str,
                m7a_running: bool = False, m7a_input_released: bool = False,
                mapped_m7a_skill: bool = False, m7a_skill_suitable_now: bool = False,
                m7a_recent_failure: bool = False, prefer_native_agent: bool = False,
                cloud_frame_authorized: bool = False) -> RuntimeOutcome:
        def result(stage: str, reason: str) -> RuntimeOutcome:
            return RuntimeOutcome(stage, reason, self.vision_gate.requests_used,
                                  self.memory.verified_actions)

        if self._halted or self.coordinator.halted:
            return result("stopped", "runtime_stopped")
        p = frame.proof
        if not goal_id or len(frame.fingerprint) != 64 or p.session_id != self.capture_session_id:
            return result("stopped", "bad_observation")
        if (p.captured_at_ms > now_ms or now_ms - p.captured_at_ms > 2000
                or not p.game_focused or not p.display_verified):
            return result("stopped", "untrusted_frame")
        cached = self.memory.find_verified_action(
            goal_id=goal_id, game_version=self.game_version, scene_id=p.scene_id,
            fingerprint=frame.fingerprint)
        think = ThinkSignal(
            session_id=p.session_id, goal_id=goal_id, scene_id=p.scene_id,
            fingerprint=frame.fingerprint, event=frame.event,
            game_focused=p.game_focused, frame_age_ms=now_ms - p.captured_at_ms,
            m7a_running=m7a_running, m7a_input_released=m7a_input_released,
            mapped_m7a_skill=mapped_m7a_skill, m7a_skill_suitable_now=m7a_skill_suitable_now,
            m7a_recent_failure=m7a_recent_failure,
            prefer_native_agent=prefer_native_agent,
            verified_cached_plan=(cached is not None),
        )
        decision = self.think_gate.decide(think)
        if decision.route is ThinkRoute.PAUSE:
            return result("stopped", decision.reason)
        if decision.route is ThinkRoute.KEEP_LOCAL:
            return result("local", decision.reason)
        if decision.route is ThinkRoute.USE_M7A:
            # Only a suggestion to a separate task manager; no implicit launch.
            return result("m7a_candidate", decision.reason)
        if decision.route is ThinkRoute.USE_VERIFIED_PLAN:
            if cached is None:
                return result("stopped", "cached_action_missing")
            wire = cached
            source = "verified_cache"
        else:
            if not cloud_frame_authorized or frame.image is None:
                return result("local", "cloud_frame_not_authorized")
            visual_event = {
                ThinkEvent.NEW_GOAL: "manual", ThinkEvent.UNKNOWN_SCENE: "unexpected",
                ThinkEvent.SCRIPT_FAILED: "unexpected", ThinkEvent.UNEXPECTED: "unexpected",
                ThinkEvent.VERIFICATION_FAILED: "unexpected", ThinkEvent.MANUAL: "manual",
            }.get(frame.event, "none")
            quota = self.vision_gate.evaluate(FrameSignal(
                timestamp_ms=p.captured_at_ms, fingerprint=frame.fingerprint,
                focused_game=p.game_focused and p.display_verified,
                event=visual_event,
            ), now_ms=now_ms)
            if not quota.send_to_model:
                return result("local", "cloud_denied:" + quota.reason)
            self.think_gate.record_cloud_request_dispatched(think)
            try:
                wire = self.model.propose(
                    frame.image, history_summary=self.memory.context_summary())
            except Exception:
                self.memory.record_observed_failure("model_request_failed")
                return result("local", "model_request_failed_no_input")
            source = "model"
        parsed = parse_gamer_action(wire)
        if parsed.chunk is None:
            return result("stopped", "invalid_action:" + parsed.reason)
        if parsed.chunk.is_noop:
            return result("local", "noop_no_input")
        canonical = parsed.chunk.to_wire()
        try:
            binding = self.port.resolve(p, canonical)
        except Exception:
            return result("stopped", "trusted_control_mapping_failed")
        decision = self.coordinator.preflight(
            canonical, local_binding=binding, frame=p,
            lease=self.lease, now_ms=now_ms)
        if not decision.permitted or decision.ticket is None:
            return result("stopped", "preflight:" + decision.reason)
        ticket = decision.ticket
        try:
            sent = self.port.dispatch(ticket, p, now_ms=now_ms)
            next_frame = self.port.observe_after() if sent else None
            observed_now_ms = self.port.monotonic_ms()
        except Exception:
            next_frame = None
        if next_frame is None:
            self.coordinator.stop()
            self._halted = True
            self.memory.record_observed_failure("dispatch_or_capture_failed")
            return result("stopped", "dispatch_or_capture_failed")
        verified = self.coordinator.verify_step(
            ticket, next_frame=next_frame, now_ms=observed_now_ms)
        if not verified.permitted:
            self._halted = True
            self.memory.record_observed_failure("postcondition_not_verified")
            return result("stopped", verified.reason)
        assert binding is not None
        self.memory.remember_success(
            goal_id=goal_id, game_version=self.game_version,
            before_scene=p.scene_id, after_scene=next_frame.scene_id,
            action_wire=canonical, fingerprint=frame.fingerprint,
            observed_event=binding.expected_event_id,
            verified_at_ms=next_frame.captured_at_ms,
        )
        return result("verified", source + "_postcondition_verified")

    def stop(self) -> None:
        self._halted = True
        self.coordinator.stop()
