# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

PurrfectBytes is a multi-platform text-to-speech and video generation platform for language learning, featuring character-level highlighting synchronized with audio. The project consists of:

- **PurrfectBytesWeb/** - FastAPI backend and web interface (Python 3.13+, managed with `uv`). Private application running on the developer's desktop only — not deployed, no external users.
- **PurrfectBytesAndroid/** - Native Android app (Kotlin + Jetpack Compose)
- **PurrfectBytesiOS/** - Native iOS app (Swift + SwiftUI)

The three applications are independent of each other — changes to one platform don't require changes to the others.

## Build & Development Commands

### Web Application (PurrfectBytesWeb/)

**Setup & Dependencies:**
```bash
cd PurrfectBytesWeb
uv sync                    # Installs deps + dev group (pytest, httpx, pytest-mock)
uv sync --extra test       # Adds pytest-cov, pytest-asyncio, pyfakefs
uv sync --extra dev        # Adds black, isort, flake8, mypy
```

**Running the Server:**
```bash
uv run python main.py      # Serves on 127.0.0.1:9000 (SERVER_HOST/SERVER_PORT in src/config/settings.py)
                           # DEBUG=true env var enables auto-reload

# Alternative with explicit port:
uv run uvicorn main:app --reload --port 9000
```

**Environment / Secrets** (in PurrfectBytesWeb/):
- `.env` — `GEMINI_API_KEY`, `OPENAI_API_KEY`, `ANTHROPIC_API_KEY` (for YouTube metadata generation)
- `client_secrets.json` / `youtube_token.json` — YouTube OAuth2 (paths overridable via `YOUTUBE_CLIENT_SECRETS_FILE` / `YOUTUBE_TOKEN_FILE`)
- Generated media goes to `/tmp/audio_files/` and `/tmp/video_files/` — deliberately in `/tmp` so a reboot flushes it

**Testing:**
```bash
uv run pytest tests/ -v                                       # All tests
uv run pytest tests/unit/services/test_tts_service.py -v      # One file
uv run pytest tests/unit/services/test_tts_service.py::TestClassName::test_name -v  # One test
uv run pytest tests/ --cov=src --cov-report=html              # Coverage (needs --extra test)
```
Test layout: `tests/unit/services/`, `tests/unit/utils/`, `tests/integration/test_api.py`. Shared fixtures in `tests/conftest.py`.

**Code Quality:**
```bash
uv run black .              # Format code
uv run isort .              # Sort imports
uv run flake8 src/          # Lint code
uv run mypy src/            # Type checking
```

### Android Application (PurrfectBytesAndroid/)

```bash
cd PurrfectBytesAndroid
./gradlew assembleDebug      # Build debug APK
./gradlew installDebug       # Install on connected device
./gradlew test               # Unit tests
./gradlew connectedAndroidTest   # Instrumented tests
```

Requirements: JDK 17, Android SDK (minSdk 24, compile/targetSdk 34), `local.properties` with SDK path.

### iOS Application (PurrfectBytesiOS/)

```bash
open PurrfectBytesiOS/PurrfectBytes.xcodeproj
```
Bundle ID `com.purrfectbytes.app`; configure team signing in Xcode. Server URL lives in `Services/APIService.swift`.

## Architecture

### Web Application - Layered Service Architecture

```
src/api/ (routing only)  →  src/services/ (business logic)  →  src/utils/  →  src/config/settings.py
```

**Routing:** `src/api/routes.py` is only an aggregator — it combines six domain routers, and endpoints live in those modules:
- `conversion_routes.py` — `/convert`, `/convert-to-video`, `/preview` (returns PNG bytes directly)
- `language_routes.py` — `/detect-language`, `/tts-engines`, `/tts-voices/{engine}`, `/supported-languages`
- `repetition_routes.py` — repeat/concatenate audio and video
- `file_routes.py` — download/delete/cleanup
- `system_routes.py` — health check
- `youtube_routes.py` — metadata generation, OAuth, upload, playlists

Heavy handlers (TTS, video generation) are deliberately plain `def`, not `async def` — FastAPI runs them in its threadpool so a long render doesn't block the event loop. Keep new heavy endpoints sync.

**Services (src/services/):**
- `tts_service.py` + `tts_engines.py` — TTS with pluggable engines behind `BaseTTSEngine` ABC: `gtts` (Google) and `edge` (Microsoft Edge TTS, async API run in a thread). To add an engine, subclass `BaseTTSEngine` and register in the `TTSEngine` enum / `ENGINE_INFO`.
- `audio_timing.py` — single source of character-timing logic. Edge TTS emits real word timestamps during synthesis (stored as `<audio>.words.json` sidecars) which are interpolated to characters; uniform spacing is the fallback for other engines.
- `video_service.py` — `VideoService` class: orchestration, concatenation, repetition, cleanup
- `video_generation.py` — functional core: font loading/fallback, precomputed character layout, frame rendering
- `language_detection.py` — auto-detect with CJK-specific logic
- `youtube_metadata_service.py` — LLM metadata generation (see below)
- `youtube_upload_service.py` — OAuth2 flow + upload

**Utils worth knowing:** `utils/ffmpeg_utils.py` concatenates/repeats videos by ffmpeg stream copy (no re-encode — near-instant); `utils/audio_utils.py` reads audio duration via mutagen (librosa was removed).

**Other layers:** `src/models/schemas.py` (Pydantic request/response models), `src/utils/` (`text_utils.py` CJK handling, `font_utils.py`, `logger.py`, `exceptions.py`), `src/config/settings.py` (all configuration: dirs, video 1280x720@24fps, language maps, cleanup policy).

### Character-Level Video Highlighting (core feature)

1. **Audio** (`tts_service.py`): generate audio via selected engine, return path + duration. Edge TTS also writes a `.words.json` sidecar with real word timestamps.
2. **Timing** (`audio_timing.py`): word boundaries interpolated to characters (or uniform fallback), with lead time (0.3s) and overlap (0.4s) for smooth transitions
3. **Rendering** (`video_generation.py`): character layout computed once, static base frame cached, per frame only highlighted characters are redrawn; MoviePy encodes (x264 `veryfast`, all cores)
4. **Repetition** (`ffmpeg_utils.py`): repeats/concatenation are ffmpeg stream copies of the single render — never re-encode N copies

### CJK (Chinese/Japanese/Korean) Support

- Character-based (not word-based) text wrapping
- Font selection with fallback probing (`_test_font_supports_text`) — Noto Sans CJK etc.; see `FONTS.md`
- CJK-specific language detection and character timing

### Repetition & Concatenation

Generate once, concatenate N times (never re-generate): `/repeat-audio`, `/repeat-video`, `/concatenate-audio`, `/concatenate-video`. CLI equivalent: `concatenate.py`.

### YouTube Integration & AI Metadata

- **Metadata providers** (`youtube_metadata_service.py`): `gemini` (gemini-3.5-flash, default), `openai` (gpt-5.4-mini), `anthropic` (claude-sonnet-4-5-20250929). Unified "My Study Journal" prompt template; robust regex parsing (`_parse_response`) guarantees the format regardless of minor AI variance. Android has its own independent implementation (`YouTubeMetadataGenerator.kt`).
- **Title format**: `My Study Journal: [LANGUAGE] Sentence - "[TEXT]" | Reading & Pronunciation`, guaranteed ≤ 100 chars.
- **OAuth2**: scope `https://www.googleapis.com/auth/youtube`, persistent token (`youtube_token.json`), endpoints `/youtube/auth-url` → `/oauth2callback` → `/youtube/auth-status`.
- **Upload**: Education category (27), public/private/unlisted, optional playlist add, FFmpeg `faststart` optimization.

### Android Application - MVVM + Hilt

```
Compose UI (ui/screens/, ui/components/) → MainViewModel (StateFlow) → services/ → data/remote/PurrfectBytesApi.kt (Retrofit)
```

Notable: the Android app is not a thin client — it has on-device implementations of several web features in `services/`: `EdgeTTSEngine.kt`, `VideoGeneratorService.kt`, `AnthropicService.kt`, `YouTubeAuthManager.kt`/`YouTubeMetadataGenerator.kt`/`YouTubeVideoUploader.kt`, and ML Kit text recognition (`TextRecognitionProcessor.kt`, incl. CJK) fed by CameraX (`CameraScreen.kt`) with clickable text overlays (`ui/components/`). DI modules in `di/AppModule.kt`.

### iOS Application - MVVM

```
SwiftUI Views (HomeView, VideoCreationView, HistoryView, LoginView, SettingsView)
    → TTSViewModel (@Published / Combine)
        → APIService, AuthenticationManager (Keychain)
```

## API Endpoints Reference

- `POST /convert`, `POST /convert-to-video`, `POST /preview`
- `POST /detect-language`, `GET /tts-engines`, `GET /tts-voices/{engine}`, `GET /supported-languages`
- `POST /repeat-audio`, `POST /repeat-video`, `POST /concatenate-audio`, `POST /concatenate-video`
- `GET /download/{filename}`, `GET /download-video/{filename}`, `DELETE /audio/{filename}`, `DELETE /video/{filename}`
- `POST /generate-youtube-metadata`, `GET /youtube/providers`, `GET /youtube/auth-url`, `GET /oauth2callback`, `GET /youtube/auth-status`, `GET /youtube/playlists`, `POST /youtube/upload`
- `GET /health`, `POST /cleanup`, `GET /docs` (Swagger), `GET /redoc`

## Development Guidelines

**Web:**
1. Business logic belongs in `services/`, not route modules; add new endpoints to the matching domain router, not `routes.py`
2. Use Pydantic models in `schemas.py` for validation; custom exceptions from `utils/exceptions.py`
3. Use the logger from `utils/logger.py`; new settings go in `config/settings.py`

**Android:** MVVM with Hilt DI, Kotlin Coroutines/Flow, Compose + Material Design 3, state via StateFlow in ViewModels.

**iOS:** MVVM, SwiftUI, Combine, Keychain for sensitive data.
