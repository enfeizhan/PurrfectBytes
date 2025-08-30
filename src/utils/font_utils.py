"""Font loading and management utilities."""

import os
from typing import Optional
from PIL import ImageFont
from src.config.settings import FONT_CONFIG

def load_font(font_size: int = 48) -> ImageFont.ImageFont:
    """
    Load a TrueType font or fall back to default.
    
    Args:
        font_size: Size of the font to load
        
    Returns:
        ImageFont instance
    """
    # Try primary font paths
    for font_path in FONT_CONFIG["primary_paths"]:
        try:
            return ImageFont.truetype(font_path, font_size)
        except (OSError, IOError):
            continue
    
    # Try to find any TTF font in common directories
    font_directories = [
        "/Library/Fonts",
        "/System/Library/Fonts", 
        "/System/Library/Fonts/Supplemental"
    ]
    
    for font_dir in font_directories:
        if not os.path.exists(font_dir):
            continue
            
        for font_file in os.listdir(font_dir):
            if font_file.endswith('.ttf'):
                try:
                    font_path = os.path.join(font_dir, font_file)
                    return ImageFont.truetype(font_path, font_size)
                except (OSError, IOError):
                    continue
    
    # Fallback to default font
    return ImageFont.load_default()

def get_available_fonts() -> list[str]:
    """Get list of available system fonts."""
    available_fonts = []
    font_directories = [
        "/Library/Fonts",
        "/System/Library/Fonts",
        "/System/Library/Fonts/Supplemental"
    ]
    
    for font_dir in font_directories:
        if not os.path.exists(font_dir):
            continue
            
        for font_file in os.listdir(font_dir):
            if font_file.endswith(('.ttf', '.ttc', '.otf')):
                available_fonts.append(os.path.join(font_dir, font_file))
    
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