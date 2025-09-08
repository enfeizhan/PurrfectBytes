# PurrfectBytes - Modular Architecture

## Overview
PurrfectBytes is a text-to-speech and video generation service with character-level highlighting, built with a modular, future-proof architecture.

## Project Structure

```
PurrfectBytes/
├── main.py                     # Application entry point
├── app.py                      # Legacy monolithic app (deprecated)
├── src/                        # Modular source code
│   ├── __init__.py
│   ├── api/                    # API layer
│   │   ├── __init__.py
│   │   └── routes.py           # FastAPI route definitions
│   ├── services/               # Business logic services
│   │   ├── __init__.py
│   │   ├── language_detection.py  # Language detection service
│   │   ├── tts_service.py      # Text-to-speech generation
│   │   └── video_service.py    # Video generation with highlighting
│   ├── models/                 # Data models and schemas
│   │   ├── __init__.py
│   │   └── schemas.py          # Pydantic models for API
│   ├── utils/                  # Utility functions
│   │   ├── __init__.py
│   │   ├── text_utils.py       # Text processing utilities
│   │   ├── font_utils.py       # Font loading and management
│   │   ├── exceptions.py       # Custom exceptions
│   │   └── logger.py           # Logging configuration
│   └── config/                 # Configuration management
│       ├── __init__.py
│       └── settings.py         # Application settings
├── templates/                  # HTML templates
│   └── index.html
├── audio_files/               # Generated audio files
├── video_files/               # Generated video files
└── pyproject.toml             # Project dependencies
```

## Key Features

### 🏗️ **Modular Architecture**
- **Separation of Concerns**: Each module has a single responsibility
- **Service Layer**: Business logic isolated from API layer
- **Configuration Management**: Centralized settings with environment support
- **Error Handling**: Custom exceptions with proper error propagation

### 🔧 **Services**

#### Language Detection Service (`src/services/language_detection.py`)
- Automatic language detection from text
- Support for 19+ languages
- Fallback mechanisms for unsupported languages

#### TTS Service (`src/services/tts_service.py`)
- Text-to-speech generation using gTTS
- Audio analysis for character timing
- Cleanup utilities for old files

#### Video Service (`src/services/video_service.py`)
- Character-level highlighting synchronized with audio
- Support for CJK and Latin text wrapping
- Configurable video parameters

### 📊 **Models & Schemas** (`src/models/schemas.py`)
- **Pydantic Models**: Type validation and serialization
- **Request/Response Models**: Structured API communication
- **Configuration Models**: Type-safe configuration

### 🛠️ **Utilities**

#### Text Utils (`src/utils/text_utils.py`)
- CJK character detection and handling
- Smart text wrapping for different scripts
- Text cleaning and preparation

#### Font Utils (`src/utils/font_utils.py`)
- Cross-platform font loading
- Font compatibility testing
- Fallback mechanisms

#### Logging (`src/utils/logger.py`)
- Structured logging with context
- Request/response logging middleware
- Performance monitoring

### ⚙️ **Configuration** (`src/config/settings.py`)
- **Environment-based Config**: Development/production settings
- **Feature Flags**: Enable/disable functionality
- **Resource Management**: Directory and file settings
- **Performance Tuning**: Video/audio quality settings

## API Endpoints

### Core Functionality
- `POST /detect-language` - Detect text language
- `POST /convert` - Convert text to audio
- `POST /convert-to-video` - Generate video with highlighting
- `GET /download/{filename}` - Download audio files
- `GET /download-video/{filename}` - Download video files

### Management
- `GET /health` - Health check and status
- `GET /supported-languages` - List supported languages
- `POST /cleanup` - Clean old generated files
- `DELETE /audio/{filename}` - Delete specific audio file
- `DELETE /video/{filename}` - Delete specific video file

### API Documentation
- **Swagger UI**: Available at `/docs`
- **ReDoc**: Available at `/redoc`
- **OpenAPI Schema**: Available at `/openapi.json`

## Running the Application

### Development
```bash
# Install dependencies
uv sync

# Run with auto-reload
uv run python main.py

# Or use uvicorn directly
uv run uvicorn main:app --reload --host 0.0.0.0 --port 8000
```

### Production
```bash
# Set production environment
export DEBUG=false

# Run application
uv run python main.py

# Or with process manager
uv run gunicorn main:app -w 4 -k uvicorn.workers.UvicornWorker
```

## Configuration

### Environment Variables
- `DEBUG` - Enable debug mode (default: false)
- `LOG_LEVEL` - Logging level (default: INFO)

### Settings File (`src/config/settings.py`)
- Modify `VIDEO_CONFIG` for video quality settings
- Update `SUPPORTED_LANGUAGES` to add new languages
- Adjust `CLEANUP_CONFIG` for file retention policies

## Future Extensions

### 🎯 **Planned Features**
1. **Database Integration**: Store generation history
2. **User Management**: Authentication and user sessions
3. **Batch Processing**: Handle multiple files
4. **Custom Voices**: Support for different TTS engines
5. **Advanced Analytics**: Usage metrics and reporting
6. **API Rate Limiting**: Prevent abuse
7. **WebSocket Support**: Real-time progress updates

### 🏗️ **Architecture Improvements**
1. **Caching Layer**: Redis for temporary data
2. **Queue System**: Background job processing
3. **Microservices**: Split into independent services
4. **Container Support**: Docker deployment
5. **Cloud Storage**: S3/GCS for file storage

## Testing

```bash
# Run unit tests (when added)
uv run pytest

# API testing with curl
curl -X POST "http://localhost:8000/detect-language" \
  -F "text=Hello world"

curl -X POST "http://localhost:8000/convert-to-video" \
  -F "text=Hello world" -F "language=en"
```

## Contributing

1. Follow the modular structure
2. Add proper type hints
3. Include docstrings for new functions
4. Update configuration as needed
5. Add appropriate error handling
6. Test thoroughly before deployment

## Migration from Legacy

The legacy `app.py` remains for reference but should be phased out in favor of the new modular structure. All functionality has been preserved and enhanced in the new architecture.

---

**PurrfectBytes** - Modular, scalable, and future-ready text-to-speech and video generation platform! 🎉