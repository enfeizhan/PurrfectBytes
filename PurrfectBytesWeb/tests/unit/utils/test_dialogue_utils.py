"""Tests for conversation dialogue parsing."""

import pytest

from src.utils.dialogue_utils import (
    MAX_LINES,
    SPEAKER_PREFIX,
    DialogueLine,
    parse_dialogue,
    describe_dialogue,
    dialogue_display_text,
)


class TestParseDialogue:
    def test_alternates_speakers(self):
        lines = parse_dialogue("Hello\nHi there\nHow are you?\nGreat!")
        assert [l.speaker for l in lines] == [0, 1, 0, 1]
        assert [l.text for l in lines] == ["Hello", "Hi there", "How are you?", "Great!"]

    def test_skips_empty_lines_and_strips_whitespace(self):
        lines = parse_dialogue("  Hello  \n\n   \nHi there\n")
        assert lines == [
            DialogueLine(text="Hello", speaker=0),
            DialogueLine(text="Hi there", speaker=1),
        ]

    def test_single_line_rejected(self):
        with pytest.raises(ValueError, match="at least 2 lines"):
            parse_dialogue("Hello")

    def test_empty_text_rejected(self):
        with pytest.raises(ValueError, match="at least 2 lines"):
            parse_dialogue("")
        with pytest.raises(ValueError, match="at least 2 lines"):
            parse_dialogue(None)

    def test_too_many_lines_rejected(self):
        text = "\n".join(f"line {i}" for i in range(MAX_LINES + 1))
        with pytest.raises(ValueError, match="too many lines"):
            parse_dialogue(text)

    def test_max_lines_accepted(self):
        text = "\n".join(f"line {i}" for i in range(MAX_LINES))
        assert len(parse_dialogue(text)) == MAX_LINES


class TestDescribeDialogue:
    def test_describe(self):
        lines = parse_dialogue("a\nb\nc")
        assert describe_dialogue(lines) == "3 lines, 2 voices"


class TestDialogueDisplayText:
    def test_prefixes_every_line_with_speaker_marker(self):
        lines = parse_dialogue("Hello\nHi there")
        display, offsets = dialogue_display_text(lines)
        assert display == f"{SPEAKER_PREFIX}Hello\n{SPEAKER_PREFIX}Hi there"

    def test_offsets_locate_each_voiced_line(self):
        lines = parse_dialogue("Hello\nHi\nBye")
        display, offsets = dialogue_display_text(lines)
        for line, offset in zip(lines, offsets):
            assert display[offset:offset + len(line.text)] == line.text
