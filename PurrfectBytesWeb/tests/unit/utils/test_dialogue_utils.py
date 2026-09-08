"""Tests for conversation dialogue parsing."""

import pytest

from src.utils.dialogue_utils import (
    MAX_LINES,
    SPEAKER_PREFIX,
    DialogueLine,
    parse_dialogue,
    parse_voiced_dialogue,
    describe_dialogue,
    dialogue_display_text,
    split_speaker_label,
)

KOREAN_DIALOGUE = "직원: 혼자 오셨어요?\n관광객: 아니요, 친구하고 같이 왔어요."


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


class TestSplitSpeakerLabel:
    def test_splits_a_named_line(self):
        assert split_speaker_label("직원: 혼자 오셨어요?") == ("직원", "혼자 오셨어요?")

    def test_accepts_fullwidth_colon_and_no_space(self):
        assert split_speaker_label("店員：一人ですか？") == ("店員", "一人ですか？")

    def test_plain_line_is_left_alone(self):
        assert split_speaker_label("혼자 오셨어요?") == ("", "혼자 오셨어요?")

    def test_sentence_with_a_colon_is_not_a_label(self):
        # A clause ending in punctuation, an over-long prefix, and a URL must
        # all stay whole rather than being read as speaker names.
        for line in [
            "Wait, listen: this is important.",
            "A very long introductory phrase indeed: the rest",
            "See https://example.com for details",
        ]:
            assert split_speaker_label(line) == ("", line)

    def test_colon_with_nothing_after_it_is_not_a_label(self):
        assert split_speaker_label("직원:") == ("", "직원:")


class TestParseDialogueWithSpeakerLabels:
    def test_label_is_captured_and_stripped_from_the_spoken_text(self):
        lines = parse_dialogue(KOREAN_DIALOGUE)
        assert [l.label for l in lines] == ["직원", "관광객"]
        assert [l.text for l in lines] == ["혼자 오셨어요?", "아니요, 친구하고 같이 왔어요."]
        assert [l.speaker for l in lines] == [0, 1]

    def test_same_name_keeps_the_same_voice_across_consecutive_turns(self):
        lines = parse_dialogue("A: one\nA: two\nB: three\nA: four")
        assert [l.speaker for l in lines] == [0, 0, 1, 0]

    def test_names_are_matched_case_insensitively(self):
        lines = parse_dialogue("Ann: hi\nBob: hello\nann: bye")
        assert [l.speaker for l in lines] == [0, 1, 0]

    def test_more_than_two_names_rejected(self):
        with pytest.raises(ValueError, match="supports two speakers"):
            parse_dialogue("A: one\nB: two\nC: three")

    def test_unlabelled_lines_alternate_around_labelled_ones(self):
        lines = parse_dialogue("A: one\nplain\nA: three")
        assert [l.speaker for l in lines] == [0, 1, 0]
        assert lines[1].label == ""

    def test_unlabelled_dialogue_is_unchanged(self):
        lines = parse_dialogue("Hello\nHi there")
        assert [l.speaker for l in lines] == [0, 1]
        assert [l.label for l in lines] == ["", ""]


class TestParseVoicedDialogue:
    def test_replaces_texts_and_keeps_speakers(self):
        display = parse_dialogue("行った\n売った\n買った")
        voiced = parse_voiced_dialogue(display, "おこなった\nうった\nかった")
        assert [l.text for l in voiced] == ["おこなった", "うった", "かった"]
        assert [l.speaker for l in voiced] == [0, 1, 0]

    def test_skips_empty_lines_and_strips_whitespace(self):
        display = parse_dialogue("Hello\nHi")
        voiced = parse_voiced_dialogue(display, "  Hallo  \n\n   \nHoi\n")
        assert [l.text for l in voiced] == ["Hallo", "Hoi"]

    def test_speaker_names_on_override_lines_are_dropped(self):
        display = parse_dialogue(KOREAN_DIALOGUE)
        voiced = parse_voiced_dialogue(display, "직원: 혼자 오셨어요\n관광객: 아니요")
        assert [l.text for l in voiced] == ["혼자 오셨어요", "아니요"]
        assert [l.label for l in voiced] == ["직원", "관광객"]

    def test_line_count_mismatch_rejected(self):
        display = parse_dialogue("Hello\nHi")
        with pytest.raises(ValueError, match="same number of lines"):
            parse_voiced_dialogue(display, "Only one line")
        with pytest.raises(ValueError, match="same number of lines"):
            parse_voiced_dialogue(display, "a\nb\nc")
        with pytest.raises(ValueError, match="same number of lines"):
            parse_voiced_dialogue(display, "")


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

    def test_named_lines_are_shown_with_their_speaker_instead_of_the_marker(self):
        lines = parse_dialogue(KOREAN_DIALOGUE)
        display, offsets = dialogue_display_text(lines)
        assert display == "직원: 혼자 오셨어요?\n관광객: 아니요, 친구하고 같이 왔어요."
        assert SPEAKER_PREFIX not in display
        # Highlighting starts past the name, on the first spoken character
        for line, offset in zip(lines, offsets):
            assert display[offset:offset + len(line.text)] == line.text

    def test_named_and_unnamed_lines_can_mix(self):
        lines = parse_dialogue("직원: 안녕하세요\n반갑습니다")
        display, offsets = dialogue_display_text(lines)
        assert display == f"직원: 안녕하세요\n{SPEAKER_PREFIX}반갑습니다"
        for line, offset in zip(lines, offsets):
            assert display[offset:offset + len(line.text)] == line.text
