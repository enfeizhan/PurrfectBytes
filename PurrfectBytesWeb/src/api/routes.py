"""API routes for the PurrfectBytes application.

This module aggregates all domain-specific route modules into a single router
for backward compatibility. Each sub-module handles a specific domain:

- conversion_routes: Text-to-audio and text-to-video conversion
- file_routes: File download, deletion, and cleanup
- language_routes: Language detection and TTS engine info
- repetition_routes: Audio/video repetition and concatenation
- system_routes: Health check and utility endpoints
"""

from fastapi import APIRouter

from src.api.conversion_routes import router as conversion_router
from src.api.file_routes import router as file_router
from src.api.language_routes import router as language_router
from src.api.repetition_routes import router as repetition_router
from src.api.system_routes import router as system_router
from src.api.youtube_routes import youtube_router

# Create aggregated router
router = APIRouter()

# Include all domain-specific routers
router.include_router(conversion_router)
router.include_router(file_router)
router.include_router(language_router)
router.include_router(repetition_router)
router.include_router(system_router)
router.include_router(youtube_router)