# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

PurrfectBytes is a multi-platform text-to-speech and video generation platform for language learning, featuring character-level highlighting synchronized with audio. The project consists of:

- **PurrfectBytesWeb/** - FastAPI backend and web interface (Python 3.13+)
- **PurrfectBytesAndroid/** - Native Android app (Kotlin + Jetpack Compose)
- **PurrfectBytesiOS/** - Native iOS app (Swift + SwiftUI)

## Build & Development Commands

### Web Application (PurrfectBytesWeb/)

**Setup & Dependencies:**
```bash
cd PurrfectBytesWeb
uv sync                    # Install all dependencies
uv sync --extra test       # Install with test dependencies
uv sync --extra dev        # Install with dev dependencies
```

**Running the Server:**
```bash
# Development (auto-reload enabled)
uv run uvicorn main:app --reload --host 0.0.0.0 --port 8000

# Production
export DEBUG=false
uv run gunicorn main:app -w 4 -k uvicorn.workers.UvicornWorker
```

**Testing:**
```bash
uv run pytest tests/ -v                              # Run all tests
uv run pytest tests/unit/test_api.py -v             # Run specific test file
uv run pytest tests/ --cov=src --cov-report=html    # Run with coverage
```

**Code Quality:**
```bash
uv run black .              # Format code
uv run isort .              # Sort imports
uv run flake8 src/          # Lint code
uv run mypy src/            # Type checking
```

### Android Application (PurrfectBytesAndroid/)

**Build & Install:**
```bash
cd PurrfectBytesAndroid
./gradlew assembleDebug      # Build debug APK
./gradlew installDebug       # Install on connected device
./gradlew assembleRelease    # Build release APK (requires signing config)
./gradlew bundleRelease      # Create Play Store bundle
```

**Testing:**
```bash
./gradlew test                           # Run unit tests
./gradlew connectedAndroidTest           # Run instrumented tests
./gradlew createDebugCoverageReport      # Generate coverage report
```

**Requirements:**
- Android Studio (Hedgehog or newer)
- JDK 17
- Android SDK with API levels 24-34
- `local.properties` file with SDK path

### iOS Application (PurrfectBytesiOS/)

```bash
cd PurrfectBytesiOS
open PurrfectBytes.xcodeproj    # Opens in Xcode
```

**Configuration:**
- Set Bundle Identifier: `com.purrfectbytes.app`
- Configure team signing in Xcode
- Update server URL in `APIService.swift` if needed

## Architecture

### Web Application - Layered Service Architecture

The web app follows clean architecture principles with clear separation of concerns:

```
routes.py (API endpoints)
    → services/ (business logic)
        → utils/ (helpers)
            → config/ (settings)
```

**Key Modules:**
- **src/api/routes.py** - FastAPI endpoints (routing only, no business logic)
- **src/services/** - Business logic isolated in service classes:
  - `tts_service.py` - Text-to-speech using gTTS
  - `video_service.py` - Video generation with character highlighting
  - `language_detection.py` - Auto-detect language with CJK support
- **src/models/schemas.py** - Pydantic models for request/response validation
- **src/utils/** - Reusable utilities:
  - `text_utils.py` - Text processing and CJK handling
  - `font_utils.py` - Font management for multilingual support
  - `logger.py` - Structured logging with request context
- **src/config/settings.py** - Centralized configuration

**Configuration (src/config/settings.py):**
- Video settings: 1280x720 @ 24fps, font size 48
- 19+ supported languages with gTTS mapping
- CJK languages (Chinese, Japanese, Korean) have special handling
- Auto-cleanup: 24-hour cleanup interval, 7-day file retention

### Android Application - MVVM + Hilt

```
Jetpack Compose UI
    → ViewModel (StateFlow for state management)
        → Repository/Services
            → API/Database
```

**Key Components:**
- **ui/screens/** - MainScreen, CameraScreen (Jetpack Compose)
- **viewmodels/** - MainViewModel with StateFlow for reactive state
- **services/** - TextRecognitionService (ML Kit), TTSService
- **di/** - Hilt dependency injection modules

**Technologies:**
- Jetpack Compose with Material Design 3
- CameraX for camera access
- ML Kit for text recognition (including CJK)
- Media3/ExoPlayer for audio/video playback
- Retrofit + OkHttp for networking
- Kotlin Coroutines for async operations

### iOS Application - MVVM

```
SwiftUI Views
    → ViewModels (@Published properties)
        → Services (APIService, AuthenticationManager)
            → URLSession/Keychain
```

**Key Components:**
- **Views/** - HomeView, LoginView, HistingsView (SwiftUI)
- **ViewModels/** - ViewModels with Combine publishers
- **Services/** - APIService, AuthenticationManager

## Key Features & Implementation Details

### Character-Level Video Highlighting

The core innovation is synchronized character-by-character highlighting in videos:

1. **Audio Generation** (src/services/tts_service.py):
   - Uses gTTS to generate audio from text
   - Returns audio file path and duration

2. **Timing Analysis** (src/services/video_service.py):
   - Uses librosa to analyze audio and calculate per-character timing
   - Accounts for lead time (0.3s) and overlap (0.4s) for smooth transitions
   - Special handling for CJK text wrapping

3. **Video Rendering**:
   - MoviePy for video composition
   - PIL/Pillow for text rendering with proper fonts
   - Creates frame-by-frame highlighting synchronized with audio

### CJK (Chinese/Japanese/Korean) Support

Special handling for Asian languages:
- Character-based text wrapping (not word-based)
- Proper font selection (Noto Sans CJK, Arial Unicode MS)
- Language detection with CJK-specific logic
- Accurate character timing in videos

### Repetition & Concatenation

Efficient content generation with repetition:
- **Strategy**: Generate once, concatenate multiple times
- **Endpoints**: `/repeat-audio`, `/repeat-video`, `/concatenate-audio`, `/concatenate-video`
- **Synchronization**: Perfect timing maintained across all repetitions
- **CLI Tools**: `concatenate.py` for command-line usage

### Mobile Text Recognition

Android app includes ML Kit integration:
- Camera capture with CameraX
- Text extraction from photos (including CJK characters)
- Clickable text overlay on captured images
- Send extracted text to web API for TTS/video generation

## API Endpoints Reference

**Core Conversion:**
- `POST /detect-language` - Auto-detect text language
- `POST /convert` - Text → Audio
- `POST /convert-to-video` - Text → Video with highlighting

**Repetition:**
- `POST /repeat-audio` - Generate audio and repeat N times
- `POST /repeat-video` - Generate video and repeat N times

**Concatenation:**
- `POST /concatenate-audio` - Combine multiple audio files
- `POST /concatenate-video` - Combine multiple videos

**File Management:**
- `GET /download/{filename}` - Download audio
- `GET /download-video/{filename}` - Download video
- `DELETE /audio/{filename}` - Delete audio
- `DELETE /video/{filename}` - Delete video

**System:**
- `GET /health` - Health check with feature status
- `POST /cleanup` - Clean old files

**Documentation:**
- `GET /docs` - Swagger UI
- `GET /redoc` - ReDoc

## Development Guidelines

### When Modifying the Web Application:

1. **Service Layer**: Business logic belongs in services/, not routes
2. **Type Safety**: Use Pydantic models for validation
3. **Error Handling**: Use custom exceptions from utils/exceptions.py
4. **Logging**: Use the logger from utils/logger.py with request context
5. **Configuration**: Add new settings to config/settings.py
6. **Testing**: Write tests in tests/unit/ or tests/integration/

### When Modifying Android:

1. **Architecture**: Follow MVVM pattern
2. **Dependency Injection**: Use Hilt for dependencies
3. **Async**: Use Kotlin Coroutines and Flow
4. **UI**: Use Jetpack Compose with Material Design 3
5. **State**: Manage state with StateFlow in ViewModels

### When Modifying iOS:

1. **Architecture**: Follow MVVM pattern
2. **UI**: Use SwiftUI with native components
3. **Reactive**: Use Combine for reactive programming
4. **Security**: Use Keychain for sensitive data
5. **State**: Use @Published properties in ViewModels

## File Locations

**Web App Entry Point:** PurrfectBytesWeb/main.py
**Android Entry Point:** PurrfectBytesAndroid/app/src/main/java/com/purrfectbytes/android/MainActivity.kt
**iOS Entry Point:** PurrfectBytesiOS/PurrfectBytes/PurrfectBytesApp.swift

**Configuration Files:**
- Web: PurrfectBytesWeb/pyproject.toml, src/config/settings.py
- Android: PurrfectBytesAndroid/app/build.gradle.kts
- iOS: PurrfectBytesiOS/PurrfectBytes.xcodeproj/project.pbxproj

## Language Support

The platform supports 60+ languages via gTTS. Key supported languages in settings.py:
- English, Spanish, French, German, Italian, Portuguese, Russian
- Japanese, Korean, Chinese (Mandarin)
- Hindi, Arabic, Turkish, Dutch, Polish, Swedish
- And many more...

Language detection automatically identifies the input language, with special handling for CJK languages.

## YouTube Content Generation

The project includes a comprehensive AI prompt template (in main README.md) for generating YouTube titles and descriptions for language learning videos. The prompt creates:
- Formatted titles: "My Study Journal: [LANGUAGE] Sentence - "[TEXT]" | Reading & Pronunciation"
- Structured descriptions with translation, breakdown, grammar points
- Proper credit attribution for sourced content
- Appropriate hashtags for discoverability