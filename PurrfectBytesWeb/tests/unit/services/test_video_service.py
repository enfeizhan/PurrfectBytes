"""Unit tests for VideoService."""

import pytest
import time
import os
from pathlib import Path
from unittest.mock import MagicMock, patch, PropertyMock

from src.services.video_service import VideoService
from src.models.schemas import VideoConfig, AudioAnalysis, CharacterTiming


class TestVideoService:
    """Test VideoService."""

    def test_init_default_config(self):
        """Test VideoService initializes with default config."""
        service = VideoService()
        assert service.config is not None
        assert service.config.width > 0
        assert service.config.height > 0
        assert service.config.fps > 0

    def test_init_custom_config(self):
        """Test VideoService with custom config."""
        config = VideoConfig(width=640, height=480, fps=12)
        service = VideoService(config)
        assert service.config.width == 640
        assert service.config.height == 480
        assert service.config.fps == 12

    def test_create_timing_map(self, video_service):
        """Test creating timing map from character timings."""
        timings = [
            CharacterTiming(char="H", position=0, start_time=0.0, end_time=0.5),
            CharacterTiming(char="i", position=1, start_time=0.3, end_time=0.8),
        ]
        timing_map = video_service._create_timing_map(timings)

        assert 0 in timing_map
        assert 1 in timing_map
        assert timing_map[0].char == "H"
        assert timing_map[1].char == "i"

    def test_get_active_characters_at_start(self, video_service):
        """Test getting active characters at the start of audio."""
        timings = [
            CharacterTiming(char="H", position=0, start_time=0.0, end_time=0.5),
            CharacterTiming(char="i", position=1, start_time=0.3, end_time=0.8),
        ]
        timing_map = video_service._create_timing_map(timings)

        active = video_service._get_active_characters(0.1, timing_map)
        assert 0 in active
        assert 1 not in active

    def test_get_active_characters_overlap(self, video_service):
        """Test getting active characters when timing overlaps."""
        timings = [
            CharacterTiming(char="H", position=0, start_time=0.0, end_time=0.5),
            CharacterTiming(char="i", position=1, start_time=0.3, end_time=0.8),
        ]
        timing_map = video_service._create_timing_map(timings)

        active = video_service._get_active_characters(0.4, timing_map)
        assert 0 in active
        assert 1 in active

    def test_get_active_characters_none_active(self, video_service):
        """Test getting active characters when none are active."""
        timings = [
            CharacterTiming(char="H", position=0, start_time=0.0, end_time=0.5),
        ]
        timing_map = video_service._create_timing_map(timings)

        active = video_service._get_active_characters(1.0, timing_map)
        assert len(active) == 0

    def test_cleanup_old_files(self, video_service, video_dir):
        """Test cleaning up old video files."""
        old_file = video_dir / "old_video.mp4"
        new_file = video_dir / "new_video.mp4"
        old_file.touch()
        new_file.touch()

        # Make one file appear old
        old_time = time.time() - (25 * 3600)  # 25 hours ago
        os.utime(old_file, times=(old_time, old_time))

        removed_count = video_service.cleanup_old_files(max_age_hours=24)
        assert removed_count >= 1
        assert not old_file.exists()
        assert new_file.exists()

    def test_cleanup_old_files_keeps_recent(self, video_service, video_dir):
        """Test cleanup keeps recent files."""
        recent_file = video_dir / "recent_video.mp4"
        recent_file.touch()

        removed_count = video_service.cleanup_old_files(max_age_hours=24)
        assert removed_count == 0
        assert recent_file.exists()

    def test_cleanup_old_files_empty_dir(self, video_service):
        """Test cleanup on empty directory."""
        removed_count = video_service.cleanup_old_files(max_age_hours=24)
        assert removed_count == 0

    def test_get_video_info_not_found(self, video_service, video_dir):
        """Test get_video_info with non-existent file."""
        non_existent = video_dir / "nonexistent.mp4"
        with pytest.raises(FileNotFoundError):
            video_service.get_video_info(non_existent)

    def test_get_video_info_success(self, video_service, video_dir):
        """Test get_video_info with existing file."""
        test_file = video_dir / "test_video.mp4"
        test_file.write_bytes(b"MOCK_VIDEO_DATA" * 100)

        with patch('src.services.video_service.VideoFileClip') as mock_clip:
            mock_instance = MagicMock()
            mock_instance.duration = 5.0
            mock_instance.__enter__ = MagicMock(return_value=mock_instance)
            mock_instance.__exit__ = MagicMock(return_value=False)
            mock_clip.return_value = mock_instance

            info = video_service.get_video_info(test_file)
            assert info["filename"] == "test_video.mp4"
            assert info["size"] > 0

    def test_concatenate_videos_empty_list(self, video_service):
        """Test concatenation with empty list raises ValueError."""
        with pytest.raises(ValueError, match="No video files provided"):
            video_service.concatenate_videos([])

    def test_concatenate_videos_missing_file(self, video_service, video_dir):
        """Test concatenation with non-existent file raises ValueError."""
        missing = video_dir / "missing.mp4"
        with pytest.raises(ValueError, match="Video file not found"):
            video_service.concatenate_videos([missing])

    def test_generate_multiple_and_concatenate_mismatched_lengths(self, video_service):
        """Test generate_multiple_and_concatenate with mismatched list lengths."""
        with pytest.raises(ValueError, match="same length"):
            video_service.generate_multiple_and_concatenate(
                texts=["Hello"],
                audio_paths=[Path("/tmp/a.mp3"), Path("/tmp/b.mp3")],
                audio_analyses=[]
            )

    def test_generate_multiple_and_concatenate_empty(self, video_service):
        """Test generate_multiple_and_concatenate with empty lists."""
        with pytest.raises(ValueError, match="No texts provided"):
            video_service.generate_multiple_and_concatenate(
                texts=[],
                audio_paths=[],
                audio_analyses=[]
            )

    def test_generate_and_repeat_negative_reps(self, video_service):
        """Test generate_and_repeat with negative repetitions."""
        dummy_path = Path("/tmp/dummy.mp3")
        dummy_analysis = AudioAnalysis(
            duration=1.0,
            character_timings=[],
            words_per_second=5.0,
            lead_time=0.3,
            overlap_duration=0.4
        )
        with pytest.raises(ValueError, match="at least 1"):
            video_service.generate_and_repeat(
                text="Test",
                single_audio_path=dummy_path,
                audio_analysis=dummy_analysis,
                repetitions=0
            )

    def test_generate_frame_returns_numpy_array(self, video_service):
        """Test that _generate_frame returns a numpy array."""
        import numpy as np

        timing_map = {}
        lines = ["Hello"]
        frame = video_service._generate_frame(0.0, "Hello", lines, timing_map, None, None)

        assert isinstance(frame, np.ndarray)
        assert frame.shape[0] == video_service.config.height
        assert frame.shape[1] == video_service.config.width
        assert frame.shape[2] == 3  # RGB

    def test_draw_text_with_highlighting_no_active(self, video_service):
        """Test drawing text with no highlighted characters."""
        from PIL import Image, ImageDraw

        img = Image.new('RGB', (video_service.config.width, video_service.config.height))
        draw = ImageDraw.Draw(img)
        active_chars = set()

        # Should not raise
        video_service._draw_text_with_highlighting(
            draw, "Hi", ["Hi"], active_chars, None, None
        )
