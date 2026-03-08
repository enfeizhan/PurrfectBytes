"""Unit tests for font utilities."""

import pytest
from pathlib import Path
from unittest.mock import patch, MagicMock

from src.utils.font_utils import load_font, find_best_font_for_text


class TestFontUtils:
    """Test font utility functions."""

    def test_load_font_returns_font_object(self):
        """Test load_font returns a usable font object."""
        font = load_font(48)
        assert font is not None
        # Font should have a getbbox or textbbox-like interface
        # (either a TrueType font or default bitmap font)

    def test_load_font_different_sizes(self):
        """Test load_font with different sizes."""
        small_font = load_font(12)
        large_font = load_font(72)
        assert small_font is not None
        assert large_font is not None



    def test_find_best_font_for_text_english(self):
        """Test finding best font for English text."""
        font = find_best_font_for_text("Hello World", 48)
        assert font is not None

    def test_find_best_font_for_text_empty_string(self):
        """Test finding best font for empty string."""
        font = find_best_font_for_text("", 48)
        assert font is not None  # Should still return a usable font

    def test_find_best_font_for_text_mixed_chars(self):
        """Test finding best font for mixed character text."""
        font = find_best_font_for_text("Hello 世界 123", 48)
        assert font is not None

    @patch('src.utils.font_utils.ImageFont.load_default')
    @patch('src.utils.font_utils._get_system_font_directories', return_value=[])
    @patch('src.utils.font_utils.ImageFont.truetype', side_effect=OSError("Font not found"))
    def test_load_font_fallback(self, mock_truetype, mock_dirs, mock_default):
        """Test load_font falls back to default when no TrueType fonts available."""
        mock_default.return_value = MagicMock()
        font = load_font(48)
        assert font is not None
        mock_default.assert_called_once()

    def test_load_font_reasonable_default_size(self):
        """Test that default font size parameter works."""
        font = load_font()  # Should use default size
        assert font is not None
