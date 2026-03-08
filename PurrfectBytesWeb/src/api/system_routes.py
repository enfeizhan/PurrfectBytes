"""System API routes — health check and utility endpoints."""

import time

from fastapi import APIRouter

from src.config.settings import AUDIO_DIR, VIDEO_DIR
from src.models.schemas import HealthCheck

router = APIRouter()


@router.get("/health", response_model=HealthCheck)
async def health_check():
    """Health check endpoint."""
    start_time = time.time()

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


@router.get("/docs-redirect")
async def docs_redirect():
    """Redirect to API docs."""
    from fastapi.responses import RedirectResponse
    return RedirectResponse(url="/docs")
