"""Video generation service with character-level highlighting."""

import uuid
from pathlib import Path
from typing import Set, List, Tuple, Optional
import numpy as np

from moviepy.video.VideoClip import VideoClip
from moviepy.audio.io.AudioFileClip import AudioFileClip
from moviepy.video.io.VideoFileClip import VideoFileClip
from moviepy import concatenate_videoclips
from PIL import Image, ImageDraw

from src.config.settings import VIDEO_DIR, VIDEO_CONFIG
from src.models.schemas import AudioAnalysis, VideoConfig, CharacterTiming
from src.utils.text_utils import wrap_text_for_video
from src.utils.font_utils import load_font
from src.utils.logger import get_logger

logger = get_logger(__name__)

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
    
    def concatenate_videos(
        self,
        video_paths: List[Path],
        output_filename: Optional[str] = None,
        method: str = "compose"
    ) -> Path:
        """
        Concatenate multiple video files into a single file.
        
        Args:
            video_paths: List of paths to video files to concatenate
            output_filename: Optional output filename (generates UUID if not provided)
            method: Concatenation method ('compose' or 'chain')
            
        Returns:
            Path to the concatenated video file
            
        Raises:
            ValueError: If no video files provided or files don't exist
            Exception: If concatenation fails
        """
        if not video_paths:
            raise ValueError("No video files provided for concatenation")
        
        # Validate all paths exist
        for path in video_paths:
            if not path.exists():
                raise ValueError(f"Video file not found: {path}")
        
        # Generate output filename if not provided
        if not output_filename:
            output_filename = f"concat_{uuid.uuid4()}.mp4"
        output_path = self.video_dir / output_filename
        
        try:
            # Load video clips
            clips = []
            for video_path in video_paths:
                clip = VideoFileClip(str(video_path))
                clips.append(clip)
            
            # Concatenate videos
            final_clip = concatenate_videoclips(clips, method=method)
            
            # Write the concatenated video
            final_clip.write_videofile(
                str(output_path),
                fps=self.config.fps,
                codec='libx264',
                audio_codec='aac',
                temp_audiofile='temp-audio.m4a',
                remove_temp=True,
                logger=None  # Suppress output
            )
            
            # Clean up clips
            for clip in clips:
                clip.close()
            final_clip.close()
            
            return output_path
            
        except Exception as e:
            # Clean up if file was partially created
            if output_path.exists():
                output_path.unlink()
            raise Exception(f"Failed to concatenate video files: {str(e)}")
    
    def generate_multiple_and_concatenate(
        self,
        texts: List[str],
        audio_paths: List[Path],
        audio_analyses: List[AudioAnalysis],
        output_filename: Optional[str] = None
    ) -> Tuple[Path, List[Path]]:
        """
        Generate multiple videos from texts and audio, then concatenate them.
        
        Args:
            texts: List of texts to display in videos
            audio_paths: List of corresponding audio file paths
            audio_analyses: List of corresponding audio analyses
            output_filename: Optional output filename for concatenated file
            
        Returns:
            Tuple of (concatenated_video_path, individual_video_paths)
            
        Raises:
            ValueError: If lists have different lengths
            Exception: If generation or concatenation fails
        """
        # Validate inputs
        if not (len(texts) == len(audio_paths) == len(audio_analyses)):
            raise ValueError("Texts, audio paths, and analyses must have the same length")
        
        if not texts:
            raise ValueError("No texts provided for video generation")
        
        individual_paths = []
        
        try:
            # Generate individual video files
            for text, audio_path, audio_analysis in zip(texts, audio_paths, audio_analyses):
                video_path = self.generate_video(text, audio_path, audio_analysis)
                individual_paths.append(video_path)
            
            # Concatenate all video files
            concatenated_path = self.concatenate_videos(individual_paths, output_filename)
            
            return concatenated_path, individual_paths
            
        except Exception as e:
            # Clean up any generated files on failure
            for path in individual_paths:
                if path.exists():
                    try:
                        path.unlink()
                    except OSError:
                        pass
            raise Exception(f"Failed to generate and concatenate videos: {str(e)}")
    
    def generate_and_repeat(
        self,
        text: str,
        single_audio_path: Path,
        audio_analysis: AudioAnalysis,
        repetitions: int = 10,
        output_filename: Optional[str] = None
    ) -> Path:
        """
        Generate one video with proper highlighting, then repeat it multiple times.
        
        Args:
            text: Text to display in video
            single_audio_path: Path to single iteration audio file
            audio_analysis: Audio timing analysis for single iteration
            repetitions: Number of times to repeat the video
            output_filename: Optional output filename for concatenated file
            
        Returns:
            Path to the video file
            
        Raises:
            Exception: If generation or concatenation fails
        """
        if repetitions < 1:
            raise ValueError("Repetitions must be at least 1")
        
        # Generate single video with proper highlighting
        single_video_path = self.generate_video(text, single_audio_path, audio_analysis)
        
        try:
            # If only 1 repetition, just return the single video
            if repetitions == 1:
                if output_filename:
                    output_path = self.video_dir / output_filename
                    single_video_path.rename(output_path)
                    return output_path
                return single_video_path
            
            # For multiple repetitions, try moviepy concatenation first
            try:
                # Load the single video clip
                single_clip = VideoFileClip(str(single_video_path))
                
                # Create list of the same clip repeated
                clips = [single_clip] * repetitions
                
                # Concatenate the clips
                final_clip = concatenate_videoclips(clips, method="compose")
                
                # Generate output filename
                if not output_filename:
                    output_filename = f"repeat_{repetitions}x_{uuid.uuid4()}.mp4"
                output_path = self.video_dir / output_filename
                
                # Write the concatenated video
                final_clip.write_videofile(
                    str(output_path),
                    fps=self.config.fps,
                    codec='libx264',
                    audio_codec='aac',
                    temp_audiofile='temp-audio.m4a',
                    remove_temp=True,
                    logger=None
                )
                
                # Clean up
                single_clip.close()
                final_clip.close()
                single_video_path.unlink()  # Remove single video
                
                return output_path
                
            except Exception as e:
                # Fallback: Use the single video and duplicate audio externally
                # This isn't perfect but better than broken highlighting
                logger.warning(f"Video concatenation failed, using single video: {e}")
                
                if output_filename:
                    output_path = self.video_dir / output_filename
                    single_video_path.rename(output_path)
                    return output_path
                return single_video_path
                
        except Exception as e:
            # Clean up on failure
            if single_video_path.exists():
                try:
                    single_video_path.unlink()
                except OSError:
                    pass
            raise Exception(f"Failed to generate and repeat video: {str(e)}")