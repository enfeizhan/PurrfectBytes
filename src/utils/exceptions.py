"""Custom exceptions for the PurrfectBytes application."""

class PurrfectBytesException(Exception):
    """Base exception for PurrfectBytes application."""
    
    def __init__(self, message: str, code: str = None, details: dict = None):
        self.message = message
        self.code = code
        self.details = details or {}
        super().__init__(message)

class LanguageDetectionError(PurrfectBytesException):
    """Exception raised when language detection fails."""
    pass

class TTSGenerationError(PurrfectBytesException):
    """Exception raised when TTS generation fails."""
    pass

class VideoGenerationError(PurrfectBytesException):
    """Exception raised when video generation fails."""
    pass

class AudioAnalysisError(PurrfectBytesException):
    """Exception raised when audio analysis fails."""
    pass

class FileNotFoundError(PurrfectBytesException):
    """Exception raised when a required file is not found."""
    pass

class InvalidInputError(PurrfectBytesException):
    """Exception raised when input validation fails."""
    pass

class ConfigurationError(PurrfectBytesException):
    """Exception raised when configuration is invalid."""
    pass