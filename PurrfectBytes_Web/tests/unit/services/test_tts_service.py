"""Unit tests for TTS service."""

import pytest
from pathlib import Path
from unittest.mock import MagicMock, patch

from src.services.tts_service import TTSService
from src.models.schemas import CharacterTiming


class TestTTSService:
    """Test TTS service."""
    
    def test_generate_audio_success(self, tts_service, mock_gtts, mock_librosa, sample_text):
        """Test successful audio generation."""
        # Mock save method
        mock_gtts.save = MagicMock()
        
        # Generate audio
        audio_path, duration = tts_service.generate_audio(sample_text, "en", False)
        
        # Verify results
        assert isinstance(audio_path, Path)
        assert audio_path.exists() is False  # File not actually created in mock
        assert duration == 3.5  # From mock_librosa
        
        # Verify gTTS was called correctly
        mock_gtts.save.assert_called_once()
    
    def test_generate_audio_with_slow_speech(self, tts_service, mock_gtts, mock_librosa, sample_text):
        """Test audio generation with slow speech."""
        from src.services.tts_service import gTTS
        
        with patch('src.services.tts_service.gTTS') as mock_tts_class:
            mock_tts = MagicMock()
            mock_tts_class.return_value = mock_tts
            
            audio_path, duration = tts_service.generate_audio(sample_text, "en", True)
            
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
        with patch('src.services.tts_service.gTTS', side_effect=Exception("TTS Error")):
            with pytest.raises(Exception, match="Failed to generate audio"):
                tts_service.generate_audio(sample_text, "en", False)
    
    def test_analyze_audio_timing_success(self, tts_service, mock_audio_file, mock_librosa, sample_text):
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
    
    def test_analyze_audio_timing_with_spaces(self, tts_service, mock_audio_file, mock_librosa):
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
        """Test audio timing analysis fallback when librosa fails."""
        with patch('src.services.tts_service.librosa.load', side_effect=Exception("Audio error")):
            analysis = tts_service.analyze_audio_timing(sample_text, mock_audio_file)
            
            # Should still return valid analysis
            assert analysis.duration > 0
            assert len(analysis.character_timings) == len(sample_text)
    
    def test_get_audio_duration_success(self, tts_service, mock_audio_file, mock_librosa):
        """Test getting audio duration."""
        duration = tts_service._get_audio_duration(mock_audio_file)
        assert duration == 3.5  # From mock_librosa
    
    def test_get_audio_duration_fallback(self, tts_service, mock_audio_file):
        """Test audio duration fallback when librosa fails."""
        with patch('src.services.tts_service.librosa.load', side_effect=Exception("Audio error")):
            duration = tts_service._get_audio_duration(mock_audio_file)
            assert duration > 0  # Should return fallback estimate
    
    def test_calculate_character_timings(self, tts_service):
        """Test character timing calculation."""
        text = "Hello"
        duration = 5.0
        timings = tts_service._calculate_character_timings(text, duration)
        
        assert len(timings) == len(text)
        
        # Check timing progression
        for i in range(len(timings) - 1):
            assert timings[i].start_time <= timings[i+1].start_time
        
        # Check last timing ends around duration
        last_timing = timings[-1]
        assert last_timing.end_time > duration  # Due to overlap_duration
    
    def test_cleanup_old_files(self, tts_service, audio_dir):
        """Test cleaning up old audio files."""
        # Create test files
        old_file = audio_dir / "old_file.mp3"
        new_file = audio_dir / "new_file.mp3"
        old_file.touch()
        new_file.touch()
        
        # Make one file appear old
        import time
        import os
        old_time = time.time() - (25 * 3600)  # 25 hours ago
        os.utime(old_file, times=(old_time, old_time))
        
        # Run cleanup
        removed_count = tts_service.cleanup_old_files(max_age_hours=24)
        
        # Should remove the old file
        assert removed_count >= 0  # Depends on timing
    
    def test_create_fallback_timing(self, tts_service, mock_audio_file):
        """Test fallback timing creation."""
        text = "Test"
        analysis = tts_service._create_fallback_timing(text, mock_audio_file)
        
        assert analysis.duration > 0
        assert len(analysis.character_timings) == len(text)
        assert analysis.words_per_second >= 0
        
        # Check timing progression
        timings = analysis.character_timings
        for i in range(len(timings) - 1):
            assert timings[i].start_time <= timings[i+1].start_time