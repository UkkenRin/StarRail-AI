"""Local frame streaming budget tests; no API, no screenshots and no game runtime."""
import unittest
from starrail_core.live_vision import FrameSignal, LiveVisionGate, VisionBudget, fingerprint_distance

BLACK = bytes(64)
WHITE = bytes([255] * 64)


def frame(ts=1000, event="scene_change", fingerprint=BLACK, focused=True):
    return FrameSignal(ts, fingerprint, focused, event)


class LiveVisionGateTests(unittest.TestCase):
    def test_default_no_cloud_even_on_new_scene(self):
        gate = LiveVisionGate()
        for n in range(1000):
            decision = gate.evaluate(frame(1000 + n, "scene_change"), now_ms=1000 + n)
            self.assertFalse(decision.send_to_model)
        self.assertEqual(gate.requests_used, 0)

    def test_unchanged_video_never_uploads_without_event(self):
        gate = LiveVisionGate(VisionBudget(cloud_enabled=True))
        for n in range(1000):
            decision = gate.evaluate(frame(1000 + 1000*n, "none"), now_ms=1000 + 1000*n)
            self.assertFalse(decision.send_to_model)
        self.assertEqual(gate.requests_used, 0)

    def test_scene_changes_are_deduplicated_and_rate_limited(self):
        gate = LiveVisionGate(VisionBudget(cloud_enabled=True, max_requests_per_task=2))
        self.assertTrue(gate.evaluate(frame(), now_ms=1000).send_to_model)
        self.assertEqual(gate.evaluate(frame(2000), now_ms=2000).reason, "cooldown")
        self.assertEqual(gate.evaluate(frame(11000), now_ms=11000).reason, "near_duplicate")
        self.assertTrue(gate.evaluate(frame(12000, fingerprint=WHITE), now_ms=12000).send_to_model)
        self.assertEqual(gate.evaluate(frame(24000, fingerprint=BLACK), now_ms=24000).reason,
                         "task_budget_exhausted")
        self.assertEqual(gate.requests_used, 2)

    def test_event_can_use_same_image_but_respects_budget(self):
        gate = LiveVisionGate(VisionBudget(cloud_enabled=True, max_requests_per_task=2,
                                           minimum_interval_ms=0))
        self.assertTrue(gate.evaluate(frame(1000, "battle_turn"), now_ms=1000).send_to_model)
        self.assertTrue(gate.evaluate(frame(2000, "manual"), now_ms=2000).send_to_model)
        self.assertEqual(gate.evaluate(frame(3000, "manual"), now_ms=3000).reason,
                         "task_budget_exhausted")

    def test_stale_and_unfocused_frames_never_allowed(self):
        gate = LiveVisionGate(VisionBudget(cloud_enabled=True))
        self.assertEqual(gate.evaluate(frame(1000), now_ms=5000).reason, "stale_frame")
        self.assertEqual(gate.evaluate(frame(1000), now_ms=500).reason, "stale_frame")
        self.assertEqual(gate.evaluate(frame(1000, focused=False), now_ms=1000).reason,
                         "game_not_focused")
        self.assertEqual(gate.requests_used, 0)

    def test_reset_requires_explicit_new_task(self):
        gate = LiveVisionGate(VisionBudget(cloud_enabled=True, max_requests_per_task=1))
        self.assertTrue(gate.evaluate(frame(), now_ms=1000).send_to_model)
        self.assertFalse(gate.evaluate(frame(20000), now_ms=20000).send_to_model)
        gate.reset_for_new_user_task()
        self.assertTrue(gate.evaluate(frame(20000), now_ms=20000).send_to_model)

    def test_distance_and_invalid_config(self):
        self.assertEqual(fingerprint_distance(BLACK, BLACK), 0)
        self.assertEqual(fingerprint_distance(BLACK, WHITE), 255)
        with self.assertRaises(ValueError): fingerprint_distance(b"x", BLACK)
        with self.assertRaises(ValueError): VisionBudget(max_requests_per_task=-1)
        with self.assertRaises(ValueError): VisionBudget(scene_change_threshold=300)
        with self.assertRaises(ValueError):
            LiveVisionGate().evaluate(FrameSignal(1, b"x", True), now_ms=1)


if __name__ == "__main__":
    unittest.main()
