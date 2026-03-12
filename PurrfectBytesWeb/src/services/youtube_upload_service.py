"""YouTube upload service using YouTube Data API v3."""

import os
import json
from pathlib import Path
from typing import Optional

from src.config.settings import (
    YOUTUBE_CLIENT_SECRETS_FILE,
    YOUTUBE_TOKEN_FILE,
    YOUTUBE_SCOPES,
)
from src.utils.logger import get_logger

logger = get_logger(__name__)


class YouTubeUploadService:
    """Service for authenticating with YouTube and uploading videos."""

    def __init__(self):
        self._credentials = None
        self._current_flow = None

    def is_configured(self) -> bool:
        """Check if client_secrets.json exists."""
        return os.path.exists(YOUTUBE_CLIENT_SECRETS_FILE)

    def is_authenticated(self) -> bool:
        """Check if we have valid stored credentials."""
        creds = self._load_credentials()
        return creds is not None and creds.valid

    def get_auth_url(self) -> str:
        """
        Generate OAuth2 consent URL for the user.
        """
        if not self.is_configured():
            raise FileNotFoundError(
                f"YouTube client secrets file not found at {YOUTUBE_CLIENT_SECRETS_FILE}. "
                "Please download it from the Google Cloud Console."
            )

        from google_auth_oauthlib.flow import Flow

        flow = Flow.from_client_secrets_file(
            YOUTUBE_CLIENT_SECRETS_FILE,
            scopes=YOUTUBE_SCOPES,
            redirect_uri="http://localhost:9000/oauth2callback",
        )

        auth_url, _ = flow.authorization_url(
            access_type="offline",
            prompt="consent",
        )
        
        # Save flow to preserve PKCE code verifier between requests
        self._current_flow = flow

        return auth_url

    def handle_callback(self, authorization_response_url: str) -> bool:
        """
        Exchange the authorization code for credentials and store them.
        """
        if not self._current_flow:
            raise ValueError("No OAuth flow found. Please start the auth process again.")
            
        self._current_flow.fetch_token(authorization_response=authorization_response_url)
        credentials = self._current_flow.credentials

        # Store credentials
        self._save_credentials(credentials)
        self._credentials = credentials
        
        # Clear the temporary flow
        self._current_flow = None

        logger.info("YouTube OAuth credentials saved successfully")
        return True

    def get_playlists(self) -> list[dict]:
        """
        Fetch the user's YouTube playlists.

        Returns:
            List of dicts with 'id' and 'title' keys
        """
        service = self._get_service()

        playlists = []
        request = service.playlists().list(
            part="snippet",
            mine=True,
            maxResults=50,
        )

        while request:
            response = request.execute()
            for item in response.get("items", []):
                playlists.append({
                    "id": item["id"],
                    "title": item["snippet"]["title"],
                    "description": item["snippet"].get("description", ""),
                })
            request = service.playlists().list_next(request, response)

        return playlists

    def upload_video(
        self,
        video_path: Path,
        title: str,
        description: str,
        tags: Optional[list[str]] = None,
        playlist_id: Optional[str] = None,
        privacy_status: str = "public",
    ) -> dict:
        """
        Upload a video to YouTube.

        Args:
            video_path: Path to the video file
            title: Video title
            description: Video description
            tags: Optional list of tags
            playlist_id: Optional playlist to add the video to
            privacy_status: Privacy status (public, private, unlisted)

        Returns:
            dict with 'video_id', 'video_url', and upload details
        """
        if not video_path.exists():
            raise FileNotFoundError(f"Video file not found: {video_path}")

        service = self._get_service()

        from googleapiclient.http import MediaFileUpload

        body = {
            "snippet": {
                "title": title[:100],  # YouTube title limit
                "description": description[:5000],  # YouTube description limit
                "tags": tags or [],
                "categoryId": "27",  # Education category
            },
            "status": {
                "privacyStatus": privacy_status,
                "selfDeclaredMadeForKids": False,
            },
        }

        media = MediaFileUpload(
            str(video_path),
            mimetype="video/mp4",
            resumable=True,
            chunksize=1024 * 1024 * 10,  # 10MB chunks
        )

        logger.info(f"Uploading video: {title[:50]}...")

        request = service.videos().insert(
            part="snippet,status",
            body=body,
            media_body=media,
        )

        response = None
        while response is None:
            status, response = request.next_chunk()
            if status:
                progress = int(status.progress() * 100)
                logger.info(f"Upload progress: {progress}%")

        video_id = response["id"]
        logger.info(f"Video uploaded successfully: {video_id}")

        # Add to playlist if specified
        if playlist_id:
            try:
                self._add_to_playlist(service, video_id, playlist_id)
                logger.info(f"Video added to playlist: {playlist_id}")
            except Exception as e:
                logger.warning(f"Failed to add video to playlist: {e}")

        return {
            "video_id": video_id,
            "video_url": f"https://www.youtube.com/watch?v={video_id}",
            "title": title,
            "playlist_id": playlist_id,
        }

    def _add_to_playlist(self, service, video_id: str, playlist_id: str):
        """Add a video to a playlist."""
        body = {
            "snippet": {
                "playlistId": playlist_id,
                "resourceId": {
                    "kind": "youtube#video",
                    "videoId": video_id,
                },
            },
        }
        service.playlistItems().insert(part="snippet", body=body).execute()

    def _get_service(self):
        """Get an authenticated YouTube API service."""
        creds = self._load_credentials()
        if not creds or not creds.valid:
            if creds and creds.expired and creds.refresh_token:
                from google.auth.transport.requests import Request
                creds.refresh(Request())
                self._save_credentials(creds)
            else:
                raise RuntimeError(
                    "YouTube is not authenticated. Please connect your YouTube account first."
                )

        from googleapiclient.discovery import build
        return build("youtube", "v3", credentials=creds)

    def _load_credentials(self):
        """Load stored OAuth credentials."""
        if self._credentials and self._credentials.valid:
            return self._credentials

        if not os.path.exists(YOUTUBE_TOKEN_FILE):
            return None

        try:
            from google.oauth2.credentials import Credentials
            creds = Credentials.from_authorized_user_file(
                YOUTUBE_TOKEN_FILE
            )

            # Try to refresh if expired
            if creds and creds.expired and creds.refresh_token:
                from google.auth.transport.requests import Request
                creds.refresh(Request())
                self._save_credentials(creds)

            self._credentials = creds
            return creds
        except Exception as e:
            logger.warning(f"Failed to load YouTube credentials: {e}")
            return None

    def _save_credentials(self, credentials):
        """Save OAuth credentials to file."""
        with open(YOUTUBE_TOKEN_FILE, "w") as f:
            f.write(credentials.to_json())
