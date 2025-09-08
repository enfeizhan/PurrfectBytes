# PurrfectBytes Web Application

FastAPI-based web application for text-to-speech conversion and video generation with character-level highlighting.

## 🚀 Quick Start

### Prerequisites
- Python 3.13+
- [uv](https://docs.astral.sh/uv/) for dependency management

### Installation & Running

```bash
# Install dependencies
uv sync

# Run the development server
uv run uvicorn main:app --reload

# Visit http://localhost:8000
```

## ✨ Features

- **Text-to-Speech**: Convert text to high-quality audio using Google Text-to-Speech (gTTS)
- **Video Generation**: Create videos with synchronized character highlighting
- **Audio/Video Repetition & Concatenation**: Generate content multiple times with perfect synchronization
- **Multi-Language Support**: Automatic language detection with 60+ supported languages
- **CJK Text Handling**: Proper character-based text wrapping for Asian languages
- **Real-time Processing**: Fast audio and video generation with progress feedback
- **File Management**: Automatic cleanup of old files with configurable retention
- **RESTful API**: Complete API endpoints for integration

## 🛠️ Development

### Running Tests
```bash
# Install test dependencies
uv sync --extra test

# Run all tests
uv run pytest tests/ -v

# Run with coverage
uv run pytest tests/ --cov=src --cov-report=html
```

### Code Quality
```bash
# Install dev dependencies
uv sync --extra dev

# Format code
uv run black .
uv run isort .

# Lint code
uv run flake8 src/
uv run mypy src/
```

## 📁 Directory Structure

```
PurrfectBytes_Web/
├── src/                    # Source code
│   ├── api/               # FastAPI routes and endpoints
│   ├── services/          # Business logic services
│   ├── models/            # Pydantic data models
│   ├── utils/             # Utility functions
│   └── config/            # Configuration management
├── tests/                 # Test suite
│   ├── unit/              # Unit tests
│   └── integration/       # Integration tests
├── templates/             # HTML templates
├── audio_files/           # Generated audio files (ignored)
├── video_files/           # Generated video files (ignored)
├── main.py               # Application entry point
├── app.py                # Legacy app file
├── concatenate.py        # Audio/video concatenation utilities
├── pyproject.toml        # Project configuration
└── uv.lock              # Dependency lock file
```

## 🌍 API Endpoints

- `GET /` - Web interface
- `GET /health` - Health check
- `POST /detect-language` - Detect text language
- `POST /convert` - Convert text to audio
- `POST /convert-to-video` - Convert text to video
- `GET /download/{filename}` - Download audio files
- `GET /download-video/{filename}` - Download video files
- `POST /cleanup` - Cleanup old files

## 🎨 Technology Stack

- **Backend**: FastAPI, Python 3.13
- **Text-to-Speech**: Google Text-to-Speech (gTTS)
- **Video Processing**: MoviePy, PIL/Pillow
- **Language Detection**: langdetect
- **Audio Analysis**: librosa
- **Testing**: pytest, httpx
- **Dependency Management**: uv

## 🔄 Concatenation & Repetition Features

### Web Interface
- **Repetition Control**: Input field to specify number of repetitions (default: 10)
- **Smart Processing**: Automatically uses concatenation when repetitions > 1
- **Progress Feedback**: Shows generation status and file information

### API Endpoints
- `POST /repeat-audio` - Generate and repeat audio content
- `POST /repeat-video` - Generate and repeat video content with synchronized highlighting
- `POST /concatenate-audio` - Concatenate multiple different texts
- `POST /concatenate-video` - Concatenate multiple different videos

### Command Line Interface
```bash
# Generate audio with 10 repetitions
uv run python concatenate.py audio --count 10

# Generate video with custom texts
uv run python concatenate.py video --texts "Hello" "World" "Test"

# Use file input
uv run python concatenate.py audio --file texts.txt
```

### How It Works
1. **Efficient Generation**: Creates content once, then concatenates copies
2. **Perfect Synchronization**: Each repetition maintains perfect highlighting timing
3. **Fallback Support**: Works with or without ffmpeg installation
4. **Clean Implementation**: Simple, robust approach without complex timing manipulation

## 🚀 Deployment

### Development
```bash
uv run uvicorn main:app --reload --host 0.0.0.0 --port 8000
```

### Production
```bash
# Install production dependencies
uv sync --no-dev

# Run with Gunicorn
uv run gunicorn main:app -w 4 -k uvicorn.workers.UvicornWorker
```

### Docker (Optional)
```dockerfile
FROM python:3.13-slim
WORKDIR /app
COPY . .
RUN pip install uv && uv sync --no-dev
CMD ["uv", "run", "uvicorn", "main:app", "--host", "0.0.0.0", "--port", "8000"]
```

## 🤝 Contributing

1. Fork the repository
2. Create a feature branch (`git checkout -b feature/amazing-feature`)
3. Make your changes in the `PurrfectBytes_Web/` directory
4. Run tests (`uv run pytest`)
5. Commit your changes (`git commit -m 'Add amazing feature'`)
6. Push to the branch (`git push origin feature/amazing-feature`)
7. Open a Pull Request

---

**Note**: This is the web application component. For mobile apps, see the parent directory's iOS and Android folders.