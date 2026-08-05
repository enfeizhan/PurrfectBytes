"""Conversion API routes — text-to-audio and text-to-video endpoints.

Handlers are plain `def` on purpose: FastAPI runs them in its threadpool, so
long TTS/video generation doesn't block the event loop and the server stays
responsive while a video renders.
"""

import io
import uuid

from fastapi import APIRouter, Form, HTTPException
from fastapi.responses import Response
from typing import Optional

from src.models.schemas import ConversionResult
from src.services.language_detection import LanguageDetectionService
from src.services.tts_service import TTSService
from src.services.video_service import VideoService
from src.config.settings import VIDEO_DIR
from src.utils.logger import get_logger, RequestLogger, log_error
from src.utils.sequence_utils import (
    parse_sequence,
    sequence_slug,
    describe_sequence,
    total_repetitions,
)

language_service = LanguageDetectionService()
tts_service = TTSService()
video_service = VideoService()
logger = get_logger(__name__)

router = APIRouter()


@router.post("/convert", response_model=ConversionResult)
def convert_to_audio(
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
def convert_to_video(
    text: str = Form(...),
    language: str = Form("en"),
    slow: bool = Form(False),
    font_size: int = Form(48),
    repetitions: int = Form(10),
    show_qr_code: bool = Form(False),
    engine: str = Form("edge"),
    voice: Optional[str] = Form(None),
    sequence: Optional[str] = Form(None)
):
    """Convert text to video with synchronized highlighting using the specified TTS engine.

    When `sequence` is provided (e.g. "2n,3s" = 2 normal then 3 slow), it takes
    precedence over `repetitions` and `slow`.
    """
    with RequestLogger(logger, f"video conversion ({language}, font_size={font_size}, engine={engine}, reps={repetitions}, seq={sequence})"):
        audio_by_speed = {}
        video_by_speed = {}
        video_path = None

        try:
            steps = None
            if sequence:
                try:
                    steps = parse_sequence(sequence)
                except ValueError as e:
                    raise HTTPException(status_code=400, detail=str(e))

            if not language_service.is_supported_language(language):
                language = "en"
                logger.warning("Unsupported language, defaulting to English")

            if font_size < 16 or font_size > 200:
                logger.warning(f"Font size {font_size} out of range, using default 48")
                font_size = 48

            if repetitions < 1 or repetitions > 100:
                logger.warning(f"Repetitions {repetitions} out of range, using 1")
                repetitions = 1

            engine_enum = TTSService.parse_engine(engine)

            from src.services.video_generation import create_video_with_text
            from src.utils.ffmpeg_utils import concat_copy, repeat_copy
            from src.utils.text_utils import filename_slug

            duration_by_speed = {}
            # Render one audio+video per distinct speed. Must stay sequential:
            # video encoding uses a shared temp-audio.m4a scratch file.
            for slow_flag in sorted({s.slow for s in steps} if steps else {slow}):
                logger.info(f"Generating audio for video with engine={engine} (slow={slow_flag})")
                audio_by_speed[slow_flag], duration_by_speed[slow_flag] = tts_service.generate_audio(
                    text, language, slow_flag, engine=engine_enum, voice=voice
                )

                speed_prefix = "slow_" if slow_flag else ""
                single_video_path = VIDEO_DIR / f"{speed_prefix}{filename_slug(text)}_{uuid.uuid4().hex[:8]}.mp4"
                logger.info(f"Generating video with character highlighting (font_size={font_size}, slow={slow_flag})")
                create_video_with_text(text, audio_by_speed[slow_flag], single_video_path,
                                       font_size=font_size, show_qr_code=show_qr_code)
                video_by_speed[slow_flag] = single_video_path

            if steps:
                try:
                    logger.info(f"Concatenating sequence {sequence} via stream copy")
                    ordered = [video_by_speed[s.slow] for s in steps for _ in range(s.count)]
                    concat_filename = f"seq_{sequence_slug(steps)}_{filename_slug(text)}_{uuid.uuid4().hex[:8]}.mp4"
                    video_path = concat_copy(ordered, VIDEO_DIR / concat_filename)
                    duration = sum(s.count * duration_by_speed[s.slow] for s in steps)
                    for single in video_by_speed.values():
                        single.unlink()
                    video_by_speed = {}
                    logger.info(f"Sequence video: {video_path.name}")
                except Exception as concat_error:
                    logger.warning(f"Sequence concat failed: {concat_error}, using single video")
                    first_speed = steps[0].slow
                    video_path = video_by_speed.pop(first_speed)
                    duration = duration_by_speed[first_speed]
                message = (
                    f"Video generated with sequence {describe_sequence(steps)} "
                    f"({total_repetitions(steps)} repetitions)"
                )
                # Keep one audio for the "Download Audio Only" link (prefer
                # normal speed); the other is an intermediate.
                kept_speed = False if False in audio_by_speed else True
                audio_path = audio_by_speed.pop(kept_speed)
            elif repetitions > 1:
                audio_path = audio_by_speed.pop(slow)
                single_video_path = video_by_speed[slow]
                try:
                    logger.info(f"Repeating video {repetitions}x via stream copy")
                    concat_filename = f"repeat_{repetitions}x_{filename_slug(text)}_{uuid.uuid4().hex[:8]}.mp4"
                    video_path = repeat_copy(single_video_path, repetitions, VIDEO_DIR / concat_filename)
                    duration = duration_by_speed[slow] * repetitions
                    single_video_path.unlink()
                    video_by_speed = {}
                    logger.info(f"Repeated video: {video_path.name}")
                except Exception as concat_error:
                    logger.warning(f"Repetition failed: {concat_error}, using single video")
                    video_path = video_by_speed.pop(slow)
                    duration = duration_by_speed[slow]
                message = f"Video generated and repeated {repetitions} times"
            else:
                audio_path = audio_by_speed.pop(slow)
                video_path = video_by_speed.pop(slow)
                duration = duration_by_speed[slow]
                message = "Video generated"

            # Remove leftover intermediates not returned to the client:
            # unused per-speed audios (and timing sidecars) and videos.
            for leftover in audio_by_speed.values():
                for path in (leftover, leftover.with_name(leftover.name + ".words.json")):
                    if path.exists():
                        try:
                            path.unlink()
                        except OSError:
                            pass
            audio_by_speed = {}
            for leftover in video_by_speed.values():
                if leftover != video_path and leftover.exists():
                    try:
                        leftover.unlink()
                    except OSError:
                        pass
            video_by_speed = {}

            logger.info(f"Video generated successfully: {video_path.name}")

            return ConversionResult(
                success=True,
                audio_filename=audio_path.name,
                video_filename=video_path.name,
                audio_url=f"/download/{audio_path.name}",
                video_url=f"/download-video/{video_path.name}",
                duration=duration,
                message=message
            )

        except HTTPException:
            raise
        except Exception as e:
            cleanup_paths = list(audio_by_speed.values()) + list(video_by_speed.values())
            if video_path:
                cleanup_paths.append(video_path)
            for path in cleanup_paths:
                if path.exists():
                    try:
                        path.unlink()
                    except OSError:
                        pass

            log_error(logger, e, "video conversion")
            raise HTTPException(
                status_code=500,
                detail=f"Video conversion failed: {str(e)}"
            )


@router.post("/preview")
def generate_preview(
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

        buffer = io.BytesIO()
        preview_img.save(buffer, format='PNG')

        return Response(content=buffer.getvalue(), media_type="image/png")
    except Exception as e:
        log_error(logger, e, "preview generation")
        raise HTTPException(
            status_code=500,
            detail=f"Failed to generate preview: {str(e)}"
        )
