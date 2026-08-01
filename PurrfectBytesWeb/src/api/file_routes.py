"""File management API routes — download, delete, and cleanup endpoints."""

from pathlib import Path

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


def _validate_filename(filename: str) -> str:
    """Reject path traversal — filenames must be plain names, no directories."""
    if not filename or filename != Path(filename).name or filename in (".", ".."):
        raise HTTPException(status_code=400, detail="Invalid filename")
    return filename


@router.get("/files")
def list_files(limit: int = 20):
    """List recently generated audio and video files, newest first."""
    def collect(directory: Path, patterns: tuple, url_prefix: str, kind: str) -> list:
        files = []
        for pattern in patterns:
            files.extend(directory.glob(pattern))
        return [
            {
                "filename": f.name,
                "kind": kind,
                "size": f.stat().st_size,
                "modified": f.stat().st_mtime,
                "url": f"{url_prefix}/{f.name}",
            }
            for f in files
        ]

    entries = collect(AUDIO_DIR, ("*.mp3", "*.wav", "*.m4a"), "/download", "audio")
    entries += collect(VIDEO_DIR, ("*.mp4",), "/download-video", "video")
    entries.sort(key=lambda e: e["modified"], reverse=True)

    return {"success": True, "files": entries[:max(1, min(limit, 100))]}


@router.get("/download/{filename}")
async def download_audio(filename: str):
    """Download audio file."""
    file_path = AUDIO_DIR / _validate_filename(filename)

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
    """Download video file (also serves preview PNGs)."""
    file_path = VIDEO_DIR / _validate_filename(filename)

    if not file_path.exists():
        logger.warning(f"Video file not found: {filename}")
        raise HTTPException(status_code=404, detail="Video file not found")

    media_type = "image/png" if filename.endswith(".png") else "video/mp4"
    logger.info(f"Serving video file: {filename}")
    return FileResponse(
        path=file_path,
        media_type=media_type,
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
    file_path = AUDIO_DIR / _validate_filename(filename)

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
    file_path = VIDEO_DIR / _validate_filename(filename)

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
def cleanup_old_files(max_age_hours: int = 24):
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
