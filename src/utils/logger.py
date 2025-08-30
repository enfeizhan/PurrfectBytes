"""Logging configuration for the PurrfectBytes application."""

import logging
import sys
from datetime import datetime
from pathlib import Path
from typing import Optional

def setup_logger(
    name: str = "purrfectbytes",
    level: int = logging.INFO,
    log_file: Optional[Path] = None,
    format_string: str = None
) -> logging.Logger:
    """
    Set up application logger.
    
    Args:
        name: Logger name
        level: Logging level
        log_file: Optional log file path
        format_string: Optional custom format string
        
    Returns:
        Configured logger instance
    """
    if format_string is None:
        format_string = (
            "%(asctime)s - %(name)s - %(levelname)s - "
            "%(filename)s:%(lineno)d - %(message)s"
        )
    
    formatter = logging.Formatter(format_string)
    logger = logging.getLogger(name)
    logger.setLevel(level)
    
    # Remove existing handlers to avoid duplicates
    for handler in logger.handlers[:]:
        logger.removeHandler(handler)
    
    # Console handler
    console_handler = logging.StreamHandler(sys.stdout)
    console_handler.setLevel(level)
    console_handler.setFormatter(formatter)
    logger.addHandler(console_handler)
    
    # File handler (optional)
    if log_file:
        log_file.parent.mkdir(parents=True, exist_ok=True)
        file_handler = logging.FileHandler(log_file)
        file_handler.setLevel(level)
        file_handler.setFormatter(formatter)
        logger.addHandler(file_handler)
    
    return logger

def get_logger(name: str = "purrfectbytes") -> logging.Logger:
    """Get logger instance."""
    return logging.getLogger(name)

def log_request(logger: logging.Logger, endpoint: str, params: dict = None):
    """Log API request."""
    params_str = f" with params: {params}" if params else ""
    logger.info(f"API request to {endpoint}{params_str}")

def log_response(logger: logging.Logger, endpoint: str, status: str, duration: float = None):
    """Log API response."""
    duration_str = f" in {duration:.2f}s" if duration else ""
    logger.info(f"API response from {endpoint}: {status}{duration_str}")

def log_error(logger: logging.Logger, error: Exception, context: str = None):
    """Log error with context."""
    context_str = f" in {context}" if context else ""
    logger.error(f"Error{context_str}: {str(error)}", exc_info=True)

def log_performance(logger: logging.Logger, operation: str, duration: float, details: dict = None):
    """Log performance metrics."""
    details_str = f" - {details}" if details else ""
    logger.info(f"Performance: {operation} took {duration:.2f}s{details_str}")

class RequestLogger:
    """Context manager for request logging."""
    
    def __init__(self, logger: logging.Logger, operation: str, log_params: bool = True):
        self.logger = logger
        self.operation = operation
        self.log_params = log_params
        self.start_time = None
    
    def __enter__(self):
        self.start_time = datetime.now()
        self.logger.info(f"Starting {self.operation}")
        return self
    
    def __exit__(self, exc_type, exc_val, exc_tb):
        duration = (datetime.now() - self.start_time).total_seconds()
        
        if exc_type is None:
            self.logger.info(f"Completed {self.operation} in {duration:.2f}s")
        else:
            self.logger.error(
                f"Failed {self.operation} after {duration:.2f}s: {str(exc_val)}"
            )
        
        return False  # Don't suppress exceptions