from fastapi import FastAPI, Form, Request
from fastapi.responses import FileResponse, HTMLResponse
from fastapi.staticfiles import StaticFiles
from fastapi.templating import Jinja2Templates
from gtts import gTTS
from moviepy.video.VideoClip import ColorClip, ImageClip, VideoClip
from moviepy.video.compositing.CompositeVideoClip import CompositeVideoClip
from moviepy.audio.io.AudioFileClip import AudioFileClip
from PIL import Image, ImageDraw, ImageFont
import numpy as np
import os
import uuid
from pathlib import Path
import textwrap
import re
import speech_recognition as sr
from pydub import AudioSegment
import librosa
import json
from langdetect import detect
from langdetect.lang_detect_exception import LangDetectException

app = FastAPI()

# Use /tmp for temporary files (auto-cleaned by OS)
AUDIO_DIR = Path("/tmp/purrfect_bytes/audio")
AUDIO_DIR.mkdir(parents=True, exist_ok=True)
VIDEO_DIR = Path("/tmp/purrfect_bytes/video")
VIDEO_DIR.mkdir(parents=True, exist_ok=True)

# Assets directory for QR codes and other static assets
BASE_DIR = Path(__file__).parent
ASSETS_DIR = BASE_DIR / "assets"
ASSETS_DIR.mkdir(parents=True, exist_ok=True)

# Assets directory for creative files (background, logos)
ASSETS_DIR = Path("assets")

templates = Jinja2Templates(directory="templates")

@app.get("/", response_class=HTMLResponse)
async def home(request: Request):
    return templates.TemplateResponse("index.html", {"request": request})

@app.post("/convert")
async def convert_text_to_speech(
    text: str = Form(...),
    language: str = Form("en"),
    slow: bool = Form(False),
    engine: str = Form("edge")
):
    if not text:
        return {"error": "No text provided"}

    # Ensure directories exist (in case they were deleted)
    AUDIO_DIR.mkdir(parents=True, exist_ok=True)

    try:
        # Import TTS service
        from src.services.tts_service import TTSService
        tts_service = TTSService()
        
        # Parse engine and generate audio
        engine_enum = TTSService.parse_engine(engine)
        audio_path, duration = tts_service.generate_audio(
            text, language, slow, engine=engine_enum
        )
        
        print(f"✓ Audio generated with engine: {engine}")
        
        return {
            "success": True,
            "filename": audio_path.name,
            "audio_url": f"/download/{audio_path.name}",
            "duration": duration
        }
    except Exception as e:
        import traceback
        traceback.print_exc()
        return {"error": str(e)}

@app.get("/download/{filename}")
async def download_audio(filename: str):
    filepath = AUDIO_DIR / filename
    if not filepath.exists():
        return {"error": "File not found"}
    
    return FileResponse(
        path=filepath,
        media_type="audio/mpeg",
        filename=filename
    )

@app.delete("/audio/{filename}")
async def delete_audio(filename: str):
    filepath = AUDIO_DIR / filename
    if filepath.exists():
        os.remove(filepath)
        return {"success": True, "message": "File deleted"}
    return {"error": "File not found"}

def test_font_supports_text(font, text):
    """Test if a font can render the given text"""
    try:
        from PIL import ImageDraw, Image
        # Create a small test image
        test_img = Image.new('RGB', (100, 100))
        draw = ImageDraw.Draw(test_img)
        # Try to get bbox for each character
        for char in text:
            try:
                draw.textbbox((0, 0), char, font=font)
            except:
                return False
        return True
    except:
        return False

def load_font_for_text(text, font_size=48):
    """Load a font that supports the given text - with CJK prioritization"""
    import platform

    system = platform.system()

    # Check if text contains CJK characters
    has_cjk = any(is_cjk_character(char) for char in text if char.strip())

    # Platform-specific font paths - CJK fonts first if needed
    if system == "Darwin":  # macOS
        if has_cjk:
            font_paths = [
                "/Library/Fonts/Arial Unicode.ttf",  # Supports CJK
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
            # Prioritize Noto CJK fonts for Asian languages
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
                "C:\\Windows\\Fonts\\msgothic.ttc",  # MS Gothic (Japanese)
                "C:\\Windows\\Fonts\\msyh.ttc",      # Microsoft YaHei (Chinese)
                "C:\\Windows\\Fonts\\malgun.ttf",    # Malgun Gothic (Korean)
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
            # Test if font supports the text
            if test_font_supports_text(font, text):
                print(f"✓ Loaded font: {font_path} at size {font_size}")
                return font
            else:
                print(f"⚠ Font {font_path} doesn't support all characters in text, trying next...")
        except (OSError, IOError):
            continue

    # If no primary fonts found, search system directories for Noto CJK fonts
    if system == "Linux" and has_cjk:
        print("🔍 Searching for CJK fonts in system directories...")
        search_patterns = ["noto", "cjk", "jp", "cn", "kr", "japanese", "chinese", "korean"]
        search_dirs = ["/usr/share/fonts"]

        for search_dir in search_dirs:
            if not os.path.exists(search_dir):
                continue
            try:
                for root, dirs, files in os.walk(search_dir):
                    # Prioritize directories with CJK-related names
                    if has_cjk and not any(pattern in root.lower() for pattern in search_patterns):
                        continue

                    for font_file in files:
                        if font_file.endswith(('.ttf', '.ttc', '.otf')):
                            # Prioritize CJK fonts
                            if has_cjk and not any(pattern in font_file.lower() for pattern in search_patterns):
                                continue

                            try:
                                font_path = os.path.join(root, font_file)
                                font = ImageFont.truetype(font_path, font_size)
                                if test_font_supports_text(font, text):
                                    print(f"✓ Loaded font: {font_path} at size {font_size}")
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
                            font_path = os.path.join(root, font_file)
                            font = ImageFont.truetype(font_path, font_size)
                            if test_font_supports_text(font, text):
                                print(f"✓ Loaded font: {font_path} at size {font_size}")
                                return font
                        except (OSError, IOError):
                            continue
        except (OSError, PermissionError):
            continue

    # Fallback to default (this will be tiny!)
    print(f"⚠ WARNING: No suitable TrueType fonts found for this text, using default bitmap font")
    return ImageFont.load_default()

def load_font(font_size=48, text=None):
    """Load a font - optionally optimized for specific text"""
    if text:
        return load_font_for_text(text, font_size)
    else:
        # For backward compatibility, load a general-purpose font
        return load_font_for_text("ABCabc123", font_size)

def is_cjk_character(char):
    """Check if character is Chinese, Japanese, or Korean"""
    code = ord(char)
    return (
        (0x4E00 <= code <= 0x9FFF) or    # CJK Unified Ideographs
        (0x3400 <= code <= 0x4DBF) or    # CJK Extension A
        (0x3040 <= code <= 0x309F) or    # Hiragana
        (0x30A0 <= code <= 0x30FF) or    # Katakana
        (0xAC00 <= code <= 0xD7AF)       # Hangul
    )

def wrap_text_for_video(text, width, font, draw, padding=50):
    """Wrap text to fit within video width with proper handling for CJK languages"""
    max_width = width - (padding * 2)
    
    # For languages with CJK characters, use different approach
    has_cjk = any(is_cjk_character(char) for char in text)
    
    if has_cjk:
        # For CJK text, wrap by characters rather than words
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
    else:
        # For Latin-based languages, wrap by words
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
                    # Word is too long, try character-based splitting
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

def create_animated_text_video(text, audio_path, output_path):
    """Create a video with animated text synchronized with audio"""
    
    # Load audio and get duration
    audio = AudioFileClip(str(audio_path))
    duration = audio.duration
    
    # Video settings
    video_width = 1280
    video_height = 720
    fps = 24
    bg_color = (30, 30, 40)
    
    # Font settings
    font_size = 48
    font_size_bold = 56  # Bigger difference for highlighting
    font = load_font(font_size)
    font_bold = load_font(font_size_bold)
    
    # Create a dummy image to calculate text dimensions
    dummy_img = Image.new('RGB', (video_width, video_height))
    dummy_draw = ImageDraw.Draw(dummy_img)
    
    # Wrap text into lines that fit the screen
    lines = wrap_text_for_video(text, video_width, font, dummy_draw)
    words = text.split()
    
    # Calculate timing for each word (simple estimation)
    words_per_second = len(words) / duration if duration > 0 else 1
    
    # Debug print
    print(f"Duration: {duration}s, Words: {len(words)}, Words per second: {words_per_second}")
    
    def make_frame(t):
        """Create a frame at time t"""
        # Create image for this frame
        img = Image.new('RGB', (video_width, video_height), color=bg_color)
        draw = ImageDraw.Draw(img)
        
        # Calculate which word should be highlighted (with bounds checking)
        # Add a small delay before first word starts highlighting
        adjusted_time = max(0, t - 0.5)  # Start highlighting after 0.5 seconds
        current_word_index = min(int(adjusted_time * words_per_second), len(words) - 1)
        
        # If we have only one word, make it highlight for middle portion of audio
        if len(words) == 1:
            if duration > 0:
                # Highlight the word during middle 60% of the audio
                start_highlight = duration * 0.2
                end_highlight = duration * 0.8
                current_word_index = 0 if start_highlight <= t <= end_highlight else -1
            else:
                current_word_index = -1
        
        # Debug print for first few seconds
        if t < 2.0 and int(t * 10) % 5 == 0:  # Print every 0.5 seconds
            print(f"Time: {t:.2f}s, Current word index: {current_word_index}, Total words: {len(words)}")
        
        # Draw text with current word highlighted
        y_position = (video_height - len(lines) * 70) // 2  # Increased line height
        word_counter = 0
        
        for line in lines:
            line_words = line.split()
            
            # Calculate line width to center it
            line_width = 0
            for word in line_words:
                bbox = draw.textbbox((0, 0), word + " ", font=font)
                line_width += bbox[2] - bbox[0]
            
            x_position = (video_width - line_width) // 2  # Center the line
            
            for word in line_words:
                # Determine if this word should be highlighted
                is_current = (word_counter == current_word_index)
                
                # Choose font and color based on whether word is current
                if is_current:
                    use_font = font_bold
                    color = (255, 220, 0)  # Bright yellow for highlighted
                    # Draw a subtle background for the highlighted word
                    bbox = draw.textbbox((x_position, y_position), word, font=use_font)
                    draw.rectangle([bbox[0] - 5, bbox[1] - 2, bbox[2] + 5, bbox[3] + 2], 
                                 fill=(80, 80, 120))  # More visible background
                else:
                    use_font = font
                    color = (220, 220, 220)  # White for normal text
                
                # Draw the word
                draw.text((x_position, y_position), word, font=use_font, fill=color)
                
                # Calculate next word position
                bbox = draw.textbbox((0, 0), word + " ", font=use_font)
                word_width = bbox[2] - bbox[0]
                x_position += word_width
                
                word_counter += 1
            
            y_position += 70  # Line height
        
        return np.array(img)
    
    # Create video clip from the frame function
    video_clip = VideoClip(make_frame, duration=duration)
    
    # Add audio
    video = video_clip.with_audio(audio)
    
    # Write video file
    video.write_videofile(
        str(output_path),
        fps=fps,
        codec='libx264',
        audio_codec='aac',
        temp_audiofile='temp-audio.m4a',
        remove_temp=True,
        logger=None
    )
    
    # Clean up
    video.close()
    audio.close()

def analyze_audio_timing(text, audio_path):
    """Analyze audio to create character-level timing with lead compensation"""
    try:
        # Load audio with librosa for analysis
        y, sr_rate = librosa.load(str(audio_path))
        duration = librosa.get_duration(y=y, sr=sr_rate)
        
        # Simple approach: divide audio duration by character count
        # This is a basic estimation - for more accuracy, we'd need forced alignment
        chars = list(text.replace(' ', ''))  # Remove spaces for character timing
        char_count = len(chars)
        
        if char_count == 0:
            return []
        
        # Timing adjustments for better synchronization
        # TTS typically has a slight delay, and we want highlighting to appear slightly ahead
        lead_time = 0.3  # Start highlighting 0.3 seconds earlier
        overlap_duration = 0.4  # Each character highlighted for longer
        
        # Create timing for each character with lead compensation
        char_timings = []
        chars_per_second = char_count / duration
        
        current_pos = 0
        for i, char in enumerate(text):
            if char == ' ':
                # Spaces get slight pause but still show highlighting
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
                # Regular characters - start earlier and last longer
                start_time = max(0, (current_pos / chars_per_second) - lead_time) if chars_per_second > 0 else max(0, i * 0.1 - lead_time)
                end_time = ((current_pos + 1) / chars_per_second) + overlap_duration if chars_per_second > 0 else (i + 1) * 0.1
                char_timings.append({
                    'char': char,
                    'start_time': start_time,
                    'end_time': end_time,
                    'position': i
                })
                current_pos += 1
        
        # Debug output for timing
        print(f"Audio duration: {duration:.2f}s, Characters: {len(text)}")
        print(f"Lead time: {lead_time}s, Overlap: {overlap_duration}s")
        if len(char_timings) > 0:
            print(f"First char timing: {char_timings[0]['start_time']:.2f}-{char_timings[0]['end_time']:.2f}")
            if len(char_timings) > 1:
                print(f"Second char timing: {char_timings[1]['start_time']:.2f}-{char_timings[1]['end_time']:.2f}")
        
        return char_timings
        
    except Exception as e:
        print(f"Error analyzing audio: {e}")
        # Fallback: simple linear timing with lead compensation
        char_timings = []
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

def create_character_animated_video(text, audio_path, output_path, font_size=48, show_qr_code=False):
    """Create video with character-level highlighting and optional QR code overlay"""

    # Load audio and get duration
    audio = AudioFileClip(str(audio_path))
    duration = audio.duration

    # Analyze audio for character timing
    char_timings = analyze_audio_timing(text, audio_path)

    # Video settings
    video_width = 1280
    video_height = 720
    fps = 24
    bg_color = (30, 30, 40)  # Fallback color if no background image

    # Font settings (accept custom font_size parameter, load font optimized for text)
    font = load_font(font_size, text=text)

    # Load background image (configurable - just replace assets/background.png to change it)
    background_img = None
    try:
        bg_path = ASSETS_DIR / "background.png"
        bg_img = Image.open(bg_path).convert("RGB")
        # Resize background to match video dimensions
        background_img = bg_img.resize((video_width, video_height), Image.Resampling.LANCZOS)
        print(f"✓ Background image loaded successfully! Size: {video_width}x{video_height}")
    except Exception as e:
        print(f"⚠ Background image not found, using solid color background: {e}")

    # Load QR code if enabled
    qr_code_img = None
    # Import settings to get QR code configuration
    from src.config.settings import QR_CODE_CONFIG
    qr_size = QR_CODE_CONFIG.get("size", 120)
    qr_margin = QR_CODE_CONFIG.get("margin", 20)
    qr_opacity = QR_CODE_CONFIG.get("opacity", 0.9)
    if show_qr_code:
        try:
            qr_path = ASSETS_DIR / "paypal_qr.png"
            qr_img = Image.open(qr_path).convert("RGBA")
            # Resize QR code to appropriate size
            qr_code_img = qr_img.resize((qr_size, qr_size), Image.Resampling.LANCZOS)
            print(f"✓ QR code loaded successfully! Size: {qr_size}x{qr_size}")
        except Exception as e:
            print(f"⚠ QR code image not found: {e}")

    # Load cat logo
    cat_logo = None
    cat_size = 80  # Size of the cat logo
    try:
        logo_path = ASSETS_DIR / "logo_small.png"
        cat_img = Image.open(logo_path).convert("RGBA")
        # Resize cat to appropriate size
        cat_logo = cat_img.resize((cat_size, cat_size), Image.Resampling.LANCZOS)
        print(f"✓ Cat logo loaded successfully! Size: {cat_size}x{cat_size}")
    except Exception as e:
        print(f"✗ ERROR: Could not load cat logo: {e}")
        import traceback
        traceback.print_exc()

    # Create a dummy image to calculate text dimensions
    dummy_img = Image.new('RGB', (video_width, video_height))
    dummy_draw = ImageDraw.Draw(dummy_img)

    # Wrap text into lines that fit the screen
    lines = wrap_text_for_video(text, video_width, font, dummy_draw)
    
    def make_frame(t):
        """Create a frame at time t with character-level highlighting"""
        # Use background image if available, otherwise use solid color
        if background_img is not None:
            img = background_img.copy()  # Copy to avoid modifying the original
        else:
            img = Image.new('RGB', (video_width, video_height), color=bg_color)
        draw = ImageDraw.Draw(img)

        # Find which characters should be highlighted at time t
        active_chars = set()
        for timing in char_timings:
            if timing['start_time'] <= t <= timing['end_time']:
                active_chars.add(timing['position'])

        # Draw text with character-level highlighting
        # Track position of highlighted characters for cat placement
        y_position = (video_height - len(lines) * 70) // 2
        char_position = 0
        cat_x = None
        cat_y = None

        for line in lines:
            # Calculate line width to center it
            line_width = 0
            for char in line:
                bbox = draw.textbbox((0, 0), char, font=font)
                line_width += bbox[2] - bbox[0]

            x_position = (video_width - line_width) // 2

            for char in line:
                # Determine if this character should be highlighted
                is_active = char_position in active_chars

                if is_active:
                    # Highlighted character - high contrast for visibility
                    color = (255, 255, 255)  # White text
                    # Draw bright contrasting background for highlighting
                    bbox = draw.textbbox((x_position, y_position), char, font=font)
                    draw.rectangle([bbox[0] - 4, bbox[1] - 4, bbox[2] + 4, bbox[3] + 4],
                                 fill=(220, 50, 50))  # Bright red background for high contrast

                    # Track position for cat (use middle of highlighted section)
                    if cat_x is None:
                        char_width = bbox[2] - bbox[0]
                        cat_x = x_position + char_width // 2
                        cat_y = y_position
                else:
                    # Normal character - dark brown for good contrast with warm beige background
                    color = (80, 50, 30)  # Dark brown - matches the "Purrfect Bytes" text in the background

                # Draw the character
                if char != ' ' or not is_active:  # Don't draw space backgrounds
                    draw.text((x_position, y_position), char, font=font, fill=color)

                # Calculate next character position
                bbox = draw.textbbox((0, 0), char, font=font)
                char_width = bbox[2] - bbox[0]
                x_position += char_width

                char_position += 1

            y_position += 70  # Line height

        # Draw cat logo above the highlighted character
        if cat_logo is not None and cat_x is not None and cat_y is not None:
            # Position cat above the text with some offset
            cat_offset_y = cat_size + 10  # Position cat above text
            cat_paste_x = int(cat_x - cat_size // 2)
            cat_paste_y = int(cat_y - cat_offset_y)

            # Ensure cat stays within bounds
            cat_paste_x = max(0, min(cat_paste_x, video_width - cat_size))
            cat_paste_y = max(0, min(cat_paste_y, video_height - cat_size))

            # Debug: print cat position for first frame
            if t < 0.1:
                print(f"🐱 Cat positioned at ({cat_paste_x}, {cat_paste_y}) for highlighted char at ({cat_x}, {cat_y})")

            # Paste cat with transparency
            img.paste(cat_logo, (cat_paste_x, cat_paste_y), cat_logo)

        # Draw QR code overlay in bottom left corner
        if qr_code_img is not None:
            # Position: bottom left corner
            qr_x = qr_margin
            qr_y = video_height - qr_size - qr_margin

            # Apply opacity by creating a semi-transparent overlay
            qr_with_opacity = qr_code_img.copy()
            alpha = qr_with_opacity.split()[3]  # Get alpha channel
            alpha = alpha.point(lambda p: int(p * qr_opacity))  # Apply configured opacity
            qr_with_opacity.putalpha(alpha)

            # Paste QR code with transparency
            img.paste(qr_with_opacity, (qr_x, qr_y), qr_with_opacity)

            # Debug: print QR position for first frame
            if t < 0.1:
                print(f"📱 QR code positioned at ({qr_x}, {qr_y})")

        return np.array(img)
    
    # Create video clip from the frame function
    video_clip = VideoClip(make_frame, duration=duration)
    
    # Add audio
    video = video_clip.with_audio(audio)
    
    # Write video file
    video.write_videofile(
        str(output_path),
        fps=fps,
        codec='libx264',
        audio_codec='aac',
        temp_audiofile='temp-audio.m4a',
        remove_temp=True,
        logger=None
    )
    
    # Clean up
    video.close()
    audio.close()

def create_video_with_text(text, audio_path, output_path, duration=None, font_size=48, show_qr_code=False):
    """Main function to create video with character-level text highlighting and optional QR code"""
    return create_character_animated_video(text, audio_path, output_path, font_size=font_size, show_qr_code=show_qr_code)

def create_preview_frame(text, font_size=48, show_qr_code=False, highlight_position=0):
    """Generate a single preview frame showing how the video will look"""
    from src.config.settings import QR_CODE_CONFIG

    # Video settings
    video_width = 1280
    video_height = 720
    bg_color = (30, 30, 40)

    # Font settings
    font = load_font(font_size, text=text)

    # Load background image
    background_img = None
    try:
        bg_path = ASSETS_DIR / "background.png"
        bg_img = Image.open(bg_path).convert("RGB")
        background_img = bg_img.resize((video_width, video_height), Image.Resampling.LANCZOS)
    except Exception as e:
        print(f"⚠ Background image not found: {e}")

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
        except Exception as e:
            print(f"⚠ QR code image not found: {e}")

    # Load cat logo
    cat_logo = None
    cat_size = 80
    try:
        logo_path = ASSETS_DIR / "logo_small.png"
        cat_img = Image.open(logo_path).convert("RGBA")
        cat_logo = cat_img.resize((cat_size, cat_size), Image.Resampling.LANCZOS)
    except Exception as e:
        print(f"⚠ Cat logo not found: {e}")

    # Create image
    if background_img is not None:
        img = background_img.copy()
    else:
        img = Image.new('RGB', (video_width, video_height), color=bg_color)
    draw = ImageDraw.Draw(img)

    # Create dummy image for text wrapping
    dummy_img = Image.new('RGB', (video_width, video_height))
    dummy_draw = ImageDraw.Draw(dummy_img)
    lines = wrap_text_for_video(text, video_width, font, dummy_draw)

    # Draw text with highlighting at specified position
    y_position = (video_height - len(lines) * 70) // 2
    char_position = 0
    cat_x = None
    cat_y = None

    for line in lines:
        # Calculate line width to center it
        line_width = 0
        for char in line:
            bbox = draw.textbbox((0, 0), char, font=font)
            line_width += bbox[2] - bbox[0]

        x_position = (video_width - line_width) // 2

        for char in line:
            # Highlight character at specified position
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

    # Draw cat logo
    if cat_logo is not None and cat_x is not None and cat_y is not None:
        cat_offset_y = cat_size + 10
        cat_paste_x = int(cat_x - cat_size // 2)
        cat_paste_y = int(cat_y - cat_offset_y)
        cat_paste_x = max(0, min(cat_paste_x, video_width - cat_size))
        cat_paste_y = max(0, min(cat_paste_y, video_height - cat_size))
        img.paste(cat_logo, (cat_paste_x, cat_paste_y), cat_logo)

    # Draw QR code
    if qr_code_img is not None:
        qr_x = qr_margin
        qr_y = video_height - qr_size - qr_margin
        qr_with_opacity = qr_code_img.copy()
        alpha = qr_with_opacity.split()[3]
        alpha = alpha.point(lambda p: int(p * qr_opacity))
        qr_with_opacity.putalpha(alpha)
        img.paste(qr_with_opacity, (qr_x, qr_y), qr_with_opacity)

    return img

@app.post("/preview")
async def generate_preview(
    text: str = Form(...),
    font_size: int = Form(48),
    show_qr_code: bool = Form(False),
    highlight_position: int = Form(0)
):
    """Generate a preview frame showing how the video will look"""
    if not text:
        return {"error": "No text provided"}

    if font_size < 16 or font_size > 200:
        font_size = 48

    try:
        # Generate preview frame
        preview_img = create_preview_frame(text, font_size, show_qr_code, highlight_position)

        # Save preview to temporary file
        preview_filename = f"preview_{uuid.uuid4()}.png"
        preview_path = VIDEO_DIR / preview_filename
        preview_img.save(str(preview_path), format='PNG')

        return {
            "success": True,
            "preview_url": f"/download-video/{preview_filename}",
            "message": "Preview generated successfully"
        }
    except Exception as e:
        print(f"Error generating preview: {e}")
        import traceback
        traceback.print_exc()
        return {"error": f"Failed to generate preview: {str(e)}"}

@app.post("/convert-to-video")
async def convert_text_to_video(
    text: str = Form(...),
    language: str = Form("en"),
    slow: bool = Form(False),
    repetitions: int = Form(1),
    font_size: int = Form(48),
    show_qr_code: bool = Form(False),
    engine: str = Form("edge")
):
    if not text:
        return {"error": "No text provided"}

    if repetitions < 1 or repetitions > 100:
        return {"error": "Repetitions must be between 1 and 100"}

    # Validate font size
    if font_size < 16 or font_size > 200:
        print(f"Font size {font_size} out of range, using default 48")
        font_size = 48

    print(f"✓ Received font_size parameter: {font_size}")
    print(f"✓ QR code overlay: {'enabled' if show_qr_code else 'disabled'}")
    print(f"✓ Using TTS engine: {engine}")

    # Ensure directories exist (in case they were deleted)
    AUDIO_DIR.mkdir(parents=True, exist_ok=True)
    VIDEO_DIR.mkdir(parents=True, exist_ok=True)

    # Generate unique filename for video
    video_filename = f"{uuid.uuid4()}.mp4"
    video_path = VIDEO_DIR / video_filename

    try:
        # Import TTS service and generate audio with selected engine
        from src.services.tts_service import TTSService
        tts_service = TTSService()
        engine_enum = TTSService.parse_engine(engine)
        audio_path, audio_duration = tts_service.generate_audio(
            text, language, slow, engine=engine_enum
        )

        # Then create video with text and audio (with cat animation) using custom font size and QR code overlay
        create_video_with_text(text, audio_path, video_path, font_size=font_size, show_qr_code=show_qr_code)

        # If only 1 repetition, return the single video
        if repetitions == 1:
            return {
                "success": True,
                "video_filename": video_filename,
                "audio_filename": audio_path.name,
                "video_url": f"/download-video/{video_filename}",
                "audio_url": f"/download/{audio_path.name}"
            }

        # For multiple repetitions, concatenate the video
        from moviepy.video.io.VideoFileClip import VideoFileClip
        from moviepy import concatenate_videoclips

        try:
            # Load the single video
            single_clip = VideoFileClip(str(video_path))

            # Create list of repeated clips
            clips = [single_clip] * repetitions

            # Concatenate
            final_clip = concatenate_videoclips(clips, method="compose")

            # Generate new filename for concatenated video
            concat_filename = f"repeat_{repetitions}x_{uuid.uuid4()}.mp4"
            concat_path = VIDEO_DIR / concat_filename

            # Write concatenated video
            final_clip.write_videofile(
                str(concat_path),
                fps=24,
                codec='libx264',
                audio_codec='aac',
                temp_audiofile='temp-audio.m4a',
                remove_temp=True,
                logger=None
            )

            # Get duration before closing
            duration = single_clip.duration * repetitions

            # Clean up
            single_clip.close()
            final_clip.close()
            video_path.unlink()  # Remove single video

            return {
                "success": True,
                "filename": concat_filename,
                "video_url": f"/download-video/{concat_filename}",
                "audio_url": f"/download/{audio_path.name}",
                "duration": duration,
                "message": f"Video generated and repeated {repetitions} times"
            }

        except Exception as e:
            # If concatenation fails, just return the single video
            print(f"Concatenation failed: {e}, returning single video")
            return {
                "success": True,
                "video_filename": video_filename,
                "audio_filename": audio_path.name,
                "video_url": f"/download-video/{video_filename}",
                "audio_url": f"/download/{audio_path.name}",
                "message": f"Video generated (concatenation failed, showing single video): {str(e)}"
            }

    except Exception as e:
        # Clean up files if error occurs
        if audio_path.exists():
            os.remove(audio_path)
        if video_path.exists():
            os.remove(video_path)
        return {"error": str(e)}

@app.get("/download-video/{filename}")
async def download_video(filename: str):
    filepath = VIDEO_DIR / filename
    if not filepath.exists():
        return {"error": "File not found"}

    return FileResponse(
        path=filepath,
        media_type="video/mp4",
        filename=filename
    )

@app.get("/favicon.ico")
async def favicon():
    """Serve the cat logo as favicon"""
    favicon_path = ASSETS_DIR / "logo_small.png"
    if not favicon_path.exists():
        return {"error": "Favicon not found"}

    return FileResponse(
        path=favicon_path,
        media_type="image/png"
    )


@app.post("/detect-language")
async def detect_language(text: str = Form(...)):
    """Detect language of input text"""
    if not text or len(text.strip()) < 3:
        return {"language": "en", "confidence": 0.0, "language_name": "English"}
    
    # Language code mapping to names and gTTS codes
    language_mapping = {
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
        'vi': {'name': 'Vietnamese', 'gtts': 'vi'},
    }
    
    try:
        detected_lang = detect(text.strip())
        
        if detected_lang in language_mapping:
            lang_info = language_mapping[detected_lang]
            return {
                "language": lang_info['gtts'],
                "language_name": lang_info['name'],
                "confidence": 0.9,  # langdetect doesn't provide confidence, using estimated value
                "detected_code": detected_lang
            }
        else:
            # Fallback to English for unsupported languages
            return {
                "language": "en",
                "language_name": "English",
                "confidence": 0.5,
                "detected_code": detected_lang,
                "note": f"Detected '{detected_lang}' but using English as fallback"
            }
            
    except LangDetectException:
        # If detection fails, default to English
        return {
            "language": "en",
            "language_name": "English", 
            "confidence": 0.0,
            "error": "Could not detect language, defaulting to English"
        }

if __name__ == "__main__":
    import uvicorn
    uvicorn.run(app, host="0.0.0.0", port=8000)