"""Content-addressed cache for synthesized speech.

Synthesis is the slowest step in making a video, and it is repeatable: the same
text, in the same voice, at the same speed, from the same engine says the same
words with the same timing. So re-rendering something already spoken can be a
file copy instead of a network round-trip - which covers most renders in
practice, because the usual workflow is to adjust a video and render the same
sentence again.

Repeatable is not bit-exact. Edge TTS was measured returning the same byte
length, duration and word boundaries for identical input, with the decoded
waveform differing by about -29 dB between calls. So an entry is one particular
recording, not a stand-in for the engine: reusing it makes repeat renders of a
sentence sound exactly alike, where re-synthesizing would vary slightly.

Entries are copied out, never handed over. Callers own the path they are given
and several of them delete it when finished (see
``TTSService.generate_conversation``), which would otherwise destroy the cache
entry itself.

One entry is three files, written in this order so a reader racing a writer can
never mistake a half-written entry for a complete one - the metadata is the
commit marker, and a lookup that cannot read it is simply a miss:

    <key>.mp3              the audio
    <key>.mp3.words.json   Edge word timestamps, when the engine produced them
    <key>.meta.json        duration and bookkeeping; written last

The cache sits beside the generated audio (``/tmp/audio_files/tts_cache``), so
a reboot flushes it along with everything else in ``/tmp``. Nothing here is
load-bearing: every failure path degrades to synthesizing as before.
"""

import hashlib
import json
import os
import shutil
import time
import uuid
from pathlib import Path
from typing import Optional

from src.services.audio_timing import word_boundaries_path
from src.utils.logger import get_logger

logger = get_logger(__name__)

# Bump this to invalidate every existing entry - warranted when a change makes
# audio stored by an older version wrong to reuse.
CACHE_FORMAT_VERSION = 1

# Entries run a few tens of KB, so this bounds the directory rather than the
# disk. The least recently used entries are evicted first.
DEFAULT_MAX_ENTRIES = 500

META_SUFFIX = ".meta.json"


def _unlink_quietly(path: Path) -> None:
    """Delete a file if it is there, ignoring anything that goes wrong."""
    try:
        path.unlink()
    except OSError:
        pass


class TTSCache:
    """Reusable copies of generated speech, keyed by what produced them."""

    def __init__(
        self,
        cache_dir: Path,
        audio_format: str = "mp3",
        max_entries: int = DEFAULT_MAX_ENTRIES,
        enabled: bool = True,
    ):
        self.cache_dir = Path(cache_dir)
        self.audio_format = audio_format
        self.max_entries = max_entries
        self.enabled = enabled

    def key(
        self,
        text: str,
        language: str,
        slow: bool,
        engine: str,
        voice: Optional[str] = None,
    ) -> str:
        """Fingerprint of everything that can change the synthesized audio.

        The engine is the one that will actually run, not the one that was
        asked for - they differ when an unavailable engine falls back to gTTS,
        and caching that under the requested engine's name would serve the
        wrong voice once the requested engine came back.
        """
        material = "\x00".join([
            str(CACHE_FORMAT_VERSION),
            self.audio_format,
            engine,
            language,
            voice or "",
            "slow" if slow else "normal",
            text,
        ])
        return hashlib.sha256(material.encode("utf-8")).hexdigest()

    def fetch(self, key: str, destination: Path) -> Optional[float]:
        """Copy cached audio to `destination` and return its duration.

        Returns None on a miss, which includes every kind of failure - the
        caller then synthesizes exactly as it would without a cache.
        """
        if not self.enabled:
            return None

        destination = Path(destination)
        try:
            metadata = json.loads(self._meta_path(key).read_text())
            duration = float(metadata["duration"])
        except (OSError, ValueError, TypeError, KeyError):
            return None

        audio_path = self._audio_path(key)
        try:
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(audio_path, destination)
            sidecar = word_boundaries_path(audio_path)
            if sidecar.exists():
                shutil.copyfile(sidecar, word_boundaries_path(destination))
        except OSError as e:
            # A half-copied destination would look like real audio to the
            # renderer, so take it away before reporting the miss.
            logger.warning(f"Could not reuse cached audio {key[:8]}: {e}")
            _unlink_quietly(destination)
            _unlink_quietly(word_boundaries_path(destination))
            return None

        self._touch(key)
        logger.info(
            f"Reused cached audio {key[:8]} ({duration:.2f}s) as {destination.name}"
        )
        return duration

    def store(self, key: str, audio_path: Path, duration: float) -> None:
        """Take a copy of freshly generated audio. Failures are not fatal."""
        if not self.enabled:
            return

        audio_path = Path(audio_path)
        try:
            self.cache_dir.mkdir(parents=True, exist_ok=True)
            cached_audio = self._audio_path(key)
            self._publish(audio_path.read_bytes(), cached_audio)

            sidecar = word_boundaries_path(audio_path)
            if sidecar.exists():
                self._publish(sidecar.read_bytes(), word_boundaries_path(cached_audio))

            # Last, so an interrupted store reads back as a miss rather than as
            # an entry whose audio is missing or truncated.
            self._publish(
                json.dumps({"duration": duration, "stored_at": time.time()}).encode("utf-8"),
                self._meta_path(key),
            )
        except OSError as e:
            logger.debug(f"Could not cache audio for {key[:8]}: {e}")
            return

        logger.debug(f"Cached audio {key[:8]} ({duration:.2f}s)")
        self._prune()

    def clear(self) -> int:
        """Remove every entry. Returns the number of entries removed."""
        try:
            metas = list(self.cache_dir.glob(f"*{META_SUFFIX}"))
        except OSError:
            return 0
        for meta in metas:
            self._evict(meta.name[: -len(META_SUFFIX)])
        return len(metas)

    def _audio_path(self, key: str) -> Path:
        return self.cache_dir / f"{key}.{self.audio_format}"

    def _meta_path(self, key: str) -> Path:
        return self.cache_dir / f"{key}{META_SUFFIX}"

    @staticmethod
    def _publish(data: bytes, target: Path) -> None:
        """Put `data` at `target` in one step, so no reader sees a partial file."""
        temp = target.with_name(f"{target.name}.{uuid.uuid4().hex[:8]}.part")
        try:
            temp.write_bytes(data)
            os.replace(temp, target)
        except OSError:
            _unlink_quietly(temp)
            raise

    def _touch(self, key: str) -> None:
        """Mark an entry as just used, for eviction order."""
        now = time.time()
        for path in (self._meta_path(key), self._audio_path(key)):
            try:
                os.utime(path, (now, now))
            except OSError:
                pass

    def _evict(self, key: str) -> None:
        # Metadata first: that alone makes the entry a miss, so the audio is
        # never read after it has been chosen for eviction.
        _unlink_quietly(self._meta_path(key))
        _unlink_quietly(word_boundaries_path(self._audio_path(key)))
        _unlink_quietly(self._audio_path(key))

    def _prune(self) -> None:
        """Drop the least recently used entries down to `max_entries`."""
        try:
            metas = list(self.cache_dir.glob(f"*{META_SUFFIX}"))
        except OSError:
            return
        if len(metas) <= self.max_entries:
            return

        def last_used(path: Path) -> float:
            try:
                return path.stat().st_mtime
            except OSError:
                return 0.0  # Vanished under us; first in line to be dropped.

        for meta in sorted(metas, key=last_used)[: len(metas) - self.max_entries]:
            self._evict(meta.name[: -len(META_SUFFIX)])
