"""Parsing and helpers for two-voice conversation texts (lines alternate speakers)."""

import re
from dataclasses import dataclass
from typing import List, Optional, Tuple

MAX_LINES = 40

# Shown at the start of an unlabelled dialogue line on screen (never voiced)
SPEAKER_PREFIX = "— "

# A line may name its speaker ("직원: 혼자 오셨어요?"). The name is displayed but
# never voiced, and every line with the same name gets the same voice.
MAX_LABEL_LEN = 20
_LABEL_PATTERN = re.compile(
    r"^([^\s:：/][^:：/]{0,%d})[:：][ \t]*(\S.*)$" % (MAX_LABEL_LEN - 1)
)
# Punctuation that means the colon ends a sentence clause, not a speaker name
_NOT_A_LABEL = re.compile(r"[.!?。！？…,、;]")


@dataclass(frozen=True)
class DialogueLine:
    """One spoken line of a conversation, assigned to speaker 0 or 1.

    `label` is the speaker name written in front of the line ("직원"), shown on
    screen but never voiced; empty when the line carries no name.
    """

    text: str
    speaker: int
    label: str = ""


def split_speaker_label(line: str) -> Tuple[str, str]:
    """Split "직원: 혼자 오셨어요?" into ("직원", "혼자 오셨어요?").

    Returns ("", line) when the line carries no speaker name — the match is
    kept deliberately narrow so an ordinary sentence containing a colon is
    left alone.
    """
    match = _LABEL_PATTERN.match(line)
    if not match:
        return "", line

    label, spoken = match.group(1).strip(), match.group(2).strip()
    # A leading slash means the colon belongs to a URL scheme, not a speaker
    if not label or not spoken or spoken.startswith("/") or _NOT_A_LABEL.search(label):
        return "", line
    return label, spoken


def _non_empty_lines(text: str) -> List[str]:
    return [line for line in (line.strip() for line in (text or "").splitlines()) if line]


def parse_dialogue(text: str) -> List[DialogueLine]:
    """
    Split conversation text into lines spoken by two alternating speakers.

    A line may name its speaker ("직원: 혼자 오셨어요?"); lines sharing a name
    share a voice, so a speaker may take two turns in a row. Unlabelled lines
    simply alternate: speaker 0, 1, 0, 1, ... Empty lines are skipped.
    Raises ValueError with a user-facing message on invalid input.
    """
    lines = _non_empty_lines(text)

    if len(lines) < 2:
        raise ValueError(
            "Conversation mode needs at least 2 lines of text - "
            "each line alternates between the two voices"
        )
    if len(lines) > MAX_LINES:
        raise ValueError(f"Conversation has too many lines (max {MAX_LINES})")

    parsed = [split_speaker_label(line) for line in lines]

    # Distinct speaker names, in order of first appearance, become voices 0 and 1
    names: List[str] = []
    for label, _ in parsed:
        if label and label.casefold() not in {n.casefold() for n in names}:
            names.append(label)
    if len(names) > 2:
        raise ValueError(
            f"Conversation mode supports two speakers, but found "
            f"{len(names)}: {', '.join(names)}"
        )
    speaker_of = {name.casefold(): index for index, name in enumerate(names)}

    dialogue = []
    previous: Optional[int] = None
    for label, spoken in parsed:
        if label:
            speaker = speaker_of[label.casefold()]
        else:
            speaker = 0 if previous is None else 1 - previous
        dialogue.append(DialogueLine(text=spoken, speaker=speaker, label=label))
        previous = speaker
    return dialogue


def parse_voiced_dialogue(
    display_lines: List[DialogueLine], voiced_text: str
) -> List[DialogueLine]:
    """
    Parse a pronunciation-override text against an already-parsed dialogue.

    Each non-empty line of `voiced_text` is voiced in place of the matching
    display line, keeping its speaker and displayed speaker name. A speaker
    name on an override line is dropped (it is never voiced), so the override
    can be pasted from the text above and edited in place. Raises ValueError
    with a user-facing message when the line counts differ.
    """
    lines = _non_empty_lines(voiced_text)

    if len(lines) != len(display_lines):
        raise ValueError(
            f"Pronunciation override must have the same number of lines as the "
            f"text ({len(display_lines)}), got {len(lines)}"
        )

    return [
        DialogueLine(
            text=split_speaker_label(line)[1],
            speaker=display.speaker,
            label=display.label,
        )
        for line, display in zip(lines, display_lines)
    ]


def describe_dialogue(lines: List[DialogueLine]) -> str:
    """Human-readable form: "6 lines, 2 voices"."""
    return f"{len(lines)} lines, 2 voices"


def dialogue_display_text(lines: List[DialogueLine]) -> Tuple[str, List[int]]:
    """The dialogue as displayed on screen, plus each line's voiced-text offset.

    Every line gets a leading marker: its speaker's name when the line names
    one, an em-dash otherwise. The returned offsets locate each line's spoken
    text within the display text (past the marker), so highlighting sweeps only
    the spoken part and per-line audio timing maps onto the full-dialogue layout.
    """
    parts = []
    offsets = []
    offset = 0
    for line in lines:
        prefix = f"{line.label}: " if line.label else SPEAKER_PREFIX
        parts.append(prefix + line.text)
        offsets.append(offset + len(prefix))
        offset += len(prefix) + len(line.text) + 1  # +1 for the newline
    return "\n".join(parts), offsets
