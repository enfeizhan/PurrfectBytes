# PurrfectBytes

<div align="center">
  <img src="ChatGPT Image Aug 30, 2025, 06_50_32 PM.png" alt="PurrfectBytes Logo" width="180"/>
  &nbsp;&nbsp;&nbsp;
  <img src="ChatGPT Image Aug 30, 2025, 07_28_15 PM.png" alt="PurrfectBytes Banner" width="300"/>
</div>

## 🎵 Text-to-Speech & Video Generation Platform

A comprehensive web application that converts text into audio files and synchronized videos with character-level highlighting. Built with FastAPI and featuring automatic language detection with CJK (Chinese, Japanese, Korean) support.

## ✨ Features

- **Text-to-Speech**: Convert text to high-quality audio using Google Text-to-Speech (gTTS)
- **Video Generation**: Create videos with synchronized character highlighting
- **Multi-Language Support**: Automatic language detection with 60+ supported languages
- **CJK Text Handling**: Proper character-based text wrapping for Asian languages
- **Real-time Processing**: Fast audio and video generation with progress feedback
- **File Management**: Automatic cleanup of old files with configurable retention
- **RESTful API**: Complete API endpoints for integration

## 🚀 Quick Start

### Prerequisites
- Python 3.13+
- [uv](https://docs.astral.sh/uv/) for dependency management

### Installation

```bash
# Clone the repository
git clone https://github.com/enfeizhan/PurrfectBytes.git
cd PurrfectBytes

# Install dependencies
uv sync

# Run the application
uv run uvicorn main:app --reload
```

Visit `http://localhost:8000` to access the web interface.

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

## 📁 Project Structure

```
PurrfectBytes/
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
└── main.py               # Application entry point
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

## 🤝 Contributing

1. Fork the repository
2. Create a feature branch (`git checkout -b feature/amazing-feature`)
3. Make your changes
4. Run tests (`uv run pytest`)
5. Commit your changes (`git commit -m 'Add amazing feature'`)
6. Push to the branch (`git push origin feature/amazing-feature`)
7. Open a Pull Request

## 📄 License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.

## 🙏 Acknowledgments

- Built with [Claude Code](https://claude.ai/code)
- Logo designed with AI assistance
- Inspired by the need for accessible text-to-speech solutions