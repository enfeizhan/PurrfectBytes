"""Test configuration and fixtures."""

import pytest
import tempfile
import shutil
from pathlib import Path
from unittest.mock import MagicMock

from fastapi.testclient import TestClient
from src.services.language_detection import LanguageDetectionService
from src.services.tts_service import TTSService
from src.services.video_service import VideoService
from src.models.schemas import VideoConfig


@pytest.fixture
def temp_dir():
    """Create a temporary directory for test files."""
    temp_dir = Path(tempfile.mkdtemp())
    yield temp_dir
    shutil.rmtree(temp_dir)


@pytest.fixture
def audio_dir(temp_dir):
    """Create temporary audio directory."""
    audio_dir = temp_dir / "audio_files"
    audio_dir.mkdir()
    return audio_dir


@pytest.fixture
def video_dir(temp_dir):
    """Create temporary video directory."""
    video_dir = temp_dir / "video_files"
    video_dir.mkdir()
    return video_dir


@pytest.fixture
def mock_audio_file(temp_dir):
    """Create a mock audio file for testing."""
    audio_file = temp_dir / "test_audio.mp3"
    # Create a minimal MP3-like file (just for testing)
    audio_file.write_bytes(b"MOCK_MP3_DATA" * 100)
    return audio_file


@pytest.fixture
def sample_text():
    """Sample text for testing."""
    return "Hello, this is a test message for the TTS system."


@pytest.fixture
def sample_japanese_text():
    """Sample Japanese text for testing CJK handling."""
    return "みなさんからのコメントもいつも楽しく読ませていただいています！"


@pytest.fixture
def language_service():
    """Create language detection service."""
    return LanguageDetectionService()


@pytest.fixture
def tts_service(audio_dir, monkeypatch):
    """Create TTS service with mocked directory."""
    monkeypatch.setattr("src.services.tts_service.AUDIO_DIR", audio_dir)
    return TTSService()


@pytest.fixture
def video_service(video_dir, monkeypatch):
    """Create video service with mocked directory."""
    monkeypatch.setattr("src.services.video_service.VIDEO_DIR", video_dir)
    config = VideoConfig(
        width=640, height=480, fps=12  # Smaller/faster for tests
    )
    return VideoService(config)


@pytest.fixture
def mock_gtts(mocker):
    """Mock gTTS to avoid network calls."""
    mock_tts = mocker.MagicMock()
    mock_tts_class = mocker.patch('src.services.tts_service.gTTS')
    mock_tts_class.return_value = mock_tts
    return mock_tts


@pytest.fixture
def mock_librosa(mocker):
    """Mock librosa to avoid audio processing."""
    mock_librosa = mocker.patch('src.services.tts_service.librosa')
    mock_librosa.load.return_value = ([1, 2, 3], 22050)  # Mock audio data
    mock_librosa.get_duration.return_value = 3.5  # Mock duration
    return mock_librosa


@pytest.fixture
def mock_moviepy(mocker):
    """Mock MoviePy components to avoid video processing."""
    mock_video_clip = mocker.MagicMock()
    mock_audio_clip = mocker.MagicMock()
    mock_audio_clip.duration = 3.5
    
    mocker.patch('src.services.video_service.VideoClip', return_value=mock_video_clip)
    mocker.patch('src.services.video_service.AudioFileClip', return_value=mock_audio_clip)
    
    return {
        'video_clip': mock_video_clip,
        'audio_clip': mock_audio_clip
    }


@pytest.fixture
def client():
    """Create test client for API testing."""
    # Import here to avoid circular imports
    from main import app
    return TestClient(app)


@pytest.fixture
def mock_all_external_deps(mock_gtts, mock_librosa, mock_moviepy):
    """Mock all external dependencies for integration tests."""
    return {
        'gtts': mock_gtts,
        'librosa': mock_librosa,
        'moviepy': mock_moviepy
    }