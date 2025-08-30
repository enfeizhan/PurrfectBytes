"""Text processing utilities."""

from typing import List
from PIL import ImageDraw, ImageFont
from src.config.settings import CJK_UNICODE_RANGES

def is_cjk_character(char: str) -> bool:
    """Check if character is Chinese, Japanese, or Korean."""
    code = ord(char)
    return any(start <= code <= end for start, end in CJK_UNICODE_RANGES)

def has_cjk_characters(text: str) -> bool:
    """Check if text contains any CJK characters."""
    return any(is_cjk_character(char) for char in text)

def wrap_text_for_video(
    text: str, 
    width: int, 
    font: ImageFont.ImageFont, 
    draw: ImageDraw.ImageDraw, 
    padding: int = 50
) -> List[str]:
    """
    Wrap text to fit within video width with proper handling for CJK languages.
    
    Args:
        text: Text to wrap
        width: Video width in pixels
        font: Font to use for measuring
        draw: ImageDraw instance for text measurement
        padding: Padding from edges
        
    Returns:
        List of text lines that fit within the specified width
    """
    if not text:
        return [""]
    
    max_width = width - (padding * 2)
    
    if has_cjk_characters(text):
        return _wrap_cjk_text(text, max_width, font, draw)
    else:
        return _wrap_latin_text(text, max_width, font, draw)

def _wrap_cjk_text(
    text: str, 
    max_width: int, 
    font: ImageFont.ImageFont, 
    draw: ImageDraw.ImageDraw
) -> List[str]:
    """Wrap CJK text by characters."""
    lines = []
    current_line = ""
    
    for char in text:
        test_line = current_line + char
        bbox = draw.textbbox((0, 0), test_line, font=font)
        line_width = bbox[2] - bbox[0]
        
        if line_width <= max_width:
            current_line = test_line
        else:
            if current_line:
                lines.append(current_line)
            current_line = char
    
    if current_line:
        lines.append(current_line)
    
    return lines

def _wrap_latin_text(
    text: str, 
    max_width: int, 
    font: ImageFont.ImageFont, 
    draw: ImageDraw.ImageDraw
) -> List[str]:
    """Wrap Latin text by words with character fallback for long words."""
    words = text.split()
    lines = []
    current_line = []
    
    for word in words:
        test_line = ' '.join(current_line + [word])
        bbox = draw.textbbox((0, 0), test_line, font=font)
        line_width = bbox[2] - bbox[0]
        
        if line_width <= max_width:
            current_line.append(word)
        else:
            if current_line:
                lines.append(' '.join(current_line))
                current_line = [word]
            else:
                # Word is too long, split by characters
                char_line = ""
                for char in word:
                    test_char_line = char_line + char
                    bbox = draw.textbbox((0, 0), test_char_line, font=font)
                    char_width = bbox[2] - bbox[0]
                    
                    if char_width <= max_width:
                        char_line = test_char_line
                    else:
                        if char_line:
                            lines.append(char_line)
                        char_line = char
                
                if char_line:
                    current_line = [char_line]
                else:
                    current_line = []
    
    if current_line:
        lines.append(' '.join(current_line))
    
    return lines

def clean_text_for_tts(text: str) -> str:
    """Clean and prepare text for TTS processing."""
    # Remove excessive whitespace
    text = ' '.join(text.split())
    
    # Remove or replace problematic characters
    replacements = {
        '"': '"',
        '"': '"',
        ''': "'",
        ''': "'",
        '…': '...',
        '—': '-',
        '–': '-',
    }
    
    for old, new in replacements.items():
        text = text.replace(old, new)
    
    return text.strip()

def estimate_reading_time(text: str, words_per_minute: int = 200) -> float:
    """Estimate reading time for text in seconds."""
    word_count = len(text.split())
    return (word_count / words_per_minute) * 60

def truncate_text(text: str, max_length: int = 1000) -> str:
    """Truncate text to maximum length while preserving word boundaries."""
    if len(text) <= max_length:
        return text
    
    truncated = text[:max_length]
    last_space = truncated.rfind(' ')
    
    if last_space > max_length * 0.8:  # Only truncate at word boundary if close enough
        return truncated[:last_space] + '...'
    else:
        return truncated + '...'