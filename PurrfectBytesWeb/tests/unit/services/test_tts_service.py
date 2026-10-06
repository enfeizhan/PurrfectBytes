"""Unit tests for TTS service."""

import json

import pytest
from pathlib import Path
from unittest.mock import MagicMock, patch

from src.services.audio_timing import (
    compute_character_timings,
    save_word_boundaries,
    word_boundaries_path,
)
from src.services.tts_service import TTSService
from src.models.schemas import CharacterTiming


class TestTTSService:
    """Test TTS service."""

    def test_generate_audio_success(self, tts_service, mock_gtts, mock_audio_duration, sample_text):
        """Test successful audio generation."""
        from src.services.tts_engines import TTSEngine

        audio_path, duration = tts_service.generate_audio(
            sample_text, "en", False, engine=TTSEngine.GTTS
        )

        assert isinstance(audio_path, Path)
        assert duration == 3.5  # From mock_audio_duration

        # Verify gTTS was called correctly
        mock_gtts.save.assert_called_once()

    def test_generate_audio_with_slow_speech(self, tts_service, mock_audio_duration, sample_text, mocker):
        """Test audio generation with slow speech."""
        from src.services.tts_engines import TTSEngine

        mock_tts = mocker.MagicMock()
        mock_tts_class = mocker.patch('gtts.gTTS', return_value=mock_tts)

        tts_service.generate_audio(sample_text, "en", True, engine=TTSEngine.GTTS)

        # Verify gTTS was called with slow=True
        mock_tts_class.assert_called_once_with(text=sample_text, lang="en", slow=True)

    def test_generate_audio_empty_text(self, tts_service):
        """Test audio generation with empty text."""
        with pytest.raises(ValueError, match="No valid text provided"):
            tts_service.generate_audio("", "en", False)

        with pytest.raises(ValueError, match="No valid text provided"):
            tts_service.generate_audio("   ", "en", False)

    def test_generate_audio_gtts_failure(self, tts_service, sample_text):
        """Test handling gTTS failures."""
        from src.services.tts_engines import TTSEngine

        with patch('gtts.gTTS', side_effect=Exception("TTS Error")):
            with pytest.raises(Exception, match="Failed to generate audio"):
                tts_service.generate_audio(sample_text, "en", False, engine=TTSEngine.GTTS)

    def test_analyze_audio_timing_success(self, tts_service, mock_audio_file, mock_audio_duration, sample_text):
        """Test successful audio timing analysis."""
        analysis = tts_service.analyze_audio_timing(sample_text, mock_audio_file)

        assert analysis.duration == 3.5
        assert len(analysis.character_timings) == len(sample_text)
        assert analysis.words_per_second > 0
        assert analysis.lead_time == 0.3
        assert analysis.overlap_duration == 0.4

        # Check first character timing
        first_timing = analysis.character_timings[0]
        assert isinstance(first_timing, CharacterTiming)
        assert first_timing.char == sample_text[0]
        assert first_timing.position == 0
        assert first_timing.start_time >= 0
        assert first_timing.end_time > first_timing.start_time

    def test_analyze_audio_timing_with_spaces(self, tts_service, mock_audio_file, mock_audio_duration):
        """Test audio timing analysis with spaces."""
        text_with_spaces = "Hello world"
        analysis = tts_service.analyze_audio_timing(text_with_spaces, mock_audio_file)

        # Find space character timing
        space_timing = None
        for timing in analysis.character_timings:
            if timing.char == ' ':
                space_timing = timing
                break

        assert space_timing is not None
        assert space_timing.char == ' '

    def test_analyze_audio_timing_fallback(self, tts_service, mock_audio_file, sample_text):
        """Test audio timing analysis fallback when duration reading fails."""
        with patch('src.services.tts_service.get_audio_duration', side_effect=Exception("Audio error")):
            analysis = tts_service.analyze_audio_timing(sample_text, mock_audio_file)

            # Should still return valid analysis
            assert analysis.duration > 0
            assert len(analysis.character_timings) == len(sample_text)

    def test_analyze_audio_timing_uses_word_boundaries(self, tts_service, mock_audio_file, mock_audio_duration):
        """Word boundary sidecar timestamps drive character timing when present."""
        text = "Hello world"
        boundaries = [
            {"word": "Hello", "start": 0.5, "end": 1.0},
            {"word": "world", "start": 1.5, "end": 2.0},
        ]
        word_boundaries_path(mock_audio_file).write_text(json.dumps(boundaries))

        analysis = tts_service.analyze_audio_timing(text, mock_audio_file)

        assert len(analysis.character_timings) == len(text)
        # First char of "Hello" starts at boundary time minus lead time
        first = analysis.character_timings[0]
        assert first.start_time == pytest.approx(max(0.0, 0.5 - analysis.lead_time))
        # First char of "world" (position 6) starts at its boundary minus lead time
        w = analysis.character_timings[6]
        assert w.char == "w"
        assert w.start_time == pytest.approx(1.5 - analysis.lead_time)

    def test_compute_character_timings(self):
        """Test uniform character timing calculation."""
        text = "Hello"
        duration = 5.0
        timings = compute_character_timings(text, duration)

        assert len(timings) == len(text)

        # Check timing progression
        for i in range(len(timings) - 1):
            assert timings[i].start_time <= timings[i + 1].start_time

        # Check last timing ends around duration
        last_timing = timings[-1]
        assert last_timing.end_time > duration  # Due to overlap_duration

    def test_compute_character_timings_empty_text(self):
        """Empty text produces no timings."""
        assert compute_character_timings("", 3.0) == []

    def test_generate_sequence_orders_and_cleans_up(self, tts_service, audio_dir, mocker):
        """Sequence synthesizes once per speed, concatenates in order, removes sources."""
        from src.utils.sequence_utils import parse_sequence

        normal = audio_dir / "normal.mp3"
        slow = audio_dir / "slow.mp3"
        normal.write_bytes(b"n")
        slow.write_bytes(b"s")
        normal_sidecar = audio_dir / "normal.mp3.words.json"
        normal_sidecar.write_text("[]")

        generate = mocker.patch.object(
            tts_service, "generate_audio",
            side_effect=lambda text, lang, slow_flag, engine=None, voice=None:
                (slow, 4.0) if slow_flag else (normal, 3.0)
        )
        output = audio_dir / "out.mp3"
        concat = mocker.patch.object(tts_service, "concatenate_audio", return_value=output)

        steps = parse_sequence("2n,3s")
        result_path, duration = tts_service.generate_sequence("hello", steps)

        assert result_path == output
        assert duration == pytest.approx(2 * 3.0 + 3 * 4.0)
        # One synthesis per distinct speed
        assert generate.call_count == 2
        # Concatenation receives the expanded, ordered path list
        ordered_paths, output_filename = concat.call_args[0]
        assert ordered_paths == [normal, normal, slow, slow, slow]
        assert output_filename.startswith("seq_2n-3s_")
        # Per-speed sources and sidecars are removed
        assert not normal.exists()
        assert not slow.exists()
        assert not normal_sidecar.exists()

    def test_generate_conversation_alternates_voices_and_cleans_up(self, tts_service, audio_dir, mocker):
        """Each line uses its speaker's voice; lines concatenate in order × repetitions."""
        from src.utils.dialogue_utils import parse_dialogue

        line_files = []

        def fake_generate(text, lang, slow_flag, engine=None, voice=None):
            path = audio_dir / f"line{len(line_files)}.mp3"
            path.write_bytes(b"x")
            path.with_name(path.name + ".words.json").write_text("[]")
            line_files.append(path)
            return path, 2.0

        generate = mocker.patch.object(tts_service, "generate_audio", side_effect=fake_generate)
        output = audio_dir / "out.mp3"
        concat = mocker.patch.object(tts_service, "concatenate_audio", return_value=output)

        dialogue = parse_dialogue("Hello\nHi\nBye")
        result_path, duration = tts_service.generate_conversation(
            dialogue, voices=("voiceA", "voiceB"), repetitions=2
        )

        assert result_path == output
        assert duration == pytest.approx(3 * 2.0 * 2)
        # One synthesis per line, with the speaker's voice
        assert generate.call_count == 3
        voices_used = [call.args[4] for call in generate.call_args_list]
        assert voices_used == ["voiceA", "voiceB", "voiceA"]
        # Concatenation receives the per-line paths repeated in order
        ordered_paths, output_filename = concat.call_args[0]
        assert ordered_paths == line_files * 2
        assert output_filename.startswith("conv_3lines_")
        # Per-line sources and sidecars are removed
        for path in line_files:
            assert not path.exists()
            assert not path.with_name(path.name + ".words.json").exists()

    def test_generate_conversation_with_sequence(self, tts_service, audio_dir, mocker):
        """With steps, each line is synthesized per distinct speed and ordered per step."""
        from src.utils.dialogue_utils import parse_dialogue
        from src.utils.sequence_utils import parse_sequence

        created = []

        def fake_generate(text, lang, slow_flag, engine=None, voice=None):
            path = audio_dir / f"{'slow' if slow_flag else 'norm'}_{len(created)}.mp3"
            path.write_bytes(b"x")
            created.append(path)
            return path, 4.0 if slow_flag else 2.0

        generate = mocker.patch.object(tts_service, "generate_audio", side_effect=fake_generate)
        output = audio_dir / "out.mp3"
        concat = mocker.patch.object(tts_service, "concatenate_audio", return_value=output)

        dialogue = parse_dialogue("Hello\nHi")
        steps = parse_sequence("2n,1s")
        result_path, duration = tts_service.generate_conversation(
            dialogue, voices=("voiceA", "voiceB"), steps=steps
        )

        assert result_path == output
        # 2 normal plays of (2+2)s + 1 slow play of (4+4)s
        assert duration == pytest.approx(2 * 4.0 + 1 * 8.0)
        # One synthesis per line per distinct speed
        assert generate.call_count == 4
        normal_lines, slow_lines = created[:2], created[2:]
        ordered_paths, output_filename = concat.call_args[0]
        assert ordered_paths == normal_lines + normal_lines + slow_lines
        assert output_filename.startswith("conv_2lines_2n-1s_")
        for path in created:
            assert not path.exists()

    def test_generate_sequence_single_speed_synthesizes_once(self, tts_service, audio_dir, mocker):
        """An all-normal sequence only synthesizes one audio."""
        from src.utils.sequence_utils import parse_sequence

        normal = audio_dir / "normal.mp3"
        normal.write_bytes(b"n")
        generate = mocker.patch.object(
            tts_service, "generate_audio", return_value=(normal, 2.0)
        )
        concat = mocker.patch.object(
            tts_service, "concatenate_audio", return_value=audio_dir / "out.mp3"
        )

        _, duration = tts_service.generate_sequence("hi", parse_sequence("2n,3n"))

        assert generate.call_count == 1
        assert duration == pytest.approx(5 * 2.0)
        assert concat.call_args[0][0] == [normal] * 5

    def test_generate_sequence_cleans_up_on_failure(self, tts_service, audio_dir, mocker):
        """Sources are removed even when concatenation fails."""
        from src.utils.sequence_utils import parse_sequence

        normal = audio_dir / "normal.mp3"
        normal.write_bytes(b"n")
        mocker.patch.object(tts_service, "generate_audio", return_value=(normal, 2.0))
        mocker.patch.object(
            tts_service, "concatenate_audio", side_effect=Exception("boom")
        )

        with pytest.raises(Exception, match="Failed to generate sequence audio"):
            tts_service.generate_sequence("hi", parse_sequence("2n"))

        assert not normal.exists()

    def test_cleanup_old_files(self, tts_service, audio_dir):
        """Test cleaning up old audio files."""
        # Create test files
        old_file = audio_dir / "old_file.mp3"
        old_sidecar = audio_dir / "old_file.mp3.words.json"
        new_file = audio_dir / "new_file.mp3"
        old_file.touch()
        old_sidecar.touch()
        new_file.touch()

        # Make old files appear old
        import time
        import os
        old_time = time.time() - (25 * 3600)  # 25 hours ago
        os.utime(old_file, times=(old_time, old_time))
        os.utime(old_sidecar, times=(old_time, old_time))

        # Run cleanup
        removed_count = tts_service.cleanup_old_files(max_age_hours=24)

        assert removed_count == 2
        assert not old_file.exists()
        assert not old_sidecar.exists()
        assert new_file.exists()


class TestGenerateAudioCaching:
    """generate_audio reuses speech it has already synthesized."""

    @pytest.fixture
    def cached_service(self, tts_service):
        """The service with its cache on - the suite runs with it off."""
        tts_service.cache.enabled = True
        return tts_service

    @pytest.fixture
    def synthesis(self, cached_service, mocker):
        """Count synthesis calls, writing a real file each time like the engine does."""
        from src.services.tts_engines import TTSEngine, TTSEngineFactory

        engine = TTSEngineFactory.get_engine(TTSEngine.GTTS, cached_service.audio_dir, "mp3")
        calls = []

        def fake_generate(text, language="en", slow=False, voice=None):
            path = engine.new_output_path(text)
            path.write_bytes(f"AUDIO:{text}|{language}|{slow}|{voice}".encode())
            save_word_boundaries(path, [{"word": text.split()[0], "start": 0.0, "end": 0.4}])
            calls.append((text, language, slow, voice))
            return path, 2.5

        mocker.patch.object(engine, "generate", side_effect=fake_generate)
        return calls

    def test_same_request_twice_synthesizes_once(self, cached_service, synthesis, sample_text):
        from src.services.tts_engines import TTSEngine

        first_path, first_duration = cached_service.generate_audio(
            sample_text, "en", False, engine=TTSEngine.GTTS
        )
        second_path, second_duration = cached_service.generate_audio(
            sample_text, "en", False, engine=TTSEngine.GTTS
        )

        assert len(synthesis) == 1
        assert second_duration == first_duration == 2.5
        # A copy of its own, not the same file handed out twice
        assert second_path != first_path
        assert second_path.read_bytes() == first_path.read_bytes()

    def test_cached_audio_is_named_like_generated_audio(self, cached_service, synthesis, sample_text):
        from src.services.tts_engines import TTSEngine

        first_path, _ = cached_service.generate_audio(sample_text, "en", False, engine=TTSEngine.GTTS)
        second_path, _ = cached_service.generate_audio(sample_text, "en", False, engine=TTSEngine.GTTS)

        assert second_path.parent == first_path.parent
        assert second_path.name.startswith("gtts_")
        assert second_path.suffix == first_path.suffix

    def test_word_timings_come_back_too(self, cached_service, synthesis, sample_text):
        """Without the sidecar, highlighting silently falls back to uniform spacing."""
        from src.services.tts_engines import TTSEngine

        first_path, _ = cached_service.generate_audio(sample_text, "en", False, engine=TTSEngine.GTTS)
        expected = json.loads(word_boundaries_path(first_path).read_text())

        second_path, _ = cached_service.generate_audio(sample_text, "en", False, engine=TTSEngine.GTTS)

        assert json.loads(word_boundaries_path(second_path).read_text()) == expected

    def test_deleting_the_returned_file_keeps_the_entry(self, cached_service, synthesis, sample_text):
        """Callers such as generate_conversation delete what they are given."""
        from src.services.tts_engines import TTSEngine

        first_path, _ = cached_service.generate_audio(sample_text, "en", False, engine=TTSEngine.GTTS)
        first_path.unlink()
        word_boundaries_path(first_path).unlink()

        second_path, duration = cached_service.generate_audio(
            sample_text, "en", False, engine=TTSEngine.GTTS
        )

        assert len(synthesis) == 1
        assert duration == 2.5
        assert second_path.exists()

    @pytest.mark.parametrize("changed", [
        {"language": "ja"},
        {"slow": True},
        {"voice": "other-voice"},
        {"text": "Something else entirely."},
    ])
    def test_a_different_request_is_synthesized_afresh(self, cached_service, synthesis, sample_text, changed):
        from src.services.tts_engines import TTSEngine

        request = dict(text=sample_text, language="en", slow=False, voice="a-voice")
        cached_service.generate_audio(engine=TTSEngine.GTTS, **request)
        cached_service.generate_audio(engine=TTSEngine.GTTS, **{**request, **changed})

        assert len(synthesis) == 2

    def test_cache_off_synthesizes_every_time(self, cached_service, synthesis, sample_text):
        from src.services.tts_engines import TTSEngine

        cached_service.cache.enabled = False
        cached_service.generate_audio(sample_text, "en", False, engine=TTSEngine.GTTS)
        cached_service.generate_audio(sample_text, "en", False, engine=TTSEngine.GTTS)

        assert len(synthesis) == 2

    def test_a_repeated_conversation_line_is_synthesized_once(
        self, cached_service, synthesis, audio_dir, mocker
    ):
        """The everyday payoff: a speaker saying the same thing twice."""
        from src.services.tts_engines import TTSEngine
        from src.utils.dialogue_utils import parse_dialogue

        mocker.patch.object(cached_service, "concatenate_audio", return_value=audio_dir / "out.mp3")
        dialogue = parse_dialogue("Good morning\nGood morning")

        _, duration = cached_service.generate_conversation(
            dialogue, engine=TTSEngine.GTTS, voices=("voiceA", "voiceA")
        )

        assert len(synthesis) == 1
        assert duration == pytest.approx(2 * 2.5)
