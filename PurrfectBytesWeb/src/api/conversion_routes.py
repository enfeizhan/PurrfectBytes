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
    SequenceStep,
    parse_sequence,
    sequence_slug,
    describe_sequence,
    total_repetitions,
)
from src.utils.dialogue_utils import (
    parse_dialogue,
    parse_voiced_dialogue,
    describe_dialogue,
    dialogue_display_text,
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
    voice: Optional[str] = Form(None),
    voiced_text: Optional[str] = Form(None)
):
    """Convert text to audio using the specified TTS engine.

    `voiced_text`, when provided, is spoken instead of `text` — a pronunciation
    override for words the TTS engine misreads (e.g. kanji respelled in kana).
    """
    with RequestLogger(logger, f"audio conversion ({language}, engine={engine})"):
        try:
            if not language_service.is_supported_language(language):
                language = "en"
                logger.warning("Unsupported language, defaulting to English")

            engine_enum = TTSService.parse_engine(engine)

            speech_text = voiced_text.strip() if voiced_text and voiced_text.strip() else text
            audio_path, duration = tts_service.generate_audio(
                speech_text, language, slow, engine=engine_enum, voice=voice
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
    sequence: Optional[str] = Form(None),
    conversation: bool = Form(False),
    voice_b: Optional[str] = Form(None),
    voiced_text: Optional[str] = Form(None)
):
    """Convert text to video with synchronized highlighting using the specified TTS engine.

    When `sequence` is provided (e.g. "2n,3s" = 2 normal then 3 slow), it takes
    precedence over `repetitions` and `slow`. When `conversation` is set, lines
    of the text alternate between `voice` and `voice_b`, each rendered as its
    own clip and concatenated; combined with a sequence, each step plays the
    whole conversation at that step's speed. A line may name its speaker
    ("직원: 혼자 오셨어요?"): the name stays on screen but is never voiced or
    highlighted, and lines sharing a name share a voice.

    `voiced_text`, when provided, is spoken instead of `text` while the video
    still displays `text` — a pronunciation override for words the TTS engine
    misreads. Highlight timing maps the audio's word boundaries back onto the
    displayed text where the words still match, bridging respelled words (and
    falling back to uniform spacing when most of the text was respelled). In
    conversation mode the override must have the same number of lines.
    """
    with RequestLogger(logger, f"video conversion ({language}, font_size={font_size}, engine={engine}, reps={repetitions}, seq={sequence}, conv={conversation})"):
        audio_by_speed = {}
        video_by_speed = {}
        line_audios = []
        line_videos = []
        video_path = None
        audio_path = None

        try:
            speech_override = voiced_text.strip() if voiced_text and voiced_text.strip() else None

            dialogue = None
            voiced_dialogue = None
            if conversation:
                try:
                    dialogue = parse_dialogue(text)
                    if speech_override:
                        voiced_dialogue = parse_voiced_dialogue(dialogue, speech_override)
                except ValueError as e:
                    raise HTTPException(status_code=400, detail=str(e))

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
            # Render one audio+video per distinct speed (or per dialogue line).
            if dialogue is None:
                for slow_flag in sorted({s.slow for s in steps} if steps else {slow}):
                    logger.info(f"Generating audio for video with engine={engine} (slow={slow_flag})")
                    audio_by_speed[slow_flag], duration_by_speed[slow_flag] = tts_service.generate_audio(
                        speech_override or text, language, slow_flag,
                        engine=engine_enum, voice=voice
                    )

                    speed_prefix = "slow_" if slow_flag else ""
                    single_video_path = VIDEO_DIR / f"{speed_prefix}{filename_slug(text)}_{uuid.uuid4().hex[:8]}.mp4"
                    logger.info(f"Generating video with character highlighting (font_size={font_size}, slow={slow_flag})")
                    create_video_with_text(text, audio_by_speed[slow_flag], single_video_path,
                                           font_size=font_size, show_qr_code=show_qr_code,
                                           slow_indicator=slow_flag)
                    video_by_speed[slow_flag] = single_video_path

            if dialogue:
                conv_steps = steps if steps else [SequenceStep(count=repetitions, slow=slow)]
                # The whole dialogue stays on screen in every clip; each clip
                # highlights only the line being voiced, located by its
                # character offset within the joined display text.
                display_text, line_offsets = dialogue_display_text(dialogue)
                # slow_flag -> per-line audios/videos/durations, in dialogue order
                conv_audios = {}
                conv_videos = {}
                conv_durations = {}
                for slow_flag in sorted({s.slow for s in conv_steps}):
                    conv_audios[slow_flag] = []
                    conv_videos[slow_flag] = []
                    conv_durations[slow_flag] = []
                    for i, line in enumerate(dialogue):
                        line_voice = voice_b if line.speaker == 1 else voice
                        voiced_line = voiced_dialogue[i].text if voiced_dialogue else line.text
                        logger.info(f"Generating conversation line {i + 1}/{len(dialogue)} (speaker {line.speaker}, slow={slow_flag})")
                        line_audio, line_duration = tts_service.generate_audio(
                            voiced_line, language, slow_flag, engine=engine_enum, voice=line_voice
                        )
                        line_audios.append(line_audio)
                        conv_audios[slow_flag].append(line_audio)
                        conv_durations[slow_flag].append(line_duration)

                        speed_tag = "s" if slow_flag else "n"
                        line_video = VIDEO_DIR / f"convline{i}{speed_tag}_{filename_slug(line.text)}_{uuid.uuid4().hex[:8]}.mp4"
                        create_video_with_text(line.text, line_audio, line_video,
                                               font_size=font_size, show_qr_code=show_qr_code,
                                               display_text=display_text,
                                               text_offset=line_offsets[i],
                                               text_align="left",
                                               slow_indicator=slow_flag)
                        line_videos.append(line_video)
                        conv_videos[slow_flag].append(line_video)

                seq_part = f"{sequence_slug(steps)}_" if steps else ""
                base_name = f"conv_{len(dialogue)}lines_{seq_part}{filename_slug(text)}_{uuid.uuid4().hex[:8]}"
                try:
                    logger.info(f"Concatenating {len(line_videos)} conversation clips via stream copy")
                    ordered = [
                        clip
                        for st in conv_steps
                        for _ in range(st.count)
                        for clip in conv_videos[st.slow]
                    ]
                    video_path = concat_copy(ordered, VIDEO_DIR / f"{base_name}.mp4")
                    duration = sum(
                        st.count * sum(conv_durations[st.slow]) for st in conv_steps
                    )
                    logger.info(f"Conversation video: {video_path.name}")
                except Exception as concat_error:
                    logger.warning(f"Conversation concat failed: {concat_error}, using first line's video")
                    first_speed = conv_steps[0].slow
                    video_path = conv_videos[first_speed][0]
                    duration = conv_durations[first_speed][0]
                if steps:
                    message = (
                        f"Conversation video generated ({describe_dialogue(dialogue)}, "
                        f"sequence {describe_sequence(steps)}, "
                        f"{total_repetitions(steps)} repetitions)"
                    )
                else:
                    message = (
                        f"Conversation video generated ({describe_dialogue(dialogue)}, "
                        f"{repetitions} repetitions)"
                    )

                # Build the matching audio for the "Download Audio Only" link.
                try:
                    ordered_audios = [
                        clip
                        for st in conv_steps
                        for _ in range(st.count)
                        for clip in conv_audios[st.slow]
                    ]
                    audio_path = tts_service.concatenate_audio(
                        ordered_audios,
                        f"{base_name}.{tts_service.audio_config['format']}"
                    )
                except Exception as audio_concat_error:
                    logger.warning(f"Conversation audio concat failed: {audio_concat_error}, keeping first line's audio")
                    audio_path = line_audios[0]
            elif steps:
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
            # unused per-speed/per-line audios (and timing sidecars) and videos.
            for leftover in list(audio_by_speed.values()) + line_audios:
                if leftover == audio_path:
                    continue
                for path in (leftover, leftover.with_name(leftover.name + ".words.json")):
                    if path.exists():
                        try:
                            path.unlink()
                        except OSError:
                            pass
            audio_by_speed = {}
            line_audios = []
            for leftover in list(video_by_speed.values()) + line_videos:
                if leftover != video_path and leftover.exists():
                    try:
                        leftover.unlink()
                    except OSError:
                        pass
            video_by_speed = {}
            line_videos = []

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
            cleanup_paths = (
                list(audio_by_speed.values()) + list(video_by_speed.values())
                + line_audios + line_videos
            )
            if video_path:
                cleanup_paths.append(video_path)
            if audio_path:
                cleanup_paths.append(audio_path)
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
    highlight_position: int = Form(0),
    conversation: bool = Form(False),
    slow: bool = Form(False)
):
    """Generate a preview frame showing how the video will look."""
    if not text:
        raise HTTPException(status_code=400, detail="No text provided")

    if font_size < 16 or font_size > 200:
        font_size = 48

    text_align = "center"
    if conversation:
        try:
            dialogue = parse_dialogue(text)
        except ValueError as e:
            raise HTTPException(status_code=400, detail=str(e))
        text, line_offsets = dialogue_display_text(dialogue)
        text_align = "left"
        if highlight_position == 0:
            # Highlight the first voiced character, not the speaker marker
            highlight_position = line_offsets[0]

    try:
        from src.services.video_generation import create_preview_frame

        preview_img = create_preview_frame(text, font_size, show_qr_code, highlight_position,
                                           text_align=text_align, slow_indicator=slow)

        buffer = io.BytesIO()
        preview_img.save(buffer, format='PNG')

        return Response(content=buffer.getvalue(), media_type="image/png")
    except Exception as e:
        log_error(logger, e, "preview generation")
        raise HTTPException(
            status_code=500,
            detail=f"Failed to generate preview: {str(e)}"
        )
