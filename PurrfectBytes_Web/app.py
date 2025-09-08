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

AUDIO_DIR = Path("audio_files")
AUDIO_DIR.mkdir(exist_ok=True)
VIDEO_DIR = Path("video_files")
VIDEO_DIR.mkdir(exist_ok=True)

templates = Jinja2Templates(directory="templates")

@app.get("/", response_class=HTMLResponse)
async def home(request: Request):
    return templates.TemplateResponse("index.html", {"request": request})

@app.post("/convert")
async def convert_text_to_speech(
    text: str = Form(...),
    language: str = Form("en"),
    slow: bool = Form(False)
):
    if not text:
        return {"error": "No text provided"}
    
    filename = f"{uuid.uuid4()}.mp3"
    filepath = AUDIO_DIR / filename
    
    try:
        tts = gTTS(text=text, lang=language, slow=slow)
        tts.save(str(filepath))
        
        return {
            "success": True,
            "filename": filename,
            "download_url": f"/download/{filename}"
        }
    except Exception as e:
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

def load_font(font_size=48):
    """Load a TrueType font or fall back to default"""
    font = None
    try:
        font = ImageFont.truetype("/Library/Fonts/Arial Unicode.ttf", font_size)
    except:
        try:
            # Try some common system fonts
            for font_path in [
                "/System/Library/Fonts/Helvetica.ttc",
                "/System/Library/Fonts/Avenir.ttc",
            ]:
                try:
                    font = ImageFont.truetype(font_path, font_size)
                    break
                except:
                    continue
        except:
            pass
    
    if font is None:
        font = ImageFont.load_default()
    
    return font

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

def create_character_animated_video(text, audio_path, output_path):
    """Create video with character-level highlighting"""
    
    # Load audio and get duration
    audio = AudioFileClip(str(audio_path))
    duration = audio.duration
    
    # Analyze audio for character timing
    char_timings = analyze_audio_timing(text, audio_path)
    
    # Video settings
    video_width = 1280
    video_height = 720
    fps = 24
    bg_color = (30, 30, 40)
    
    # Font settings
    font_size = 48
    font = load_font(font_size)
    
    # Create a dummy image to calculate text dimensions
    dummy_img = Image.new('RGB', (video_width, video_height))
    dummy_draw = ImageDraw.Draw(dummy_img)
    
    # Wrap text into lines that fit the screen
    lines = wrap_text_for_video(text, video_width, font, dummy_draw)
    
    def make_frame(t):
        """Create a frame at time t with character-level highlighting"""
        img = Image.new('RGB', (video_width, video_height), color=bg_color)
        draw = ImageDraw.Draw(img)
        
        # Find which characters should be highlighted at time t
        active_chars = set()
        for timing in char_timings:
            if timing['start_time'] <= t <= timing['end_time']:
                active_chars.add(timing['position'])
        
        # Draw text with character-level highlighting
        y_position = (video_height - len(lines) * 70) // 2
        char_position = 0
        
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
                    # Highlighted character
                    color = (255, 220, 0)  # Bright yellow
                    # Draw background
                    bbox = draw.textbbox((x_position, y_position), char, font=font)
                    draw.rectangle([bbox[0] - 2, bbox[1] - 2, bbox[2] + 2, bbox[3] + 2], 
                                 fill=(80, 80, 120))
                else:
                    # Normal character
                    color = (220, 220, 220) if char != ' ' else (220, 220, 220)
                
                # Draw the character
                if char != ' ' or not is_active:  # Don't draw space backgrounds
                    draw.text((x_position, y_position), char, font=font, fill=color)
                
                # Calculate next character position
                bbox = draw.textbbox((0, 0), char, font=font)
                char_width = bbox[2] - bbox[0]
                x_position += char_width
                
                char_position += 1
            
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

def create_video_with_text(text, audio_path, output_path, duration=None):
    """Main function to create video with character-level text highlighting"""
    return create_character_animated_video(text, audio_path, output_path)

@app.post("/convert-to-video")
async def convert_text_to_video(
    text: str = Form(...),
    language: str = Form("en"),
    slow: bool = Form(False)
):
    if not text:
        return {"error": "No text provided"}
    
    # Generate unique filenames
    audio_filename = f"{uuid.uuid4()}.mp3"
    video_filename = f"{uuid.uuid4()}.mp4"
    audio_path = AUDIO_DIR / audio_filename
    video_path = VIDEO_DIR / video_filename
    
    try:
        # First create audio
        tts = gTTS(text=text, lang=language, slow=slow)
        tts.save(str(audio_path))
        
        # Then create video with text and audio
        create_video_with_text(text, audio_path, video_path)
        
        return {
            "success": True,
            "video_filename": video_filename,
            "audio_filename": audio_filename,
            "video_url": f"/download-video/{video_filename}",
            "audio_url": f"/download/{audio_filename}"
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