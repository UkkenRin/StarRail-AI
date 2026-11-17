"""Exclusive script-to-AI control handoff for StarRail-AI.

This module implements ONLY local handoff/permission bookkeeping. It does not
capture frames, run an LLM, launch M7A, tap or read the actual game. Real adapters
must prove script input has stopped, game focus, target safety and user consent.
Do NOT use the model's own assertion as proof of verified target or focus.
"""
from __future__ import annotations

from dataclasses import dataclass
from enum import Enum
import secrets


class ControlPhase(str, Enum):
    IDLE = "idle"
    SCRIPT_RUNNING = "script_running"
    SCRIPT_PAUSING = "script_pausing"
    SCRIPT_PAUSED = "script_paused"
    AGENT_ACTIVE = "agent_active"
    STOPPED = "stopped"


class HandoffError(RuntimeError):
    pass


@dataclass(frozen=True)
class AgentLease:
    """Unpredictable one-session capability, never sent to the AI model."""
    token: str
    expires_at_ms: int


@dataclass(frozen=True)
class ActionPermission:
    permitted: bool
    reason: str


class AgentHandoff:
    """Single-task, single-threaded coordinator; an OS mutex is still required.

    No autonomous fallback is allowed while the M7A script still owns input.
    A handoff needs a positive script-stop acknowledgement and a newly verified
    focused-game frame. Each proposed action needs its own local validation.
    """

    _ALLOWED_KINDS = frozenset({
        "wait", "open_game_menu", "select_verified_control",
        "drag_verified_control", "toggle_in_game_auto_battle",
    })

    def __init__(self, *, max_frame_age_ms: int = 2000, lease_ms: int = 30000):
        if max_frame_age_ms < 1 or lease_ms < 1:
            raise ValueError("invalid freshness or lease limits")
        self.max_frame_age_ms = max_frame_age_ms
        self.lease_ms = lease_ms
        self.phase = ControlPhase.IDLE
        self.failure_reason: str | None = None
        self._lease: AgentLease | None = None
        self._agent_origin_script = False

    def start_script(self, *, user_authorized: bool) -> None:
        """Only an explicit new user task can start the script."""
        if self.phase is not ControlPhase.IDLE or not user_authorized:
            raise HandoffError("new task needs authorization and an idle coordinator")
        self.phase = ControlPhase.SCRIPT_RUNNING

    def report_failure(self, reason: str) -> None:
        """Request release of M7A input; no agent action permission yet."""
        if self.phase is not ControlPhase.SCRIPT_RUNNING:
            raise HandoffError("no running script to hand off")
        if not reason or len(reason) > 300:
            raise ValueError("invalid failure reason")
        self.failure_reason = reason
        self.phase = ControlPhase.SCRIPT_PAUSING

    def acknowledge_script_released(self, *, input_stopped: bool) -> None:
        """An independent local executor must confirm that inputs are released."""
        if self.phase is not ControlPhase.SCRIPT_PAUSING or not input_stopped:
            raise HandoffError("script input not confirmed released")
        self.phase = ControlPhase.SCRIPT_PAUSED

    def _fresh(self, timestamp_ms: int, now_ms: int) -> bool:
        return 0 <= timestamp_ms <= now_ms and now_ms - timestamp_ms <= self.max_frame_age_ms

    def grant_agent_control(
        self, *, user_authorized: bool, game_focused: bool,
        last_frame_ms: int, now_ms: int,
    ) -> AgentLease:
        """Grant temporary permission to attempt locally verified game actions."""
        if self.phase is not ControlPhase.SCRIPT_PAUSED:
            raise HandoffError("script must be stopped first")
        return self._issue_lease(
            user_authorized=user_authorized, game_focused=game_focused,
            last_frame_ms=last_frame_ms, now_ms=now_ms, from_script=True,
        )

    def start_agent_direct(
        self, *, user_authorized: bool, game_focused: bool,
        last_frame_ms: int, now_ms: int,
    ) -> AgentLease:
        """An AI-first task need not launch an M7A script at all."""
        if self.phase is not ControlPhase.IDLE:
            raise HandoffError("direct AI task requires idle coordinator")
        return self._issue_lease(
            user_authorized=user_authorized, game_focused=game_focused,
            last_frame_ms=last_frame_ms, now_ms=now_ms, from_script=False,
        )

    def _issue_lease(
        self, *, user_authorized: bool, game_focused: bool,
        last_frame_ms: int, now_ms: int, from_script: bool,
    ) -> AgentLease:
        if not user_authorized or not game_focused or not self._fresh(last_frame_ms, now_ms):
            raise HandoffError("authorization, focused game or fresh frame missing")
        lease = AgentLease(secrets.token_urlsafe(24), now_ms + self.lease_ms)
        self._lease = lease
        self._agent_origin_script = from_script
        self.phase = ControlPhase.AGENT_ACTIVE
        return lease

    def check_action(
        self, lease: AgentLease, *, kind: str, target_verified_locally: bool,
        game_focused: bool, last_frame_ms: int, now_ms: int,
        high_impact: bool = False, user_confirmed_high_impact: bool = False,
    ) -> ActionPermission:
        """Fail closed. Inputs must be checked AGAIN by the actual executor."""
        if self.phase is not ControlPhase.AGENT_ACTIVE or self._lease != lease:
            return ActionPermission(False, "not_current_agent_lease")
        if now_ms > lease.expires_at_ms:
            return ActionPermission(False, "lease_expired")
        if kind not in self._ALLOWED_KINDS:
            return ActionPermission(False, "unapproved_action_kind")
        if not game_focused:
            return ActionPermission(False, "game_not_focused")
        if not self._fresh(last_frame_ms, now_ms):
            return ActionPermission(False, "stale_game_frame")
        if not target_verified_locally:
            return ActionPermission(False, "target_not_verified")
        if high_impact and not user_confirmed_high_impact:
            return ActionPermission(False, "high_impact_requires_confirmation")
        return ActionPermission(True, "permitted_for_local_executor")

    def finish_recovery(self, *, state_verified: bool, script_can_resume: bool) -> ControlPhase:
        """Return input to M7A only after checking its expected state."""
        if self.phase is not ControlPhase.AGENT_ACTIVE:
            raise HandoffError("AI has no control to relinquish")
        self._lease = None
        if self._agent_origin_script:
            if state_verified and script_can_resume:
                self.failure_reason = None
                self.phase = ControlPhase.SCRIPT_RUNNING
            else:
                self.phase = ControlPhase.SCRIPT_PAUSED
        else:
            # AI-first sessions never start M7A just because a model asked.
            self.phase = ControlPhase.IDLE if state_verified else ControlPhase.STOPPED
        self._agent_origin_script = False
        return self.phase

    def stop(self) -> None:
        """Human override/revocation; the same task may never silently restart."""
        self._lease = None
        self._agent_origin_script = False
        self.phase = ControlPhase.STOPPED

    @property
    def script_may_send_input(self) -> bool:
        return self.phase is ControlPhase.SCRIPT_RUNNING

    @property
    def agent_may_request_input(self) -> bool:
        return self.phase is ControlPhase.AGENT_ACTIVE
