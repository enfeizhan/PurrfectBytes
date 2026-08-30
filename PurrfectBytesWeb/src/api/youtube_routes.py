"""YouTube-related API routes for metadata generation and video upload."""

import json

from typing import Optional

from fastapi import APIRouter, Form, Request, Query
from fastapi.responses import RedirectResponse, HTMLResponse

from src.services.source_store import SourceStore
from src.services.youtube_metadata_service import YouTubeMetadataService
from src.services.youtube_upload_service import YouTubeUploadService
from src.config.settings import VIDEO_DIR
from src.utils.logger import get_logger

logger = get_logger(__name__)

youtube_router = APIRouter(tags=["youtube"])

metadata_service = YouTubeMetadataService()
upload_service = YouTubeUploadService()
source_store = SourceStore()


@youtube_router.post("/extract-youtube-items")
def extract_youtube_items(
    text: str = Form(...),
    provider: str = Form("gemini"),
):
    """Extract vocabulary/grammar items from a sentence for user review."""
    try:
        items = metadata_service.extract_items(text, provider)
        return {"success": True, "items": items}
    except ValueError as e:
        return {"success": False, "error": str(e)}
    except Exception as e:
        logger.error(f"Item extraction failed: {e}")
        return {"success": False, "error": f"Extraction failed: {str(e)}"}


@youtube_router.post("/generate-youtube-metadata")
def generate_youtube_metadata(
    text: str = Form(...),
    provider: str = Form("gemini"),
    source_id: Optional[str] = Form(None),
    items: Optional[str] = Form(None),
):
    """Generate YouTube title and description for a sentence.

    `items` is an optional JSON array of user-approved vocabulary/grammar
    items (from /extract-youtube-items); when given, the description's
    Breakdown and Grammar Points sections contain exactly those items.
    """
    try:
        approved_items = None
        if items is not None:
            try:
                approved_items = json.loads(items)
            except ValueError:
                approved_items = None
            if not isinstance(approved_items, list):
                return {"success": False, "error": "Invalid items selection"}

        credit = None
        if source_id:
            source = source_store.get_source(source_id)
            if not source:
                return {"success": False, "error": "Unknown text source - it may have been deleted"}
            credit = source["credit"]

        result = metadata_service.generate(text, provider, credit=credit, items=approved_items)
        return {
            "success": True,
            "title": result["title"],
            "description": result["description"],
        }
    except ValueError as e:
        return {"success": False, "error": str(e)}
    except Exception as e:
        logger.error(f"Metadata generation failed: {e}")
        return {"success": False, "error": f"Generation failed: {str(e)}"}


@youtube_router.get("/sources")
def list_sources():
    """List saved text sources."""
    return {"success": True, "sources": source_store.list_sources()}


@youtube_router.post("/sources")
def add_source(
    name: str = Form(...),
    credit: str = Form(...),
):
    """Save a new text source with its verbatim credit line."""
    try:
        source = source_store.add_source(name, credit)
        return {"success": True, "source": source}
    except ValueError as e:
        return {"success": False, "error": str(e)}
    except Exception as e:
        logger.error(f"Failed to save source: {e}")
        return {"success": False, "error": f"Failed to save source: {str(e)}"}


@youtube_router.delete("/sources/{source_id}")
def delete_source(source_id: str):
    """Delete a saved text source."""
    try:
        if not source_store.delete_source(source_id):
            return {"success": False, "error": "Unknown text source"}
        return {"success": True}
    except Exception as e:
        logger.error(f"Failed to delete source: {e}")
        return {"success": False, "error": f"Failed to delete source: {str(e)}"}


@youtube_router.get("/youtube/providers")
def get_llm_providers():
    """Get available LLM providers and their status."""
    return {
        "success": True,
        "providers": metadata_service.get_available_providers(),
    }


@youtube_router.get("/youtube/auth-url")
def get_youtube_auth_url():
    """Get the OAuth2 authorization URL for YouTube."""
    try:
        if not upload_service.is_configured():
            return {
                "success": False,
                "error": "YouTube client secrets not configured. Place client_secrets.json in the project root.",
            }
        auth_url = upload_service.get_auth_url()
        return {"success": True, "auth_url": auth_url}
    except Exception as e:
        logger.error(f"Failed to get auth URL: {e}")
        return {"success": False, "error": str(e)}


@youtube_router.get("/oauth2callback")
def oauth2_callback(request: Request):
    """Handle OAuth2 callback from Google."""
    try:
        # Get the full URL including query parameters
        callback_url = str(request.url)
        upload_service.handle_callback(callback_url)

        # Return a success page that auto-closes or redirects
        return HTMLResponse(content="""
        <!DOCTYPE html>
        <html>
        <head><title>YouTube Connected</title></head>
        <body style="display:flex;align-items:center;justify-content:center;height:100vh;font-family:sans-serif;background:#1a1a28;color:white;">
            <div style="text-align:center;">
                <h1>✅ YouTube Connected!</h1>
                <p>You can close this tab and return to PurrfectBytes.</p>
                <script>
                    // Notify the opener window
                    if (window.opener) {
                        window.opener.postMessage('youtube-auth-success', '*');
                        setTimeout(() => window.close(), 2000);
                    }
                </script>
            </div>
        </body>
        </html>
        """)
    except Exception as e:
        logger.error(f"OAuth callback failed: {e}")
        return HTMLResponse(content=f"""
        <!DOCTYPE html>
        <html>
        <head><title>Authentication Failed</title></head>
        <body style="display:flex;align-items:center;justify-content:center;height:100vh;font-family:sans-serif;background:#1a1a28;color:white;">
            <div style="text-align:center;">
                <h1>❌ Authentication Failed</h1>
                <p>{str(e)}</p>
                <a href="/" style="color:#667eea;">Return to PurrfectBytes</a>
            </div>
        </body>
        </html>
        """, status_code=400)


@youtube_router.get("/youtube/auth-status")
def youtube_auth_status():
    """Check if YouTube is authenticated."""
    return {
        "success": True,
        "configured": upload_service.is_configured(),
        "authenticated": upload_service.is_authenticated(),
    }


@youtube_router.get("/youtube/playlists")
def get_youtube_playlists():
    """Get the user's YouTube playlists."""
    try:
        playlists = upload_service.get_playlists()
        return {"success": True, "playlists": playlists}
    except Exception as e:
        logger.error(f"Failed to fetch playlists: {e}")
        return {"success": False, "error": str(e)}


@youtube_router.post("/youtube/upload")
def upload_to_youtube(
    video_filename: str = Form(...),
    title: str = Form(...),
    description: str = Form(""),
    tags: str = Form(""),
    playlist_id: str = Form(""),
    privacy_status: str = Form("public"),
):
    """Upload a video to YouTube."""
    try:
        video_path = VIDEO_DIR / video_filename
        if not video_path.exists():
            return {"success": False, "error": f"Video file not found: {video_filename}"}

        tag_list = [t.strip() for t in tags.split(",") if t.strip()] if tags else []

        result = upload_service.upload_video(
            video_path=video_path,
            title=title,
            description=description,
            tags=tag_list,
            playlist_id=playlist_id if playlist_id else None,
            privacy_status=privacy_status,
        )

        return {
            "success": True,
            "video_id": result["video_id"],
            "video_url": result["video_url"],
            "message": f"Video uploaded successfully!",
        }
    except Exception as e:
        logger.error(f"YouTube upload failed: {e}")
        return {"success": False, "error": str(e)}
