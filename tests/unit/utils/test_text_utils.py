"""Unit tests for text utilities."""

import pytest
from PIL import Image, ImageDraw, ImageFont

from src.utils.text_utils import (
    is_cjk_character,
    has_cjk_characters,
    wrap_text_for_video,
    clean_text_for_tts,
    estimate_reading_time,
    truncate_text
)


class TestTextUtils:
    """Test text utility functions."""
    
    def test_is_cjk_character(self):
        """Test CJK character detection."""
        # Test CJK characters
        assert is_cjk_character('中') is True  # Chinese
        assert is_cjk_character('あ') is True  # Hiragana
        assert is_cjk_character('ア') is True  # Katakana
        assert is_cjk_character('한') is True  # Hangul
        
        # Test non-CJK characters
        assert is_cjk_character('a') is False  # Latin
        assert is_cjk_character('1') is False  # Number
        assert is_cjk_character(' ') is False  # Space
        assert is_cjk_character('!') is False  # Punctuation
    
    def test_has_cjk_characters(self):
        """Test text CJK detection."""
        # Text with CJK characters
        assert has_cjk_characters('Hello 世界') is True
        assert has_cjk_characters('こんにちは') is True
        assert has_cjk_characters('안녕하세요') is True
        
        # Text without CJK characters
        assert has_cjk_characters('Hello World') is False
        assert has_cjk_characters('123 ABC') is False
        assert has_cjk_characters('') is False
    
    def test_wrap_latin_text_for_video(self):
        """Test text wrapping for Latin text."""
        # Create a mock font and draw object
        img = Image.new('RGB', (800, 600))
        draw = ImageDraw.Draw(img)
        font = ImageFont.load_default()
        
        # Test normal wrapping
        text = "This is a long sentence that should be wrapped into multiple lines"
        lines = wrap_text_for_video(text, 200, font, draw, padding=20)
        
        assert len(lines) > 1  # Should be wrapped
        assert all(isinstance(line, str) for line in lines)
        
        # Test short text
        short_text = "Short"
        lines = wrap_text_for_video(short_text, 400, font, draw)
        assert len(lines) == 1
        assert lines[0] == short_text
    
    def test_wrap_cjk_text_for_video(self, sample_japanese_text):
        """Test text wrapping for CJK text."""
        img = Image.new('RGB', (400, 300))  # Narrow width
        draw = ImageDraw.Draw(img)
        font = ImageFont.load_default()
        
        lines = wrap_text_for_video(sample_japanese_text, 100, font, draw, padding=20)
        
        # Should wrap character by character
        assert len(lines) > 1
        assert all(isinstance(line, str) for line in lines)
        
        # Total characters should be preserved
        total_chars = sum(len(line) for line in lines)
        assert total_chars == len(sample_japanese_text)
    
    def test_wrap_text_empty_input(self):
        """Test text wrapping with empty input."""
        img = Image.new('RGB', (800, 600))
        draw = ImageDraw.Draw(img)
        font = ImageFont.load_default()
        
        lines = wrap_text_for_video("", 400, font, draw)
        assert len(lines) == 1
        assert lines[0] == ""
    
    def test_clean_text_for_tts(self):
        """Test text cleaning for TTS."""
        # Test whitespace normalization
        text = "Hello    world\n\ntest"
        cleaned = clean_text_for_tts(text)
        assert cleaned == "Hello world test"
        
        # Test quote replacement
        text = '"Hello" and \'world\''
        cleaned = clean_text_for_tts(text)
        assert cleaned == '"Hello" and \'world\''
        
        # Test ellipsis replacement
        text = "Hello… world"
        cleaned = clean_text_for_tts(text)
        assert cleaned == "Hello... world"
        
        # Test dash replacement
        text = "Hello—world–test"
        cleaned = clean_text_for_tts(text)
        assert cleaned == "Hello-world-test"
        
        # Test empty and whitespace-only text
        assert clean_text_for_tts("") == ""
        assert clean_text_for_tts("   ") == ""
    
    def test_estimate_reading_time(self):
        """Test reading time estimation."""
        # Test with default WPM (200)
        text = "This is a test sentence with ten words exactly here."  # 10 words
        time_seconds = estimate_reading_time(text)
        expected_time = (10 / 200) * 60  # Should be 3 seconds
        assert abs(time_seconds - expected_time) < 0.1
        
        # Test with custom WPM
        time_seconds = estimate_reading_time(text, words_per_minute=120)
        expected_time = (10 / 120) * 60  # Should be 5 seconds
        assert abs(time_seconds - expected_time) < 0.1
        
        # Test empty text
        assert estimate_reading_time("") == 0
    
    def test_truncate_text(self):
        """Test text truncation."""
        # Test text shorter than limit
        short_text = "Short text"
        assert truncate_text(short_text, max_length=100) == short_text
        
        # Test text longer than limit - should truncate at word boundary
        long_text = "This is a very long text that should be truncated at word boundaries"
        truncated = truncate_text(long_text, max_length=30)
        assert len(truncated) <= 33  # 30 + "..." = 33
        assert truncated.endswith('...')
        assert not truncated.startswith('...')  # Should not start with ellipsis
        
        # Test text that can't be truncated at word boundary
        no_spaces = "a" * 50
        truncated = truncate_text(no_spaces, max_length=30)
        assert len(truncated) == 33  # 30 + "..." = 33
        assert truncated.endswith('...')
        
        # Test edge case with max_length 0
        assert truncate_text("any text", max_length=0) == "..."