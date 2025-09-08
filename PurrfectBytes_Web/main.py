"""Main FastAPI application entry point."""

import time
import uvicorn
from fastapi import FastAPI, Request
from fastapi.responses import HTMLResponse
from fastapi.templating import Jinja2Templates
from fastapi.middleware.cors import CORSMiddleware
from contextlib import asynccontextmanager

from src.api.routes import router
from src.config.settings import (
    SERVER_HOST, 
    SERVER_PORT, 
    DEBUG, 
    TEMPLATES_DIR,
    get_config
)
from src.utils.logger import setup_logger, get_logger
from src.services.tts_service import TTSService
from src.services.video_service import VideoService

# Setup logging
logger = setup_logger("purrfectbytes", log_file=None)

# Initialize services for cleanup on startup
tts_service = TTSService()
video_service = VideoService()

@asynccontextmanager
async def lifespan(app: FastAPI):
    """Application lifespan manager."""
    logger.info("Starting PurrfectBytes application")
    
    # Startup tasks
    config = get_config()
    logger.info(f"Configuration loaded - Audio dir: {config['audio_dir']}, Video dir: {config['video_dir']}")
    
    # Optional: Cleanup old files on startup
    if config['cleanup']['cleanup_on_startup']:
        try:
            audio_cleaned = tts_service.cleanup_old_files(config['cleanup']['auto_cleanup_hours'])
            video_cleaned = video_service.cleanup_old_files(config['cleanup']['auto_cleanup_hours'])
            logger.info(f"Startup cleanup: {audio_cleaned + video_cleaned} old files removed")
        except Exception as e:
            logger.warning(f"Startup cleanup failed: {e}")
    
    yield
    
    # Shutdown tasks
    logger.info("Shutting down PurrfectBytes application")

def create_app() -> FastAPI:
    """Create and configure FastAPI application."""
    app = FastAPI(
        title="PurrfectBytes TTS & Video Generator",
        description="Convert text to speech and generate videos with character-level highlighting",
        version="1.0.0",
        lifespan=lifespan,
        debug=DEBUG
    )
    
    # Add CORS middleware
    app.add_middleware(
        CORSMiddleware,
        allow_origins=["*"],  # Configure appropriately for production
        allow_credentials=True,
        allow_methods=["*"],
        allow_headers=["*"],
    )
    
    # Include API routes
    app.include_router(router, prefix="/api/v1", tags=["api"])
    
    # Also include routes at root level for backward compatibility
    app.include_router(router)
    
    # Setup templates
    templates = Jinja2Templates(directory=str(TEMPLATES_DIR))
    
    @app.get("/", response_class=HTMLResponse)
    async def home(request: Request):
        """Serve the main application page."""
        return templates.TemplateResponse("index.html", {"request": request})
    
    @app.get("/docs-redirect")
    async def docs_redirect():
        """Redirect to API documentation."""
        return {"message": "API documentation available at /docs"}
    
    # Add middleware for request logging
    @app.middleware("http")
    async def log_requests(request: Request, call_next):
        """Log all HTTP requests."""
        start_time = time.time()
        
        # Log request
        logger.info(f"Request: {request.method} {request.url.path}")
        
        # Process request
        response = await call_next(request)
        
        # Log response
        process_time = time.time() - start_time
        logger.info(
            f"Response: {response.status_code} for {request.method} "
            f"{request.url.path} in {process_time:.2f}s"
        )
        
        return response
    
    return app

# Create the application instance
app = create_app()

if __name__ == "__main__":
    logger.info(f"Starting server on {SERVER_HOST}:{SERVER_PORT}")
    logger.info(f"Debug mode: {DEBUG}")
    
    uvicorn.run(
        "main:app" if DEBUG else app,
        host=SERVER_HOST,
        port=SERVER_PORT,
        reload=DEBUG,
        log_level="info" if DEBUG else "warning"
    )
