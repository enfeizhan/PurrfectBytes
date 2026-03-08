"""File management API routes — download, delete, and cleanup endpoints."""

import time

from fastapi import APIRouter, HTTPException
from fastapi.responses import FileResponse

from src.config.settings import AUDIO_DIR, VIDEO_DIR, ASSETS_DIR
from src.services.tts_service import TTSService
from src.services.video_service import VideoService
from src.utils.logger import get_logger, RequestLogger, log_error

tts_service = TTSService()
video_service = VideoService()
logger = get_logger(__name__)

router = APIRouter()


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


@router.get("/favicon.ico")
async def favicon():
    """Serve the cat logo as favicon."""
    favicon_path = ASSETS_DIR / "logo_small.png"
    if not favicon_path.exists():
        raise HTTPException(status_code=404, detail="Favicon not found")

    return FileResponse(
        path=favicon_path,
        media_type="image/png"
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
