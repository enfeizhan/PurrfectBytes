"""Integration tests for API endpoints."""

import pytest
from fastapi.testclient import TestClient
from unittest.mock import patch


class TestAPIEndpoints:
    """Test API endpoints."""
    
    def test_home_page(self, client):
        """Test home page loads."""
        response = client.get("/")
        assert response.status_code == 200
        assert "text/html" in response.headers["content-type"]
    
    def test_health_check(self, client):
        """Test health check endpoint."""
        response = client.get("/health")
        assert response.status_code == 200
        
        data = response.json()
        assert data["status"] == "healthy"
        assert "version" in data
        assert "uptime" in data
        assert "features" in data
        assert isinstance(data["features"], dict)
    
    def test_supported_languages(self, client):
        """Test supported languages endpoint."""
        response = client.get("/supported-languages")
        assert response.status_code == 200
        
        data = response.json()
        assert "languages" in data
        assert "total" in data
        assert data["total"] > 10
        assert "en" in data["languages"]
    
    def test_detect_language_english(self, client):
        """Test language detection for English."""
        response = client.post("/detect-language", data={"text": "Hello world this is a long English sentence to ensure proper detection"})
        assert response.status_code == 200
        
        data = response.json()
        assert data["language"] == "en"
        assert data["language_name"] == "English"
        assert data["confidence"] > 0
    
    def test_detect_language_empty_text(self, client):
        """Test language detection with empty text."""
        response = client.post("/detect-language", data={"text": ""})
        assert response.status_code == 200
        
        data = response.json()
        assert data["language"] == "en"  # Should default to English
        assert data["confidence"] == 0.0
        assert "error" in data
    
    def test_convert_to_audio_success(self, client, mock_all_external_deps):
        """Test successful audio conversion."""
        # Mock the save method
        mock_all_external_deps['gtts'].save = lambda path: None
        
        response = client.post("/convert", data={
            "text": "Hello world",
            "language": "en",
            "slow": "false"
        })
        
        assert response.status_code == 200
        data = response.json()
        assert data["success"] is True
        assert "audio_filename" in data
        assert "audio_url" in data
        assert "duration" in data
    
    def test_convert_to_audio_empty_text(self, client):
        """Test audio conversion with empty text."""
        response = client.post("/convert", data={
            "text": "",
            "language": "en"
        })
        
        assert response.status_code == 500
        assert "failed" in response.json()["detail"].lower()
    
    def test_convert_to_video_success(self, client, mock_all_external_deps):
        """Test successful video conversion."""
        # Mock the save method and video generation
        mock_all_external_deps['gtts'].save = lambda path: None
        mock_all_external_deps['moviepy']['video_clip'].write_videofile = lambda *args, **kwargs: None
        
        response = client.post("/convert-to-video", data={
            "text": "Hello world",
            "language": "en",
            "slow": "false"
        })
        
        assert response.status_code == 200
        data = response.json()
        assert data["success"] is True
        assert "audio_filename" in data
        assert "video_filename" in data
        assert "audio_url" in data
        assert "video_url" in data
        assert "duration" in data
    
    def test_convert_to_video_japanese(self, client, mock_all_external_deps, sample_japanese_text):
        """Test video conversion with Japanese text."""
        # Mock the save method and video generation
        mock_all_external_deps['gtts'].save = lambda path: None
        mock_all_external_deps['moviepy']['video_clip'].write_videofile = lambda *args, **kwargs: None
        
        response = client.post("/convert-to-video", data={
            "text": sample_japanese_text,
            "language": "ja",
            "slow": "false"
        })
        
        assert response.status_code == 200
        data = response.json()
        assert data["success"] is True
    
    def test_convert_to_video_unsupported_language(self, client, mock_all_external_deps):
        """Test video conversion with unsupported language."""
        # Mock the save method and video generation
        mock_all_external_deps['gtts'].save = lambda path: None
        mock_all_external_deps['moviepy']['video_clip'].write_videofile = lambda *args, **kwargs: None
        
        response = client.post("/convert-to-video", data={
            "text": "Hello world",
            "language": "xyz",  # Unsupported
            "slow": "false"
        })
        
        assert response.status_code == 200  # Should default to English
        data = response.json()
        assert data["success"] is True
    
    def test_convert_to_video_with_sequence(self, client, mock_all_external_deps):
        """Video conversion honors a normal/slow speed sequence."""
        mock_all_external_deps['gtts'].save = lambda path: None

        response = client.post("/convert-to-video", data={
            "text": "Hi",
            "language": "en",
            "sequence": "1n,1s"
        })

        assert response.status_code == 200
        data = response.json()
        assert data["success"] is True
        assert data["video_filename"].startswith("seq_1n-1s_")
        assert data["duration"] > 0
        assert "sequence 1 normal, 1 slow" in data["message"]

    def test_convert_to_video_invalid_sequence(self, client):
        """Invalid sequence specs are rejected with 400 before any generation."""
        for bad in ("0n", "101n", "2x"):
            response = client.post("/convert-to-video", data={
                "text": "Hi",
                "sequence": bad
            })
            assert response.status_code == 400

    def test_repeat_audio_with_sequence(self, client, mock_all_external_deps):
        """Audio repetition honors a normal/slow speed sequence."""
        mock_all_external_deps['gtts'].save = lambda path: None

        response = client.post("/repeat-audio", data={
            "text": "Hi",
            "language": "en",
            "sequence": "2n,1s"
        })

        assert response.status_code == 200
        data = response.json()
        assert data["success"] is True
        assert data["audio_filename"].startswith("seq_2n-1s_")
        assert "sequence 2 normal, 1 slow" in data["message"]
        assert "3 repetitions" in data["message"]

    def test_repeat_audio_invalid_sequence(self, client):
        """Invalid sequence specs are rejected with 400."""
        for bad in ("0n", "101n", "abc"):
            response = client.post("/repeat-audio", data={
                "text": "Hi",
                "sequence": bad
            })
            assert response.status_code == 400

    def test_download_audio_not_found(self, client):
        """Test downloading non-existent audio file."""
        response = client.get("/download/nonexistent.mp3")
        assert response.status_code == 404
        assert "not found" in response.json()["detail"].lower()
    
    def test_download_video_not_found(self, client):
        """Test downloading non-existent video file."""
        response = client.get("/download-video/nonexistent.mp4")
        assert response.status_code == 404
        assert "not found" in response.json()["detail"].lower()
    
    def test_delete_audio_not_found(self, client):
        """Test deleting non-existent audio file."""
        response = client.delete("/audio/nonexistent.mp3")
        assert response.status_code == 404
        assert "not found" in response.json()["detail"].lower()
    
    def test_delete_video_not_found(self, client):
        """Test deleting non-existent video file."""
        response = client.delete("/video/nonexistent.mp4")
        assert response.status_code == 404
        assert "not found" in response.json()["detail"].lower()
    
    def test_cleanup_files(self, client):
        """Test file cleanup endpoint."""
        response = client.post("/cleanup?max_age_hours=24")
        assert response.status_code == 200
        
        data = response.json()
        assert data["success"] is True
        assert "audio_files_removed" in data
        assert "video_files_removed" in data
        assert "total_files_removed" in data
    
    @pytest.mark.parametrize("endpoint,method", [
        ("/detect-language", "POST"),
        ("/convert", "POST"),
        ("/convert-to-video", "POST"),
    ])
    def test_missing_form_data(self, client, endpoint, method):
        """Test API endpoints with missing form data."""
        if method == "POST":
            response = client.post(endpoint, data={})
        else:
            response = client.get(endpoint)
        
        assert response.status_code in [422, 500]  # Validation error or server error