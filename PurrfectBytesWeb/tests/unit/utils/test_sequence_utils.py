"""Unit tests for speed-sequence parsing."""

import pytest

from src.utils.sequence_utils import (
    SequenceStep,
    parse_sequence,
    sequence_slug,
    total_repetitions,
    describe_sequence,
)


class TestParseSequence:
    """Test parse_sequence."""

    def test_multi_step(self):
        steps = parse_sequence("2n,3s,2n")
        assert steps == [
            SequenceStep(count=2, slow=False),
            SequenceStep(count=3, slow=True),
            SequenceStep(count=2, slow=False),
        ]

    def test_single_step(self):
        assert parse_sequence("5s") == [SequenceStep(count=5, slow=True)]

    def test_whitespace_and_case_tolerance(self):
        assert parse_sequence(" 2N , 3S ") == [
            SequenceStep(count=2, slow=False),
            SequenceStep(count=3, slow=True),
        ]

    def test_trailing_comma_ignored(self):
        assert parse_sequence("2n,") == [SequenceStep(count=2, slow=False)]

    def test_empty_raises(self):
        with pytest.raises(ValueError, match="empty"):
            parse_sequence("")
        with pytest.raises(ValueError, match="empty"):
            parse_sequence("  ,  ")

    def test_invalid_tokens_raise(self):
        for bad in ("2x", "abc", "n2", "2", "n", "2ns", "-1n"):
            with pytest.raises(ValueError, match="Invalid sequence step"):
                parse_sequence(bad)

    def test_zero_count_raises(self):
        with pytest.raises(ValueError, match="out of range"):
            parse_sequence("0n")

    def test_step_count_over_limit_raises(self):
        with pytest.raises(ValueError, match="out of range"):
            parse_sequence("101n")

    def test_total_over_limit_raises(self):
        with pytest.raises(ValueError, match="totals"):
            parse_sequence("60n,60s")

    def test_too_many_steps_raises(self):
        spec = ",".join(["1n"] * 21)
        with pytest.raises(ValueError, match="too many steps"):
            parse_sequence(spec)


class TestSequenceHelpers:
    """Test slug/total/describe helpers."""

    def test_sequence_slug(self):
        assert sequence_slug(parse_sequence("2n,3s,2n")) == "2n-3s-2n"

    def test_total_repetitions(self):
        assert total_repetitions(parse_sequence("2n,3s,2n")) == 7

    def test_describe_sequence(self):
        assert describe_sequence(parse_sequence("2n,3s")) == "2 normal, 3 slow"
