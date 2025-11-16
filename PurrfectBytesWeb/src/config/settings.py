"""Configuration settings for the PurrfectBytes application."""

from pathlib import Path
from typing import Dict, Any
import os

# Base directories
BASE_DIR = Path(__file__).parent.parent.parent
AUDIO_DIR = BASE_DIR / "audio_files"
VIDEO_DIR = BASE_DIR / "video_files"
TEMPLATES_DIR = BASE_DIR / "templates"
ASSETS_DIR = BASE_DIR / "assets"

# Ensure directories exist
AUDIO_DIR.mkdir(exist_ok=True)
VIDEO_DIR.mkdir(exist_ok=True)
ASSETS_DIR.mkdir(exist_ok=True)

# Server settings
SERVER_HOST = "0.0.0.0"
SERVER_PORT = 8000
DEBUG = os.getenv("DEBUG", "false").lower() == "true"

# Video generation settings
VIDEO_CONFIG = {
    "width": 1280,
    "height": 720,
    "fps": 24,
    "bg_color": (30, 30, 40),
    "font_size": 48,
    "font_size_bold": 56,
    "lead_time": 0.3,  # Start highlighting earlier (seconds)
    "overlap_duration": 0.4,  # How long to keep characters highlighted
    "padding": 50,  # Text padding from edges
    "line_height": 70,  # Distance between text lines
}

# Audio settings
AUDIO_CONFIG = {
    "format": "mp3",
    "quality": "high",
    "temp_audio_file": "temp-audio.m4a",
}

# Font settings - Platform-specific primary font paths
import platform

_system = platform.system()

if _system == "Darwin":  # macOS
    _primary_font_paths = [
        "/Library/Fonts/Arial Unicode.ttf",
        "/System/Library/Fonts/Helvetica.ttc",
        "/System/Library/Fonts/Avenir.ttc",
        "/System/Library/Fonts/Supplemental/Arial.ttf",
    ]
elif _system == "Linux":
    _primary_font_paths = [
        "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf",
        "/usr/share/fonts/truetype/liberation/LiberationSans-Regular.ttf",
        "/usr/share/fonts/truetype/noto/NotoSans-Regular.ttf",
        "/usr/share/fonts/truetype/ubuntu/Ubuntu-R.ttf",
    ]
elif _system == "Windows":
    _primary_font_paths = [
        "C:\\Windows\\Fonts\\arial.ttf",
        "C:\\Windows\\Fonts\\calibri.ttf",
        "C:\\Windows\\Fonts\\segoeui.ttf",
    ]
else:
    _primary_font_paths = []

FONT_CONFIG = {
    "primary_paths": _primary_font_paths,
    "fallback_size": 48,
}

# Language detection settings
LANGUAGE_CONFIG = {
    "min_chars_for_detection": 3,
    "auto_detect_threshold": 10,  # Characters needed for auto-detection
    "detection_delay": 1.5,  # Seconds to wait after user stops typing
}

# Supported languages for TTS
SUPPORTED_LANGUAGES: Dict[str, Dict[str, str]] = {
    'en': {'name': 'English', 'gtts': 'en'},
    'es': {'name': 'Spanish', 'gtts': 'es'},
    'fr': {'name': 'French', 'gtts': 'fr'},
    'de': {'name': 'German', 'gtts': 'de'},
    'it': {'name': 'Italian', 'gtts': 'it'},
    'pt': {'name': 'Portuguese', 'gtts': 'pt'},
    'ru': {'name': 'Russian', 'gtts': 'ru'},
    'ja': {'name': 'Japanese', 'gtts': 'ja'},
    'ko': {'name': 'Korean', 'gtts': 'ko'},
    'zh': {'name': 'Chinese', 'gtts': 'zh'},
    'ar': {'name': 'Arabic', 'gtts': 'ar'},
    'hi': {'name': 'Hindi', 'gtts': 'hi'},
    'nl': {'name': 'Dutch', 'gtts': 'nl'},
    'pl': {'name': 'Polish', 'gtts': 'pl'},
    'tr': {'name': 'Turkish', 'gtts': 'tr'},
    'sv': {'name': 'Swedish', 'gtts': 'sv'},
    'da': {'name': 'Danish', 'gtts': 'da'},
    'no': {'name': 'Norwegian', 'gtts': 'no'},
    'fi': {'name': 'Finnish', 'gtts': 'fi'},
}

# CJK character ranges for text wrapping
CJK_UNICODE_RANGES = [
    (0x4E00, 0x9FFF),  # CJK Unified Ideographs
    (0x3400, 0x4DBF),  # CJK Extension A
    (0x3040, 0x309F),  # Hiragana
    (0x30A0, 0x30FF),  # Katakana
    (0xAC00, 0xD7AF),  # Hangul
]

# API rate limiting (future feature)
RATE_LIMIT_CONFIG = {
    "requests_per_minute": 60,
    "burst_limit": 10,
}

# File cleanup settings
CLEANUP_CONFIG = {
    "auto_cleanup_hours": 24,  # Remove files older than 24 hours
    "max_file_age_days": 7,    # Maximum file retention
    "cleanup_on_startup": True,
}

# QR Code overlay settings
QR_CODE_CONFIG = {
    "default_enabled": False,  # Default: don't show QR code
    "qr_image_path": ASSETS_DIR / "paypal_qr.png",  # Path to PayPal QR code image
    "position": "bottom_left",  # Position: bottom_left, bottom_right, top_left, top_right
    "size": 150,  # QR code size in pixels
    "margin": 20,  # Margin from edges in pixels
    "opacity": 0.9,  # Opacity (0.0 to 1.0)
}

def get_config() -> Dict[str, Any]:
    """Get all configuration as a dictionary."""
    return {
        "base_dir": BASE_DIR,
        "audio_dir": AUDIO_DIR,
        "video_dir": VIDEO_DIR,
        "templates_dir": TEMPLATES_DIR,
        "assets_dir": ASSETS_DIR,
        "server": {"host": SERVER_HOST, "port": SERVER_PORT, "debug": DEBUG},
        "video": VIDEO_CONFIG,
        "audio": AUDIO_CONFIG,
        "fonts": FONT_CONFIG,
        "languages": LANGUAGE_CONFIG,
        "supported_languages": SUPPORTED_LANGUAGES,
        "cjk_ranges": CJK_UNICODE_RANGES,
        "rate_limit": RATE_LIMIT_CONFIG,
        "cleanup": CLEANUP_CONFIG,
        "qr_code": QR_CODE_CONFIG,
    }