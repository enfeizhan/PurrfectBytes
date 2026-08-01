"""Unit tests for TTS service."""

import json

import pytest
from pathlib import Path
from unittest.mock import MagicMock, patch

from src.services.audio_timing import compute_character_timings, word_boundaries_path
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
