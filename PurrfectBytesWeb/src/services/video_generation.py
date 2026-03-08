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
from typing import Optional

from moviepy.video.VideoClip import VideoClip
from moviepy.audio.io.AudioFileClip import AudioFileClip
from PIL import Image, ImageDraw, ImageFont
import librosa

from src.config.settings import ASSETS_DIR, QR_CODE_CONFIG
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


def analyze_audio_timing(text: str, audio_path) -> list:
    """Analyze audio to create character-level timing with lead compensation."""
    try:
        y, sr_rate = librosa.load(str(audio_path))
        duration = librosa.get_duration(y=y, sr=sr_rate)

        chars = list(text.replace(' ', ''))
        char_count = len(chars)

        if char_count == 0:
            return []

        lead_time = 0.3
        overlap_duration = 0.4

        char_timings = []
        chars_per_second = char_count / duration

        current_pos = 0
        for i, char in enumerate(text):
            if char == ' ':
                start_time = max(0, (current_pos / chars_per_second) - lead_time) if chars_per_second > 0 else max(0, i * 0.1 - lead_time)
                end_time = ((current_pos + 0.5) / chars_per_second) + overlap_duration if chars_per_second > 0 else (i + 1) * 0.1
                char_timings.append({
                    'char': char,
                    'start_time': start_time,
                    'end_time': end_time,
                    'position': i
                })
                current_pos += 0.5
            else:
                start_time = max(0, (current_pos / chars_per_second) - lead_time) if chars_per_second > 0 else max(0, i * 0.1 - lead_time)
                end_time = ((current_pos + 1) / chars_per_second) + overlap_duration if chars_per_second > 0 else (i + 1) * 0.1
                char_timings.append({
                    'char': char,
                    'start_time': start_time,
                    'end_time': end_time,
                    'position': i
                })
                current_pos += 1

        logger.debug(f"Audio duration: {duration:.2f}s, Characters: {len(text)}")
        return char_timings

    except Exception as e:
        logger.warning(f"Error analyzing audio: {e}, using fallback timing")
        char_timings = []
        try:
            y, sr_rate = librosa.load(str(audio_path))
            duration = librosa.get_duration(y=y, sr=sr_rate)
        except Exception:
            duration = len(text) * 0.1
        char_duration = duration / len(text) if len(text) > 0 else 0.1
        lead_time = 0.3
        for i, char in enumerate(text):
            char_timings.append({
                'char': char,
                'start_time': max(0, i * char_duration - lead_time),
                'end_time': (i + 1) * char_duration,
                'position': i
            })
        return char_timings


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

    char_timings = analyze_audio_timing(text, audio_path)

    video_width = 1280
    video_height = 720
    fps = 24
    bg_color = (30, 30, 40)

    font = load_font(font_size, text=text)

    background_img, qr_code_img, cat_logo, qr_size, qr_margin, qr_opacity, cat_size = _load_assets(show_qr_code)

    dummy_img = Image.new('RGB', (video_width, video_height))
    dummy_draw = ImageDraw.Draw(dummy_img)
    lines = wrap_text_for_video(text, video_width, font, dummy_draw)

    def make_frame(t):
        if background_img is not None:
            img = background_img.copy()
        else:
            img = Image.new('RGB', (video_width, video_height), color=bg_color)
        draw = ImageDraw.Draw(img)

        active_chars = set()
        for timing in char_timings:
            if timing['start_time'] <= t <= timing['end_time']:
                active_chars.add(timing['position'])

        y_position = (video_height - len(lines) * 70) // 2
        char_position = 0
        cat_x = None
        cat_y = None

        for line in lines:
            line_width = 0
            for char in line:
                bbox = draw.textbbox((0, 0), char, font=font)
                line_width += bbox[2] - bbox[0]

            x_position = (video_width - line_width) // 2

            for char in line:
                is_active = char_position in active_chars

                if is_active:
                    color = (255, 255, 255)
                    bbox = draw.textbbox((x_position, y_position), char, font=font)
                    draw.rectangle([bbox[0] - 4, bbox[1] - 4, bbox[2] + 4, bbox[3] + 4],
                                 fill=(220, 50, 50))
                    if cat_x is None:
                        char_width = bbox[2] - bbox[0]
                        cat_x = x_position + char_width // 2
                        cat_y = y_position
                else:
                    color = (80, 50, 30)

                if char != ' ' or not is_active:
                    draw.text((x_position, y_position), char, font=font, fill=color)

                bbox = draw.textbbox((0, 0), char, font=font)
                char_width = bbox[2] - bbox[0]
                x_position += char_width
                char_position += 1

            y_position += 70

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

        return np.array(img)

    video_clip = VideoClip(make_frame, duration=duration)
    video = video_clip.with_audio(audio)

    video.write_videofile(
        str(output_path),
        fps=fps,
        codec='libx264',
        audio_codec='aac',
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

    y_position = (video_height - len(lines) * 70) // 2
    char_position = 0
    cat_x = None
    cat_y = None

    for line in lines:
        line_width = 0
        for char in line:
            bbox = draw.textbbox((0, 0), char, font=font)
            line_width += bbox[2] - bbox[0]

        x_position = (video_width - line_width) // 2

        for char in line:
            is_active = char_position == highlight_position

            if is_active:
                color = (255, 255, 255)
                bbox = draw.textbbox((x_position, y_position), char, font=font)
                draw.rectangle([bbox[0] - 4, bbox[1] - 4, bbox[2] + 4, bbox[3] + 4],
                             fill=(220, 50, 50))
                if cat_x is None:
                    char_width = bbox[2] - bbox[0]
                    cat_x = x_position + char_width // 2
                    cat_y = y_position
            else:
                color = (80, 50, 30)

            if char != ' ' or not is_active:
                draw.text((x_position, y_position), char, font=font, fill=color)

            bbox = draw.textbbox((0, 0), char, font=font)
            char_width = bbox[2] - bbox[0]
            x_position += char_width
            char_position += 1

        y_position += 70

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
