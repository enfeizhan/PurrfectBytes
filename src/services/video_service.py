"""Video generation service with character-level highlighting."""

import uuid
from pathlib import Path
from typing import Set
import numpy as np

from moviepy.video.VideoClip import VideoClip
from moviepy.audio.io.AudioFileClip import AudioFileClip
from PIL import Image, ImageDraw

from src.config.settings import VIDEO_DIR, VIDEO_CONFIG
from src.models.schemas import AudioAnalysis, VideoConfig
from src.utils.text_utils import wrap_text_for_video
from src.utils.font_utils import load_font

class VideoService:
    """Service for generating videos with synchronized text highlighting."""
    
    def __init__(self, config: VideoConfig = None):
        self.video_dir = VIDEO_DIR
        self.config = config or VideoConfig(**VIDEO_CONFIG)
    
    def generate_video(
        self, 
        text: str, 
        audio_path: Path, 
        audio_analysis: AudioAnalysis
    ) -> Path:
        """
        Generate a video with character-level highlighting synchronized with audio.
        
        Args:
            text: Original text to display
            audio_path: Path to the audio file
            audio_analysis: Audio timing analysis
            
        Returns:
            Path to generated video file
            
        Raises:
            Exception: If video generation fails
        """
        # Generate unique filename
        video_filename = f"{uuid.uuid4()}.mp4"
        video_path = self.video_dir / video_filename
        
        try:
            # Load audio
            audio = AudioFileClip(str(audio_path))
            duration = audio.duration
            
            # Load fonts
            font = load_font(self.config.font_size)
            font_bold = load_font(self.config.font_size_bold)
            
            # Prepare text wrapping
            dummy_img = Image.new('RGB', (self.config.width, self.config.height))
            dummy_draw = ImageDraw.Draw(dummy_img)
            lines = wrap_text_for_video(
                text, self.config.width, font, dummy_draw, self.config.padding
            )
            
            # Create character timing lookup
            timing_map = self._create_timing_map(audio_analysis.character_timings)
            
            # Create frame generation function
            def make_frame(t):
                return self._generate_frame(
                    t, text, lines, timing_map, font, font_bold
                )
            
            # Create video clip
            video_clip = VideoClip(make_frame, duration=duration)
            video_clip = video_clip.with_audio(audio)
            
            # Write video file
            video_clip.write_videofile(
                str(video_path),
                fps=self.config.fps,
                codec='libx264',
                audio_codec='aac',
                temp_audiofile='temp-audio.m4a',
                remove_temp=True,
                logger=None  # Suppress output
            )
            
            # Clean up
            video_clip.close()
            audio.close()
            
            return video_path
            
        except Exception as e:
            # Clean up partial file
            if video_path.exists():
                video_path.unlink()
            raise Exception(f"Failed to generate video: {str(e)}")
    
    def _create_timing_map(self, character_timings) -> dict:
        """Create a lookup map for character timing by position."""
        return {timing.position: timing for timing in character_timings}
    
    def _generate_frame(
        self, 
        t: float, 
        text: str, 
        lines: list[str], 
        timing_map: dict,
        font, 
        font_bold
    ) -> np.ndarray:
        """Generate a single video frame at time t."""
        # Create image
        img = Image.new('RGB', (self.config.width, self.config.height), 
                       color=self.config.bg_color)
        draw = ImageDraw.Draw(img)
        
        # Find active characters at time t
        active_chars = self._get_active_characters(t, timing_map)
        
        # Draw text with highlighting
        self._draw_text_with_highlighting(
            draw, text, lines, active_chars, font, font_bold
        )
        
        return np.array(img)
    
    def _get_active_characters(self, t: float, timing_map: dict) -> Set[int]:
        """Get set of character positions that should be highlighted at time t."""
        active_chars = set()
        
        for pos, timing in timing_map.items():
            if timing.start_time <= t <= timing.end_time:
                active_chars.add(pos)
        
        return active_chars
    
    def _draw_text_with_highlighting(
        self,
        draw: ImageDraw.ImageDraw,
        text: str,
        lines: list[str], 
        active_chars: Set[int],
        font,
        font_bold
    ):
        """Draw text with character-level highlighting."""
        # Calculate starting Y position (centered vertically)
        y_position = (self.config.height - len(lines) * self.config.line_height) // 2
        char_position = 0
        
        for line in lines:
            # Calculate line width for centering
            line_width = 0
            for char in line:
                bbox = draw.textbbox((0, 0), char, font=font)
                line_width += bbox[2] - bbox[0]
            
            x_position = (self.config.width - line_width) // 2
            
            # Draw each character
            for char in line:
                is_active = char_position in active_chars
                
                if is_active:
                    # Highlighted character
                    use_font = font_bold
                    color = (255, 220, 0)  # Bright yellow
                    
                    # Draw background rectangle
                    bbox = draw.textbbox((x_position, y_position), char, font=use_font)
                    draw.rectangle(
                        [bbox[0] - 2, bbox[1] - 2, bbox[2] + 2, bbox[3] + 2],
                        fill=(80, 80, 120)
                    )
                else:
                    # Normal character
                    use_font = font
                    color = (220, 220, 220)
                
                # Draw the character
                draw.text((x_position, y_position), char, font=use_font, fill=color)
                
                # Move to next character position
                bbox = draw.textbbox((0, 0), char, font=use_font)
                char_width = bbox[2] - bbox[0]
                x_position += char_width
                char_position += 1
            
            y_position += self.config.line_height
    
    def cleanup_old_files(self, max_age_hours: int = 24) -> int:
        """
        Remove old video files.
        
        Args:
            max_age_hours: Maximum age of files to keep
            
        Returns:
            Number of files removed
        """
        import time
        
        removed_count = 0
        current_time = time.time()
        max_age_seconds = max_age_hours * 3600
        
        for video_file in self.video_dir.glob("*.mp4"):
            if current_time - video_file.stat().st_mtime > max_age_seconds:
                try:
                    video_file.unlink()
                    removed_count += 1
                except OSError:
                    pass  # File might be in use or already deleted
        
        return removed_count
    
    def get_video_info(self, video_path: Path) -> dict:
        """Get information about a video file."""
        if not video_path.exists():
            raise FileNotFoundError(f"Video file not found: {video_path}")
        
        try:
            # Get basic file info
            stat = video_path.stat()
            
            # Try to get video duration (basic approach)
            try:
                from moviepy.video.io.VideoFileClip import VideoFileClip
                with VideoFileClip(str(video_path)) as clip:
                    duration = clip.duration
            except Exception:
                duration = None
            
            return {
                "filename": video_path.name,
                "size": stat.st_size,
                "created_at": stat.st_ctime,
                "duration": duration,
                "path": str(video_path)
            }
            
        except Exception as e:
            raise Exception(f"Failed to get video info: {str(e)}")