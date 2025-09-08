"""Text-to-Speech service using gTTS."""

import uuid
from pathlib import Path
from typing import Tuple, Optional, List

from gtts import gTTS
import librosa
from pydub import AudioSegment

from src.config.settings import AUDIO_DIR, AUDIO_CONFIG
from src.utils.text_utils import clean_text_for_tts
from src.models.schemas import CharacterTiming, AudioAnalysis
from src.utils.logger import get_logger

logger = get_logger(__name__)

class TTSService:
    """Service for text-to-speech conversion and audio analysis."""
    
    def __init__(self):
        self.audio_dir = AUDIO_DIR
        self.audio_config = AUDIO_CONFIG
    
    def generate_audio(
        self, 
        text: str, 
        language: str = "en", 
        slow: bool = False
    ) -> Tuple[Path, float]:
        """
        Generate audio file from text.
        
        Args:
            text: Text to convert to speech
            language: Language code for TTS
            slow: Whether to use slow speech speed
            
        Returns:
            Tuple of (audio_file_path, duration_in_seconds)
            
        Raises:
            Exception: If audio generation fails
        """
        # Clean and prepare text
        clean_text = clean_text_for_tts(text)
        
        if not clean_text:
            raise ValueError("No valid text provided for TTS")
        
        # Generate unique filename
        audio_filename = f"{uuid.uuid4()}.{self.audio_config['format']}"
        audio_path = self.audio_dir / audio_filename
        
        try:
            # Generate speech
            tts = gTTS(text=clean_text, lang=language, slow=slow)
            tts.save(str(audio_path))
            
            # Get audio duration
            duration = self._get_audio_duration(audio_path)
            
            return audio_path, duration
            
        except Exception as e:
            # Clean up file if it was created
            if audio_path.exists():
                audio_path.unlink()
            raise Exception(f"Failed to generate audio: {str(e)}")
    
    def analyze_audio_timing(self, text: str, audio_path: Path) -> AudioAnalysis:
        """
        Analyze audio to create character-level timing information.
        
        Args:
            text: Original text used for TTS
            audio_path: Path to the generated audio file
            
        Returns:
            AudioAnalysis with timing information
        """
        try:
            # Load audio with librosa for analysis
            y, sr_rate = librosa.load(str(audio_path))
            duration = librosa.get_duration(y=y, sr=sr_rate)
            
            # Calculate character timings
            char_timings = self._calculate_character_timings(text, duration)
            
            # Calculate additional metrics
            word_count = len(text.split())
            words_per_second = word_count / duration if duration > 0 else 0
            
            return AudioAnalysis(
                duration=duration,
                character_timings=char_timings,
                words_per_second=words_per_second,
                lead_time=0.3,  # From config
                overlap_duration=0.4  # From config
            )
            
        except Exception as e:
            # Fallback to simple timing calculation
            return self._create_fallback_timing(text, audio_path)
    
    def _get_audio_duration(self, audio_path: Path) -> float:
        """Get duration of audio file."""
        try:
            y, sr = librosa.load(str(audio_path))
            return librosa.get_duration(y=y, sr=sr)
        except Exception:
            # Fallback estimation based on text length
            return len(audio_path.read_bytes()) / 16000  # Rough estimate
    
    def _calculate_character_timings(
        self, 
        text: str, 
        duration: float, 
        lead_time: float = 0.3, 
        overlap_duration: float = 0.4
    ) -> list[CharacterTiming]:
        """Calculate timing for each character in the text."""
        chars = list(text.replace(' ', ''))  # Remove spaces for timing calculation
        char_count = len(chars)
        
        if char_count == 0:
            return []
        
        char_timings = []
        chars_per_second = char_count / duration if duration > 0 else 1
        
        current_pos = 0
        for i, char in enumerate(text):
            if char == ' ':
                # Spaces get shorter timing
                start_time = max(0, (current_pos / chars_per_second) - lead_time)
                end_time = ((current_pos + 0.5) / chars_per_second) + overlap_duration
                current_pos += 0.5
            else:
                # Regular characters
                start_time = max(0, (current_pos / chars_per_second) - lead_time)
                end_time = ((current_pos + 1) / chars_per_second) + overlap_duration
                current_pos += 1
            
            char_timings.append(CharacterTiming(
                char=char,
                start_time=start_time,
                end_time=end_time,
                position=i
            ))
        
        return char_timings
    
    def _create_fallback_timing(self, text: str, audio_path: Path) -> AudioAnalysis:
        """Create fallback timing when audio analysis fails."""
        # Estimate duration
        duration = len(text) * 0.1  # Rough estimate: 10 chars per second
        
        # Create simple linear timing
        char_timings = []
        char_duration = duration / len(text) if len(text) > 0 else 0.1
        
        for i, char in enumerate(text):
            char_timings.append(CharacterTiming(
                char=char,
                start_time=i * char_duration,
                end_time=(i + 1) * char_duration,
                position=i
            ))
        
        return AudioAnalysis(
            duration=duration,
            character_timings=char_timings,
            words_per_second=len(text.split()) / duration if duration > 0 else 0,
            lead_time=0.3,
            overlap_duration=0.4
        )
    
    def cleanup_old_files(self, max_age_hours: int = 24) -> int:
        """
        Remove old audio files.
        
        Args:
            max_age_hours: Maximum age of files to keep
            
        Returns:
            Number of files removed
        """
        import time
        
        removed_count = 0
        current_time = time.time()
        max_age_seconds = max_age_hours * 3600
        
        for audio_file in self.audio_dir.glob("*.mp3"):
            if current_time - audio_file.stat().st_mtime > max_age_seconds:
                try:
                    audio_file.unlink()
                    removed_count += 1
                except OSError:
                    pass  # File might be in use or already deleted
        
        return removed_count
    
    def concatenate_audio(
        self,
        audio_paths: List[Path],
        output_filename: Optional[str] = None
    ) -> Path:
        """
        Concatenate multiple audio files into a single file.
        
        Args:
            audio_paths: List of paths to audio files to concatenate
            output_filename: Optional output filename (generates UUID if not provided)
            
        Returns:
            Path to the concatenated audio file
            
        Raises:
            ValueError: If no audio files provided or files don't exist
            Exception: If concatenation fails
        """
        if not audio_paths:
            raise ValueError("No audio files provided for concatenation")
        
        # Validate all paths exist
        for path in audio_paths:
            if not path.exists():
                raise ValueError(f"Audio file not found: {path}")
        
        # Generate output filename if not provided
        if not output_filename:
            output_filename = f"concat_{uuid.uuid4()}.{self.audio_config['format']}"
        output_path = self.audio_dir / output_filename
        
        try:
            # Load and concatenate audio files
            combined = AudioSegment.empty()
            
            for audio_path in audio_paths:
                audio = AudioSegment.from_file(str(audio_path))
                combined += audio
            
            # Export the concatenated audio
            combined.export(str(output_path), format=self.audio_config['format'])
            
            return output_path
            
        except Exception as e:
            # Clean up if file was partially created
            if output_path.exists():
                output_path.unlink()
            raise Exception(f"Failed to concatenate audio files: {str(e)}")
    
    def generate_multiple_and_concatenate(
        self,
        texts: List[str],
        language: str = "en",
        slow: bool = False,
        output_filename: Optional[str] = None
    ) -> Tuple[Path, List[Path], float]:
        """
        Generate multiple audio files from texts and concatenate them.
        
        Args:
            texts: List of texts to convert to speech
            language: Language code for TTS
            slow: Whether to use slow speech speed
            output_filename: Optional output filename for concatenated file
            
        Returns:
            Tuple of (concatenated_file_path, individual_file_paths, total_duration)
            
        Raises:
            Exception: If generation or concatenation fails
        """
        if not texts:
            raise ValueError("No texts provided for TTS generation")
        
        individual_paths = []
        total_duration = 0.0
        
        try:
            # Generate individual audio files
            for text in texts:
                audio_path, duration = self.generate_audio(text, language, slow)
                individual_paths.append(audio_path)
                total_duration += duration
            
            # Concatenate all audio files
            concatenated_path = self.concatenate_audio(individual_paths, output_filename)
            
            return concatenated_path, individual_paths, total_duration
            
        except Exception as e:
            # Clean up any generated files on failure
            for path in individual_paths:
                if path.exists():
                    try:
                        path.unlink()
                    except OSError:
                        pass
            raise Exception(f"Failed to generate and concatenate audio: {str(e)}")
    
    def generate_and_repeat(
        self,
        text: str,
        repetitions: int = 10,
        language: str = "en",
        slow: bool = False,
        output_filename: Optional[str] = None
    ) -> Tuple[Path, float]:
        """
        Generate a single audio file and concatenate it multiple times.
        
        Args:
            text: Text to convert to speech
            repetitions: Number of times to repeat the audio
            language: Language code for TTS
            slow: Whether to use slow speech speed
            output_filename: Optional output filename for concatenated file
            
        Returns:
            Tuple of (concatenated_file_path, total_duration)
            
        Raises:
            Exception: If generation or concatenation fails
        """
        if repetitions < 1:
            raise ValueError("Repetitions must be at least 1")
        
        # Generate single audio file
        audio_path, duration = self.generate_audio(text, language, slow)
        
        try:
            # If only 1 repetition, just return the generated file
            if repetitions == 1:
                return audio_path, duration
            
            # Try to use pydub if ffmpeg is available
            try:
                # Load the audio once
                audio = AudioSegment.from_file(str(audio_path))
                
                # Generate output filename if not provided
                if not output_filename:
                    output_filename = f"repeat_{repetitions}x_{uuid.uuid4()}.{self.audio_config['format']}"
                output_path = self.audio_dir / output_filename
                
                # Concatenate by repeating the same audio
                combined = AudioSegment.empty()
                for _ in range(repetitions):
                    combined += audio
                
                # Export the concatenated audio
                combined.export(str(output_path), format=self.audio_config['format'])
                
                # Clean up the original file
                audio_path.unlink()
                
                return output_path, duration * repetitions
                
            except (OSError, FileNotFoundError) as ffmpeg_error:
                # Fallback: Generate repeated text and use gTTS directly
                logger.warning(f"FFmpeg not available, using text repetition fallback: {ffmpeg_error}")
                
                # Clean up the single audio file
                audio_path.unlink()
                
                # Create repeated text with natural pauses
                repeated_text = (" ... ".join([text] * repetitions))
                
                # Generate output filename if not provided
                if not output_filename:
                    output_filename = f"repeat_{repetitions}x_{uuid.uuid4()}.{self.audio_config['format']}"
                output_path = self.audio_dir / output_filename
                
                # Generate the repeated audio directly
                tts = gTTS(text=repeated_text, lang=language, slow=slow)
                tts.save(str(output_path))
                
                # Estimate duration (approximate)
                estimated_duration = duration * repetitions
                
                return output_path, estimated_duration
            
        except Exception as e:
            # Clean up on failure
            if audio_path.exists():
                try:
                    audio_path.unlink()
                except OSError:
                    pass
            raise Exception(f"Failed to repeat and concatenate audio: {str(e)}")