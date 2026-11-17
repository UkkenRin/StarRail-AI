"""Gamer-inspired sparse reasoning offline tests; no API, screenshots or device."""
from dataclasses import replace
import unittest

from starrail_core.live_vision import (
    FrameSignal, LiveVisionGate, VisionBudget,
)
from starrail_core.sparse_thinking import (
    SparseThinkGate, ThinkDecision, ThinkEvent, ThinkRoute, ThinkSignal,
)


def signal(event=ThinkEvent.ROUTINE, *, tone=5, **kwargs):
    values = dict(
        session_id="test-session",
        goal_id="finish-daily",
        scene_id="map",
        fingerprint=bytes([tone] * 64),
        event=event,
        game_focused=True,
        frame_age_ms=100,
    )
    values.update(kwargs)
    return ThinkSignal(**values)


class SparseThinkingTests(unittest.TestCase):
    def setUp(self):
        self.gate = SparseThinkGate()

    def test_thousand_normal_frames_do_not_request_model_or_reasoning(self):
        for i in range(1000):
            candidate = self.gate.decide(signal(tone=i % 10))
            self.assertFalse(candidate.reason_candidate)
            self.assertEqual(candidate.route, ThinkRoute.KEEP_LOCAL)

    def test_m7a_is_optional_not_a_default_policy(self):
        # Existence of a script says nothing about scene/task suitability.
        known_only = self.gate.decide(signal(
            ThinkEvent.NEW_GOAL, mapped_m7a_skill=True))
        self.assertTrue(known_only.reason_candidate)

        verified_skill = dict(
            mapped_m7a_skill=True, m7a_skill_suitable_now=True,
        )
        selected = self.gate.decide(signal(ThinkEvent.NEW_GOAL, **verified_skill))
        self.assertEqual(selected.route, ThinkRoute.USE_M7A)
        self.assertEqual(selected.reason, "locally_verified_m7a_skill")
        self.assertEqual(self.gate.decide(signal(
            ThinkEvent.ROUTINE, **verified_skill)).route, ThinkRoute.USE_M7A)

        for extra in (
            {"prefer_native_agent": True},
            {"m7a_recent_failure": True},
            {"m7a_skill_suitable_now": False},
        ):
            with self.subTest(extra=extra):
                flags = {**verified_skill, **extra}
                choice = self.gate.decide(signal(ThinkEvent.NEW_GOAL, **flags))
                self.assertTrue(choice.reason_candidate)

        # Running means the skill still owns input, not that the agent selected
        # M7A over native control when starting this task.
        running = self.gate.decide(
            signal(ThinkEvent.NEW_GOAL, m7a_running=True)
        )
        self.assertEqual(running.route, ThinkRoute.KEEP_LOCAL)

    def test_locally_verified_cached_plan_is_reused_without_new_model_call(self):
        x = self.gate.decide(signal(ThinkEvent.NEW_GOAL, verified_cached_plan=True))
        self.assertEqual(x.route, ThinkRoute.USE_VERIFIED_PLAN)
        self.assertEqual(
            self.gate.decide(signal(
                ThinkEvent.NEW_GOAL, verified_cached_plan=True,
                mapped_m7a_skill=True, m7a_skill_suitable_now=True,
            )).route,
            ThinkRoute.USE_VERIFIED_PLAN,
        )
        # Unexpected UI invalidates easy reuse even when there is a cached plan.
        y = self.gate.decide(signal(ThinkEvent.UNEXPECTED, verified_cached_plan=True))
        self.assertTrue(y.reason_candidate)

    def test_key_events_trigger_one_reasoning_candidate_not_a_call(self):
        for event in (ThinkEvent.NEW_GOAL, ThinkEvent.UNKNOWN_SCENE,
                      ThinkEvent.UNEXPECTED, ThinkEvent.VERIFICATION_FAILED,
                      ThinkEvent.MANUAL):
            with self.subTest(event=event):
                x = self.gate.decide(signal(event))
                self.assertTrue(x.reason_candidate)
                self.assertEqual(x.reason, "new_reasoning_event")
        # Merely considering it does not count a model call.
        self.assertTrue(self.gate.decide(signal(ThinkEvent.UNKNOWN_SCENE)).reason_candidate)

    def test_m7a_script_failed_requires_confirmed_release_before_reasoning(self):
        blocked = self.gate.decide(
            signal(ThinkEvent.SCRIPT_FAILED, m7a_running=True)
        )
        self.assertEqual(blocked.route, ThinkRoute.PAUSE)
        blocked = self.gate.decide(signal(ThinkEvent.SCRIPT_FAILED))
        self.assertEqual(blocked.reason, "await_m7a_input_release")
        released = self.gate.decide(
            signal(ThinkEvent.SCRIPT_FAILED, m7a_input_released=True)
        )
        self.assertTrue(released.reason_candidate)
        self.assertEqual(self.gate.decide(signal(
            ThinkEvent.UNEXPECTED, m7a_running=True
        )).reason, "await_m7a_input_release")

    def test_local_focus_and_frame_staleness_block_reasoning(self):
        for bad in (
            signal(ThinkEvent.UNKNOWN_SCENE, game_focused=False),
            signal(ThinkEvent.UNKNOWN_SCENE, frame_age_ms=-1),
            signal(ThinkEvent.UNKNOWN_SCENE, frame_age_ms=2001),
        ):
            self.assertEqual(self.gate.decide(bad).route, ThinkRoute.PAUSE)

    def test_do_not_repeat_reasoning_without_new_visual_evidence(self):
        event = signal(ThinkEvent.UNKNOWN_SCENE, scene_id="")
        self.assertTrue(self.gate.decide(event).reason_candidate)
        self.gate.record_cloud_request_dispatched(event)
        self.assertEqual(
            self.gate.decide(event).reason, "same_evidence_already_sent"
        )
        self.assertEqual(
            self.gate.decide(replace(event, fingerprint=bytes([10] * 64))).reason,
            "same_evidence_already_sent"
        )
        changed = replace(event, fingerprint=bytes([90] * 64))
        self.assertTrue(self.gate.decide(changed).reason_candidate)
        self.gate.record_cloud_request_dispatched(changed)
        self.assertEqual(
            self.gate.decide(replace(changed, fingerprint=bytes([180] * 64))).reason,
            "context_reasoning_limit",
        )
        with self.assertRaises(ValueError):
            self.gate.record_cloud_request_dispatched(changed)

    def test_separate_user_goal_and_new_session_have_distinct_contexts(self):
        one = signal(ThinkEvent.MANUAL)
        self.gate.record_cloud_request_dispatched(one)
        self.assertFalse(self.gate.decide(one).reason_candidate)
        self.assertTrue(self.gate.decide(replace(one, goal_id="new-goal")).reason_candidate)
        self.assertTrue(self.gate.decide(replace(one, session_id="new-session")).reason_candidate)
        self.gate.reset_for_new_user_task()
        self.assertTrue(self.gate.decide(one).reason_candidate)

    def test_cloud_reasoning_also_needs_the_existing_vision_budget_gate(self):
        sparse = signal(ThinkEvent.UNEXPECTED)
        decision = self.gate.decide(sparse)
        self.assertTrue(decision.reason_candidate)
        frame = FrameSignal(
            timestamp_ms=1000, fingerprint=sparse.fingerprint,
            focused_game=True, event="unexpected",
        )
        default_budget = LiveVisionGate()  # cloud OFF by default
        refused = default_budget.evaluate(frame, now_ms=1000)
        self.assertFalse(refused.send_to_model)
        self.assertEqual(refused.reason, "no_explicit_cloud_consent")
        # No real model request was dispatched, so the sparse trigger remains.
        self.assertTrue(self.gate.decide(sparse).reason_candidate)

        authorized = LiveVisionGate(VisionBudget(
            cloud_enabled=True, max_requests_per_task=1,
        ))
        allowed = authorized.evaluate(frame, now_ms=1000)
        self.assertTrue(allowed.send_to_model)
        self.gate.record_cloud_request_dispatched(sparse)
        self.assertFalse(self.gate.decide(sparse).reason_candidate)
        self.assertFalse(authorized.evaluate(replace(
            frame, timestamp_ms=11000, fingerprint=bytes([80] * 64),
        ), now_ms=11000).send_to_model)

    def test_invalid_signals_and_config_rejected(self):
        for sample in (
            signal(fingerprint=b"wrong"),
            signal(goal_id=""),
            signal(session_id=""),
            signal(event="freeform-untrusted"),
        ):
            with self.assertRaises(ValueError):
                self.gate.decide(sample)
        with self.assertRaises(ValueError):
            SparseThinkGate(max_requests_per_context=0)


if __name__ == "__main__":
    unittest.main()
