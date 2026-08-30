"""Tests for the saved text source store."""

import pytest

from src.services.source_store import SourceStore


@pytest.fixture
def store(temp_dir, monkeypatch):
    monkeypatch.setattr(
        "src.config.settings.SAVED_SOURCES_FILE",
        str(temp_dir / "saved_sources.json"),
    )
    return SourceStore()


class TestSourceStore:
    def test_empty_store_lists_nothing(self, store):
        assert store.list_sources() == []

    def test_add_and_get_round_trip(self, store):
        source = store.add_source("Shin Kanzen Master N1", "From the textbook 新完全マスター N1.")
        assert source["name"] == "Shin Kanzen Master N1"
        assert source["credit"] == "From the textbook 新完全マスター N1."
        assert store.get_source(source["id"]) == source
        assert store.list_sources() == [source]

    def test_add_strips_whitespace(self, store):
        source = store.add_source("  A Podcast  ", "  Credit line.  ")
        assert source["name"] == "A Podcast"
        assert source["credit"] == "Credit line."

    @pytest.mark.parametrize("name,credit", [("", "credit"), ("name", ""), ("  ", "credit")])
    def test_add_rejects_empty_fields(self, store, name, credit):
        with pytest.raises(ValueError):
            store.add_source(name, credit)

    def test_delete(self, store):
        source = store.add_source("Book", "From a book.")
        assert store.delete_source(source["id"]) is True
        assert store.list_sources() == []
        assert store.delete_source(source["id"]) is False

    def test_get_unknown_id(self, store):
        assert store.get_source("nope") is None

    def test_corrupt_file_yields_empty_list(self, store, temp_dir):
        (temp_dir / "saved_sources.json").write_text("{not json")
        assert store.list_sources() == []
