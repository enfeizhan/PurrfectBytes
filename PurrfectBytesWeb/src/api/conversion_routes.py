"""Conversion API routes — text-to-audio and text-to-video endpoints."""

import uuid

from fastapi import APIRouter, Form, HTTPException
from typing import Optional

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


@router.post("/convert", response_model=ConversionResult)
async def convert_to_audio(
    text: str = Form(...),
    language: str = Form("en"),
    slow: bool = Form(False),
    engine: str = Form("edge"),
    voice: Optional[str] = Form(None)
):
    """Convert text to audio using the specified TTS engine."""
    with RequestLogger(logger, f"audio conversion ({language}, engine={engine})"):
        try:
            if not language_service.is_supported_language(language):
                language = "en"
                logger.warning("Unsupported language, defaulting to English")

            engine_enum = TTSService.parse_engine(engine)

            audio_path, duration = tts_service.generate_audio(
                text, language, slow, engine=engine_enum, voice=voice
            )

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
    font_size: int = Form(48),
    repetitions: int = Form(1),
    show_qr_code: bool = Form(False),
    engine: str = Form("edge"),
    voice: Optional[str] = Form(None)
):
    """Convert text to video with synchronized highlighting using the specified TTS engine."""
    with RequestLogger(logger, f"video conversion ({language}, font_size={font_size}, engine={engine}, reps={repetitions})"):
        audio_path = None
        video_path = None

        try:
            if not language_service.is_supported_language(language):
                language = "en"
                logger.warning("Unsupported language, defaulting to English")

            if font_size < 16 or font_size > 200:
                logger.warning(f"Font size {font_size} out of range, using default 48")
                font_size = 48

            if repetitions < 1 or repetitions > 100:
                logger.warning(f"Repetitions {repetitions} out of range, using 1")
                repetitions = 1

            logger.info(f"Received: font_size={font_size}, repetitions={repetitions}")

            engine_enum = TTSService.parse_engine(engine)

            logger.info(f"Generating audio for video with engine={engine}")
            audio_path, duration = tts_service.generate_audio(
                text, language, slow, engine=engine_enum, voice=voice
            )

            logger.info("Analyzing audio timing")
            audio_analysis = tts_service.analyze_audio_timing(text, audio_path)

            # Use the styled video generation function
            from src.services.video_generation import create_video_with_text
            import uuid as uuid_module
            from moviepy.video.io.VideoFileClip import VideoFileClip
            from moviepy import concatenate_videoclips

            single_video_filename = f"{uuid_module.uuid4()}.mp4"
            single_video_path = VIDEO_DIR / single_video_filename

            logger.info(f"Generating video with character highlighting (font_size={font_size})")
            create_video_with_text(text, audio_path, single_video_path, font_size=font_size, show_qr_code=show_qr_code)

            if repetitions > 1:
                try:
                    logger.info(f"Concatenating video {repetitions} times")
                    single_clip = VideoFileClip(str(single_video_path))
                    clips = [single_clip] * repetitions
                    final_clip = concatenate_videoclips(clips, method="compose")

                    concat_filename = f"repeat_{repetitions}x_{uuid_module.uuid4()}.mp4"
                    video_path = VIDEO_DIR / concat_filename

                    final_clip.write_videofile(
                        str(video_path),
                        fps=24,
                        codec='libx264',
                        audio_codec='aac',
                        temp_audiofile='temp-audio.m4a',
                        remove_temp=True,
                        logger=None
                    )

                    duration = single_clip.duration * repetitions
                    single_clip.close()
                    final_clip.close()
                    single_video_path.unlink()

                    logger.info(f"Concatenated video: {video_path.name}")
                except Exception as concat_error:
                    logger.warning(f"Concatenation failed: {concat_error}, using single video")
                    video_path = single_video_path
            else:
                video_path = single_video_path

            logger.info(f"Video generated successfully: {video_path.name}")

            return ConversionResult(
                success=True,
                audio_filename=audio_path.name,
                video_filename=video_path.name,
                audio_url=f"/download/{audio_path.name}",
                video_url=f"/download-video/{video_path.name}",
                duration=duration,
                message=f"Video generated" + (f" and repeated {repetitions} times" if repetitions > 1 else "")
            )

        except Exception as e:
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


@router.post("/preview")
async def generate_preview(
    text: str = Form(...),
    font_size: int = Form(48),
    show_qr_code: bool = Form(False),
    highlight_position: int = Form(0)
):
    """Generate a preview frame showing how the video will look."""
    if not text:
        raise HTTPException(status_code=400, detail="No text provided")

    if font_size < 16 or font_size > 200:
        font_size = 48

    try:
        from src.services.video_generation import create_preview_frame

        preview_img = create_preview_frame(text, font_size, show_qr_code, highlight_position)

        preview_filename = f"preview_{uuid.uuid4()}.png"
        preview_path = VIDEO_DIR / preview_filename
        preview_img.save(str(preview_path), format='PNG')

        return {
            "success": True,
            "preview_url": f"/download-video/{preview_filename}",
            "message": "Preview generated successfully"
        }
    except Exception as e:
        log_error(logger, e, "preview generation")
        raise HTTPException(
            status_code=500,
            detail=f"Failed to generate preview: {str(e)}"
        )
