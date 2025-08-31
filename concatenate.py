#!/usr/bin/env python3
"""Command-line script for concatenating audio and video files."""

import argparse
import sys
from pathlib import Path
from typing import List

from src.services.tts_service import TTSService
from src.services.video_service import VideoService
from src.services.language_detection import LanguageDetectionService
from src.utils.logger import setup_logger

# Setup logger
logger = setup_logger("concatenate")

def generate_sample_texts(count: int = 10) -> List[str]:
    """Generate sample texts for demonstration."""
    templates = [
        "Hello, this is sample text number {}.",
        "The quick brown fox jumps over the lazy dog, iteration {}.",
        "Welcome to PurrfectBytes, generating clip number {}.",
        "This is a demonstration of concatenation, part {}.",
        "Testing text-to-speech and video generation, segment {}.",
        "Creating amazing content with AI, episode {}.",
        "Let's explore the possibilities together, chapter {}.",
        "Technology meets creativity in clip {}.",
        "Building something wonderful, section {}.",
        "Thank you for using PurrfectBytes, finale {}."
    ]
    
    texts = []
    for i in range(count):
        template = templates[i % len(templates)]
        texts.append(template.format(i + 1))
    
    return texts

def concatenate_audio(
    texts: List[str],
    output_file: str = None,
    language: str = None,
    slow: bool = False
):
    """Generate and concatenate audio files."""
    try:
        tts_service = TTSService()
        lang_service = LanguageDetectionService()
        
        # Auto-detect language if not specified
        if not language:
            lang_result = lang_service.detect_language(texts[0])
            language = lang_result.language
            logger.info(f"Detected language: {language}")
        
        logger.info(f"Generating {len(texts)} audio files...")
        
        # Generate and concatenate
        concat_path, individual_paths, total_duration = tts_service.generate_multiple_and_concatenate(
            texts=texts,
            language=language,
            slow=slow,
            output_filename=output_file
        )
        
        logger.info(f"Successfully created concatenated audio: {concat_path}")
        logger.info(f"Total duration: {total_duration:.2f} seconds")
        logger.info(f"Generated {len(individual_paths)} individual files")
        
        # Optionally clean up individual files
        cleanup = input("Clean up individual files? (y/n): ").lower() == 'y'
        if cleanup:
            for path in individual_paths:
                try:
                    path.unlink()
                except OSError:
                    pass
            logger.info("Individual files cleaned up")
        
        return concat_path
        
    except Exception as e:
        logger.error(f"Audio concatenation failed: {e}")
        sys.exit(1)

def concatenate_video(
    texts: List[str],
    output_file: str = None,
    language: str = None,
    slow: bool = False
):
    """Generate and concatenate video files with audio."""
    try:
        tts_service = TTSService()
        video_service = VideoService()
        lang_service = LanguageDetectionService()
        
        # Auto-detect language if not specified
        if not language:
            lang_result = lang_service.detect_language(texts[0])
            language = lang_result.language
            logger.info(f"Detected language: {language}")
        
        logger.info(f"Generating {len(texts)} audio files...")
        
        # First generate all audio files
        audio_paths = []
        audio_analyses = []
        
        for i, text in enumerate(texts):
            logger.info(f"Processing text {i+1}/{len(texts)}...")
            
            # Generate audio
            audio_path, duration = tts_service.generate_audio(text, language, slow)
            audio_paths.append(audio_path)
            
            # Analyze audio
            audio_analysis = tts_service.analyze_audio_timing(text, audio_path)
            audio_analyses.append(audio_analysis)
        
        logger.info(f"Generating {len(texts)} video files...")
        
        # Generate and concatenate videos
        concat_path, individual_paths = video_service.generate_multiple_and_concatenate(
            texts=texts,
            audio_paths=audio_paths,
            audio_analyses=audio_analyses,
            output_filename=output_file
        )
        
        logger.info(f"Successfully created concatenated video: {concat_path}")
        logger.info(f"Generated {len(individual_paths)} individual video files")
        
        # Optionally clean up individual files
        cleanup = input("Clean up individual files? (y/n): ").lower() == 'y'
        if cleanup:
            # Clean up audio files
            for path in audio_paths:
                try:
                    path.unlink()
                except OSError:
                    pass
            
            # Clean up video files
            for path in individual_paths:
                try:
                    path.unlink()
                except OSError:
                    pass
            
            logger.info("Individual files cleaned up")
        
        return concat_path
        
    except Exception as e:
        logger.error(f"Video concatenation failed: {e}")
        sys.exit(1)

def main():
    parser = argparse.ArgumentParser(
        description="Concatenate multiple audio or video files generated from text"
    )
    
    parser.add_argument(
        "type",
        choices=["audio", "video"],
        help="Type of content to generate and concatenate"
    )
    
    parser.add_argument(
        "--texts",
        nargs="+",
        help="List of texts to convert (if not provided, uses sample texts)"
    )
    
    parser.add_argument(
        "--file",
        help="Text file containing texts (one per line)"
    )
    
    parser.add_argument(
        "--count",
        type=int,
        default=10,
        help="Number of sample texts to generate (default: 10)"
    )
    
    parser.add_argument(
        "--output",
        help="Output filename for concatenated file"
    )
    
    parser.add_argument(
        "--language",
        help="Language code (e.g., 'en', 'es', 'fr'). Auto-detected if not specified"
    )
    
    parser.add_argument(
        "--slow",
        action="store_true",
        help="Use slower speech speed"
    )
    
    args = parser.parse_args()
    
    # Determine texts to use
    texts = []
    
    if args.texts:
        texts = args.texts
    elif args.file:
        file_path = Path(args.file)
        if not file_path.exists():
            logger.error(f"File not found: {args.file}")
            sys.exit(1)
        
        with open(file_path, 'r', encoding='utf-8') as f:
            texts = [line.strip() for line in f if line.strip()]
    else:
        # Generate sample texts
        texts = generate_sample_texts(args.count)
        logger.info(f"Using {len(texts)} sample texts")
    
    if not texts:
        logger.error("No texts provided")
        sys.exit(1)
    
    logger.info(f"Processing {len(texts)} texts for {args.type} concatenation")
    
    # Process based on type
    if args.type == "audio":
        result = concatenate_audio(
            texts=texts,
            output_file=args.output,
            language=args.language,
            slow=args.slow
        )
    else:  # video
        result = concatenate_video(
            texts=texts,
            output_file=args.output,
            language=args.language,
            slow=args.slow
        )
    
    logger.info(f"Concatenation complete! Output: {result}")

if __name__ == "__main__":
    main()