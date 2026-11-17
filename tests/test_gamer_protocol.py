"""GameScaling wire-compatibility tests. No GPU, API key, device or input access."""
import unittest

from starrail_core.gamer_protocol import (
    ACTION_END, ACTION_START, CHUNK_MS, KEYS, MAX_MESSAGE_CHARS,
    parse_gamer_action, preview_gamer_action,
)


def wire(body: str) -> str:
    return f"{ACTION_START}{body}{ACTION_END}"


class GamerProtocolTests(unittest.TestCase):
    def test_public_game_scaling_examples(self):
        message = "<think></think>" + wire("0 0 0 ; W ; W ; W ; W ; W ; W")
        result = parse_gamer_action(message)
        self.assertTrue(result.valid)
        self.assertEqual(result.chunk.duration_ms, 200)
        self.assertEqual(result.chunk.steps, (("W",),) * 6)
        self.assertEqual(result.chunk.to_wire(), wire("0 0 0 ; W ; W ; W ; W ; W ; W"))

    def test_source_vocab_and_case_sensitive_function_keys(self):
        self.assertEqual(len(KEYS), 74)
        result = parse_gamer_action(wire("120 -40 0 ; W ; W LB ; ; Shift W ; ; one"))
        self.assertTrue(result.valid)
        self.assertEqual(result.chunk.steps[1], ("W", "LB"))
        self.assertEqual(result.chunk.steps[-1], ("one",))
        self.assertTrue(parse_gamer_action(wire("0 0 0 ; one ; One ; ; ; ;")).valid)
        self.assertFalse(parse_gamer_action(wire("0 0 0 ; w ; ; ; ; ;")).valid)

    def test_quoted_action_in_reasoning_is_not_executed(self):
        quoted = wire("10 0 0 ; LB ; LB ; LB ; LB ; LB ; LB")
        real = wire("0 0 0 ; ; ; ; ; ;")
        result = parse_gamer_action("<think>Could try " + quoted + " but do not.</think>\n" + real)
        self.assertTrue(result.valid)
        self.assertTrue(result.chunk.is_noop)
        self.assertEqual(result.chunk.to_wire(), real)

    def test_incomplete_reasoning_and_no_tags_fail_closed(self):
        for message in (
            "<think>not finished " + wire("0 0 0 ; W ; W ; W ; W ; W ; W"),
            "0 0 0 ; W ; W ; W ; W ; W ; W",
            "<think>ok</think>garbage" + wire("0 0 0 ; W ; W ; W ; W ; W ; W"),
            wire("0 0 0 ; W ; W ; W ; W ; W ; W") + wire("0 0 0 ; ; ; ; ; ;"),
            "<think><think>duplicate</think>" + wire("0 0 0 ; ; ; ; ; ;"),
            "<think>no action</think>",
            "<think>reasoning</think><think>again</think>" + wire("0 0 0 ; ; ; ; ; ;"),
        ):
            with self.subTest(message=message[:65]):
                self.assertFalse(parse_gamer_action(message).valid)

    def test_exactly_six_groups_required(self):
        for bad in (
            "0 0 0 ; W ; W ; W ; W ; W",
            "0 0 0 ; W ; W ; W ; W ; W ; W ; W",
            "0 0 0 ; W ; W ; W ; W ; W ; W ;",
        ):
            self.assertEqual(parse_gamer_action(wire(bad)).reason, "expected_six_action_steps")

    def test_reject_overflow_unknown_duplicated_and_non_integer_actions(self):
        cases = {
            "1001 0 0 ; ; ; ; ; ;": "mouse_or_scroll_out_of_range",
            "-1001 0 0 ; ; ; ; ; ;": "mouse_or_scroll_out_of_range",
            "0 0 6 ; ; ; ; ; ;": "mouse_or_scroll_out_of_range",
            "0 0 0 ; W A S D Space ; ; ; ; ;": "too_many_keys_in_step",
            "0 0 0 ; W W ; ; ; ; ;": "duplicate_key_in_step",
            "0 0 0 ; sudo ; ; ; ; ;": "unknown_or_case_mismatched_key",
            "0 0 0 ; Swipe ; ; ; ; ;": "unknown_or_case_mismatched_key",
            "0.5 0 0 ; ; ; ; ; ;": "invalid_mouse_or_scroll_numbers",
            "NaN 0 0 ; ; ; ; ; ;": "invalid_mouse_or_scroll_numbers",
            "+2 0 0 ; ; ; ; ; ;": "invalid_mouse_or_scroll_numbers",
            "0 0 0 ; ; ; ; ; ;\nclick": "action_contains_unexpected_text",
        }
        for body, expected in cases.items():
            with self.subTest(body=body):
                self.assertEqual(parse_gamer_action(wire(body)).reason, expected)

    def test_boundary_values_and_noop(self):
        chunk = parse_gamer_action(wire("1000 -1000 5 ; ; ; ; ; ;")).chunk
        self.assertIsNotNone(chunk)
        self.assertFalse(chunk.is_noop)
        self.assertEqual(chunk.mouse_x_per_mille, 1000)
        self.assertEqual(chunk.mouse_y_per_mille, -1000)
        self.assertEqual(chunk.scroll_notches, 5)
        noop = parse_gamer_action(wire("0 0 0 ; ; ; ; ; ;")).chunk
        self.assertTrue(noop.is_noop)
        self.assertEqual(parse_gamer_action(noop.to_wire()).chunk, noop)

    def test_preview_is_always_non_executable(self):
        valid = preview_gamer_action(wire("0 0 0 ; W ; ; ; ; ;"))
        self.assertTrue(valid["valid"])
        self.assertFalse(valid["executable"])
        self.assertEqual(valid["key_groups"][0], ["W"])
        self.assertEqual(valid["duration_ms"], CHUNK_MS)
        invalid = preview_gamer_action("drop tables")
        self.assertFalse(invalid["valid"])
        self.assertFalse(invalid["executable"])

    def test_limit_and_malformed_input(self):
        self.assertEqual(parse_gamer_action("x" * (MAX_MESSAGE_CHARS + 1)).reason,
                         "empty_or_oversized_message")
        self.assertFalse(parse_gamer_action("").valid)
        self.assertFalse(parse_gamer_action(None).valid)
        self.assertFalse(parse_gamer_action(wire("0 0 0 ; LB ; ; ; ; ;") + "<anything>").valid)


if __name__ == "__main__":
    unittest.main()
