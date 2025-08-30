"""Language detection service."""

from typing import Optional
from langdetect import detect
from langdetect.lang_detect_exception import LangDetectException

from src.models.schemas import LanguageDetectionResult
from src.config.settings import SUPPORTED_LANGUAGES, LANGUAGE_CONFIG

class LanguageDetectionService:
    """Service for detecting text language."""
    
    def __init__(self):
        self.supported_languages = SUPPORTED_LANGUAGES
        self.min_chars = LANGUAGE_CONFIG["min_chars_for_detection"]
    
    def detect_language(self, text: str) -> LanguageDetectionResult:
        """
        Detect the language of input text.
        
        Args:
            text: Text to analyze
            
        Returns:
            LanguageDetectionResult with detection information
        """
        # Validate input
        if not text or len(text.strip()) < self.min_chars:
            return LanguageDetectionResult(
                language="en",
                language_name="English",
                confidence=0.0,
                error="Text too short for detection"
            )
        
        try:
            detected_lang = detect(text.strip())
            
            if detected_lang in self.supported_languages:
                lang_info = self.supported_languages[detected_lang]
                return LanguageDetectionResult(
                    language=lang_info['gtts'],
                    language_name=lang_info['name'],
                    confidence=0.9,  # langdetect doesn't provide confidence
                    detected_code=detected_lang
                )
            else:
                # Unsupported language detected
                return LanguageDetectionResult(
                    language="en",
                    language_name="English", 
                    confidence=0.5,
                    detected_code=detected_lang,
                    note=f"Detected '{detected_lang}' but using English as fallback"
                )
                
        except LangDetectException as e:
            # Detection failed
            return LanguageDetectionResult(
                language="en",
                language_name="English",
                confidence=0.0,
                error=f"Language detection failed: {str(e)}"
            )
    
    def is_supported_language(self, lang_code: str) -> bool:
        """Check if a language code is supported for TTS."""
        return lang_code in self.supported_languages
    
    def get_supported_languages(self) -> dict:
        """Get all supported languages."""
        return self.supported_languages
    
    def get_language_info(self, lang_code: str) -> Optional[dict]:
        """Get information about a specific language."""
        return self.supported_languages.get(lang_code)
    
    def normalize_language_code(self, lang_code: str) -> str:
        """Normalize language code to supported format."""
        if lang_code in self.supported_languages:
            return self.supported_languages[lang_code]['gtts']
        return 'en'  # Default fallback