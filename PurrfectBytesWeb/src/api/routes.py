"""API routes for the PurrfectBytes application."""

import os
import time
import uuid
from pathlib import Path
from typing import Optional, List

from fastapi import APIRouter, Form, HTTPException
from fastapi.responses import FileResponse
from pydantic import BaseModel

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

# Request models for concatenation
class ConcatenateAudioRequest(BaseModel):
    texts: List[str]
    language: Optional[str] = None
    slow: bool = False
    output_filename: Optional[str] = None

class ConcatenateVideoRequest(BaseModel):
    texts: List[str]
    language: Optional[str] = None
    slow: bool = False
    output_filename: Optional[str] = None

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
    slow: bool = Form(False),
    font_size: int = Form(48)
):
    """Convert text to video with synchronized highlighting."""
    with RequestLogger(logger, f"video conversion ({language}, font_size={font_size})"):
        audio_path = None
        video_path = None

        try:
            # Validate language
            if not language_service.is_supported_language(language):
                language = "en"
                logger.warning(f"Unsupported language, defaulting to English")

            # Validate font size (reasonable range)
            if font_size < 16 or font_size > 200:
                logger.warning(f"Font size {font_size} out of range, using default 48")
                font_size = 48

            logger.info(f"Received font_size parameter: {font_size}")

            # Generate audio first
            logger.info("Generating audio for video")
            audio_path, duration = tts_service.generate_audio(text, language, slow)

            # Analyze audio timing
            logger.info("Analyzing audio timing")
            audio_analysis = tts_service.analyze_audio_timing(text, audio_path)

            # Create custom video config with user-specified font size
            from src.models.schemas import VideoConfig
            from src.config.settings import VIDEO_CONFIG

            # Calculate proportional sizes
            bold_size = int(font_size * 1.2)
            line_height = int(font_size * 1.5)

            logger.info(f"Creating video config: font_size={font_size}, bold={bold_size}, line_height={line_height}")

            custom_config = VideoConfig(
                **{**VIDEO_CONFIG,
                   "font_size": font_size,
                   "font_size_bold": bold_size,
                   "line_height": line_height
                }
            )

            # Generate video with custom config
            logger.info(f"Generating video with character highlighting (font_size={font_size})")
            custom_video_service = VideoService(config=custom_config)
            video_path = custom_video_service.generate_video(text, audio_path, audio_analysis)

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

@router.get("/download/audio/{filename}")
async def download_audio_new(filename: str):
    """Download audio file (new route)."""
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

@router.get("/download/video/{filename}")
async def download_video_new(filename: str):
    """Download video file (new route)."""
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

@router.post("/repeat-audio")
async def repeat_audio_endpoint(
    text: str = Form(...),
    repetitions: int = Form(10),
    language: str = Form("en"),
    slow: bool = Form(False)
):
    """Generate audio once and repeat it multiple times."""
    with RequestLogger(logger, "audio repetition"):
        try:
            # Validate input
            if not text:
                raise HTTPException(status_code=400, detail="No text provided")
            if repetitions < 1 or repetitions > 100:
                raise HTTPException(status_code=400, detail="Repetitions must be between 1 and 100")
            
            # Auto-detect language if requested
            if language == "auto":
                lang_result = language_service.detect_language(text)
                language = lang_result.language
                logger.info(f"Auto-detected language: {language}")
            
            # Generate and repeat audio
            audio_path, total_duration = tts_service.generate_and_repeat(
                text=text,
                repetitions=repetitions,
                language=language,
                slow=slow
            )
            
            filename = audio_path.name
            logger.info(f"Audio repetition successful: {filename} ({repetitions}x)")
            
            return ConversionResult(
                success=True,
                filename=filename,
                download_url=f"/download/audio/{filename}",
                duration=total_duration,
                message=f"Audio generated and repeated {repetitions} times"
            )
            
        except Exception as e:
            log_error(logger, e, "audio repetition")
            raise HTTPException(status_code=500, detail=f"Audio repetition failed: {str(e)}")

@router.post("/repeat-video")
async def repeat_video_endpoint(
    text: str = Form(...),
    repetitions: int = Form(10),
    language: str = Form("en"),
    slow: bool = Form(False),
    font_size: int = Form(48)
):
    """Generate video once and repeat it multiple times."""
    with RequestLogger(logger, f"video repetition (font_size={font_size})"):
        try:
            # Validate input
            if not text:
                raise HTTPException(status_code=400, detail="No text provided")
            if repetitions < 1 or repetitions > 100:
                raise HTTPException(status_code=400, detail="Repetitions must be between 1 and 100")

            # Validate font size
            if font_size < 16 or font_size > 200:
                logger.warning(f"Font size {font_size} out of range, using default 48")
                font_size = 48

            logger.info(f"Repeat-video received font_size parameter: {font_size}")

            # Auto-detect language if requested
            if language == "auto":
                lang_result = language_service.detect_language(text)
                language = lang_result.language
                logger.info(f"Auto-detected language: {language}")

            # Generate single audio and analyze it
            single_audio_path, single_duration = tts_service.generate_audio(
                text=text,
                language=language,
                slow=slow
            )

            # Analyze the single audio for timing
            audio_analysis = tts_service.analyze_audio_timing(text, single_audio_path)

            # Create custom video config with user-specified font size
            from src.models.schemas import VideoConfig
            from src.config.settings import VIDEO_CONFIG

            # Calculate proportional sizes
            bold_size = int(font_size * 1.2)
            line_height = int(font_size * 1.5)

            logger.info(f"Repeat-video creating config: font_size={font_size}, bold={bold_size}, line_height={line_height}")

            custom_config = VideoConfig(
                **{**VIDEO_CONFIG,
                   "font_size": font_size,
                   "font_size_bold": bold_size,
                   "line_height": line_height
                }
            )

            # Generate video with custom config and repetitions
            custom_video_service = VideoService(config=custom_config)
            video_path = custom_video_service.generate_and_repeat(
                text=text,
                single_audio_path=single_audio_path,
                audio_analysis=audio_analysis,
                repetitions=repetitions
            )
            
            # Clean up the single audio file (video includes audio)
            try:
                single_audio_path.unlink()
            except OSError:
                pass
            
            # Calculate total duration
            audio_duration = single_duration * repetitions
            
            filename = video_path.name
            logger.info(f"Video repetition successful: {filename} ({repetitions}x)")
            
            return {
                "success": True,
                "filename": filename,
                "video_url": f"/download/video/{filename}",
                "duration": audio_duration,
                "message": f"Video generated and repeated {repetitions} times"
            }
            
        except Exception as e:
            log_error(logger, e, "video repetition")
            # Clean up any audio file created
            if 'audio_path' in locals() and audio_path.exists():
                try:
                    audio_path.unlink()
                except OSError:
                    pass
            raise HTTPException(status_code=500, detail=f"Video repetition failed: {str(e)}")

@router.post("/concatenate-audio")
async def concatenate_audio_endpoint(request: ConcatenateAudioRequest):
    """Generate and concatenate multiple audio files from texts."""
    with RequestLogger(logger, "audio concatenation"):
        try:
            # Validate input
            if not request.texts:
                raise HTTPException(status_code=400, detail="No texts provided")
            
            # Auto-detect language if not specified
            language = request.language
            if not language:
                lang_result = language_service.detect_language(request.texts[0])
                language = lang_result.language
                logger.info(f"Auto-detected language: {language}")
            
            # Generate and concatenate audio
            concat_path, individual_paths, total_duration = tts_service.generate_multiple_and_concatenate(
                texts=request.texts,
                language=language,
                slow=request.slow,
                output_filename=request.output_filename
            )
            
            logger.info(f"Audio concatenation successful: {concat_path.name}")
            
            return {
                "success": True,
                "concatenated_file": concat_path.name,
                "individual_files": [p.name for p in individual_paths],
                "total_duration": total_duration,
                "file_count": len(individual_paths),
                "download_url": f"/download/audio/{concat_path.name}"
            }
            
        except Exception as e:
            log_error(logger, e, "audio concatenation")
            raise HTTPException(status_code=500, detail=f"Audio concatenation failed: {str(e)}")

@router.post("/concatenate-video")
async def concatenate_video_endpoint(request: ConcatenateVideoRequest):
    """Generate and concatenate multiple video files from texts."""
    with RequestLogger(logger, "video concatenation"):
        try:
            # Validate input
            if not request.texts:
                raise HTTPException(status_code=400, detail="No texts provided")
            
            # Auto-detect language if not specified
            language = request.language
            if not language:
                lang_result = language_service.detect_language(request.texts[0])
                language = lang_result.language
                logger.info(f"Auto-detected language: {language}")
            
            # First generate all audio files and analyses
            audio_paths = []
            audio_analyses = []
            
            for i, text in enumerate(request.texts):
                logger.info(f"Processing text {i+1}/{len(request.texts)}")
                
                # Generate audio
                audio_path, duration = tts_service.generate_audio(text, language, request.slow)
                audio_paths.append(audio_path)
                
                # Analyze audio
                audio_analysis = tts_service.analyze_audio_timing(text, audio_path)
                audio_analyses.append(audio_analysis)
            
            # Generate and concatenate videos
            concat_path, individual_paths = video_service.generate_multiple_and_concatenate(
                texts=request.texts,
                audio_paths=audio_paths,
                audio_analyses=audio_analyses,
                output_filename=request.output_filename
            )
            
            logger.info(f"Video concatenation successful: {concat_path.name}")
            
            # Clean up audio files
            for audio_path in audio_paths:
                try:
                    audio_path.unlink()
                except OSError:
                    pass
            
            return {
                "success": True,
                "concatenated_file": concat_path.name,
                "individual_files": [p.name for p in individual_paths],
                "file_count": len(individual_paths),
                "download_url": f"/download/video/{concat_path.name}"
            }
            
        except Exception as e:
            log_error(logger, e, "video concatenation")
            # Clean up any audio files created
            if 'audio_paths' in locals():
                for audio_path in audio_paths:
                    try:
                        audio_path.unlink()
                    except OSError:
                        pass
            raise HTTPException(status_code=500, detail=f"Video concatenation failed: {str(e)}")