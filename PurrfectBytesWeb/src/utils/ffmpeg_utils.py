"""FFmpeg helpers for fast, re-encode-free video operations.

Uses the ffmpeg binary bundled with imageio-ffmpeg (already a dependency via
MoviePy), so no system ffmpeg install is required.
"""

import subprocess
import tempfile
from pathlib import Path
from typing import List

import imageio_ffmpeg

from src.utils.logger import get_logger

logger = get_logger(__name__)


def concat_copy(video_paths: List[Path], output_path: Path) -> Path:
    """Concatenate videos with identical codec parameters without re-encoding.

    All inputs must share codec/resolution/fps (true for videos produced by this
    app's single encoding path). Raises RuntimeError if ffmpeg fails.
    """
    if not video_paths:
        raise ValueError("No video files provided for concatenation")

    ffmpeg = imageio_ffmpeg.get_ffmpeg_exe()
    output_path = Path(output_path)

    with tempfile.NamedTemporaryFile(
        "w", suffix=".txt", delete=False, dir=str(output_path.parent)
    ) as f:
        for path in video_paths:
            escaped = str(Path(path).resolve()).replace("'", "'\\''")
            f.write(f"file '{escaped}'\n")
        list_path = Path(f.name)

    try:
        result = subprocess.run(
            [
                ffmpeg, "-y",
                "-f", "concat", "-safe", "0",
                "-i", str(list_path),
                "-c", "copy",
                "-movflags", "+faststart",
                str(output_path),
            ],
            capture_output=True,
            timeout=600,
        )
        if result.returncode != 0:
            if output_path.exists():
                output_path.unlink()
            stderr_tail = result.stderr.decode(errors="replace")[-500:]
            raise RuntimeError(f"ffmpeg concat failed: {stderr_tail}")
        return output_path
    finally:
        list_path.unlink(missing_ok=True)


def repeat_copy(video_path: Path, repetitions: int, output_path: Path) -> Path:
    """Repeat one video N times via stream copy — near-instant regardless of N."""
    return concat_copy([video_path] * repetitions, output_path)
