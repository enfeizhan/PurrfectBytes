"""Font loading and management utilities."""

import os
import platform
from typing import Optional
from PIL import ImageFont
from src.config.settings import FONT_CONFIG

def _get_system_font_directories() -> list[str]:
    """
    Get system-specific font directories.

    Returns:
        List of font directory paths for the current platform
    """
    system = platform.system()

    if system == "Darwin":  # macOS
        return [
            "/Library/Fonts",
            "/System/Library/Fonts",
            "/System/Library/Fonts/Supplemental",
            os.path.expanduser("~/Library/Fonts")
        ]
    elif system == "Linux":
        return [
            "/usr/share/fonts",
            "/usr/local/share/fonts",
            "/usr/share/fonts/truetype",
            "/usr/share/fonts/truetype/dejavu",
            "/usr/share/fonts/truetype/liberation",
            "/usr/share/fonts/truetype/noto",
            os.path.expanduser("~/.fonts"),
            os.path.expanduser("~/.local/share/fonts")
        ]
    elif system == "Windows":
        return [
            "C:\\Windows\\Fonts",
            os.path.expanduser("~\\AppData\\Local\\Microsoft\\Windows\\Fonts")
        ]
    else:
        return []

def load_font(font_size: int = 48) -> ImageFont.ImageFont:
    """
    Load a TrueType font or fall back to default.

    Args:
        font_size: Size of the font to load

    Returns:
        ImageFont instance
    """
    # Try primary font paths from config
    for font_path in FONT_CONFIG["primary_paths"]:
        try:
            return ImageFont.truetype(font_path, font_size)
        except (OSError, IOError):
            continue

    # Try to find any TTF font in system-specific directories
    font_directories = _get_system_font_directories()

    for font_dir in font_directories:
        if not os.path.exists(font_dir):
            continue

        # Walk through directory and subdirectories
        try:
            for root, dirs, files in os.walk(font_dir):
                for font_file in files:
                    if font_file.endswith(('.ttf', '.ttc')):
                        try:
                            font_path = os.path.join(root, font_file)
                            return ImageFont.truetype(font_path, font_size)
                        except (OSError, IOError):
                            continue
        except (OSError, PermissionError):
            # Skip directories we can't access
            continue

    # Fallback to default font
    return ImageFont.load_default()

def get_available_fonts() -> list[str]:
    """Get list of available system fonts."""
    available_fonts = []
    font_directories = _get_system_font_directories()

    for font_dir in font_directories:
        if not os.path.exists(font_dir):
            continue

        try:
            for root, dirs, files in os.walk(font_dir):
                for font_file in files:
                    if font_file.endswith(('.ttf', '.ttc', '.otf')):
                        available_fonts.append(os.path.join(root, font_file))
        except (OSError, PermissionError):
            # Skip directories we can't access
            continue

    return available_fonts

def test_font_support(text: str, font: ImageFont.ImageFont) -> bool:
    """
    Test if a font supports rendering the given text.
    
    Args:
        text: Text to test
        font: Font to test with
        
    Returns:
        True if font can render the text
    """
    try:
        # Try to get text metrics - this will fail if font doesn't support the characters
        font.getbbox(text)
        return True
    except (OSError, UnicodeError):
        return False

def find_best_font_for_text(text: str, font_size: int = 48) -> ImageFont.ImageFont:
    """
    Find the best available font for rendering the given text.
    
    Args:
        text: Text that needs to be rendered
        font_size: Desired font size
        
    Returns:
        Best suitable font for the text
    """
    # Test primary fonts first
    for font_path in FONT_CONFIG["primary_paths"]:
        try:
            font = ImageFont.truetype(font_path, font_size)
            if test_font_support(text, font):
                return font
        except (OSError, IOError):
            continue
    
    # Test other available fonts
    available_fonts = get_available_fonts()
    for font_path in available_fonts:
        try:
            font = ImageFont.truetype(font_path, font_size)
            if test_font_support(text, font):
                return font
        except (OSError, IOError):
            continue
    
    # Return default font as last resort
    return ImageFont.load_default()