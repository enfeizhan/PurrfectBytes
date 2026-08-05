"""Parsing and helpers for speed-sequence specs like "2n,3s,2n" (n=normal, s=slow)."""

import re
from dataclasses import dataclass
from typing import List

MAX_STEPS = 20
MAX_TOTAL_REPETITIONS = 100

_STEP_RE = re.compile(r"^(\d+)([ns])$")


@dataclass(frozen=True)
class SequenceStep:
    """One step of a speed sequence: repeat `count` times at normal or slow speed."""

    count: int
    slow: bool


def parse_sequence(sequence: str) -> List[SequenceStep]:
    """
    Parse a sequence spec like "2n,3s,2n" into ordered SequenceSteps.

    Raises ValueError with a user-facing message on invalid input.
    """
    tokens = [t.strip().lower() for t in (sequence or "").split(",") if t.strip()]

    if not tokens:
        raise ValueError("Sequence is empty - provide at least one step like '2n' or '3s'")
    if len(tokens) > MAX_STEPS:
        raise ValueError(f"Sequence has too many steps (max {MAX_STEPS})")

    steps: List[SequenceStep] = []
    for token in tokens:
        match = _STEP_RE.match(token)
        if not match:
            raise ValueError(
                f"Invalid sequence step '{token}' - use a count followed by "
                f"'n' (normal) or 's' (slow), e.g. '2n' or '3s'"
            )
        count = int(match.group(1))
        if not 1 <= count <= MAX_TOTAL_REPETITIONS:
            raise ValueError(
                f"Step count {count} out of range (1-{MAX_TOTAL_REPETITIONS})"
            )
        steps.append(SequenceStep(count=count, slow=match.group(2) == "s"))

    total = total_repetitions(steps)
    if total > MAX_TOTAL_REPETITIONS:
        raise ValueError(
            f"Sequence totals {total} repetitions (max {MAX_TOTAL_REPETITIONS})"
        )

    return steps


def sequence_slug(steps: List[SequenceStep]) -> str:
    """Filename-safe form: "2n-3s-2n"."""
    return "-".join(f"{s.count}{'s' if s.slow else 'n'}" for s in steps)


def total_repetitions(steps: List[SequenceStep]) -> int:
    return sum(s.count for s in steps)


def describe_sequence(steps: List[SequenceStep]) -> str:
    """Human-readable form: "2 normal, 3 slow, 2 normal"."""
    return ", ".join(f"{s.count} {'slow' if s.slow else 'normal'}" for s in steps)
