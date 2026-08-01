"""Video generation functions with character-level highlighting, background images, and overlays.

This module contains the styled video generation functions that produce videos with:
- Background image support
- Character-level highlighting with red highlight boxes
- Cat logo overlay following highlighted text
- PayPal QR code overlay
- CJK-aware text wrapping

These functions were originally in app.py and have been migrated here as part
of the modular architecture.
"""

import os
import numpy as np
from pathlib import Path
from typing import List, Optional

from moviepy.video.VideoClip import VideoClip
from moviepy.audio.io.AudioFileClip import AudioFileClip
from PIL import Image, ImageDraw, ImageFont

from src.config.settings import ASSETS_DIR, QR_CODE_CONFIG
from src.services.audio_timing import compute_character_timings, load_word_boundaries
from src.utils.audio_utils import get_audio_duration
from src.utils.text_utils import wrap_text_for_video, is_cjk_character
from src.utils.font_utils import find_best_font_for_text, load_font as _load_font_basic
from src.utils.logger import get_logger

logger = get_logger(__name__)


def _test_font_supports_text(font, text: str) -> bool:
    """Test if a font can render the given text."""
    try:
        test_img = Image.new('RGB', (100, 100))
        draw = ImageDraw.Draw(test_img)
        for char in text:
            try:
                draw.textbbox((0, 0), char, font=font)
            except Exception:
                return False
        return True
    except Exception:
        return False


def _load_font_for_text(text: str, font_size: int = 48) -> ImageFont.ImageFont:
    """Load a font that supports the given text - with CJK prioritization."""
    import platform

    system = platform.system()
    has_cjk = any(is_cjk_character(char) for char in text if char.strip())

    # Platform-specific font paths - CJK fonts first if needed
    if system == "Darwin":
        if has_cjk:
            font_paths = [
                "/Library/Fonts/Arial Unicode.ttf",
                "/System/Library/Fonts/Supplemental/Arial Unicode.ttf",
            ]
        else:
            font_paths = [
                "/Library/Fonts/Arial Unicode.ttf",
                "/System/Library/Fonts/Helvetica.ttc",
                "/System/Library/Fonts/Avenir.ttc",
                "/System/Library/Fonts/Supplemental/Arial.ttf",
            ]
    elif system == "Linux":
        if has_cjk:
            font_paths = [
                "/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc",
                "/usr/share/fonts/truetype/noto/NotoSansCJK-Regular.ttc",
                "/usr/share/fonts/truetype/noto/NotoSansJP-Regular.otf",
                "/usr/share/fonts/truetype/noto/NotoSansCJKjp-Regular.otf",
                "/usr/share/fonts/truetype/noto/NotoSans-Regular.ttf",
            ]
        else:
            font_paths = [
                "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf",
                "/usr/share/fonts/truetype/liberation/LiberationSans-Regular.ttf",
                "/usr/share/fonts/truetype/noto/NotoSans-Regular.ttf",
                "/usr/share/fonts/truetype/ubuntu/Ubuntu-R.ttf",
            ]
    elif system == "Windows":
        if has_cjk:
            font_paths = [
                "C:\\Windows\\Fonts\\msgothic.ttc",
                "C:\\Windows\\Fonts\\msyh.ttc",
                "C:\\Windows\\Fonts\\malgun.ttf",
                "C:\\Windows\\Fonts\\arial.ttf",
            ]
        else:
            font_paths = [
                "C:\\Windows\\Fonts\\arial.ttf",
                "C:\\Windows\\Fonts\\calibri.ttf",
                "C:\\Windows\\Fonts\\segoeui.ttf",
            ]
    else:
        font_paths = []

    # Try primary font paths
    for font_path in font_paths:
        try:
            font = ImageFont.truetype(font_path, font_size)
            if _test_font_supports_text(font, text):
                logger.debug(f"Loaded font: {font_path} at size {font_size}")
                return font
        except (OSError, IOError):
            continue

    # Search system directories for CJK fonts on Linux
    if system == "Linux" and has_cjk:
        search_patterns = ["noto", "cjk", "jp", "cn", "kr", "japanese", "chinese", "korean"]
        search_dirs = ["/usr/share/fonts"]

        for search_dir in search_dirs:
            if not os.path.exists(search_dir):
                continue
            try:
                for root, dirs, files in os.walk(search_dir):
                    if has_cjk and not any(p in root.lower() for p in search_patterns):
                        continue
                    for font_file in files:
                        if font_file.endswith(('.ttf', '.ttc', '.otf')):
                            if has_cjk and not any(p in font_file.lower() for p in search_patterns):
                                continue
                            try:
                                full_path = os.path.join(root, font_file)
                                font = ImageFont.truetype(full_path, font_size)
                                if _test_font_supports_text(font, text):
                                    logger.debug(f"Loaded font: {full_path} at size {font_size}")
                                    return font
                            except (OSError, IOError):
                                continue
            except (OSError, PermissionError):
                continue

    # Fallback: search all system directories
    if system == "Linux":
        search_dirs = ["/usr/share/fonts", "/usr/local/share/fonts"]
    elif system == "Darwin":
        search_dirs = ["/Library/Fonts", "/System/Library/Fonts"]
    elif system == "Windows":
        search_dirs = ["C:\\Windows\\Fonts"]
    else:
        search_dirs = []

    for search_dir in search_dirs:
        if not os.path.exists(search_dir):
            continue
        try:
            for root, dirs, files in os.walk(search_dir):
                for font_file in files:
                    if font_file.endswith(('.ttf', '.ttc', '.otf')):
                        try:
                            full_path = os.path.join(root, font_file)
                            font = ImageFont.truetype(full_path, font_size)
                            if _test_font_supports_text(font, text):
                                logger.debug(f"Loaded font: {full_path} at size {font_size}")
                                return font
                        except (OSError, IOError):
                            continue
        except (OSError, PermissionError):
            continue

    logger.warning("No suitable TrueType fonts found, using default bitmap font")
    return ImageFont.load_default()


def load_font(font_size: int = 48, text: str = None) -> ImageFont.ImageFont:
    """Load a font - optionally optimized for specific text."""
    if text:
        return _load_font_for_text(text, font_size)
    else:
        return _load_font_for_text("ABCabc123", font_size)


def analyze_audio_timing(text: str, audio_path, duration: Optional[float] = None) -> list:
    """Create character-level timing for the audio (word boundaries when available)."""
    if duration is None:
        try:
            duration = get_audio_duration(Path(audio_path))
        except Exception as e:
            logger.warning(f"Error reading audio duration: {e}, estimating from text length")
            duration = len(text) * 0.1

    timings = compute_character_timings(text, duration, load_word_boundaries(audio_path))
    logger.debug(f"Audio duration: {duration:.2f}s, Characters: {len(text)}")
    return [
        {
            'char': t.char,
            'start_time': t.start_time,
            'end_time': t.end_time,
            'position': t.position,
        }
        for t in timings
    ]


def _compute_char_layout(
    lines: List[str],
    font: ImageFont.ImageFont,
    draw: ImageDraw.ImageDraw,
    video_width: int,
    video_height: int,
    line_height: int = 70,
) -> List[dict]:
    """Precompute position and bounding box for every character.

    Glyph metrics don't change between frames, so this runs once per video
    instead of once per character per frame.
    """
    layout = []
    y_position = (video_height - len(lines) * line_height) // 2

    for line in lines:
        widths = []
        for char in line:
            bbox = draw.textbbox((0, 0), char, font=font)
            widths.append(bbox[2] - bbox[0])

        x_position = (video_width - sum(widths)) // 2
        for char, width in zip(line, widths):
            bbox = draw.textbbox((x_position, y_position), char, font=font)
            layout.append({
                'char': char,
                'x': x_position,
                'y': y_position,
                'bbox': bbox,
            })
            x_position += width

        y_position += line_height

    return layout


def _load_assets(show_qr_code: bool = False):
    """Load background image, QR code, and cat logo from assets directory."""
    # Load background image
    background_img = None
    try:
        bg_path = ASSETS_DIR / "background.png"
        bg_img = Image.open(bg_path).convert("RGB")
        background_img = bg_img.resize((1280, 720), Image.Resampling.LANCZOS)
        logger.debug("Background image loaded successfully")
    except Exception as e:
        logger.debug(f"Background image not found, using solid color: {e}")

    # Load QR code if enabled
    qr_code_img = None
    qr_size = QR_CODE_CONFIG.get("size", 120)
    qr_margin = QR_CODE_CONFIG.get("margin", 20)
    qr_opacity = QR_CODE_CONFIG.get("opacity", 0.9)
    if show_qr_code:
        try:
            qr_path = ASSETS_DIR / "paypal_qr.png"
            qr_img = Image.open(qr_path).convert("RGBA")
            qr_code_img = qr_img.resize((qr_size, qr_size), Image.Resampling.LANCZOS)
            logger.debug(f"QR code loaded, size: {qr_size}x{qr_size}")
        except Exception as e:
            logger.debug(f"QR code image not found: {e}")

    # Load cat logo
    cat_logo = None
    cat_size = 80
    try:
        logo_path = ASSETS_DIR / "logo_small.png"
        cat_img = Image.open(logo_path).convert("RGBA")
        cat_logo = cat_img.resize((cat_size, cat_size), Image.Resampling.LANCZOS)
        logger.debug(f"Cat logo loaded, size: {cat_size}x{cat_size}")
    except Exception as e:
        logger.warning(f"Could not load cat logo: {e}")

    return background_img, qr_code_img, cat_logo, qr_size, qr_margin, qr_opacity, cat_size


def create_character_animated_video(
    text: str,
    audio_path,
    output_path,
    font_size: int = 48,
    show_qr_code: bool = False
):
    """Create video with character-level highlighting and optional QR code overlay."""
    audio = AudioFileClip(str(audio_path))
    duration = audio.duration

    char_timings = analyze_audio_timing(text, audio_path, duration=duration)

    video_width = 1280
    video_height = 720
    fps = 24
    bg_color = (30, 30, 40)

    font = load_font(font_size, text=text)

    background_img, qr_code_img, cat_logo, qr_size, qr_margin, qr_opacity, cat_size = _load_assets(show_qr_code)

    dummy_img = Image.new('RGB', (video_width, video_height))
    dummy_draw = ImageDraw.Draw(dummy_img)
    lines = wrap_text_for_video(text, video_width, font, dummy_draw)

    char_layout = _compute_char_layout(lines, font, dummy_draw, video_width, video_height)

    # Static base frame: background, all characters in inactive color, QR code.
    # Per frame we only overlay the currently highlighted characters — the
    # highlight rectangle fully covers the inactive glyph underneath.
    if background_img is not None:
        base_img = background_img.copy()
    else:
        base_img = Image.new('RGB', (video_width, video_height), color=bg_color)
    base_draw = ImageDraw.Draw(base_img)
    for entry in char_layout:
        base_draw.text((entry['x'], entry['y']), entry['char'], font=font, fill=(80, 50, 30))

    if qr_code_img is not None:
        qr_x = qr_margin
        qr_y = video_height - qr_size - qr_margin
        qr_with_opacity = qr_code_img.copy()
        alpha = qr_with_opacity.split()[3]
        alpha = alpha.point(lambda p: int(p * qr_opacity))
        qr_with_opacity.putalpha(alpha)
        base_img.paste(qr_with_opacity, (qr_x, qr_y), qr_with_opacity)

    def make_frame(t):
        img = base_img.copy()
        draw = ImageDraw.Draw(img)

        cat_x = None
        cat_y = None

        for timing in char_timings:
            if not (timing['start_time'] <= t <= timing['end_time']):
                continue
            position = timing['position']
            if position >= len(char_layout):
                continue
            entry = char_layout[position]
            bbox = entry['bbox']

            draw.rectangle([bbox[0] - 4, bbox[1] - 4, bbox[2] + 4, bbox[3] + 4],
                           fill=(220, 50, 50))
            if entry['char'] != ' ':
                draw.text((entry['x'], entry['y']), entry['char'], font=font, fill=(255, 255, 255))

            if cat_x is None:
                cat_x = entry['x'] + (bbox[2] - bbox[0]) // 2
                cat_y = entry['y']

        if cat_logo is not None and cat_x is not None and cat_y is not None:
            cat_offset_y = cat_size + 10
            cat_paste_x = int(cat_x - cat_size // 2)
            cat_paste_y = int(cat_y - cat_offset_y)
            cat_paste_x = max(0, min(cat_paste_x, video_width - cat_size))
            cat_paste_y = max(0, min(cat_paste_y, video_height - cat_size))
            img.paste(cat_logo, (cat_paste_x, cat_paste_y), cat_logo)

        return np.array(img)

    video_clip = VideoClip(make_frame, duration=duration)
    video = video_clip.with_audio(audio)

    video.write_videofile(
        str(output_path),
        fps=fps,
        codec='libx264',
        audio_codec='aac',
        preset='veryfast',
        threads=os.cpu_count(),
        temp_audiofile='temp-audio.m4a',
        remove_temp=True,
        logger=None
    )

    video.close()
    audio.close()


def create_video_with_text(
    text: str,
    audio_path,
    output_path,
    duration=None,
    font_size: int = 48,
    show_qr_code: bool = False
):
    """Main function to create video with character-level text highlighting and optional QR code."""
    return create_character_animated_video(
        text, audio_path, output_path,
        font_size=font_size, show_qr_code=show_qr_code
    )


def create_preview_frame(
    text: str,
    font_size: int = 48,
    show_qr_code: bool = False,
    highlight_position: int = 0
) -> Image.Image:
    """Generate a single preview frame showing how the video will look."""
    video_width = 1280
    video_height = 720
    bg_color = (30, 30, 40)

    font = load_font(font_size, text=text)

    background_img, qr_code_img, cat_logo, qr_size, qr_margin, qr_opacity, cat_size = _load_assets(show_qr_code)

    if background_img is not None:
        img = background_img.copy()
    else:
        img = Image.new('RGB', (video_width, video_height), color=bg_color)
    draw = ImageDraw.Draw(img)

    dummy_img = Image.new('RGB', (video_width, video_height))
    dummy_draw = ImageDraw.Draw(dummy_img)
    lines = wrap_text_for_video(text, video_width, font, dummy_draw)

    char_layout = _compute_char_layout(lines, font, dummy_draw, video_width, video_height)

    cat_x = None
    cat_y = None

    for char_position, entry in enumerate(char_layout):
        is_active = char_position == highlight_position
        bbox = entry['bbox']

        if is_active:
            color = (255, 255, 255)
            draw.rectangle([bbox[0] - 4, bbox[1] - 4, bbox[2] + 4, bbox[3] + 4],
                         fill=(220, 50, 50))
            if cat_x is None:
                cat_x = entry['x'] + (bbox[2] - bbox[0]) // 2
                cat_y = entry['y']
        else:
            color = (80, 50, 30)

        if entry['char'] != ' ' or not is_active:
            draw.text((entry['x'], entry['y']), entry['char'], font=font, fill=color)

    if cat_logo is not None and cat_x is not None and cat_y is not None:
        cat_offset_y = cat_size + 10
        cat_paste_x = int(cat_x - cat_size // 2)
        cat_paste_y = int(cat_y - cat_offset_y)
        cat_paste_x = max(0, min(cat_paste_x, video_width - cat_size))
        cat_paste_y = max(0, min(cat_paste_y, video_height - cat_size))
        img.paste(cat_logo, (cat_paste_x, cat_paste_y), cat_logo)

    if qr_code_img is not None:
        qr_x = qr_margin
        qr_y = video_height - qr_size - qr_margin
        qr_with_opacity = qr_code_img.copy()
        alpha = qr_with_opacity.split()[3]
        alpha = alpha.point(lambda p: int(p * qr_opacity))
        qr_with_opacity.putalpha(alpha)
        img.paste(qr_with_opacity, (qr_x, qr_y), qr_with_opacity)

    return img
