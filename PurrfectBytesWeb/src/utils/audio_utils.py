"""Lightweight audio helpers shared across services."""

from pathlib import Path

from src.utils.logger import get_logger

logger = get_logger(__name__)


def get_audio_duration(audio_path: Path) -> float:
    """Get audio duration in seconds without decoding the full signal."""
    audio_path = Path(audio_path)

    try:
        from mutagen import File as MutagenFile

        info = MutagenFile(str(audio_path))
        if info is not None and info.info is not None and info.info.length:
            return float(info.info.length)
    except Exception as e:
        logger.debug(f"mutagen could not read duration of {audio_path.name}: {e}")

    try:
        from pydub import AudioSegment

        return len(AudioSegment.from_file(str(audio_path))) / 1000.0
    except Exception as e:
        logger.warning(f"Could not get audio duration for {audio_path.name}: {e}")
        # Rough estimate from file size as a last resort
        return len(audio_path.read_bytes()) / 16000
