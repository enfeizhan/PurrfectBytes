"""API routes for the PurrfectBytes application."""

import os
import time
import uuid
from pathlib import Path
from typing import Optional

from fastapi import APIRouter, Form, HTTPException
from fastapi.responses import FileResponse

from src.models.schemas import (
    LanguageDetectionResult, 
    ConversionResult, 
    HealthCheck,
    ErrorResponse
)
from src.services.language_detection import LanguageDetectionService
from src.services.tts_service import TTSService
from src.services.video_service import VideoService
from src.config.settings import AUDIO_DIR, VIDEO_DIR
from src.utils.logger import get_logger, RequestLogger, log_error
from src.utils.exceptions import (
    TTSGenerationError, 
    VideoGenerationError, 
    LanguageDetectionError,
    FileNotFoundError as PurrfectFileNotFoundError
)

# Initialize services
language_service = LanguageDetectionService()
tts_service = TTSService()
video_service = VideoService()
logger = get_logger(__name__)

# Create router
router = APIRouter()

@router.post("/detect-language", response_model=LanguageDetectionResult)
async def detect_language_endpoint(text: str = Form(...)):
    """Detect language of input text."""
    with RequestLogger(logger, "language detection"):
        try:
            result = language_service.detect_language(text)
            logger.info(f"Language detected: {result.language} ({result.confidence})")
            return result
        except Exception as e:
            log_error(logger, e, "language detection")
            raise HTTPException(status_code=500, detail="Language detection failed")

@router.post("/convert", response_model=ConversionResult)
async def convert_to_audio(
    text: str = Form(...),
    language: str = Form("en"),
    slow: bool = Form(False)
):
    """Convert text to audio."""
    with RequestLogger(logger, f"audio conversion ({language})"):
        try:
            # Validate language
            if not language_service.is_supported_language(language):
                language = "en"
                logger.warning(f"Unsupported language, defaulting to English")
            
            # Generate audio
            audio_path, duration = tts_service.generate_audio(text, language, slow)
            
            return ConversionResult(
                success=True,
                audio_filename=audio_path.name,
                audio_url=f"/download/{audio_path.name}",
                duration=duration
            )
            
        except Exception as e:
            log_error(logger, e, "audio conversion")
            raise HTTPException(
                status_code=500, 
                detail=f"Audio conversion failed: {str(e)}"
            )

@router.post("/convert-to-video", response_model=ConversionResult)
async def convert_to_video(
    text: str = Form(...),
    language: str = Form("en"), 
    slow: bool = Form(False)
):
    """Convert text to video with synchronized highlighting."""
    with RequestLogger(logger, f"video conversion ({language})"):
        audio_path = None
        video_path = None
        
        try:
            # Validate language
            if not language_service.is_supported_language(language):
                language = "en"
                logger.warning(f"Unsupported language, defaulting to English")
            
            # Generate audio first
            logger.info("Generating audio for video")
            audio_path, duration = tts_service.generate_audio(text, language, slow)
            
            # Analyze audio timing
            logger.info("Analyzing audio timing")
            audio_analysis = tts_service.analyze_audio_timing(text, audio_path)
            
            # Generate video
            logger.info("Generating video with character highlighting")
            video_path = video_service.generate_video(text, audio_path, audio_analysis)
            
            logger.info(f"Video generated successfully: {video_path.name}")
            
            return ConversionResult(
                success=True,
                audio_filename=audio_path.name,
                video_filename=video_path.name,
                audio_url=f"/download/{audio_path.name}",
                video_url=f"/download-video/{video_path.name}",
                duration=duration
            )
            
        except Exception as e:
            # Clean up files on error
            if audio_path and audio_path.exists():
                try:
                    audio_path.unlink()
                except OSError:
                    pass
            if video_path and video_path.exists():
                try:
                    video_path.unlink()
                except OSError:
                    pass
            
            log_error(logger, e, "video conversion")
            raise HTTPException(
                status_code=500,
                detail=f"Video conversion failed: {str(e)}"
            )

@router.get("/download/{filename}")
async def download_audio(filename: str):
    """Download audio file."""
    file_path = AUDIO_DIR / filename
    
    if not file_path.exists():
        logger.warning(f"Audio file not found: {filename}")
        raise HTTPException(status_code=404, detail="Audio file not found")
    
    logger.info(f"Serving audio file: {filename}")
    return FileResponse(
        path=file_path,
        media_type="audio/mpeg",
        filename=filename
    )

@router.get("/download-video/{filename}")
async def download_video(filename: str):
    """Download video file."""
    file_path = VIDEO_DIR / filename
    
    if not file_path.exists():
        logger.warning(f"Video file not found: {filename}")
        raise HTTPException(status_code=404, detail="Video file not found")
    
    logger.info(f"Serving video file: {filename}")
    return FileResponse(
        path=file_path,
        media_type="video/mp4",
        filename=filename
    )

@router.delete("/audio/{filename}")
async def delete_audio(filename: str):
    """Delete audio file."""
    file_path = AUDIO_DIR / filename
    
    try:
        if file_path.exists():
            file_path.unlink()
            logger.info(f"Deleted audio file: {filename}")
            return {"success": True, "message": "File deleted"}
        else:
            raise HTTPException(status_code=404, detail="File not found")
    except HTTPException:
        raise
    except Exception as e:
        log_error(logger, e, "audio file deletion")
        raise HTTPException(status_code=500, detail="Failed to delete file")

@router.delete("/video/{filename}")  
async def delete_video(filename: str):
    """Delete video file."""
    file_path = VIDEO_DIR / filename
    
    try:
        if file_path.exists():
            file_path.unlink()
            logger.info(f"Deleted video file: {filename}")
            return {"success": True, "message": "File deleted"}
        else:
            raise HTTPException(status_code=404, detail="File not found")
    except HTTPException:
        raise
    except Exception as e:
        log_error(logger, e, "video file deletion")
        raise HTTPException(status_code=500, detail="Failed to delete file")

@router.get("/health", response_model=HealthCheck)
async def health_check():
    """Health check endpoint."""
    start_time = time.time()
    
    # Check services
    features = {
        "language_detection": True,
        "tts_generation": True, 
        "video_generation": True,
        "audio_download": AUDIO_DIR.exists(),
        "video_download": VIDEO_DIR.exists()
    }
    
    return HealthCheck(
        status="healthy",
        version="1.0.0",
        uptime=time.time() - start_time,
        features=features
    )

@router.get("/supported-languages")
async def get_supported_languages():
    """Get list of supported languages."""
    return {
        "languages": language_service.get_supported_languages(),
        "total": len(language_service.get_supported_languages())
    }

@router.post("/cleanup")
async def cleanup_old_files(max_age_hours: int = 24):
    """Clean up old generated files."""
    with RequestLogger(logger, "file cleanup"):
        try:
            audio_removed = tts_service.cleanup_old_files(max_age_hours)
            video_removed = video_service.cleanup_old_files(max_age_hours)
            
            total_removed = audio_removed + video_removed
            logger.info(f"Cleanup completed: {total_removed} files removed")
            
            return {
                "success": True,
                "audio_files_removed": audio_removed,
                "video_files_removed": video_removed,
                "total_files_removed": total_removed
            }
            
        except Exception as e:
            log_error(logger, e, "file cleanup")
            raise HTTPException(status_code=500, detail="Cleanup failed")