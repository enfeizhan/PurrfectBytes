"""Persistent store for saved text sources (used for YouTube description credits)."""

import json
import uuid
from datetime import datetime
from pathlib import Path
from typing import List, Optional

from src.utils.logger import get_logger

logger = get_logger(__name__)


class SourceStore:
    """
    Small JSON-file-backed store of text sources.

    Each source is {"id", "name", "credit", "created_at"} where `credit` is the
    exact sentence inserted verbatim into a generated YouTube description.
    """

    def _store_path(self) -> Path:
        # Read from settings at call time so tests can monkeypatch the path.
        from src.config.settings import SAVED_SOURCES_FILE

        return Path(SAVED_SOURCES_FILE)

    def list_sources(self) -> List[dict]:
        """Return all saved sources; an unreadable or missing file yields []."""
        path = self._store_path()
        if not path.exists():
            return []
        try:
            sources = json.loads(path.read_text(encoding="utf-8"))
            if not isinstance(sources, list):
                raise ValueError("saved sources file must contain a list")
            return sources
        except (OSError, ValueError) as e:
            logger.warning(f"Could not read saved sources from {path}: {e}")
            return []

    def get_source(self, source_id: str) -> Optional[dict]:
        """Return the source with the given id, or None."""
        for source in self.list_sources():
            if source.get("id") == source_id:
                return source
        return None

    def add_source(self, name: str, credit: str) -> dict:
        """Save a new source. Raises ValueError on empty name or credit."""
        name = (name or "").strip()
        credit = (credit or "").strip()
        if not name:
            raise ValueError("Source name is required")
        if not credit:
            raise ValueError("Credit line is required")

        source = {
            "id": uuid.uuid4().hex[:8],
            "name": name,
            "credit": credit,
            "created_at": datetime.now().isoformat(timespec="seconds"),
        }
        sources = self.list_sources()
        sources.append(source)
        self._write(sources)
        return source

    def delete_source(self, source_id: str) -> bool:
        """Delete a source by id. Returns False when the id is unknown."""
        sources = self.list_sources()
        remaining = [s for s in sources if s.get("id") != source_id]
        if len(remaining) == len(sources):
            return False
        self._write(remaining)
        return True

    def _write(self, sources: List[dict]) -> None:
        path = self._store_path()
        path.write_text(
            json.dumps(sources, ensure_ascii=False, indent=2), encoding="utf-8"
        )
