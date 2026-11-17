"""Read-only strict adapter for the public Gamer / GameScaling action protocol.

Wire format (from the upstream public README, not the unreleased Gamer model):
    <think>optional reasoning</think>
    <|action_start|>X Y Z ; g1 ; g2 ; g3 ; g4 ; g5 ; g6<|action_end|>

One 200 ms proposal contains six ~33.3 ms keyboard groups, a relative mouse
displacement in thousandths of screen dimensions, and a wheel amount.

Unlike GameScaling's research parser this safety-boundary parser intentionally
REJECTS incomplete actions, unknown controls, truncation, over-limit values,
extra steps and model text outside the action. Nothing here sends input or calls
a vision model. Do not treat successful parsing as permission to execute.
See docs/GAMER_ADAPTER.md and AGENTS.md.
"""
from __future__ import annotations

from dataclasses import dataclass
import re

ACTION_START = "<|action_start|>"
ACTION_END = "<|action_end|>"
THINK_START = "<think>"
THINK_END = "</think>"
CHUNK_MS = 200
STEP_COUNT = 6
STEP_MS = CHUNK_MS / STEP_COUNT
MAX_KEYS = 4
MAX_MESSAGE_CHARS = 4096

# The case-sensitive, 74-token public GameScaling keyboard/mouse vocabulary.
KEYS = frozenset(
    tuple("ABCDEFGHIJKLMNOPQRSTUVWXYZ")
    + tuple("zero one two three four five six seven eight nine".split())
    + tuple("One Two Three Four Five Six Seven Eight Nine Ten Eleven Twelve".split())
    + tuple("LB RB MB".split())
    + tuple("Up Down Left Right".split())
    + tuple("Shift Ctrl Alt Tab Caps Esc Space Enter Back Delete Insert Home End Pause".split())
    + tuple("Equal Minus Period Slash Quote".split())
)
_NUMBER = re.compile(r"-?(?:0|[1-9][0-9]*)\Z", re.ASCII)


@dataclass(frozen=True)
class GamerActionChunk:
    """Parsed keyboard/mouse INTENT. This is never a trusted game touch target."""

    mouse_x_per_mille: int
    mouse_y_per_mille: int
    scroll_notches: int
    steps: tuple[tuple[str, ...], ...]

    @property
    def is_noop(self) -> bool:
        return (
            self.mouse_x_per_mille == 0
            and self.mouse_y_per_mille == 0
            and self.scroll_notches == 0
            and all(not group for group in self.steps)
        )

    @property
    def duration_ms(self) -> int:
        return CHUNK_MS

    def to_wire(self) -> str:
        """Canonical, tagged, reasoning-free form for offline testing/history."""
        head = f"{self.mouse_x_per_mille} {self.mouse_y_per_mille} {self.scroll_notches}"
        # Never emit surplus spaces for empty groups; canonical no-op is
        # "0 0 0 ; ; ; ; ; ;" with exactly six separators.
        groups = " ".join("; " + " ".join(group) if group else ";" for group in self.steps)
        return f"{ACTION_START}{head} {groups}{ACTION_END}"


@dataclass(frozen=True)
class GamerParseResult:
    chunk: GamerActionChunk | None
    reason: str

    @property
    def valid(self) -> bool:
        return self.chunk is not None


def _failure(reason: str) -> GamerParseResult:
    return GamerParseResult(None, reason)


def parse_gamer_action(message: str) -> GamerParseResult:
    """Parse exactly one complete chunk, failing closed on malformed model text.

    Text from inside an optional closed <think> block is NEVER interpreted as
    commands, even when it quotes a valid-looking action. No lenient fallback.
    """
    if not isinstance(message, str) or not message or len(message) > MAX_MESSAGE_CHARS:
        return _failure("empty_or_oversized_message")
    text = message.strip()

    if text.startswith(THINK_START):
        # Only one complete reasoning envelope may precede the action.
        if text.count(THINK_START) != 1 or text.count(THINK_END) != 1:
            return _failure("incomplete_or_multiple_reasoning_blocks")
        ending = text.rfind(THINK_END)
        if ending < len(THINK_START):
            return _failure("invalid_reasoning")
        text = text[ending + len(THINK_END):].strip()
    elif THINK_START in text or THINK_END in text:
        return _failure("invalid_reasoning")

    if (
        not text.startswith(ACTION_START)
        or not text.endswith(ACTION_END)
        or text.count(ACTION_START) != 1
        or text.count(ACTION_END) != 1
    ):
        return _failure("missing_or_multiple_action_envelopes")

    body = text[len(ACTION_START):-len(ACTION_END)]
    if "\n" in body or "\r" in body or "<" in body or ">" in body:
        return _failure("action_contains_unexpected_text")
    pieces = [part.strip() for part in body.split(";")]
    if len(pieces) != STEP_COUNT + 1:
        return _failure("expected_six_action_steps")

    numbers = pieces[0].split()
    if len(numbers) != 3 or any(not _NUMBER.fullmatch(n) for n in numbers):
        return _failure("invalid_mouse_or_scroll_numbers")
    x, y, scroll = map(int, numbers)
    if abs(x) > 1000 or abs(y) > 1000 or abs(scroll) > 5:
        return _failure("mouse_or_scroll_out_of_range")

    groups: list[tuple[str, ...]] = []
    for group in pieces[1:]:
        pressed = tuple(group.split())
        if len(pressed) > MAX_KEYS:
            return _failure("too_many_keys_in_step")
        if any(key not in KEYS for key in pressed):
            return _failure("unknown_or_case_mismatched_key")
        if len(set(pressed)) != len(pressed):
            return _failure("duplicate_key_in_step")
        groups.append(pressed)

    return GamerParseResult(GamerActionChunk(x, y, scroll, tuple(groups)), "parsed_read_only")


def preview_gamer_action(message: str) -> dict[str, object]:
    """JSON-friendly dry-run output; NEVER a command for an Android/Windows executor."""
    result = parse_gamer_action(message)
    if result.chunk is None:
        return {"valid": False, "reason": result.reason, "executable": False}
    chunk = result.chunk
    return {
        "valid": True,
        "reason": result.reason,
        "executable": False,
        "duration_ms": CHUNK_MS,
        "mouse_per_mille": [chunk.mouse_x_per_mille, chunk.mouse_y_per_mille],
        "scroll_notches": chunk.scroll_notches,
        "key_groups": [list(group) for group in chunk.steps],
        "noop": chunk.is_noop,
    }
