"""Character-level timing computation shared by TTS and video generation.

Timing comes from two sources, in order of preference:

1. Real word-level timestamps captured during Edge TTS synthesis and stored in a
   sidecar ``<audio>.words.json`` file next to the audio. Characters inside each
   word are interpolated across the word's duration; characters between words
   (spaces, punctuation, unmatched words) bridge the gap between neighbors.
2. Uniform spacing across the audio duration (the historical behavior), used
   when no sidecar exists or the boundaries can't be matched to the text.
"""

import json
from pathlib import Path
from typing import List, Optional

from src.config.settings import VIDEO_CONFIG
from src.models.schemas import CharacterTiming
from src.utils.logger import get_logger

logger = get_logger(__name__)

LEAD_TIME = VIDEO_CONFIG["lead_time"]
OVERLAP_DURATION = VIDEO_CONFIG["overlap_duration"]


def word_boundaries_path(audio_path: Path) -> Path:
    """Sidecar file holding word-level timestamps for an audio file."""
    audio_path = Path(audio_path)
    return audio_path.with_name(audio_path.name + ".words.json")


def save_word_boundaries(audio_path: Path, boundaries: List[dict]) -> None:
    """Persist word boundaries next to the audio file (best effort)."""
    if not boundaries:
        return
    try:
        word_boundaries_path(audio_path).write_text(json.dumps(boundaries))
    except OSError as e:
        logger.warning(f"Could not save word boundaries for {Path(audio_path).name}: {e}")


def load_word_boundaries(audio_path: Path) -> Optional[List[dict]]:
    """Load word boundaries for an audio file, or None if unavailable."""
    path = word_boundaries_path(audio_path)
    if not path.exists():
        return None
    try:
        boundaries = json.loads(path.read_text())
        return boundaries or None
    except (OSError, ValueError) as e:
        logger.warning(f"Could not read word boundaries {path.name}: {e}")
        return None


def compute_character_timings(
    text: str,
    duration: float,
    word_boundaries: Optional[List[dict]] = None,
    lead_time: float = LEAD_TIME,
    overlap_duration: float = OVERLAP_DURATION,
) -> List[CharacterTiming]:
    """Compute per-character highlight timing for a text/audio pair."""
    if not text:
        return []

    if word_boundaries:
        timings = _timings_from_word_boundaries(
            text, duration, word_boundaries, lead_time, overlap_duration
        )
        if timings is not None:
            return timings
        logger.info("Word boundaries did not match text, falling back to uniform timing")

    return _uniform_timings(text, duration, lead_time, overlap_duration)


def _timings_from_word_boundaries(
    text: str,
    duration: float,
    boundaries: List[dict],
    lead_time: float,
    overlap_duration: float,
) -> Optional[List[CharacterTiming]]:
    """Map word timestamps onto character positions in the displayed text."""
    spans = []  # (start_index, end_index, start_time, end_time)
    cursor = 0
    for wb in boundaries:
        word = str(wb.get("word", ""))
        if not word:
            continue
        idx = text.find(word, cursor)
        if idx == -1:
            idx = text.lower().find(word.lower(), cursor)
        if idx == -1:
            continue
        spans.append((idx, idx + len(word), float(wb["start"]), float(wb["end"])))
        cursor = idx + len(word)

    # If most boundaries couldn't be located (text was normalized for TTS,
    # unusual punctuation, ...), the mapping isn't trustworthy.
    if not spans or len(spans) < max(1, len(boundaries) // 2):
        return None

    starts: List[Optional[float]] = [None] * len(text)
    ends: List[Optional[float]] = [None] * len(text)

    # Interpolate characters within each matched word
    for start_idx, end_idx, t0, t1 in spans:
        n = end_idx - start_idx
        step = (t1 - t0) / n if n else 0.0
        for k in range(n):
            starts[start_idx + k] = t0 + k * step
            ends[start_idx + k] = t0 + (k + 1) * step

    # Bridge unmatched characters across the gap between their neighbors
    prev_end = 0.0
    i = 0
    while i < len(text):
        if starts[i] is None:
            j = i
            while j < len(text) and starts[j] is None:
                j += 1
            next_start = starts[j] if j < len(text) else duration
            step = max(next_start - prev_end, 0.0) / (j - i)
            for k in range(i, j):
                starts[k] = prev_end + (k - i) * step
                ends[k] = prev_end + (k - i + 1) * step
            i = j
        else:
            prev_end = ends[i]
            i += 1

    return [
        CharacterTiming(
            char=text[i],
            start_time=max(0.0, starts[i] - lead_time),
            end_time=ends[i] + overlap_duration,
            position=i,
        )
        for i in range(len(text))
    ]


def _uniform_timings(
    text: str,
    duration: float,
    lead_time: float,
    overlap_duration: float,
) -> List[CharacterTiming]:
    """Spread characters uniformly across the audio duration (spaces count half)."""
    weights = [0.5 if c == " " else 1.0 for c in text]
    total = sum(weights) or 1.0
    rate = total / duration if duration > 0 else 1.0

    timings = []
    pos = 0.0
    for i, char in enumerate(text):
        start = max(0.0, pos / rate - lead_time)
        end = (pos + weights[i]) / rate + overlap_duration
        timings.append(
            CharacterTiming(char=char, start_time=start, end_time=end, position=i)
        )
        pos += weights[i]
    return timings
