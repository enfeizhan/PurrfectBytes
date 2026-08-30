"""Integration tests for API endpoints."""

import json

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

    def test_repeat_audio_conversation(self, client, mock_all_external_deps):
        """Conversation mode alternates two voices across lines."""
        mock_all_external_deps['gtts'].save = lambda path: None

        response = client.post("/repeat-audio", data={
            "text": "Hello\nHi there",
            "language": "en",
            "conversation": "true",
            "repetitions": "2"
        })

        assert response.status_code == 200
        data = response.json()
        assert data["success"] is True
        assert data["audio_filename"].startswith("conv_2lines_")
        assert "2 lines, 2 voices" in data["message"]
        assert "2 repetitions" in data["message"]

    def test_repeat_audio_conversation_needs_two_lines(self, client):
        """A single-line text is rejected in conversation mode."""
        response = client.post("/repeat-audio", data={
            "text": "Hello",
            "conversation": "true"
        })
        assert response.status_code == 400
        assert "at least 2 lines" in response.json()["detail"]

    def test_repeat_audio_conversation_with_sequence(self, client, mock_all_external_deps):
        """A speed sequence repeats the whole conversation at each step's speed."""
        mock_all_external_deps['gtts'].save = lambda path: None

        response = client.post("/repeat-audio", data={
            "text": "Hello\nHi there",
            "language": "en",
            "conversation": "true",
            "sequence": "1n,1s"
        })

        assert response.status_code == 200
        data = response.json()
        assert data["success"] is True
        assert data["audio_filename"].startswith("conv_2lines_1n-1s_")
        assert "2 lines, 2 voices" in data["message"]
        assert "sequence 1 normal, 1 slow" in data["message"]
        assert "2 repetitions" in data["message"]

    def test_convert_to_video_conversation(self, client, mock_all_external_deps):
        """Conversation video renders one clip per line and concatenates them."""
        mock_all_external_deps['gtts'].save = lambda path: None

        response = client.post("/convert-to-video", data={
            "text": "Hi\nHello",
            "language": "en",
            "conversation": "true",
            "repetitions": "1"
        })

        assert response.status_code == 200
        data = response.json()
        assert data["success"] is True
        assert data["video_filename"].startswith("conv_2lines_")
        assert data["audio_filename"].startswith("conv_2lines_")
        assert "2 lines, 2 voices" in data["message"]

    def test_convert_to_video_conversation_with_sequence(self, client, mock_all_external_deps):
        """A speed sequence repeats the whole conversation video at each step's speed."""
        mock_all_external_deps['gtts'].save = lambda path: None

        response = client.post("/convert-to-video", data={
            "text": "Hi\nHello",
            "language": "en",
            "conversation": "true",
            "sequence": "1n,1s"
        })

        assert response.status_code == 200
        data = response.json()
        assert data["success"] is True
        assert data["video_filename"].startswith("conv_2lines_1n-1s_")
        assert "sequence 1 normal, 1 slow" in data["message"]

    def test_convert_voices_the_override_text(self, client, mocker, temp_dir):
        """A pronunciation override is what the TTS engine actually speaks."""
        from src.api import conversion_routes
        fake_audio = temp_dir / "fake.mp3"
        fake_audio.write_bytes(b"")
        generate = mocker.patch.object(
            conversion_routes.tts_service, "generate_audio",
            return_value=(fake_audio, 1.0),
        )

        response = client.post("/convert", data={
            "text": "昨日行った",
            "language": "ja",
            "voiced_text": "きのう おこなった"
        })

        assert response.status_code == 200
        assert response.json()["success"] is True
        assert generate.call_args.args[0] == "きのう おこなった"

    def test_convert_to_video_voices_override_but_displays_text(self, client, mocker, temp_dir):
        """Video speaks the override while rendering/timing the original text."""
        from src.api import conversion_routes
        fake_audio = temp_dir / "fake.mp3"
        fake_audio.write_bytes(b"")
        generate = mocker.patch.object(
            conversion_routes.tts_service, "generate_audio",
            return_value=(fake_audio, 1.0),
        )
        render = mocker.patch("src.services.video_generation.create_video_with_text")

        response = client.post("/convert-to-video", data={
            "text": "昨日行った",
            "language": "ja",
            "repetitions": "1",
            "voiced_text": "きのうおこなった"
        })

        assert response.status_code == 200
        assert response.json()["success"] is True
        assert generate.call_args.args[0] == "きのうおこなった"
        assert render.call_args.args[0] == "昨日行った"

    def test_repeat_audio_conversation_voices_override_lines(self, client, mocker, temp_dir):
        """In conversation mode the override lines are voiced with the original speakers."""
        from src.api import repetition_routes
        fake_audio = temp_dir / "conv.mp3"
        fake_audio.write_bytes(b"")
        generate = mocker.patch.object(
            repetition_routes.tts_service, "generate_conversation",
            return_value=(fake_audio, 2.0),
        )

        response = client.post("/repeat-audio", data={
            "text": "行った\n売った",
            "conversation": "true",
            "repetitions": "1",
            "voiced_text": "おこなった\nうった"
        })

        assert response.status_code == 200
        assert response.json()["success"] is True
        lines = generate.call_args.kwargs["lines"]
        assert [line.text for line in lines] == ["おこなった", "うった"]
        assert [line.speaker for line in lines] == [0, 1]

    def test_conversation_voiced_override_line_mismatch_rejected(self, client):
        """A conversation override with a different line count is a 400."""
        for endpoint in ("/repeat-audio", "/convert-to-video"):
            response = client.post(endpoint, data={
                "text": "Hello\nHi",
                "conversation": "true",
                "voiced_text": "Only one line"
            })
            assert response.status_code == 400
            assert "same number of lines" in response.json()["detail"]

    def test_sources_crud_round_trip(self, client, temp_dir, monkeypatch):
        """Saved text sources can be created, listed, and deleted."""
        monkeypatch.setattr(
            "src.config.settings.SAVED_SOURCES_FILE",
            str(temp_dir / "saved_sources.json"),
        )

        response = client.get("/sources")
        assert response.status_code == 200
        assert response.json() == {"success": True, "sources": []}

        response = client.post("/sources", data={
            "name": "Shin Kanzen Master N1",
            "credit": "This sentence comes from the textbook 新完全マスター N1."
        })
        assert response.status_code == 200
        data = response.json()
        assert data["success"] is True
        source_id = data["source"]["id"]

        response = client.get("/sources")
        sources = response.json()["sources"]
        assert len(sources) == 1
        assert sources[0]["name"] == "Shin Kanzen Master N1"

        response = client.delete(f"/sources/{source_id}")
        assert response.json()["success"] is True
        assert client.get("/sources").json()["sources"] == []

        # Deleting again fails gracefully
        assert client.delete(f"/sources/{source_id}").json()["success"] is False

    def test_sources_rejects_empty_fields(self, client, temp_dir, monkeypatch):
        """Empty name or credit is rejected."""
        monkeypatch.setattr(
            "src.config.settings.SAVED_SOURCES_FILE",
            str(temp_dir / "saved_sources.json"),
        )
        response = client.post("/sources", data={"name": "  ", "credit": "x"})
        assert response.json()["success"] is False

    def test_generate_metadata_passes_source_credit(self, client, temp_dir, monkeypatch, mocker):
        """A selected saved source's credit line is passed to the metadata service."""
        monkeypatch.setattr(
            "src.config.settings.SAVED_SOURCES_FILE",
            str(temp_dir / "saved_sources.json"),
        )
        from src.api import youtube_routes
        generate = mocker.patch.object(
            youtube_routes.metadata_service, "generate",
            return_value={"title": "t", "description": "d"},
        )

        source = youtube_routes.source_store.add_source("Book", "From a great book.")
        response = client.post("/generate-youtube-metadata", data={
            "text": "Hello",
            "provider": "gemini",
            "source_id": source["id"]
        })

        assert response.status_code == 200
        assert response.json()["success"] is True
        assert generate.call_args.kwargs["credit"] == "From a great book."

    def test_generate_metadata_unknown_source(self, client, temp_dir, monkeypatch):
        """An unknown source id fails without calling the LLM."""
        monkeypatch.setattr(
            "src.config.settings.SAVED_SOURCES_FILE",
            str(temp_dir / "saved_sources.json"),
        )
        response = client.post("/generate-youtube-metadata", data={
            "text": "Hello",
            "source_id": "deadbeef"
        })
        assert response.status_code == 200
        data = response.json()
        assert data["success"] is False
        assert "Unknown text source" in data["error"]

    def test_extract_items_returns_items(self, client, mocker):
        """Extraction endpoint returns the service's item list."""
        from src.api import youtube_routes
        items = [{"kind": "vocabulary", "term": "元気", "phonetics": "げんき", "meaning": "healthy"}]
        extract = mocker.patch.object(
            youtube_routes.metadata_service, "extract_items", return_value=items,
        )

        response = client.post("/extract-youtube-items", data={
            "text": "元気ですか",
            "provider": "gemini"
        })

        assert response.status_code == 200
        data = response.json()
        assert data["success"] is True
        assert data["items"] == items
        assert extract.call_args.args == ("元気ですか", "gemini")

    def test_extract_items_reports_errors_in_body(self, client, mocker):
        """Extraction failures follow the router's in-body error convention."""
        from src.api import youtube_routes
        mocker.patch.object(
            youtube_routes.metadata_service, "extract_items",
            side_effect=ValueError("Could not extract items - try again"),
        )
        response = client.post("/extract-youtube-items", data={"text": "Hello"})
        assert response.status_code == 200
        data = response.json()
        assert data["success"] is False
        assert "Could not extract items" in data["error"]

    def test_generate_metadata_passes_approved_items(self, client, mocker):
        """Ticked items are decoded from JSON and forwarded to the service."""
        from src.api import youtube_routes
        generate = mocker.patch.object(
            youtube_routes.metadata_service, "generate",
            return_value={"title": "t", "description": "d"},
        )

        items = [{"kind": "grammar", "term": "ですか", "phonetics": "", "meaning": "question"}]
        response = client.post("/generate-youtube-metadata", data={
            "text": "元気ですか",
            "provider": "gemini",
            "items": json.dumps(items),
        })

        assert response.status_code == 200
        assert response.json()["success"] is True
        assert generate.call_args.kwargs["items"] == items

    def test_generate_metadata_rejects_bad_items_json(self, client, mocker):
        """Malformed items never reach the LLM."""
        from src.api import youtube_routes
        generate = mocker.patch.object(youtube_routes.metadata_service, "generate")

        for bad in ["not json", '{"kind": "vocabulary"}']:
            response = client.post("/generate-youtube-metadata", data={
                "text": "Hello",
                "items": bad,
            })
            assert response.status_code == 200
            data = response.json()
            assert data["success"] is False
            assert "Invalid items selection" in data["error"]
        generate.assert_not_called()

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