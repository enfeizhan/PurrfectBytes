"""Repetition and concatenation API routes."""

from typing import Optional, List

from fastapi import APIRouter, Form, HTTPException
from pydantic import BaseModel

from src.models.schemas import ConversionResult
from src.services.language_detection import LanguageDetectionService
from src.services.tts_service import TTSService
from src.services.video_service import VideoService
from src.config.settings import VIDEO_DIR
from src.utils.logger import get_logger, RequestLogger, log_error

language_service = LanguageDetectionService()
tts_service = TTSService()
video_service = VideoService()
logger = get_logger(__name__)

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


@router.post("/repeat-audio")
def repeat_audio_endpoint(
    text: str = Form(...),
    repetitions: int = Form(10),
    language: str = Form("en"),
    slow: bool = Form(False),
    engine: str = Form("edge"),
    voice: Optional[str] = Form(None)
):
    """Generate audio once and repeat it multiple times using the specified TTS engine."""
    with RequestLogger(logger, f"audio repetition (engine={engine})"):
        try:
            if not text:
                raise HTTPException(status_code=400, detail="No text provided")
            if repetitions < 1 or repetitions > 100:
                raise HTTPException(status_code=400, detail="Repetitions must be between 1 and 100")

            if language == "auto":
                lang_result = language_service.detect_language(text)
                language = lang_result.language
                logger.info(f"Auto-detected language: {language}")

            engine_enum = TTSService.parse_engine(engine)

            audio_path, total_duration = tts_service.generate_and_repeat(
                text=text,
                repetitions=repetitions,
                language=language,
                slow=slow,
                engine=engine_enum,
                voice=voice
            )

            filename = audio_path.name
            logger.info(f"Audio repetition successful: {filename} ({repetitions}x)")

            return ConversionResult(
                success=True,
                audio_filename=filename,
                audio_url=f"/download/{filename}",
                duration=total_duration,
                message=f"Audio generated and repeated {repetitions} times"
            )

        except Exception as e:
            log_error(logger, e, "audio repetition")
            raise HTTPException(status_code=500, detail=f"Audio repetition failed: {str(e)}")


@router.post("/repeat-video")
def repeat_video_endpoint(
    text: str = Form(...),
    repetitions: int = Form(10),
    language: str = Form("en"),
    slow: bool = Form(False),
    font_size: int = Form(48),
    engine: str = Form("edge"),
    voice: Optional[str] = Form(None)
):
    """Generate video once and repeat it multiple times using the specified TTS engine."""
    with RequestLogger(logger, f"video repetition (font_size={font_size}, engine={engine})"):
        try:
            if not text:
                raise HTTPException(status_code=400, detail="No text provided")
            if repetitions < 1 or repetitions > 100:
                raise HTTPException(status_code=400, detail="Repetitions must be between 1 and 100")

            if font_size < 16 or font_size > 200:
                logger.warning(f"Font size {font_size} out of range, using default 48")
                font_size = 48

            logger.info(f"Repeat-video received font_size parameter: {font_size}")

            if language == "auto":
                lang_result = language_service.detect_language(text)
                language = lang_result.language
                logger.info(f"Auto-detected language: {language}")

            engine_enum = TTSService.parse_engine(engine)

            single_audio_path, single_duration = tts_service.generate_audio(
                text=text,
                language=language,
                slow=slow,
                engine=engine_enum,
                voice=voice
            )

            # Use the styled video generation function
            from src.services.video_generation import create_video_with_text
            import uuid as uuid_module
            from src.utils.ffmpeg_utils import repeat_copy
            from src.utils.text_utils import filename_slug

            single_video_filename = f"{filename_slug(text)}_{uuid_module.uuid4().hex[:8]}.mp4"
            single_video_path = VIDEO_DIR / single_video_filename

            logger.info(f"Generating video with character highlighting (font_size={font_size})")
            create_video_with_text(text, single_audio_path, single_video_path, font_size=font_size, show_qr_code=True)

            if repetitions > 1:
                try:
                    repeat_filename = f"repeat_{repetitions}x_{filename_slug(text)}_{uuid_module.uuid4().hex[:8]}.mp4"
                    video_path = repeat_copy(single_video_path, repetitions, VIDEO_DIR / repeat_filename)
                    single_video_path.unlink()
                except Exception as concat_error:
                    logger.warning(f"Repetition failed, using single video: {concat_error}")
                    video_path = single_video_path
            else:
                video_path = single_video_path

            try:
                single_audio_path.unlink()
            except OSError:
                pass

            audio_duration = single_duration * repetitions

            filename = video_path.name
            logger.info(f"Video repetition successful: {filename} ({repetitions}x)")

            return {
                "success": True,
                "filename": filename,
                "video_url": f"/download-video/{filename}",
                "duration": audio_duration,
                "message": f"Video generated and repeated {repetitions} times"
            }

        except Exception as e:
            log_error(logger, e, "video repetition")
            if 'single_audio_path' in locals() and single_audio_path.exists():
                try:
                    single_audio_path.unlink()
                except OSError:
                    pass
            raise HTTPException(status_code=500, detail=f"Video repetition failed: {str(e)}")


@router.post("/concatenate-audio")
def concatenate_audio_endpoint(request: ConcatenateAudioRequest):
    """Generate and concatenate multiple audio files from texts."""
    with RequestLogger(logger, "audio concatenation"):
        try:
            if not request.texts:
                raise HTTPException(status_code=400, detail="No texts provided")

            language = request.language
            if not language:
                lang_result = language_service.detect_language(request.texts[0])
                language = lang_result.language
                logger.info(f"Auto-detected language: {language}")

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
                "download_url": f"/download/{concat_path.name}"
            }

        except Exception as e:
            log_error(logger, e, "audio concatenation")
            raise HTTPException(status_code=500, detail=f"Audio concatenation failed: {str(e)}")


@router.post("/concatenate-video")
def concatenate_video_endpoint(request: ConcatenateVideoRequest):
    """Generate and concatenate multiple video files from texts."""
    with RequestLogger(logger, "video concatenation"):
        try:
            if not request.texts:
                raise HTTPException(status_code=400, detail="No texts provided")

            language = request.language
            if not language:
                lang_result = language_service.detect_language(request.texts[0])
                language = lang_result.language
                logger.info(f"Auto-detected language: {language}")

            audio_paths = []
            audio_analyses = []

            for i, text in enumerate(request.texts):
                logger.info(f"Processing text {i+1}/{len(request.texts)}")

                audio_path, duration = tts_service.generate_audio(text, language, request.slow)
                audio_paths.append(audio_path)

                audio_analysis = tts_service.analyze_audio_timing(text, audio_path)
                audio_analyses.append(audio_analysis)

            concat_path, individual_paths = video_service.generate_multiple_and_concatenate(
                texts=request.texts,
                audio_paths=audio_paths,
                audio_analyses=audio_analyses,
                output_filename=request.output_filename
            )

            logger.info(f"Video concatenation successful: {concat_path.name}")

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
                "download_url": f"/download-video/{concat_path.name}"
            }

        except Exception as e:
            log_error(logger, e, "video concatenation")
            if 'audio_paths' in locals():
                for audio_path in audio_paths:
                    try:
                        audio_path.unlink()
                    except OSError:
                        pass
            raise HTTPException(status_code=500, detail=f"Video concatenation failed: {str(e)}")
