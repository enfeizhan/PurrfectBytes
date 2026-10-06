"""Unit tests for the synthesized-speech cache."""

import json
import os

import pytest

from src.services.audio_timing import word_boundaries_path
from src.services.tts_cache import TTSCache


@pytest.fixture
def cache(tmp_path):
    """A cache with its own directory, small enough to test eviction."""
    return TTSCache(tmp_path / "cache", audio_format="mp3", max_entries=3)


def _spoken(tmp_path, name="edge_hello_abc12345.mp3", boundaries=None):
    """A generated audio file, optionally with its word-timing sidecar."""
    audio = tmp_path / name
    audio.write_bytes(b"ID3\x04" + name.encode())
    if boundaries is not None:
        word_boundaries_path(audio).write_text(json.dumps(boundaries))
    return audio


class TestCacheKey:
    """Every input that changes the audio has to change the key."""

    BASE = dict(text="hello", language="en", slow=False, engine="edge", voice="en-US-AriaNeural")

    @pytest.mark.parametrize("field,value", [
        ("text", "goodbye"),
        ("language", "ja"),
        ("slow", True),
        ("engine", "gtts"),
        ("voice", "en-GB-SoniaNeural"),
    ])
    def test_key_changes_with_input(self, cache, field, value):
        assert cache.key(**{**self.BASE, field: value}) != cache.key(**self.BASE)

    def test_key_is_stable_for_identical_input(self, cache):
        assert cache.key(**self.BASE) == cache.key(**self.BASE)

    def test_key_changes_with_audio_format(self, tmp_path):
        mp3 = TTSCache(tmp_path / "c", audio_format="mp3")
        wav = TTSCache(tmp_path / "c", audio_format="wav")
        assert mp3.key(**self.BASE) != wav.key(**self.BASE)

    def test_missing_voice_is_not_the_same_as_a_named_one(self, cache):
        assert cache.key(**{**self.BASE, "voice": None}) != cache.key(**self.BASE)


class TestStoreAndFetch:
    """Round-tripping audio through the cache."""

    def test_fetch_on_empty_cache_is_a_miss(self, cache, tmp_path):
        destination = tmp_path / "out.mp3"

        assert cache.fetch("deadbeef", destination) is None
        assert not destination.exists()

    def test_stored_audio_comes_back_as_a_copy(self, cache, tmp_path):
        source = _spoken(tmp_path, boundaries=[{"word": "hello", "start": 0.0, "end": 0.5}])
        cache.store("k1", source, 1.25)

        destination = tmp_path / "again.mp3"
        duration = cache.fetch("k1", destination)

        assert duration == 1.25
        assert destination.exists()
        assert destination != source
        assert destination.read_bytes() == source.read_bytes()

    def test_word_timings_travel_with_the_audio(self, cache, tmp_path):
        boundaries = [{"word": "hello", "start": 0.0, "end": 0.5}]
        source = _spoken(tmp_path, boundaries=boundaries)
        cache.store("k1", source, 1.0)

        destination = tmp_path / "again.mp3"
        cache.fetch("k1", destination)

        assert json.loads(word_boundaries_path(destination).read_text()) == boundaries

    def test_audio_without_timings_still_caches(self, cache, tmp_path):
        source = _spoken(tmp_path)
        cache.store("k1", source, 2.0)

        destination = tmp_path / "again.mp3"

        assert cache.fetch("k1", destination) == 2.0
        assert not word_boundaries_path(destination).exists()

    def test_entry_survives_the_caller_deleting_what_it_received(self, cache, tmp_path):
        """Callers own their copy - several of them delete it when done."""
        source = _spoken(tmp_path, boundaries=[{"word": "hi", "start": 0.0, "end": 0.2}])
        cache.store("k1", source, 1.0)

        first = tmp_path / "first.mp3"
        cache.fetch("k1", first)
        first.unlink()
        word_boundaries_path(first).unlink()

        second = tmp_path / "second.mp3"
        assert cache.fetch("k1", second) == 1.0
        assert second.read_bytes() == source.read_bytes()

    def test_deleting_the_generated_file_does_not_empty_the_cache(self, cache, tmp_path):
        source = _spoken(tmp_path)
        cache.store("k1", source, 1.0)
        source.unlink()

        assert cache.fetch("k1", tmp_path / "out.mp3") == 1.0


class TestFailureHandling:
    """Nothing here is load-bearing: every failure is just a miss."""

    def test_storing_a_file_that_is_not_there_is_survivable(self, cache, tmp_path):
        cache.store("k1", tmp_path / "never_written.mp3", 1.0)

        assert cache.fetch("k1", tmp_path / "out.mp3") is None

    def test_audio_without_metadata_is_a_miss(self, cache, tmp_path):
        """Metadata is the commit marker, so a half-written entry is not used."""
        source = _spoken(tmp_path)
        cache.store("k1", source, 1.0)
        cache._meta_path("k1").unlink()

        assert cache.fetch("k1", tmp_path / "out.mp3") is None

    def test_unreadable_metadata_is_a_miss(self, cache, tmp_path):
        source = _spoken(tmp_path)
        cache.store("k1", source, 1.0)
        cache._meta_path("k1").write_text("{not json")

        assert cache.fetch("k1", tmp_path / "out.mp3") is None

    def test_metadata_without_audio_leaves_no_destination_behind(self, cache, tmp_path):
        """A renderer would treat a half-copied file as real audio."""
        source = _spoken(tmp_path)
        cache.store("k1", source, 1.0)
        cache._audio_path("k1").unlink()

        destination = tmp_path / "out.mp3"
        assert cache.fetch("k1", destination) is None
        assert not destination.exists()

    def test_disabled_cache_does_nothing(self, tmp_path):
        disabled = TTSCache(tmp_path / "cache", enabled=False)
        source = _spoken(tmp_path)

        disabled.store("k1", source, 1.0)

        assert disabled.fetch("k1", tmp_path / "out.mp3") is None
        assert not (tmp_path / "cache").exists()


class TestEviction:
    """The directory is bounded, oldest use first."""

    def test_least_recently_used_entry_goes_first(self, cache, tmp_path):
        source = _spoken(tmp_path)
        for index, key in enumerate(["k1", "k2", "k3"]):
            cache.store(key, source, 1.0)
            # Distinct, ordered timestamps: real stores are seconds apart.
            stamp = 1_000_000 + index
            os.utime(cache._meta_path(key), (stamp, stamp))

        # Using k1 makes k2 the oldest.
        cache._touch("k1")
        cache.store("k4", source, 1.0)

        assert cache.fetch("k2", tmp_path / "a.mp3") is None
        for survivor in ("k1", "k3", "k4"):
            assert cache.fetch(survivor, tmp_path / f"{survivor}.mp3") == 1.0

    def test_eviction_takes_the_whole_entry(self, cache, tmp_path):
        source = _spoken(tmp_path, boundaries=[{"word": "hi", "start": 0.0, "end": 0.2}])
        cache.store("k1", source, 1.0)
        cache._evict("k1")

        assert not cache._meta_path("k1").exists()
        assert not cache._audio_path("k1").exists()
        assert not word_boundaries_path(cache._audio_path("k1")).exists()

    def test_clear_removes_every_entry(self, cache, tmp_path):
        source = _spoken(tmp_path)
        cache.store("k1", source, 1.0)
        cache.store("k2", source, 1.0)

        assert cache.clear() == 2
        assert cache.fetch("k1", tmp_path / "a.mp3") is None
        assert cache.fetch("k2", tmp_path / "b.mp3") is None

    def test_clear_on_a_cache_never_used_is_harmless(self, tmp_path):
        assert TTSCache(tmp_path / "missing").clear() == 0
