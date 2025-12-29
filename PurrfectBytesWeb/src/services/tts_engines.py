"""Multiple TTS Engine implementations for PurrfectBytes.

This module provides a unified interface for different TTS engines:
- gTTS (Google Text-to-Speech) - Original, simple but monotonic
- Edge-TTS (Microsoft Edge) - Natural neural voices, free
- Piper - Offline neural TTS, fast and natural
- Coqui TTS - Open source, highly customizable
"""

import asyncio
import subprocess
import uuid
from abc import ABC, abstractmethod
from pathlib import Path
from typing import Optional, Tuple, List, Dict, Any
from enum import Enum

from src.utils.logger import get_logger

logger = get_logger(__name__)


class TTSEngine(str, Enum):
    """Available TTS engines."""
    GTTS = "gtts"
    EDGE = "edge"
    PIPER = "piper"


# Engine display names and descriptions
ENGINE_INFO: Dict[str, Dict[str, str]] = {
    TTSEngine.GTTS: {
        "name": "Google TTS (gTTS)",
        "description": "Simple and reliable, but monotonic voice",
        "requires_internet": True,
    },
    TTSEngine.EDGE: {
        "name": "Microsoft Edge TTS",
        "description": "Natural neural voices, best quality",
        "requires_internet": True,
    },
    TTSEngine.PIPER: {
        "name": "Piper (Offline)",
        "description": "Fast offline neural TTS",
        "requires_internet": False,
    },
}


class BaseTTSEngine(ABC):
    """Abstract base class for TTS engines."""
    
    def __init__(self, audio_dir: Path, audio_format: str = "mp3"):
        self.audio_dir = audio_dir
        self.audio_format = audio_format
    
    @abstractmethod
    def generate(
        self,
        text: str,
        language: str = "en",
        slow: bool = False,
        voice: Optional[str] = None
    ) -> Tuple[Path, float]:
        """
        Generate audio from text.
        
        Args:
            text: Text to convert to speech
            language: Language code (e.g., 'en', 'es', 'fr')
            slow: Whether to use slow speech speed
            voice: Optional specific voice to use
            
        Returns:
            Tuple of (audio_file_path, duration_in_seconds)
        """
        pass
    
    @abstractmethod
    def get_available_voices(self, language: str = "en") -> List[Dict[str, str]]:
        """Get available voices for a language."""
        pass
    
    @abstractmethod
    def is_available(self) -> bool:
        """Check if this engine is available/installed."""
        pass
    
    def _generate_filename(self, prefix: str = "") -> str:
        """Generate a unique filename for audio output."""
        return f"{prefix}{uuid.uuid4()}.{self.audio_format}"
    
    def _get_duration(self, audio_path: Path) -> float:
        """Get audio duration using librosa."""
        try:
            import librosa
            y, sr = librosa.load(str(audio_path))
            return librosa.get_duration(y=y, sr=sr)
        except Exception as e:
            logger.warning(f"Could not get audio duration: {e}")
            # Fallback estimation
            return len(audio_path.read_bytes()) / 16000


class GTTSEngine(BaseTTSEngine):
    """Google Text-to-Speech engine using gTTS library."""
    
    def generate(
        self,
        text: str,
        language: str = "en",
        slow: bool = False,
        voice: Optional[str] = None
    ) -> Tuple[Path, float]:
        from gtts import gTTS
        
        audio_filename = self._generate_filename("gtts_")
        audio_path = self.audio_dir / audio_filename
        
        try:
            tts = gTTS(text=text, lang=language, slow=slow)
            tts.save(str(audio_path))
            duration = self._get_duration(audio_path)
            logger.info(f"gTTS generated: {audio_filename} ({duration:.2f}s)")
            return audio_path, duration
        except Exception as e:
            if audio_path.exists():
                audio_path.unlink()
            raise RuntimeError(f"gTTS generation failed: {e}")
    
    def get_available_voices(self, language: str = "en") -> List[Dict[str, str]]:
        # gTTS doesn't have voice selection, just language
        return [{"id": language, "name": f"Default ({language})"}]
    
    def is_available(self) -> bool:
        try:
            from gtts import gTTS
            return True
        except ImportError:
            return False


class EdgeTTSEngine(BaseTTSEngine):
    """Microsoft Edge TTS engine using edge-tts library."""
    
    # Default voices for common languages
    DEFAULT_VOICES = {
        "en": "en-US-AriaNeural",
        "en-US": "en-US-AriaNeural",
        "en-GB": "en-GB-SoniaNeural",
        "en-AU": "en-AU-NatashaNeural",
        "es": "es-ES-ElviraNeural",
        "fr": "fr-FR-DeniseNeural",
        "de": "de-DE-KatjaNeural",
        "it": "it-IT-ElsaNeural",
        "pt": "pt-BR-FranciscaNeural",
        "ru": "ru-RU-SvetlanaNeural",
        "ja": "ja-JP-NanamiNeural",
        "ko": "ko-KR-SunHiNeural",
        "zh": "zh-CN-XiaoxiaoNeural",
        "ar": "ar-SA-ZariyahNeural",
        "hi": "hi-IN-SwaraNeural",
        "nl": "nl-NL-ColetteNeural",
        "pl": "pl-PL-ZofiaNeural",
        "tr": "tr-TR-EmelNeural",
        "sv": "sv-SE-SofieNeural",
        "da": "da-DK-ChristelNeural",
        "no": "nb-NO-PernilleNeural",
        "fi": "fi-FI-NooraNeural",
    }
    
    def __init__(self, audio_dir: Path, audio_format: str = "mp3"):
        super().__init__(audio_dir, audio_format)
        self._voices_cache: Optional[List[Dict[str, Any]]] = None
    
    def generate(
        self,
        text: str,
        language: str = "en",
        slow: bool = False,
        voice: Optional[str] = None
    ) -> Tuple[Path, float]:
        import edge_tts
        import concurrent.futures
        
        # Select voice
        if voice:
            selected_voice = voice
        else:
            selected_voice = self.DEFAULT_VOICES.get(language, "en-US-AriaNeural")
        
        # Adjust rate for slow speech
        rate = "-20%" if slow else "+0%"
        
        audio_filename = self._generate_filename("edge_")
        audio_path = self.audio_dir / audio_filename
        
        try:
            # Define the async generation function
            async def _generate():
                communicate = edge_tts.Communicate(text, selected_voice, rate=rate)
                await communicate.save(str(audio_path))
            
            # Run async code in a new event loop in a separate thread
            # This avoids the "cannot call asyncio.run from running event loop" error
            def run_in_thread():
                loop = asyncio.new_event_loop()
                asyncio.set_event_loop(loop)
                try:
                    loop.run_until_complete(_generate())
                finally:
                    loop.close()
            
            with concurrent.futures.ThreadPoolExecutor() as executor:
                future = executor.submit(run_in_thread)
                future.result(timeout=60)  # Wait for completion with timeout
            
            duration = self._get_duration(audio_path)
            logger.info(f"Edge-TTS generated: {audio_filename} ({duration:.2f}s) voice={selected_voice}")
            return audio_path, duration
            
        except Exception as e:
            if audio_path.exists():
                audio_path.unlink()
            raise RuntimeError(f"Edge-TTS generation failed: {e}")
    
    def get_available_voices(self, language: str = "en") -> List[Dict[str, str]]:
        """Get available Edge-TTS voices for a language."""
        import edge_tts
        import concurrent.futures
        
        try:
            async def _get_voices():
                voices = await edge_tts.list_voices()
                return voices
            
            # Run async code in a new event loop in a separate thread
            def run_in_thread():
                loop = asyncio.new_event_loop()
                asyncio.set_event_loop(loop)
                try:
                    return loop.run_until_complete(_get_voices())
                finally:
                    loop.close()
            
            with concurrent.futures.ThreadPoolExecutor() as executor:
                future = executor.submit(run_in_thread)
                all_voices = future.result(timeout=30)
            
            # Filter by language
            lang_prefix = language.lower()
            filtered = [
                {"id": v["ShortName"], "name": f"{v['ShortName']} - {v.get('Gender', 'Unknown')}"}
                for v in all_voices
                if v["Locale"].lower().startswith(lang_prefix)
            ]
            
            return filtered if filtered else [{"id": self.DEFAULT_VOICES.get(language, "en-US-AriaNeural"), "name": "Default"}]
            
        except Exception as e:
            logger.warning(f"Could not fetch Edge-TTS voices: {e}")
            return [{"id": self.DEFAULT_VOICES.get(language, "en-US-AriaNeural"), "name": "Default"}]
    
    def is_available(self) -> bool:
        try:
            import edge_tts
            return True
        except ImportError:
            return False


class PiperTTSEngine(BaseTTSEngine):
    """Piper TTS engine for offline neural TTS."""
    
    # Default models for common languages
    DEFAULT_MODELS = {
        "en": "en_US-lessac-medium",
        "en-US": "en_US-lessac-medium",
        "en-GB": "en_GB-alan-medium",
        "es": "es_ES-davefx-medium",
        "fr": "fr_FR-upmc-medium",
        "de": "de_DE-thorsten-medium",
        "it": "it_IT-riccardo-x_low",
        "ru": "ru_RU-ruslan-medium",
        "nl": "nl_NL-mls-medium",
        "pl": "pl_PL-gosia-medium",
        "uk": "uk_UA-ukrainian_tts-medium",
        "zh": "zh_CN-huayan-medium",
    }
    
    def __init__(self, audio_dir: Path, audio_format: str = "mp3"):
        # Piper outputs WAV, we'll convert if needed
        super().__init__(audio_dir, "wav")
        self.output_format = audio_format
        self._piper_path: Optional[str] = None
        self._models_dir: Optional[Path] = None
    
    def _find_piper(self) -> Optional[str]:
        """Find piper executable."""
        if self._piper_path:
            return self._piper_path
        
        # Try common locations - including when installed as folder
        locations = [
            "piper",  # In PATH
            "/usr/local/bin/piper/piper",  # Installed as folder from tarball
            "/usr/local/bin/piper",
            "/usr/bin/piper",
            str(Path.home() / ".local/bin/piper/piper"),  # User install as folder
            str(Path.home() / ".local/bin/piper"),
            str(Path.home() / "piper/piper"),  # Direct in home
        ]
        
        for loc in locations:
            try:
                # Try --help since piper may not support --version
                result = subprocess.run([loc, "--help"], capture_output=True, timeout=5)
                if result.returncode == 0:
                    self._piper_path = loc
                    logger.info(f"Found piper at: {loc}")
                    return loc
            except (subprocess.SubprocessError, FileNotFoundError, PermissionError):
                continue
        
        return None
    
    def _find_model_file(self, model_name: str) -> Optional[Path]:
        """Find the actual path to a Piper model .onnx file."""
        # Common locations for Piper models
        model_dirs = [
            Path.home() / ".local/share/piper-voices",
            Path.home() / ".piper",
            Path.home() / "piper-voices",
            Path("/usr/local/share/piper-voices"),
            Path("/usr/share/piper-voices"),
        ]
        
        # Check if model_name is already a full path
        if Path(model_name).exists():
            return Path(model_name)
        
        # Search for the model file
        for model_dir in model_dirs:
            if not model_dir.exists():
                continue
            
            # Try exact filename match
            exact_path = model_dir / f"{model_name}.onnx"
            if exact_path.exists():
                logger.info(f"Found Piper model at: {exact_path}")
                return exact_path
            
            # Try searching recursively
            for onnx_file in model_dir.glob(f"**/{model_name}.onnx"):
                logger.info(f"Found Piper model at: {onnx_file}")
                return onnx_file
            
            # Try partial match (model name might be part of filename)
            for onnx_file in model_dir.glob("**/*.onnx"):
                if model_name in onnx_file.stem:
                    logger.info(f"Found Piper model at: {onnx_file}")
                    return onnx_file
        
        return None
    
    def generate(
        self,
        text: str,
        language: str = "en",
        slow: bool = False,
        voice: Optional[str] = None
    ) -> Tuple[Path, float]:
        piper_cmd = self._find_piper()
        if not piper_cmd:
            raise RuntimeError("Piper TTS is not installed. Install it from https://github.com/rhasspy/piper")
        
        # Select model name
        model_name = voice if voice else self.DEFAULT_MODELS.get(language, "en_US-lessac-medium")
        
        # Find the actual model file path
        model_path = self._find_model_file(model_name)
        if not model_path:
            raise RuntimeError(
                f"Piper model '{model_name}' not found. "
                f"Download models from https://huggingface.co/rhasspy/piper-voices and place .onnx files in ~/.local/share/piper-voices/"
            )
        
        audio_filename = self._generate_filename("piper_")
        audio_path = self.audio_dir / audio_filename
        
        try:
            # Piper reads from stdin and outputs to file
            cmd = [piper_cmd, "--model", str(model_path), "--output_file", str(audio_path)]
            
            if slow:
                cmd.extend(["--length_scale", "1.3"])  # Slow down by 30%
            
            result = subprocess.run(
                cmd,
                input=text.encode("utf-8"),
                capture_output=True,
                timeout=60
            )
            
            if result.returncode != 0:
                raise RuntimeError(f"Piper failed: {result.stderr.decode()}")
            
            # Try to convert to mp3 if needed (requires ffmpeg)
            if self.output_format == "mp3":
                try:
                    audio_path = self._convert_to_mp3(audio_path)
                except Exception as conv_error:
                    # If conversion fails (e.g., no ffmpeg), just use the WAV file
                    logger.warning(f"Could not convert to MP3 (missing ffmpeg?): {conv_error}")
                    # Keep the WAV file as-is
            
            duration = self._get_duration(audio_path)
            logger.info(f"Piper generated: {audio_path.name} ({duration:.2f}s)")
            return audio_path, duration
            
        except subprocess.TimeoutExpired:
            if audio_path.exists():
                audio_path.unlink()
            raise RuntimeError("Piper TTS timed out")
        except Exception as e:
            if audio_path.exists():
                audio_path.unlink()
            raise RuntimeError(f"Piper TTS failed: {e}")
    
    def _convert_to_mp3(self, wav_path: Path) -> Path:
        """Convert WAV to MP3 using pydub (requires ffmpeg)."""
        from pydub import AudioSegment
        
        mp3_path = wav_path.with_suffix(".mp3")
        audio = AudioSegment.from_wav(str(wav_path))
        audio.export(str(mp3_path), format="mp3")
        wav_path.unlink()  # Remove original WAV
        return mp3_path
    
    def get_available_voices(self, language: str = "en") -> List[Dict[str, str]]:
        # Return default models - actual model list requires checking installed models
        models = []
        for lang, model in self.DEFAULT_MODELS.items():
            if lang.startswith(language):
                models.append({"id": model, "name": model})
        return models if models else [{"id": "en_US-lessac-medium", "name": "Default English"}]
    
    def _find_models_dir(self) -> Optional[Path]:
        """Find Piper models directory."""
        # Common locations for Piper models
        model_dirs = [
            Path.home() / ".local/share/piper-voices",
            Path.home() / ".piper",
            Path.home() / "piper-voices",
            Path("/usr/local/share/piper-voices"),
            Path("/usr/share/piper-voices"),
        ]
        
        for model_dir in model_dirs:
            if model_dir.exists() and any(model_dir.glob("*.onnx")):
                return model_dir
        
        return None
    
    def is_available(self) -> bool:
        piper_found = self._find_piper() is not None
        if not piper_found:
            return False
        
        # Piper is installed but needs models - still show as available but with warning
        # The generation will fail with a clear message if models are missing
        return True
    
    def has_models(self) -> bool:
        """Check if any Piper models are installed."""
        return self._find_models_dir() is not None

class TTSEngineFactory:
    """Factory for creating TTS engine instances."""
    
    _engines: Dict[TTSEngine, type] = {
        TTSEngine.GTTS: GTTSEngine,
        TTSEngine.EDGE: EdgeTTSEngine,
        TTSEngine.PIPER: PiperTTSEngine,
    }
    
    _instances: Dict[TTSEngine, BaseTTSEngine] = {}
    
    @classmethod
    def get_engine(cls, engine: TTSEngine, audio_dir: Path, audio_format: str = "mp3") -> BaseTTSEngine:
        """Get or create a TTS engine instance."""
        if engine not in cls._instances:
            engine_class = cls._engines.get(engine)
            if not engine_class:
                raise ValueError(f"Unknown TTS engine: {engine}")
            cls._instances[engine] = engine_class(audio_dir, audio_format)
        return cls._instances[engine]
    
    @classmethod
    def get_available_engines(cls) -> List[Dict[str, Any]]:
        """Get list of available TTS engines with their status."""
        from src.config.settings import AUDIO_DIR, AUDIO_CONFIG
        
        engines = []
        for engine_type in TTSEngine:
            try:
                engine = cls.get_engine(engine_type, AUDIO_DIR, AUDIO_CONFIG["format"])
                engines.append({
                    "id": engine_type.value,
                    "name": ENGINE_INFO[engine_type]["name"],
                    "description": ENGINE_INFO[engine_type]["description"],
                    "available": engine.is_available(),
                    "requires_internet": ENGINE_INFO[engine_type]["requires_internet"],
                })
            except Exception as e:
                engines.append({
                    "id": engine_type.value,
                    "name": ENGINE_INFO[engine_type]["name"],
                    "description": ENGINE_INFO[engine_type]["description"],
                    "available": False,
                    "requires_internet": ENGINE_INFO[engine_type]["requires_internet"],
                    "error": str(e),
                })
        return engines
