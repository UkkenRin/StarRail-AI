"""Optional read-only integration with the OFFICIAL GameScaling inference client.

Upstream source (Apache-2.0): https://github.com/THUSI-Lab/GameScaling
Pinned review baseline: 843a32078bd1cc7316388fb64433556f61075d26

This code imports upstream gamescaling.prompt and gamescaling.policy *if*
the official repository is installed on the Python runtime's import path.
It never vendors the model, sends device input, or calls a model by itself.

GameScalingPolicy.act() unconditionally appends a proposed action to history.
A proposed action is NOT an observed executed action in StarRail-AI.
Therefore this bridge resets upstream history before and after EVERY
read-only proposal. A future live executor needs a separate verified-action
history, not this exploratory preview client.
"""
from __future__ import annotations

from dataclasses import dataclass
from typing import Any
from urllib.parse import urlsplit

from .gamer_protocol import parse_gamer_action


@dataclass(frozen=True)
class GameScalingPreview:
    valid: bool
    reason: str
    gamer_wire: str = ""
    executable: bool = False
    latency_ms: float | None = None


def validate_inference_endpoint(base_url: str) -> str:
    """Only TLS remote endpoints or an explicit *local* vLLM server."""
    if not isinstance(base_url, str):
        raise ValueError("invalid inference URL")
    url = urlsplit(base_url)
    if (
        not url.hostname or url.username or url.password or
        url.query or url.fragment or
        url.path.rstrip("/") != "/v1"
    ):
        raise ValueError("expected a clean /v1 inference endpoint")
    if url.scheme == "http" and url.hostname not in ("127.0.0.1", "::1", "localhost"):
        raise ValueError("unencrypted non-loopback inference endpoint forbidden")
    if url.scheme not in ("http", "https"):
        raise ValueError("only HTTPS or loopback HTTP inference endpoints are supported")
    return base_url.rstrip("/")


def create_official_gamescaling_policy(
    *, base_url: str, model: str, api_key: str,
    rules: str, controls: dict[str, str],
) -> Any:
    """Build an upstream client; the caller must use ReadOnlyGameScalingPreview.

    Run this only in a trusted Python environment where the official
    GameScaling checkout and requests/Pillow are already installed. Never
    send credentials or screenshot to an untrusted endpoint.
    """
    clean_url = validate_inference_endpoint(base_url)
    if not model or len(model) > 150 or len(api_key) > 4096:
        raise ValueError("invalid model or credential size")
    if not rules or len(rules) > 5000 or len(controls) > 30:
        raise ValueError("game rules or controls out of bounds")

    try:
        from gamescaling.policy import GameScalingPolicy, PolicyConfig
        from gamescaling.prompt import build_system_prompt
        from gamescaling.action import KEY_VOCAB
    except ImportError as exc:
        raise RuntimeError(
            "Official GameScaling client is not installed in this Python environment"
        ) from exc

    if any(k not in KEY_VOCAB or not v or len(v) > 120 for k, v in controls.items()):
        raise ValueError("invalid GameScaling key descriptions")
    prompt = build_system_prompt(
        "Honkai: Star Rail", rules=rules, key_bindings=controls,
        mouse_aim=False,
    )
    cfg = PolicyConfig(
        base_url=clean_url, model=model, api_key=api_key,
        history_len=0, history_think=0, lenient_parse=False,
    )
    return GameScalingPolicy(prompt, cfg)


class ReadOnlyGameScalingPreview:
    """Offline-safe wrapper: no automatic requests, retries or executable actions.

    Even a valid action is only an untrusted proposal. Calling propose()
    requires explicit consent for THIS frame and consumes one permit. All
    actual game control must use independent focus/target/input verification.
    """

    def __init__(
        self, official_policy: Any, *, max_requests: int = 3,
        minimum_interval_ms: int = 10_000,
    ):
        if max_requests < 1 or minimum_interval_ms < 0:
            raise ValueError("invalid manual upload budget")
        self._policy = official_policy
        self._max_requests = max_requests
        self._minimum_interval_ms = minimum_interval_ms
        self._used = 0
        self._last_ms: int | None = None

    @property
    def requests_used(self) -> int:
        return self._used

    def propose(self, image: Any, *, consent_for_this_frame: bool, now_ms: int) -> GameScalingPreview:
        if not consent_for_this_frame:
            return GameScalingPreview(False, "no_explicit_frame_consent")
        if not isinstance(now_ms, int) or now_ms < 0:
            return GameScalingPreview(False, "invalid_monotonic_time")
        if self._used >= self._max_requests:
            return GameScalingPreview(False, "manual_request_budget_exhausted")
        if self._last_ms is not None and now_ms < self._last_ms:
            return GameScalingPreview(False, "monotonic_clock_reversed")
        if (self._last_ms is not None and
                now_ms - self._last_ms < self._minimum_interval_ms):
            return GameScalingPreview(False, "manual_request_cooldown")
        if image is None:
            return GameScalingPreview(False, "missing_user_selected_frame")

        self._used += 1
        self._last_ms = now_ms
        try:
            # Never carry unexecuted, unverified proposals to a later turn.
            self._policy.reset()
            decision = self._policy.act(image)
            if getattr(decision, "error", None):
                return GameScalingPreview(False, "model_request_failed")
            if getattr(decision, "finish_reason", None) != "stop":
                return GameScalingPreview(False, "model_action_not_fully_finished")
            strict = parse_gamer_action(getattr(decision, "text", None))
            if strict.chunk is None:
                return GameScalingPreview(False, "invalid_gamer_wire:" + strict.reason)
            return GameScalingPreview(
                True, "strict_read_only_proposal",
                gamer_wire=strict.chunk.to_wire(),
                latency_ms=getattr(decision, "latency_ms", None),
            )
        except Exception:
            # Do not expose upstream raw server responses or model credentials.
            return GameScalingPreview(False, "model_client_error")
        finally:
            # GameScalingPolicy.act adds the proposal to its history automatically;
            # that is not evidence of game execution.
            self._policy.reset()
