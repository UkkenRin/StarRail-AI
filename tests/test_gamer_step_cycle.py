"""Offline guard tests: no game, GPU, model, network, touch or keyboard."""
from dataclasses import replace
import unittest

from starrail_core.agent_handoff import AgentHandoff, ControlPhase
from starrail_core.gamer_protocol import parse_gamer_action
from starrail_core.gamer_step_cycle import (
    FrameProof, GamerStepCoordinator, LocalBinding, StepTicket,
)


GAMER_MENU_INTENT = "<|action_start|>0 0 0 ; Esc ; ; ; ; ;<|action_end|>"
GAMER_WALK_INTENT = "<|action_start|>0 0 0 ; W ; W ; W ; W ; W ; W<|action_end|>"
GAMER_NOOP = "<|action_start|>0 0 0 ; ; ; ; ; ;<|action_end|>"


def observed(seq=1, ts=1000, *, events=(), controls=("menu",), scene="overworld"):
    return FrameProof(
        session_id="capture-1", sequence=seq, captured_at_ms=ts, scene_id=scene,
        game_focused=True, display_verified=True,
        verified_controls=frozenset(controls),
        verified_events=frozenset(events),
    )


def local_binding(message=GAMER_MENU_INTENT, *, seq=1, kind="open_game_menu",
                  control="menu", event="menu_opened"):
    return LocalBinding(
        canonical_gamer_wire=parse_gamer_action(message).chunk.to_wire(),
        session_id="capture-1", sequence=seq, scene_id="overworld",
        kind=kind, verified_control_id=control, expected_event_id=event,
    )


class GamerStepCycleTests(unittest.TestCase):
    def setUp(self):
        self.handoff = AgentHandoff(lease_ms=20_000)
        self.lease = self.handoff.start_agent_direct(
            user_authorized=True, game_focused=True,
            last_frame_ms=1000, now_ms=1000,
        )
        self.gate = GamerStepCoordinator(
            self.handoff, capture_session_id="capture-1", max_pending_ms=3_000,
        )

    def prepare(self, message=GAMER_MENU_INTENT, *, mapping=None, frame=None, now=1000):
        return self.gate.preflight(
            message,
            local_binding=local_binding() if mapping is None else mapping,
            frame=observed() if frame is None else frame,
            lease=self.lease, now_ms=now,
        )

    def test_valid_local_mapping_is_one_step_only_and_requires_new_frame(self):
        first = self.prepare()
        self.assertTrue(first.permitted)
        self.assertIsNotNone(first.ticket)
        self.assertEqual(first.ticket.kind, "open_game_menu")
        self.assertEqual(first.ticket.control_id, "menu")
        self.assertFalse(hasattr(first.ticket, "gamer_wire"))
        self.assertFalse(self.prepare().permitted)
        self.assertEqual(self.prepare().reason, "previous_step_unverified")

        outcome = self.gate.verify_step(
            first.ticket, next_frame=observed(2, 1200, events=("menu_opened",)),
            now_ms=1200,
        )
        self.assertTrue(outcome.permitted)
        self.assertFalse(self.gate.awaiting_verification)
        self.assertEqual(self.gate.verify_step(
            first.ticket, next_frame=observed(3, 1300, events=("menu_opened",)),
            now_ms=1300,
        ).reason, "invalid_or_replayed_step_ticket")
        self.assertEqual(self.prepare(frame=observed(1, 1300)).reason, "replayed_frame")

        second = self.prepare(
            frame=observed(3, 1500),
            mapping=local_binding(seq=3),
            now=1500,
        )
        self.assertTrue(second.permitted)
        self.assertNotEqual(first.ticket.nonce, second.ticket.nonce)

    def test_raw_gamer_intent_is_never_automatically_a_touch_target(self):
        no_mapping = self.gate.preflight(
            GAMER_WALK_INTENT, local_binding=None, frame=observed(),
            lease=self.lease, now_ms=1000,
        )
        self.assertEqual(no_mapping.reason, "no_trusted_local_mapping")
        self.assertFalse(no_mapping.permitted)
        self.assertFalse(self.gate.awaiting_verification)

    def test_noop_and_invalid_wire_never_authorize_game_input(self):
        for message, reason in (
            (GAMER_NOOP, "noop_no_input"),
            ("bad action", "invalid_gamer_action:missing_or_multiple_action_envelopes"),
        ):
            with self.subTest(message=message):
                x = self.prepare(message)
                self.assertEqual(x.reason, reason)
                self.assertIsNone(x.ticket)

    def test_binding_must_match_exact_canonical_intent_scene_frame_and_session(self):
        for binding in (
            replace(local_binding(), canonical_gamer_wire=GAMER_WALK_INTENT),
            replace(local_binding(), session_id="other"),
            replace(local_binding(), sequence=2),
            replace(local_binding(), scene_id="shop"),
        ):
            with self.subTest(binding=binding):
                self.assertEqual(self.prepare(mapping=binding).reason,
                                 "binding_does_not_match_frame_and_intent")

    def test_target_and_expected_postcondition_must_be_locally_verifiable(self):
        self.assertEqual(self.prepare(mapping=local_binding(control="unknown")).reason,
                         "control_not_verified_in_frame")
        self.assertEqual(self.prepare(mapping=local_binding(event="")).reason,
                         "postcondition_not_defined")
        self.assertEqual(self.prepare(frame=replace(observed(), scene_id="")).reason,
                         "unknown_scene")
        self.assertEqual(self.prepare(frame=replace(observed(), game_focused=False)).reason,
                         "unverified_game_display")
        self.assertEqual(self.prepare(frame=replace(observed(), display_verified=False)).reason,
                         "unverified_game_display")
        self.assertEqual(self.prepare(frame=replace(observed(), session_id="next-capture")).reason,
                         "capture_session_mismatch")
        self.assertEqual(self.prepare(frame=replace(observed(), sequence=0)).reason,
                         "replayed_frame")

    def test_stale_frame_and_input_lease_are_checked_by_handoff(self):
        self.assertEqual(self.prepare(now=4000).reason, "stale_game_frame")
        self.assertEqual(self.prepare(now=22_000).reason, "lease_expired")
        self.handoff.stop()
        self.assertEqual(self.prepare().reason, "not_current_agent_lease")

    def test_unsupported_action_kind_and_dangerous_action_need_confirmation(self):
        self.assertEqual(self.prepare(
            mapping=local_binding(kind="shell")).reason, "unapproved_action_kind")
        high_impact = replace(local_binding(kind="select_verified_control"),
                              high_impact=True)
        self.assertEqual(self.prepare(mapping=high_impact).reason,
                         "high_impact_requires_confirmation")
        self.assertTrue(self.prepare(mapping=replace(
            high_impact, user_confirmed_high_impact=True)).permitted)

    def test_action_never_bypasses_m7a_input_owner(self):
        other = AgentHandoff()
        other.start_script(user_authorized=True)
        blocked = GamerStepCoordinator(other, capture_session_id="capture-1")
        decision = blocked.preflight(
            GAMER_MENU_INTENT, local_binding=local_binding(),
            frame=observed(), lease=self.lease, now_ms=1000,
        )
        self.assertFalse(decision.permitted)
        self.assertEqual(decision.reason, "not_current_agent_lease")

    def test_forged_ticket_does_not_acknowledge_action(self):
        request = self.prepare()
        forged = StepTicket("made-up", request.ticket.kind, request.ticket.control_id,
                            request.ticket.expires_at_ms)
        self.assertEqual(self.gate.verify_step(
            forged, next_frame=observed(2, 1100, events=("menu_opened",)), now_ms=1100,
        ).reason, "invalid_or_replayed_step_ticket")
        self.assertTrue(self.gate.awaiting_verification)
        self.assertTrue(self.gate.verify_step(
            request.ticket, next_frame=observed(2, 1100, events=("menu_opened",)), now_ms=1100,
        ).permitted)

    def test_missing_postcondition_stops_and_revokes_lease(self):
        ticket = self.prepare().ticket
        outcome = self.gate.verify_step(ticket, next_frame=observed(2, 1100),
                                        now_ms=1100)
        self.assertEqual(outcome.reason, "postcondition_not_verified_stopped")
        self.assertTrue(self.gate.halted)
        self.assertEqual(self.handoff.phase, ControlPhase.STOPPED)
        self.assertEqual(self.prepare().reason, "cycle_halted")

    def test_old_frame_wrong_capture_lost_focus_and_timeout_stop(self):
        updates = [
            (observed(1, 1100, events=("menu_opened",)), 1100),
            (observed(2, 999, events=("menu_opened",)), 1100),
            (replace(observed(2, 1100, events=("menu_opened",)), session_id="other"), 1100),
            (replace(observed(2, 1100, events=("menu_opened",)), game_focused=False), 1100),
            (replace(observed(2, 1100, events=("menu_opened",)), display_verified=False), 1100),
            (observed(2, 1100, events=("menu_opened",)), 4001),
        ]
        for next_frame, now in updates:
            with self.subTest(next_frame=next_frame, now=now):
                other = AgentHandoff(lease_ms=20_000)
                lease = other.start_agent_direct(
                    user_authorized=True, game_focused=True,
                    last_frame_ms=1000, now_ms=1000,
                )
                gate = GamerStepCoordinator(other, capture_session_id="capture-1")
                pending = gate.preflight(
                    GAMER_MENU_INTENT, local_binding=local_binding(),
                    frame=observed(), lease=lease, now_ms=1000,
                )
                self.assertTrue(pending.permitted)
                self.assertFalse(gate.verify_step(
                    pending.ticket, next_frame=next_frame, now_ms=now,
                ).permitted)
                self.assertTrue(gate.halted)
                self.assertEqual(other.phase, ControlPhase.STOPPED)

    def test_manual_stop_revokes_pending_ticket(self):
        pending = self.prepare()
        self.gate.stop()
        self.assertEqual(self.handoff.phase, ControlPhase.STOPPED)
        self.assertFalse(self.gate.awaiting_verification)
        self.assertEqual(self.gate.verify_step(
            pending.ticket, next_frame=observed(2, 1200, events=("menu_opened",)),
            now_ms=1200,
        ).reason, "cycle_halted")


if __name__ == "__main__":
    unittest.main()
