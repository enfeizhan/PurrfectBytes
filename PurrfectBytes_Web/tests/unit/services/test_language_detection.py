"""Unit tests for language detection service."""

import pytest
from unittest.mock import patch

from src.services.language_detection import LanguageDetectionService
from src.models.schemas import LanguageDetectionResult


class TestLanguageDetectionService:
    """Test language detection service."""
    
    def test_detect_english_text(self, language_service):
        """Test detecting English text."""
        with patch('src.services.language_detection.detect', return_value='en'):
            result = language_service.detect_language("Hello world")
            
        assert result.language == "en"
        assert result.language_name == "English"
        assert result.confidence > 0
        assert result.detected_code == "en"
        assert result.error is None
    
    def test_detect_spanish_text(self, language_service):
        """Test detecting Spanish text."""
        with patch('src.services.language_detection.detect', return_value='es'):
            result = language_service.detect_language("Hola mundo")
            
        assert result.language == "es"
        assert result.language_name == "Spanish"
        assert result.confidence > 0
        assert result.detected_code == "es"
    
    def test_detect_japanese_text(self, language_service):
        """Test detecting Japanese text."""
        with patch('src.services.language_detection.detect', return_value='ja'):
            result = language_service.detect_language("こんにちは")
            
        assert result.language == "ja"
        assert result.language_name == "Japanese"
        assert result.confidence > 0
        assert result.detected_code == "ja"
    
    def test_detect_unsupported_language(self, language_service):
        """Test handling unsupported language."""
        with patch('src.services.language_detection.detect', return_value='xy'):
            result = language_service.detect_language("Unsupported language")
            
        assert result.language == "en"  # Fallback to English
        assert result.language_name == "English"
        assert result.detected_code == "xy"
        assert "fallback" in result.note.lower()
    
    def test_detect_empty_text(self, language_service):
        """Test handling empty text."""
        result = language_service.detect_language("")
        
        assert result.language == "en"
        assert result.confidence == 0.0
        assert "too short" in result.error.lower()
    
    def test_detect_short_text(self, language_service):
        """Test handling text that's too short."""
        result = language_service.detect_language("Hi")
        
        assert result.language == "en"
        assert result.confidence == 0.0
        assert "too short" in result.error.lower()
    
    def test_detect_with_exception(self, language_service):
        """Test handling detection exceptions."""
        from langdetect.lang_detect_exception import LangDetectException
        
        with patch('src.services.language_detection.detect', 
                  side_effect=LangDetectException(1, 'Test error')):
            result = language_service.detect_language("Some text")
            
        assert result.language == "en"
        assert result.confidence == 0.0
        assert "detection failed" in result.error.lower()
    
    def test_is_supported_language(self, language_service):
        """Test language support checking."""
        assert language_service.is_supported_language('en') is True
        assert language_service.is_supported_language('es') is True
        assert language_service.is_supported_language('ja') is True
        assert language_service.is_supported_language('xyz') is False
    
    def test_get_supported_languages(self, language_service):
        """Test getting supported languages."""
        languages = language_service.get_supported_languages()
        
        assert isinstance(languages, dict)
        assert 'en' in languages
        assert 'es' in languages
        assert 'ja' in languages
        assert len(languages) > 10  # Should have many languages
    
    def test_get_language_info(self, language_service):
        """Test getting language information."""
        info = language_service.get_language_info('en')
        
        assert info is not None
        assert info['name'] == 'English'
        assert info['gtts'] == 'en'
        
        # Test non-existent language
        info = language_service.get_language_info('xyz')
        assert info is None
    
    def test_normalize_language_code(self, language_service):
        """Test language code normalization."""
        assert language_service.normalize_language_code('en') == 'en'
        assert language_service.normalize_language_code('es') == 'es'
        assert language_service.normalize_language_code('xyz') == 'en'  # Fallback