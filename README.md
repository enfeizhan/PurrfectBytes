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
- **🆕 Audio/Video Repetition & Concatenation**: Generate and concatenate content multiple times with perfect highlighting synchronization
- **Multi-Language Support**: Automatic language detection with 60+ supported languages
- **CJK Text Handling**: Proper character-based text wrapping for Asian languages
- **Real-time Processing**: Fast audio and video generation with progress feedback
- **File Management**: Automatic cleanup of old files with configurable retention
- **RESTful API**: Complete API endpoints for integration
- **Web UI**: Intuitive interface with repetition controls

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

### Mobile App Setup

#### Native iOS App
```bash
cd PurrfectBytes_iOS
open PurrfectBytes.xcodeproj  # Opens in Xcode
```

#### Native Android App
```bash
cd PurrfectBytes_Android
./gradlew assembleDebug
./gradlew installDebug
```

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
├── PurrfectBytes_iOS/     # Native Swift iOS app
│   └── PurrfectBytes/    # SwiftUI implementation
├── PurrfectBytes_Android/ # Native Kotlin Android app
│   └── app/              # Jetpack Compose implementation
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

### Backend (Web API)
- **Backend**: FastAPI, Python 3.13
- **Text-to-Speech**: Google Text-to-Speech (gTTS)
- **Video Processing**: MoviePy, PIL/Pillow
- **Language Detection**: langdetect
- **Audio Analysis**: librosa
- **Testing**: pytest, httpx
- **Dependency Management**: uv

### Mobile Apps
- **iOS Native**: Swift 5, SwiftUI, AVFoundation
- **Android Native**: Kotlin, Jetpack Compose, Hilt DI
- **State Management**: Combine (iOS), StateFlow (Android)
- **Networking**: URLSession (iOS), Retrofit (Android)

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

## 🙏 Acknowledgments

- Built with [Claude Code](https://claude.ai/code)
- Logo designed with AI assistance
- Inspired by the need for accessible text-to-speech solutions
- Concatenation feature implemented through collaborative problem-solving