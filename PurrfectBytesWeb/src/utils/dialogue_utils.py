"""Parsing and helpers for two-voice conversation texts (lines alternate speakers)."""

from dataclasses import dataclass
from typing import List, Tuple

MAX_LINES = 40

# Shown at the start of each dialogue line on screen (never voiced)
SPEAKER_PREFIX = "— "


@dataclass(frozen=True)
class DialogueLine:
    """One spoken line of a conversation, assigned to speaker 0 or 1."""

    text: str
    speaker: int


def parse_dialogue(text: str) -> List[DialogueLine]:
    """
    Split conversation text into lines alternating between two speakers.

    Non-empty lines alternate speaker 0, 1, 0, 1, ... Empty lines are skipped.
    Raises ValueError with a user-facing message on invalid input.
    """
    stripped = [line.strip() for line in (text or "").splitlines()]
    lines = [line for line in stripped if line]

    if len(lines) < 2:
        raise ValueError(
            "Conversation mode needs at least 2 lines of text - "
            "each line alternates between the two voices"
        )
    if len(lines) > MAX_LINES:
        raise ValueError(f"Conversation has too many lines (max {MAX_LINES})")

    return [
        DialogueLine(text=line, speaker=index % 2) for index, line in enumerate(lines)
    ]


def parse_voiced_dialogue(
    display_lines: List[DialogueLine], voiced_text: str
) -> List[DialogueLine]:
    """
    Parse a pronunciation-override text against an already-parsed dialogue.

    Each non-empty line of `voiced_text` is voiced in place of the matching
    display line, keeping its speaker. Raises ValueError with a user-facing
    message when the line counts differ.
    """
    stripped = [line.strip() for line in (voiced_text or "").splitlines()]
    lines = [line for line in stripped if line]

    if len(lines) != len(display_lines):
        raise ValueError(
            f"Pronunciation override must have the same number of lines as the "
            f"text ({len(display_lines)}), got {len(lines)}"
        )

    return [
        DialogueLine(text=line, speaker=display.speaker)
        for line, display in zip(lines, display_lines)
    ]


def describe_dialogue(lines: List[DialogueLine]) -> str:
    """Human-readable form: "6 lines, 2 voices"."""
    return f"{len(lines)} lines, 2 voices"


def dialogue_display_text(lines: List[DialogueLine]) -> Tuple[str, List[int]]:
    """The dialogue as displayed on screen, plus each line's voiced-text offset.

    Every line gets a leading em-dash speaker marker. The returned offsets
    locate each line's spoken text within the display text (past the marker),
    for mapping per-line audio timing onto the full-dialogue layout.
    """
    parts = []
    offsets = []
    offset = 0
    for line in lines:
        parts.append(SPEAKER_PREFIX + line.text)
        offsets.append(offset + len(SPEAKER_PREFIX))
        offset += len(SPEAKER_PREFIX) + len(line.text) + 1  # +1 for the newline
    return "\n".join(parts), offsets
