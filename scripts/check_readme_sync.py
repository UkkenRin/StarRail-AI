#!/usr/bin/env python3
"""Reject feature PRs that change user-facing code without updating README.md.

Policy check, not a semantic truth checker. It intentionally allows docs-only
and ordinary tests-only PRs to proceed without noisy README edits.
"""
from __future__ import annotations

import argparse
import subprocess
import sys

FEATURE_PREFIXES = ("starrail_core/", "android_overlay/", "data/", "scripts/")
FEATURE_EXACT = frozenset({".github/workflows/android-debug.yml"})
README = "README.md"


def requires_readme(changed: set[str]) -> bool:
    """True only when behavior, packaging, or shipped data might change."""
    return any(
        path in FEATURE_EXACT or
        any(path.startswith(prefix) for prefix in FEATURE_PREFIXES)
        for path in changed
    )


def check(changed: set[str]) -> tuple[bool, str]:
    if not requires_readme(changed):
        return True, "README sync: documentation/tests-only change; no update required."
    if README in changed:
        return True, "README sync: feature changes and README.md updated in this PR."
    relevant = sorted(path for path in changed if path in FEATURE_EXACT or
                      path.startswith(FEATURE_PREFIXES))
    return False, (
        "README.md must be updated in the SAME PR as feature/build changes.\n"
        "Files requiring the update:\n  " + "\n  ".join(relevant[:30]) +
        "\nExplain what is implemented, which CI passed, what is not device-verified, "
        "and where the actual APK can be downloaded."
    )


def changed_files(base: str, head: str) -> set[str]:
    if not base or not head:
        raise ValueError("base and head commits are required")
    result = subprocess.run(
        ["git", "diff", "--name-only", "--diff-filter=ACMRT", f"{base}...{head}"],
        check=True, text=True, capture_output=True,
    )
    return {path.strip() for path in result.stdout.splitlines() if path.strip()}


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--base", required=True, help="pull request base commit SHA")
    parser.add_argument("--head", required=True, help="pull request head commit SHA")
    args = parser.parse_args()
    try:
        ok, message = check(changed_files(args.base, args.head))
    except (ValueError, subprocess.CalledProcessError) as error:
        print(f"README sync: cannot inspect PR commits: {error}", file=sys.stderr)
        return 2
    print(message, file=sys.stdout if ok else sys.stderr)
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
