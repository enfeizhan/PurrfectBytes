"""Data models and schemas for the PurrfectBytes application."""

from typing import List, Optional, Dict, Any
from pydantic import BaseModel, Field
from enum import Enum

class OutputFormat(str, Enum):
    """Supported output formats."""
    AUDIO = "audio"
    VIDEO = "video"

class LanguageDetectionResult(BaseModel):
    """Language detection response."""
    language: str = Field(..., description="Detected language code for TTS")
    language_name: str = Field(..., description="Human-readable language name")
    confidence: float = Field(..., ge=0.0, le=1.0, description="Detection confidence")
    detected_code: Optional[str] = Field(None, description="Original detected language code")
    note: Optional[str] = Field(None, description="Additional information")
    error: Optional[str] = Field(None, description="Error message if detection failed")

class TextToSpeechRequest(BaseModel):
    """Request for text-to-speech conversion."""
    text: str = Field(..., min_length=1, description="Text to convert to speech")
    language: str = Field(default="en", description="Language code for TTS")
    slow: bool = Field(default=False, description="Use slow speech speed")
    format: OutputFormat = Field(default=OutputFormat.AUDIO, description="Output format")

class CharacterTiming(BaseModel):
    """Timing information for a single character."""
    char: str = Field(..., description="The character")
    start_time: float = Field(..., ge=0, description="Start time in seconds")
    end_time: float = Field(..., ge=0, description="End time in seconds")  
    position: int = Field(..., ge=0, description="Character position in original text")

class AudioAnalysis(BaseModel):
    """Audio analysis results."""
    duration: float = Field(..., gt=0, description="Audio duration in seconds")
    character_timings: List[CharacterTiming] = Field(..., description="Character-level timing data")
    words_per_second: float = Field(..., ge=0, description="Estimated words per second")
    lead_time: float = Field(..., ge=0, description="Lead time for highlighting")
    overlap_duration: float = Field(..., ge=0, description="Character highlight overlap duration")

class ConversionResult(BaseModel):
    """Result of audio/video conversion."""
    success: bool = Field(..., description="Whether conversion succeeded")
    audio_filename: Optional[str] = Field(None, description="Generated audio filename")
    video_filename: Optional[str] = Field(None, description="Generated video filename")
    audio_url: Optional[str] = Field(None, description="Download URL for audio")
    video_url: Optional[str] = Field(None, description="Download URL for video") 
    error: Optional[str] = Field(None, description="Error message if conversion failed")
    duration: Optional[float] = Field(None, description="Audio/video duration in seconds")
    message: Optional[str] = Field(None, description="Status message")

class VideoConfig(BaseModel):
    """Video generation configuration."""
    width: int = Field(default=1280, gt=0)
    height: int = Field(default=720, gt=0)
    fps: int = Field(default=24, gt=0)
    bg_color: tuple = Field(default=(30, 30, 40))
    font_size: int = Field(default=48, gt=0)
    font_size_bold: int = Field(default=56, gt=0)
    lead_time: float = Field(default=0.3, ge=0)
    overlap_duration: float = Field(default=0.4, ge=0)
    padding: int = Field(default=50, ge=0)
    line_height: int = Field(default=70, gt=0)

class HealthCheck(BaseModel):
    """Health check response."""
    status: str = Field(..., description="Service status")
    version: str = Field(..., description="Application version")
    uptime: float = Field(..., description="Uptime in seconds")
    features: Dict[str, bool] = Field(..., description="Available features")

class ErrorResponse(BaseModel):
    """Standard error response."""
    error: str = Field(..., description="Error message")
    code: Optional[str] = Field(None, description="Error code")
    details: Optional[Dict[str, Any]] = Field(None, description="Additional error details")
    timestamp: Optional[str] = Field(None, description="Error timestamp")

class FileInfo(BaseModel):
    """Information about generated files."""
    filename: str = Field(..., description="File name")
    size: int = Field(..., ge=0, description="File size in bytes")
    created_at: str = Field(..., description="Creation timestamp")
    mime_type: str = Field(..., description="MIME type")
    download_url: str = Field(..., description="Download URL")