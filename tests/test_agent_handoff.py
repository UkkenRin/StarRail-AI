"""Simulation tests: scripts and AI must never hold game input simultaneously."""
import unittest

from starrail_core.agent_handoff import (
    AgentHandoff, ControlPhase, HandoffError,
)


class HandoffTests(unittest.TestCase):
    def setUp(self):
        self.control = AgentHandoff(max_frame_age_ms=2000, lease_ms=10000)

    def start_and_pause(self):
        self.control.start_script(user_authorized=True)
        self.control.report_failure("M7A failed to identify the new currency-wars pop-up")
        self.assertFalse(self.control.script_may_send_input)
        self.control.acknowledge_script_released(input_stopped=True)

    def grant(self, now_ms=1000):
        self.start_and_pause()
        return self.control.grant_agent_control(
            user_authorized=True, game_focused=True,
            last_frame_ms=now_ms, now_ms=now_ms,
        )

    def action(self, lease, **changes):
        data = dict(kind="select_verified_control", target_verified_locally=True,
                    game_focused=True, last_frame_ms=1000, now_ms=1000)
        data.update(changes)
        return self.control.check_action(lease, **data)

    def test_only_user_authorized_tasks_can_start(self):
        with self.assertRaises(HandoffError):
            self.control.start_script(user_authorized=False)
        self.assertEqual(self.control.phase, ControlPhase.IDLE)
        self.control.start_script(user_authorized=True)
        self.assertTrue(self.control.script_may_send_input)
        with self.assertRaises(HandoffError):
            self.control.start_script(user_authorized=True)

    def test_no_ai_control_during_script_pause_request(self):
        self.control.start_script(user_authorized=True)
        self.control.report_failure("unexpected UI")
        self.assertFalse(self.control.script_may_send_input)
        with self.assertRaises(HandoffError):
            self.control.grant_agent_control(
                user_authorized=True, game_focused=True,
                last_frame_ms=1000, now_ms=1000)
        with self.assertRaises(HandoffError):
            self.control.acknowledge_script_released(input_stopped=False)
        self.control.acknowledge_script_released(input_stopped=True)
        self.assertEqual(self.control.phase, ControlPhase.SCRIPT_PAUSED)

    def test_fresh_focused_consent_required(self):
        self.start_and_pause()
        for overrides in (
            {"user_authorized": False},
            {"game_focused": False},
            {"now_ms": 5000},
            {"last_frame_ms": 1001},
        ):
            args = dict(user_authorized=True, game_focused=True,
                        last_frame_ms=1000, now_ms=1000)
            args.update(overrides)
            with self.assertRaises(HandoffError):
                self.control.grant_agent_control(**args)
        self.assertEqual(self.control.phase, ControlPhase.SCRIPT_PAUSED)
        lease = self.control.grant_agent_control(
            user_authorized=True, game_focused=True,
            last_frame_ms=1000, now_ms=1000)
        self.assertFalse(self.control.script_may_send_input)
        self.assertTrue(self.control.agent_may_request_input)
        self.assertTrue(self.action(lease).permitted)

    def test_denies_untrusted_target_risky_action_and_stale_image(self):
        lease = self.grant()
        self.assertEqual(self.action(lease, target_verified_locally=False).reason,
                         "target_not_verified")
        self.assertEqual(self.action(lease, kind="shell_command").reason,
                         "unapproved_action_kind")
        self.assertEqual(self.action(lease, game_focused=False).reason,
                         "game_not_focused")
        self.assertEqual(self.action(lease, now_ms=4000).reason,
                         "stale_game_frame")
        self.assertEqual(self.action(lease, high_impact=True).reason,
                         "high_impact_requires_confirmation")
        self.assertTrue(self.action(lease, high_impact=True,
                                    user_confirmed_high_impact=True).permitted)
        self.assertTrue(self.action(lease, kind="wait").permitted)

    def test_agent_lease_expires_and_new_lease_is_unrelated(self):
        lease = self.grant()
        self.assertTrue(self.action(lease, now_ms=11000, last_frame_ms=11000).permitted)
        self.assertEqual(self.action(lease, now_ms=11001, last_frame_ms=11001).reason,
                         "lease_expired")
        self.control.finish_recovery(state_verified=False, script_can_resume=False)
        self.assertEqual(self.control.phase, ControlPhase.SCRIPT_PAUSED)
        next_lease = self.control.grant_agent_control(
            user_authorized=True, game_focused=True,
            last_frame_ms=12000, now_ms=12000)
        self.assertNotEqual(lease, next_lease)
        self.assertFalse(self.action(lease).permitted)

    def test_resume_requires_verified_recovery(self):
        lease = self.grant()
        self.control.finish_recovery(state_verified=False, script_can_resume=True)
        self.assertFalse(self.control.script_may_send_input)
        next_lease = self.control.grant_agent_control(
            user_authorized=True, game_focused=True,
            last_frame_ms=2000, now_ms=2000)
        self.assertEqual(self.control.finish_recovery(
            state_verified=True, script_can_resume=True), ControlPhase.SCRIPT_RUNNING)
        self.assertTrue(self.control.script_may_send_input)
        self.assertFalse(self.action(next_lease).permitted)
        self.assertFalse(self.action(lease).permitted)

    def test_agent_first_task_never_resumes_nonexistent_script(self):
        lease = self.control.start_agent_direct(
            user_authorized=True, game_focused=True,
            last_frame_ms=1000, now_ms=1000,
        )
        self.assertFalse(self.control.script_may_send_input)
        self.assertTrue(self.action(lease).permitted)
        self.assertEqual(
            self.control.finish_recovery(state_verified=True, script_can_resume=True),
            ControlPhase.IDLE,
        )
        self.assertFalse(self.control.script_may_send_input)
        with self.assertRaises(HandoffError):
            self.control.grant_agent_control(
                user_authorized=True, game_focused=True,
                last_frame_ms=1000, now_ms=1000,
            )
        second = self.control.start_agent_direct(
            user_authorized=True, game_focused=True,
            last_frame_ms=2000, now_ms=2000,
        )
        self.assertNotEqual(lease.token, second.token)
        self.assertEqual(self.control.finish_recovery(
            state_verified=False, script_can_resume=False), ControlPhase.STOPPED)
        self.assertFalse(self.control.agent_may_request_input)
        with self.assertRaises(HandoffError):
            self.control.start_agent_direct(
                user_authorized=True, game_focused=True,
                last_frame_ms=3000, now_ms=3000,
            )

    def test_direct_agent_requires_authorization_focus_and_fresh_frame(self):
        for overrides in (
            {"user_authorized": False}, {"game_focused": False},
            {"now_ms": 5000}, {"last_frame_ms": 1002},
        ):
            args = dict(user_authorized=True, game_focused=True,
                        last_frame_ms=1000, now_ms=1000)
            args.update(overrides)
            with self.assertRaises(HandoffError):
                self.control.start_agent_direct(**args)
        self.assertEqual(self.control.phase, ControlPhase.IDLE)

    def test_manual_stop_revokes_all_and_blocks_silent_restart(self):
        lease = self.grant()
        self.control.stop()
        self.assertEqual(self.control.phase, ControlPhase.STOPPED)
        self.assertFalse(self.control.script_may_send_input)
        self.assertFalse(self.control.agent_may_request_input)
        self.assertEqual(self.action(lease).reason, "not_current_agent_lease")
        with self.assertRaises(HandoffError):
            self.control.start_script(user_authorized=True)
        with self.assertRaises(HandoffError):
            self.control.grant_agent_control(
                user_authorized=True, game_focused=True,
                last_frame_ms=3000, now_ms=3000)

    def test_invalid_transitions_are_rejected(self):
        with self.assertRaises(HandoffError):
            self.control.report_failure("unexpected")
        with self.assertRaises(HandoffError):
            self.control.finish_recovery(state_verified=True, script_can_resume=True)
        with self.assertRaises(ValueError):
            AgentHandoff(lease_ms=0)
        self.control.start_script(user_authorized=True)
        with self.assertRaises(ValueError):
            self.control.report_failure("")
        self.assertEqual(self.control.phase, ControlPhase.SCRIPT_RUNNING)


if __name__ == "__main__":
    unittest.main()
