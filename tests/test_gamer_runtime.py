"""True integration tests with fake OS ports: no cloud/network/device required."""
import unittest

from starrail_core.agent_handoff import AgentHandoff
from starrail_core.gamer_runtime import GamerAgentRuntime, ObservedFrame
from starrail_core.gamer_step_cycle import FrameProof, GamerStepCoordinator, LocalBinding
from starrail_core.live_vision import LiveVisionGate, VisionBudget
from starrail_core.sparse_thinking import SparseThinkGate, ThinkEvent
from starrail_core.verified_memory import VerifiedGameMemory

WIRE = "<think></think><|action_start|>0 0 0 ; LB ; ; ; ; ; <|action_end|>"
SIGNATURE = bytes([11] * 64)


def proof(seq=1, *, time=1000, scene="map", post=False, focused=True):
    return FrameProof(
        session_id="local-session", sequence=seq, captured_at_ms=time,
        scene_id=scene, game_focused=focused, display_verified=True,
        verified_controls=frozenset({"safe-menu-button"}),
        verified_events=frozenset({"menu_opened"} if post else {}),
    )


def observe(seq=1, *, time=1000, scene="map", event=ThinkEvent.NEW_GOAL,
            fingerprint=SIGNATURE, focused=True):
    return ObservedFrame(proof(seq, time=time, scene=scene, focused=focused),
                         fingerprint, object(), event)


class FakeModel:
    def __init__(self, reply=WIRE):
        self.reply = reply
        self.calls = 0
        self.history = []

    def propose(self, image, *, history_summary):
        self.calls += 1
        self.history.append(history_summary)
        return self.reply


class FakeGamePort:
    def __init__(self):
        self.bind_calls = 0
        self.dispatch_calls = 0
        self.allow_binding = True
        self.dispatch_ok = True
        self.provide_event = True
        self.next_seq = 2
        self.next_time = 1200
        self.now = 1250

    def resolve(self, frame, canonical_wire):
        self.bind_calls += 1
        if not self.allow_binding:
            return None
        return LocalBinding(
            canonical_gamer_wire=canonical_wire, session_id=frame.session_id,
            sequence=frame.sequence, scene_id=frame.scene_id,
            kind="select_verified_control",
            verified_control_id="safe-menu-button",
            expected_event_id="menu_opened", high_impact=False,
        )

    def dispatch(self, ticket, source_frame, *, now_ms):
        self.dispatch_calls += 1
        return self.dispatch_ok

    def observe_after(self):
        return proof(self.next_seq, time=self.next_time,
                     scene="menu", post=self.provide_event)

    def monotonic_ms(self):
        return self.now


class IntegratedLoopTests(unittest.TestCase):
    def setUp(self):
        self.handoff = AgentHandoff()
        self.lease = self.handoff.start_agent_direct(
            user_authorized=True, game_focused=True,
            last_frame_ms=1000, now_ms=1100)
        self.coordinator = GamerStepCoordinator(
            self.handoff, capture_session_id="local-session")
        self.model = FakeModel()
        self.port = FakeGamePort()
        self.memory = VerifiedGameMemory()
        self.runtime = GamerAgentRuntime(
            capture_session_id="local-session", game_version="v1",
            coordinator=self.coordinator, lease=self.lease,
            vision_gate=LiveVisionGate(VisionBudget(
                cloud_enabled=True, max_requests_per_task=2,
                minimum_interval_ms=0,
            )), think_gate=SparseThinkGate(), memory=self.memory,
            model=self.model, port=self.port)

    def run_step(self, frame=None, *, now_ms=1100, **kwargs):
        return self.runtime.run_one(
            frame or observe(), now_ms=now_ms, goal_id="open-menu",
            cloud_frame_authorized=True, **kwargs)

    def test_first_real_fake_step_calls_model_dispatches_and_verifies(self):
        outcome = self.run_step()
        self.assertEqual(outcome.stage, "verified")
        self.assertEqual(outcome.cloud_requests_used, 1)
        self.assertEqual(outcome.verified_actions, 1)
        self.assertEqual(self.model.calls, 1)
        self.assertEqual(self.port.dispatch_calls, 1)
        self.assertIn("menu_opened", self.memory.context_summary())
        self.assertNotIn("LB", self.memory.context_summary())

    def test_verified_skill_reused_without_second_model_call(self):
        self.assertEqual(self.run_step().stage, "verified")
        self.port.next_seq = 4
        self.port.next_time = 1550
        self.port.now = 1600
        again = self.run_step(
            observe(3, time=1400), now_ms=1450)
        self.assertEqual(again.stage, "verified")
        self.assertEqual(again.reason, "verified_cache_postcondition_verified")
        self.assertEqual(self.model.calls, 1)
        self.assertEqual(self.port.dispatch_calls, 2)
        self.assertEqual(again.verified_actions, 2)
        self.assertIsNone(self.memory.find_verified_action(
            goal_id="open-menu", game_version="game-updated",
            scene_id="map", fingerprint=SIGNATURE))

    def test_normal_frames_need_no_model_or_device_actions(self):
        for _ in range(1000):
            out = self.run_step(observe(event=ThinkEvent.ROUTINE))
            self.assertEqual(out.stage, "local")
        self.assertEqual(self.model.calls, 0)
        self.assertEqual(self.port.dispatch_calls, 0)
        self.assertEqual(self.memory.verified_actions, 0)

    def test_known_m7a_is_optional_candidate_not_implicit_launch(self):
        out = self.run_step(mapped_m7a_skill=True, m7a_skill_suitable_now=True)
        self.assertEqual(out.stage, "m7a_candidate")
        self.assertEqual(self.model.calls, 0)
        self.assertEqual(self.port.dispatch_calls, 0)
        # User prefers AI-native route, never override.
        self.assertEqual(self.run_step(
            mapped_m7a_skill=True, m7a_skill_suitable_now=True,
            prefer_native_agent=True).stage, "verified")
        self.assertEqual(self.model.calls, 1)

    def test_no_cloud_permission_is_no_call_and_no_action(self):
        out = self.runtime.run_one(
            observe(), now_ms=1100, goal_id="open-menu",
            cloud_frame_authorized=False)
        self.assertEqual(out.stage, "local")
        self.assertEqual(out.reason, "cloud_frame_not_authorized")
        self.assertEqual(self.model.calls, 0)

    def test_failed_postcondition_fails_closed_and_does_not_cache(self):
        self.port.provide_event = False
        out = self.run_step()
        self.assertEqual(out.stage, "stopped")
        self.assertEqual(out.reason, "postcondition_not_verified_stopped")
        self.assertEqual(self.memory.verified_actions, 0)
        self.assertEqual(self.port.dispatch_calls, 1)
        self.assertEqual(self.run_step().reason, "runtime_stopped")

    def test_missing_mapping_never_dispatches_and_never_caches(self):
        self.port.allow_binding = False
        out = self.run_step()
        self.assertEqual(out.stage, "stopped")
        self.assertIn("no_trusted_local_mapping", out.reason)
        self.assertEqual(self.port.dispatch_calls, 0)
        self.assertEqual(self.memory.verified_actions, 0)
        # Identical visual evidence must not lead to repeated paid requests.
        out = self.run_step()
        self.assertEqual(out.stage, "local")
        self.assertEqual(out.reason, "same_evidence_already_sent")
        self.assertEqual(self.model.calls, 1)

    def test_unknown_scene_may_be_analyzed_but_not_touched(self):
        out = self.run_step(observe(scene="", event=ThinkEvent.UNKNOWN_SCENE))
        self.assertEqual(out.stage, "stopped")
        self.assertIn("unknown_scene", out.reason)
        self.assertEqual(self.port.dispatch_calls, 0)

    def test_stale_or_wrong_session_never_uploads(self):
        self.assertEqual(
            self.run_step(observe(time=1), now_ms=5000).reason,
            "untrusted_frame",
        )
        self.assertEqual(self.model.calls, 0)

    def test_invalid_model_action_never_reaches_executor(self):
        self.model.reply = "some text pretending to be a click"
        out = self.run_step()
        self.assertEqual(out.stage, "stopped")
        self.assertIn("invalid_action", out.reason)
        self.assertEqual(self.port.dispatch_calls, 0)


class VerifiedMemoryTests(unittest.TestCase):
    def test_only_verified_records_are_reused_and_summary_bounded(self):
        memory = VerifiedGameMemory(max_recent=2)
        self.assertIsNone(memory.find_verified_action(
            goal_id="g", game_version="v", scene_id="s",
            fingerprint=SIGNATURE))
        for i in range(5):
            memory.remember_success(
                goal_id="g", game_version="v", before_scene=f"s{i}",
                after_scene="next", action_wire=WIRE, fingerprint=SIGNATURE,
                observed_event="arrived", verified_at_ms=i)
        self.assertEqual(memory.verified_actions, 2)
        self.assertLessEqual(len(memory.context_summary(max_chars=100)), 100)
        self.assertIsNone(memory.find_verified_action(
            goal_id="g", game_version="v", scene_id="s4",
            fingerprint=bytes([30] * 64)))
        self.assertEqual(memory.find_verified_action(
            goal_id="g", game_version="v", scene_id="s4",
            fingerprint=SIGNATURE), WIRE)
        memory.clear_on_game_version_change()
        self.assertEqual(memory.verified_actions, 0)
        self.assertIsNone(memory.find_verified_action(
            goal_id="g", game_version="v", scene_id="s4",
            fingerprint=SIGNATURE))


if __name__ == "__main__":
    unittest.main()
