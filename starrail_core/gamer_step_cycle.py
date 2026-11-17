"""Fail-closed, one-step Gamer intent preflight and observation handshake.

This is an OFFLINE orchestration contract, not a game executor. A valid Gamer
keyboard/mouse chunk is NOT a device input command. The independent, trusted
local observer must resolve it to a typed in-game control and observable
postcondition. Never allow a model to create FrameProof or LocalBinding.

A real Windows/Android adapter must acquire its own OS input lock, verify
focus/display/target AGAIN at dispatch time, then re-observe the game. Neither
this contract nor AgentHandoff proves that an operating system has done so.
"""
from __future__ import annotations

from dataclasses import dataclass, field
import secrets

from .agent_handoff import AgentHandoff, AgentLease
from .gamer_protocol import parse_gamer_action


@dataclass(frozen=True)
class FrameProof:
    """Local observer facts, never sourced from VLM JSON or a model tool call."""

    session_id: str
    sequence: int
    captured_at_ms: int
    scene_id: str
    game_focused: bool
    display_verified: bool
    verified_controls: frozenset[str] = field(default_factory=frozenset)
    verified_events: frozenset[str] = field(default_factory=frozenset)


@dataclass(frozen=True)
class LocalBinding:
    """A trusted on-device mapping; NOT a model-selected click coordinate."""

    canonical_gamer_wire: str
    session_id: str
    sequence: int
    scene_id: str
    kind: str
    verified_control_id: str
    expected_event_id: str
    high_impact: bool = False
    user_confirmed_high_impact: bool = False


@dataclass(frozen=True)
class StepTicket:
    """Opaque one-use handoff to a future trusted local executor, NOT to the AI."""

    nonce: str
    kind: str
    control_id: str
    expires_at_ms: int


@dataclass(frozen=True)
class StepDecision:
    permitted: bool
    reason: str
    ticket: StepTicket | None = None


class GamerStepCoordinator:
    """One proposed Gamer chunk -> one locally bound action -> one new observation.

    No raw Gamer keys, mouse deltas, shell commands or coordinates reach a device.
    The coordinator never sends input. The local observer must supply the
    binding and both pre/post frame proofs independently of the model.
    """

    def __init__(
        self,
        handoff: AgentHandoff,
        *,
        capture_session_id: str,
        max_pending_ms: int = 3_000,
    ):
        if not capture_session_id or max_pending_ms < 1:
            raise ValueError("invalid capture session or verification timeout")
        self.handoff = handoff
        self.capture_session_id = capture_session_id
        self.max_pending_ms = max_pending_ms
        self._pending: StepTicket | None = None
        self._source: FrameProof | None = None
        self._expected_event_id: str | None = None
        self._last_accepted_sequence = 0
        self._halted = False

    def preflight(
        self,
        gamer_message: str,
        *,
        local_binding: LocalBinding | None,
        frame: FrameProof,
        lease: AgentLease,
        now_ms: int,
    ) -> StepDecision:
        if self._halted:
            return StepDecision(False, "cycle_halted")
        if self._pending is not None:
            return StepDecision(False, "previous_step_unverified")
        parsed = parse_gamer_action(gamer_message)
        if parsed.chunk is None:
            return StepDecision(False, "invalid_gamer_action:" + parsed.reason)
        if parsed.chunk.is_noop:
            # A no-op does not require or authorize a device input transaction.
            return StepDecision(False, "noop_no_input")
        if local_binding is None:
            return StepDecision(False, "no_trusted_local_mapping")
        if frame.session_id != self.capture_session_id or not frame.session_id:
            return StepDecision(False, "capture_session_mismatch")
        if frame.sequence <= self._last_accepted_sequence or frame.sequence < 1:
            return StepDecision(False, "replayed_frame")
        if not frame.display_verified or not frame.game_focused:
            return StepDecision(False, "unverified_game_display")
        if frame.captured_at_ms < 1 or frame.captured_at_ms > now_ms:
            return StepDecision(False, "invalid_frame_clock")
        if not frame.scene_id:
            return StepDecision(False, "unknown_scene")
        if (
            local_binding.canonical_gamer_wire != parsed.chunk.to_wire()
            or local_binding.session_id != frame.session_id
            or local_binding.sequence != frame.sequence
            or local_binding.scene_id != frame.scene_id
        ):
            return StepDecision(False, "binding_does_not_match_frame_and_intent")
        if not local_binding.verified_control_id or (
            local_binding.verified_control_id not in frame.verified_controls
        ):
            return StepDecision(False, "control_not_verified_in_frame")
        if not local_binding.expected_event_id:
            return StepDecision(False, "postcondition_not_defined")

        permission = self.handoff.check_action(
            lease,
            kind=local_binding.kind,
            target_verified_locally=True,
            game_focused=frame.game_focused,
            last_frame_ms=frame.captured_at_ms,
            now_ms=now_ms,
            high_impact=local_binding.high_impact,
            user_confirmed_high_impact=local_binding.user_confirmed_high_impact,
        )
        if not permission.permitted:
            return StepDecision(False, permission.reason)
        ticket = StepTicket(
            nonce=secrets.token_urlsafe(20),
            kind=local_binding.kind,
            control_id=local_binding.verified_control_id,
            expires_at_ms=min(now_ms + self.max_pending_ms, lease.expires_at_ms),
        )
        self._pending = ticket
        self._source = frame
        self._expected_event_id = local_binding.expected_event_id
        self._last_accepted_sequence = frame.sequence
        return StepDecision(True, "local_step_preflight_only", ticket)

    def verify_step(
        self, ticket: StepTicket, *, next_frame: FrameProof, now_ms: int,
    ) -> StepDecision:
        if self._halted:
            return StepDecision(False, "cycle_halted")
        if self._pending is None or ticket != self._pending:
            return StepDecision(False, "invalid_or_replayed_step_ticket")
        source = self._source
        expected = self._expected_event_id
        if (
            source is None
            or expected is None
            or now_ms < source.captured_at_ms
            or now_ms > ticket.expires_at_ms
            or next_frame.session_id != self.capture_session_id
            or next_frame.sequence <= source.sequence
            or next_frame.captured_at_ms <= source.captured_at_ms
            or next_frame.captured_at_ms > now_ms
            or now_ms - next_frame.captured_at_ms > self.handoff.max_frame_age_ms
            or not next_frame.game_focused
            or not next_frame.display_verified
            or expected not in next_frame.verified_events
        ):
            # Failure requires a fresh user-authorized task. No silent retries.
            self.stop()
            return StepDecision(False, "postcondition_not_verified_stopped")
        self._pending = None
        self._source = None
        self._expected_event_id = None
        self._last_accepted_sequence = next_frame.sequence
        return StepDecision(True, "postcondition_verified")

    def stop(self) -> None:
        self._pending = None
        self._source = None
        self._expected_event_id = None
        self._halted = True
        self.handoff.stop()

    @property
    def awaiting_verification(self) -> bool:
        return self._pending is not None

    @property
    def halted(self) -> bool:
        return self._halted
