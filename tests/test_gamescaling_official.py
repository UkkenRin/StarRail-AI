"""The GameScaling bridge is a read-only adapter. No GPU, PIL, requests or API."""
import sys
import types
import unittest
from unittest.mock import patch

from starrail_core.gamescaling_official import (
    ReadOnlyGameScalingPreview, create_official_gamescaling_policy,
    validate_inference_endpoint,
)

GOOD = "<think></think><|action_start|>0 0 0 ; W ; W ; W ; W ; W ; W<|action_end|>"
BAD = "<|action_start|>0 0 0 ; W ; W ; W ; W ; W<|action_end|>"


class FakeDecision:
    def __init__(self, text=GOOD, *, finish_reason="stop", error=None):
        self.text = text
        self.finish_reason = finish_reason
        self.error = error
        self.latency_ms = 33.0


class FakeOfficialPolicy:
    def __init__(self, reply=None):
        self.reply = reply or FakeDecision()
        self.calls = 0
        self.reset_calls = 0
        self.images = []

    def reset(self):
        self.reset_calls += 1

    def act(self, image):
        self.calls += 1
        self.images.append(image)
        # Simulate the upstream behavior: a proposed action enters its history.
        return self.reply


class BridgeTests(unittest.TestCase):
    def test_consent_and_budget_before_any_model_call(self):
        client = FakeOfficialPolicy()
        bridge = ReadOnlyGameScalingPreview(client)
        frame = object()
        self.assertEqual(
            bridge.propose(frame, consent_for_this_frame=False, now_ms=1000).reason,
            "no_explicit_frame_consent",
        )
        self.assertEqual(
            bridge.propose(None, consent_for_this_frame=True, now_ms=1000).reason,
            "missing_user_selected_frame",
        )
        self.assertEqual(client.calls, 0)
        self.assertEqual(bridge.requests_used, 0)
        first = bridge.propose(frame, consent_for_this_frame=True, now_ms=1000)
        self.assertTrue(first.valid)
        self.assertFalse(first.executable)
        self.assertIn("<|action_start|>", first.gamer_wire)
        self.assertEqual(client.calls, 1)
        self.assertEqual(client.reset_calls, 2)
        self.assertIs(client.images[0], frame)
        self.assertEqual(
            bridge.propose(frame, consent_for_this_frame=True, now_ms=1001).reason,
            "manual_request_cooldown",
        )
        self.assertTrue(bridge.propose(frame, consent_for_this_frame=True, now_ms=11_000).valid)
        self.assertTrue(bridge.propose(frame, consent_for_this_frame=True, now_ms=21_000).valid)
        self.assertEqual(
            bridge.propose(frame, consent_for_this_frame=True, now_ms=31_000).reason,
            "manual_request_budget_exhausted",
        )
        self.assertEqual(client.calls, 3)
        self.assertEqual(client.reset_calls, 6)

    def test_invalid_or_unfinished_model_output_is_never_executable(self):
        for decision, reason in (
            (FakeDecision(BAD), "invalid_gamer_wire:expected_six_action_steps"),
            (FakeDecision(GOOD, finish_reason="length"), "model_action_not_fully_finished"),
            (FakeDecision(GOOD, error="remote key leaked?"), "model_request_failed"),
        ):
            with self.subTest(reason=reason):
                upstream = FakeOfficialPolicy(decision)
                adapter = ReadOnlyGameScalingPreview(upstream)
                result = adapter.propose(object(), consent_for_this_frame=True, now_ms=1000)
                self.assertFalse(result.valid)
                self.assertFalse(result.executable)
                self.assertEqual(result.reason, reason)
                self.assertEqual(result.gamer_wire, "")
                self.assertEqual(adapter.requests_used, 1)
                self.assertEqual(upstream.reset_calls, 2)

    def test_no_unverified_proposals_are_preserved_as_history(self):
        upstream = FakeOfficialPolicy()
        adapter = ReadOnlyGameScalingPreview(upstream, minimum_interval_ms=0)
        for n in range(3):
            result = adapter.propose(object(), consent_for_this_frame=True, now_ms=1000 + n)
            self.assertTrue(result.valid)
        self.assertEqual(upstream.reset_calls, 6)

    def test_invalid_times_and_configs(self):
        adapter = ReadOnlyGameScalingPreview(FakeOfficialPolicy(), minimum_interval_ms=0)
        self.assertEqual(
            adapter.propose(object(), consent_for_this_frame=True, now_ms=-1).reason,
            "invalid_monotonic_time",
        )
        self.assertTrue(adapter.propose(object(), consent_for_this_frame=True, now_ms=1000).valid)
        self.assertEqual(
            adapter.propose(object(), consent_for_this_frame=True, now_ms=999).reason,
            "monotonic_clock_reversed",
        )
        with self.assertRaises(ValueError):
            ReadOnlyGameScalingPreview(FakeOfficialPolicy(), max_requests=0)

    def test_endpoint_validation_prevents_plaintext_external_screenshot_uploads(self):
        self.assertEqual(validate_inference_endpoint("http://127.0.0.1:8000/v1"),
                         "http://127.0.0.1:8000/v1")
        self.assertEqual(validate_inference_endpoint("https://example.org/v1/"),
                         "https://example.org/v1")
        for url in (
            "http://example.com/v1", "http://192.168.1.10:8000/v1",
            "https://user:pass@example.com/v1", "https://example.com/v1?key=abc",
            "ftp://example.com/v1", "https://example.com/chat/completions",
        ):
            with self.subTest(url=url), self.assertRaises(ValueError):
                validate_inference_endpoint(url)

    def test_official_prompt_and_client_are_used_when_available(self):
        calls = {}
        models = types.ModuleType("gamescaling")
        policy = types.ModuleType("gamescaling.policy")
        prompt = types.ModuleType("gamescaling.prompt")
        action = types.ModuleType("gamescaling.action")
        action.KEY_VOCAB = frozenset({"W", "Esc"})

        def build_prompt(name, *, rules, key_bindings, mouse_aim):
            calls["prompt"] = (name, rules, key_bindings, mouse_aim)
            return "official prompt"

        class Config:
            def __init__(self, **kwargs):
                self.settings = kwargs

        class Policy:
            def __init__(self, text, config):
                calls["client"] = (text, config.settings)

        policy.GameScalingPolicy = Policy
        policy.PolicyConfig = Config
        prompt.build_system_prompt = build_prompt
        with patch.dict(sys.modules, {
            "gamescaling": models, "gamescaling.policy": policy,
            "gamescaling.prompt": prompt, "gamescaling.action": action,
        }):
            create_official_gamescaling_policy(
                base_url="https://example.org/v1", model="gamescaling",
                api_key="secret", rules="Navigate safe menus",
                controls={"Esc": "Open menu"},
            )
            self.assertEqual(calls["prompt"], (
                "Honkai: Star Rail", "Navigate safe menus", {"Esc": "Open menu"}, False,
            ))
            self.assertEqual(calls["client"][0], "official prompt")
            self.assertFalse(calls["client"][1]["lenient_parse"])
            self.assertEqual(calls["client"][1]["history_len"], 0)
            self.assertEqual(calls["client"][1]["base_url"], "https://example.org/v1")
            with self.assertRaises(ValueError):
                create_official_gamescaling_policy(
                    base_url="https://example.org/v1", model="gamescaling",
                    api_key="secret", rules="Safe scene", controls={"evil": "not mapped"},
                )


if __name__ == "__main__":
    unittest.main()
