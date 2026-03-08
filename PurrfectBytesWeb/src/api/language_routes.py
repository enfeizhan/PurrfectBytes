"""Language and TTS engine API routes."""

from fastapi import APIRouter, Form, HTTPException

from src.models.schemas import LanguageDetectionResult
from src.services.language_detection import LanguageDetectionService
from src.services.tts_service import TTSService
from src.utils.logger import get_logger, RequestLogger, log_error

language_service = LanguageDetectionService()
tts_service = TTSService()
logger = get_logger(__name__)

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


@router.get("/tts-engines")
async def get_tts_engines():
    """Get list of available TTS engines."""
    engines = tts_service.get_available_engines()
    return {
        "engines": engines,
        "default": "edge"
    }


@router.get("/tts-voices/{engine}")
async def get_tts_voices(engine: str, language: str = "en"):
    """Get available voices for a TTS engine."""
    try:
        engine_enum = TTSService.parse_engine(engine)
        if not engine_enum:
            raise HTTPException(status_code=400, detail=f"Invalid engine: {engine}")
        voices = tts_service.get_engine_voices(engine_enum, language)
        return {"voices": voices, "engine": engine}
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


@router.get("/supported-languages")
async def get_supported_languages():
    """Get list of supported languages."""
    return {
        "languages": language_service.get_supported_languages(),
        "total": len(language_service.get_supported_languages())
    }
