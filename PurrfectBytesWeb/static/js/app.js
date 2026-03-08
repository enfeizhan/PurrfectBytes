/* PurrfectBytes - Main Application JavaScript */

const form = document.getElementById('ttsForm');
const resultDiv = document.getElementById('result');
const audioBtn = document.getElementById('audioBtn');
const videoBtn = document.getElementById('videoBtn');
const textArea = document.getElementById('text');
const languageSelect = document.getElementById('language');
const autoDetectBtn = document.getElementById('autoDetectBtn');
const detectionResult = document.getElementById('detectionResult');
const ttsEngineSelect = document.getElementById('ttsEngine');
const engineDescription = document.getElementById('engineDescription');
const engineStatus = document.getElementById('engineStatus');

let detectionTimeout = null;
let availableEngines = {};

// Engine descriptions
const engineDescriptions = {
    'gtts': 'Simple and reliable, but monotonic voice. Requires internet.',
    'edge': '✨ Natural neural voices - Best quality for English! Requires internet.',
    'piper': '⚠️ Requires voice models to be downloaded. See piper docs for setup.'
};

// Check available TTS engines on page load
async function checkEngineAvailability() {
    try {
        const response = await fetch('/tts-engines');
        const data = await response.json();

        if (data.engines) {
            availableEngines = {};
            data.engines.forEach(engine => {
                availableEngines[engine.id] = engine.available;
            });

            // Update UI to show availability
            updateEngineUI();
        }
    } catch (error) {
        console.error('Failed to check engine availability:', error);
    }
}

function updateEngineUI() {
    const options = ttsEngineSelect.options;
    for (let i = 0; i < options.length; i++) {
        const engineId = options[i].value;
        if (availableEngines[engineId] === false) {
            options[i].text = options[i].text.replace(/^[✓✗]?\s*/, '✗ ') + ' (Not installed)';
            options[i].style.color = '#999';
        } else if (availableEngines[engineId] === true) {
            if (!options[i].text.startsWith('✓')) {
                options[i].text = options[i].text.replace(/^[✗]?\s*/, '');
            }
            options[i].style.color = '';
        }
    }
    updateEngineDescription();
}

function updateEngineDescription() {
    const selectedEngine = ttsEngineSelect.value;
    engineDescription.textContent = engineDescriptions[selectedEngine] || '';

    if (availableEngines[selectedEngine] === false) {
        engineStatus.innerHTML = '<span style="color: #e74c3c;">⚠️ This engine is not installed. Will fall back to gTTS.</span>';
    } else if (availableEngines[selectedEngine] === true) {
        engineStatus.innerHTML = '<span style="color: #27ae60;">✓ Engine available</span>';
    } else {
        engineStatus.innerHTML = '';
    }
}

// Listen for engine selection changes
ttsEngineSelect.addEventListener('change', updateEngineDescription);

// Check engines on page load
document.addEventListener('DOMContentLoaded', checkEngineAvailability);

async function detectLanguage(text) {
    if (!text || text.trim().length < 3) {
        detectionResult.style.display = 'none';
        return;
    }

    try {
        const formData = new FormData();
        formData.append('text', text);

        const response = await fetch('/detect-language', {
            method: 'POST',
            body: formData
        });

        const data = await response.json();

        if (data.language) {
            // Update language dropdown
            languageSelect.value = data.language;

            // Show detection result
            detectionResult.innerHTML = `
                ✓ Detected: <strong>${data.language_name}</strong>
                ${data.confidence ? `(${Math.round(data.confidence * 100)}% confidence)` : ''}
                ${data.note ? `<br><small style="color: #f39c12;">${data.note}</small>` : ''}
            `;
            detectionResult.style.display = 'block';
            detectionResult.style.color = data.error ? '#e74c3c' : '#27ae60';
        }
    } catch (error) {
        console.error('Language detection failed:', error);
        detectionResult.innerHTML = '❌ Detection failed';
        detectionResult.style.display = 'block';
        detectionResult.style.color = '#e74c3c';
    }
}

// Auto-detect on typing (debounced)
textArea.addEventListener('input', function () {
    clearTimeout(detectionTimeout);
    const text = this.value;

    if (text.trim().length >= 10) {  // Only detect after 10+ characters
        detectionTimeout = setTimeout(() => {
            detectLanguage(text);
        }, 1500); // Wait 1.5 seconds after user stops typing
    }
});

// Manual detection button
autoDetectBtn.addEventListener('click', function () {
    const text = textArea.value;
    if (!text.trim()) {
        alert('Please enter some text first');
        return;
    }

    autoDetectBtn.disabled = true;
    autoDetectBtn.innerHTML = '🔄 Detecting...';

    detectLanguage(text).finally(() => {
        autoDetectBtn.disabled = false;
        autoDetectBtn.innerHTML = '🔍 Auto-Detect';
    });
});

async function handleConversion(endpoint, isVideo = false) {
    const button = isVideo ? videoBtn : audioBtn;
    button.classList.add('loading');
    button.disabled = true;
    audioBtn.disabled = true;
    videoBtn.disabled = true;

    const formData = new FormData();
    const repetitions = parseInt(document.getElementById('repetitions').value) || 1;
    const fontSize = parseInt(document.getElementById('fontSize').value) || 48;
    const engine = ttsEngineSelect.value;

    // Manually add all form values to ensure proper handling
    formData.append('text', document.getElementById('text').value);
    formData.append('language', document.getElementById('language').value);
    formData.append('slow', document.getElementById('slow').checked ? 'true' : 'false');
    formData.append('repetitions', repetitions);
    formData.append('engine', engine);
    console.log('Using TTS engine:', engine);

    // For video conversion, ensure font_size and show_qr_code are included
    if (isVideo) {
        formData.delete('font_size');
        formData.append('font_size', fontSize);
        console.log('Sending font_size:', fontSize);

        const showQrCode = document.getElementById('showQrCode').checked;
        formData.delete('show_qr_code');
        formData.append('show_qr_code', showQrCode ? 'true' : 'false');
        console.log('Sending show_qr_code:', showQrCode);
    }

    try {
        const response = await fetch(endpoint, {
            method: 'POST',
            body: formData
        });

        const data = await response.json();

        if (data.success) {
            resultDiv.className = 'result success show';
            if (isVideo) {
                const videoUrl = data.video_url || data.download_url;
                const audioUrl = data.audio_url;
                const isRepeat = repetitions > 1;

                resultDiv.innerHTML = `
                    <h3>🎬 Video Generated Successfully! ${isRepeat ? `(${repetitions} repetitions)` : ''}</h3>
                    ${data.message ? `<p style="color: #666; margin: 5px 0;">${data.message}</p>` : ''}
                    ${data.duration ? `<p style="color: #666; margin: 5px 0;">Total duration: ${data.duration.toFixed(2)} seconds</p>` : ''}
                    <video controls style="width: 100%; margin-top: 15px;">
                        <source src="${videoUrl}" type="video/mp4">
                        Your browser does not support the video element.
                    </video>
                    <div style="display: flex; gap: 10px; margin-top: 15px;">
                        <a href="${videoUrl}" download class="download-btn" style="flex: 1;">
                            📥 Download Video
                        </a>
                        ${audioUrl ? `<a href="${audioUrl}" download class="download-btn" style="flex: 1; background: #6c757d;">
                            🎵 Download Audio Only
                        </a>` : ''}
                    </div>
                `;
            } else {
                const audioUrl = data.audio_url || data.download_url;
                const isRepeat = repetitions > 1;

                resultDiv.innerHTML = `
                    <h3>✅ Audio Generated Successfully! ${isRepeat ? `(${repetitions} repetitions)` : ''}</h3>
                    ${data.message ? `<p style="color: #666; margin: 5px 0;">${data.message}</p>` : ''}
                    ${data.duration ? `<p style="color: #666; margin: 5px 0;">Total duration: ${data.duration.toFixed(2)} seconds</p>` : ''}
                    <audio controls>
                        <source src="${audioUrl}" type="audio/mpeg">
                        Your browser does not support the audio element.
                    </audio>
                    <a href="${audioUrl}" download class="download-btn">
                        📥 Download Audio
                    </a>
                `;
            }
        } else {
            resultDiv.className = 'result error show';
            resultDiv.innerHTML = `
                <h3>❌ Error</h3>
                <p>${data.error || 'An error occurred during conversion.'}</p>
            `;
        }
    } catch (error) {
        resultDiv.className = 'result error show';
        resultDiv.innerHTML = `
            <h3>❌ Error</h3>
            <p>Failed to connect to the server. Please try again.</p>
        `;
    } finally {
        button.classList.remove('loading');
        button.disabled = false;
        audioBtn.disabled = false;
        videoBtn.disabled = false;
    }
}

form.addEventListener('submit', async (e) => {
    e.preventDefault();
    handleConversion('/convert', false);
});

videoBtn.addEventListener('click', async (e) => {
    e.preventDefault();
    if (!form.checkValidity()) {
        form.reportValidity();
        return;
    }
    handleConversion('/convert-to-video', true);
});

// Preview button handler
const previewBtn = document.getElementById('previewBtn');
const previewContainer = document.getElementById('previewContainer');
const previewImage = document.getElementById('previewImage');

previewBtn.addEventListener('click', async (e) => {
    e.preventDefault();

    if (!form.checkValidity()) {
        form.reportValidity();
        return;
    }

    // Show loading state
    previewBtn.classList.add('loading');
    previewBtn.disabled = true;
    previewContainer.style.display = 'none';

    const formData = new FormData(form);
    const fontSize = parseInt(document.getElementById('fontSize').value) || 48;
    const showQrCode = document.getElementById('showQrCode').checked;

    formData.delete('font_size');
    formData.append('font_size', fontSize);
    formData.delete('show_qr_code');
    formData.append('show_qr_code', showQrCode ? 'true' : 'false');
    formData.append('highlight_position', 0);  // Highlight first character

    try {
        const response = await fetch('/preview', {
            method: 'POST',
            body: formData
        });

        const data = await response.json();

        if (data.success && data.preview_url) {
            // Show preview image
            previewImage.innerHTML = `
                <img src="${data.preview_url}" alt="Video Preview" style="max-width: 100%; border-radius: 8px; box-shadow: 0 2px 8px rgba(0,0,0,0.1);">
            `;
            previewContainer.style.display = 'block';

            // Scroll to preview
            previewContainer.scrollIntoView({ behavior: 'smooth', block: 'nearest' });
        } else {
            alert(`Preview failed: ${data.error || 'Unknown error'}`);
        }
    } catch (error) {
        console.error('Preview error:', error);
        alert('Failed to generate preview. Please try again.');
    } finally {
        previewBtn.classList.remove('loading');
        previewBtn.disabled = false;
    }
});

// Update preview when font size changes
const fontSizeSelect = document.getElementById('fontSize');
fontSizeSelect.addEventListener('change', function () {
    // If preview is visible, regenerate it
    if (previewContainer.style.display !== 'none') {
        previewBtn.click();
    }
});

// ========== YouTube Metadata & Upload ==========

let lastGeneratedVideoFilename = null;

// Track generated video filename from video conversion responses
const originalHandleConversion = handleConversion;

// Store video filename when a video is generated
const _origFetch = window.fetch;
// We'll capture the video filename from the result display instead

// Copy to clipboard
function copyToClipboard(elementId) {
    const el = document.getElementById(elementId);
    const text = el.textContent || el.innerText;
    navigator.clipboard.writeText(text).then(() => {
        const btn = el.closest('.metadata-field').querySelector('.copy-btn');
        const original = btn.textContent;
        btn.textContent = '✅ Copied!';
        setTimeout(() => btn.textContent = original, 1500);
    });
}

// Generate YouTube metadata
const generateMetadataBtn = document.getElementById('generateMetadataBtn');
const metadataResult = document.getElementById('metadataResult');
const metadataTitle = document.getElementById('metadataTitle');
const metadataDescription = document.getElementById('metadataDescription');
const llmProvider = document.getElementById('llmProvider');

generateMetadataBtn.addEventListener('click', async () => {
    const text = textArea.value.trim();
    if (!text) {
        alert('Please enter some text first');
        return;
    }

    generateMetadataBtn.classList.add('loading');
    generateMetadataBtn.disabled = true;

    try {
        const formData = new FormData();
        formData.append('text', text);
        formData.append('provider', llmProvider.value);

        const response = await fetch('/generate-youtube-metadata', {
            method: 'POST',
            body: formData
        });

        const data = await response.json();

        if (data.success) {
            metadataTitle.textContent = data.title;
            metadataDescription.textContent = data.description;
            metadataResult.style.display = 'block';
            metadataResult.scrollIntoView({ behavior: 'smooth', block: 'nearest' });

            // Enable upload button if authenticated
            updateUploadButton();
        } else {
            alert(`Generation failed: ${data.error}`);
        }
    } catch (error) {
        console.error('Metadata generation error:', error);
        alert('Failed to generate metadata. Please check your API key and try again.');
    } finally {
        generateMetadataBtn.classList.remove('loading');
        generateMetadataBtn.disabled = false;
    }
});

// Check LLM provider availability
async function checkProviderAvailability() {
    try {
        const response = await fetch('/youtube/providers');
        const data = await response.json();
        if (data.success) {
            const providerStatus = document.getElementById('providerStatus');
            const statuses = data.providers.map(p =>
                `${p.available ? '✅' : '❌'} ${p.name}`
            ).join(' | ');
            providerStatus.innerHTML = statuses;
        }
    } catch (e) {
        console.error('Failed to check providers:', e);
    }
}

// YouTube OAuth
const connectYoutubeBtn = document.getElementById('connectYoutubeBtn');
const youtubeAuthStatus = document.getElementById('youtubeAuthStatus');
const uploadControls = document.getElementById('uploadControls');

connectYoutubeBtn.addEventListener('click', async () => {
    connectYoutubeBtn.classList.add('loading');
    connectYoutubeBtn.disabled = true;

    try {
        const response = await fetch('/youtube/auth-url');
        const data = await response.json();

        if (data.success) {
            // Open auth URL in a new window
            window.open(data.auth_url, 'youtube-auth', 'width=600,height=700');
        } else {
            alert(`YouTube setup error: ${data.error}`);
        }
    } catch (error) {
        alert('Failed to start YouTube authentication');
    } finally {
        connectYoutubeBtn.classList.remove('loading');
        connectYoutubeBtn.disabled = false;
    }
});

// Listen for OAuth success from popup
window.addEventListener('message', (event) => {
    if (event.data === 'youtube-auth-success') {
        checkYouTubeAuth();
    }
});

async function checkYouTubeAuth() {
    try {
        const response = await fetch('/youtube/auth-status');
        const data = await response.json();

        if (data.authenticated) {
            youtubeAuthStatus.innerHTML = '<span style="color: #27ae60;">✅ Connected</span>';
            connectYoutubeBtn.querySelector('.button-text').textContent = '✅ YouTube Connected';
            uploadControls.style.display = 'block';
            loadPlaylists();
            updateUploadButton();
        } else if (data.configured) {
            youtubeAuthStatus.innerHTML = '<span style="color: #f39c12;">⚠️ Not signed in</span>';
        } else {
            youtubeAuthStatus.innerHTML = '<span style="color: #e74c3c;">❌ client_secrets.json missing</span>';
        }
    } catch (e) {
        console.error('Auth status check failed:', e);
    }
}

async function loadPlaylists() {
    const playlistSelect = document.getElementById('playlistSelect');

    try {
        const response = await fetch('/youtube/playlists');
        const data = await response.json();

        if (data.success) {
            // Keep the "No Playlist" option, add fetched playlists
            playlistSelect.innerHTML = '<option value="">— No Playlist —</option>';
            data.playlists.forEach(pl => {
                const opt = document.createElement('option');
                opt.value = pl.id;
                opt.textContent = pl.title;
                playlistSelect.appendChild(opt);
            });
        }
    } catch (e) {
        console.error('Failed to load playlists:', e);
    }
}

function updateUploadButton() {
    const uploadBtn = document.getElementById('uploadYoutubeBtn');
    const hasVideo = lastGeneratedVideoFilename !== null;
    const hasMetadata = metadataResult.style.display !== 'none';

    uploadBtn.disabled = !(hasVideo && hasMetadata);
    if (!hasVideo) {
        uploadBtn.title = 'Generate a video first';
    } else if (!hasMetadata) {
        uploadBtn.title = 'Generate YouTube metadata first';
    } else {
        uploadBtn.title = '';
    }
}

// Upload to YouTube
const uploadYoutubeBtn = document.getElementById('uploadYoutubeBtn');
const uploadResult = document.getElementById('uploadResult');

uploadYoutubeBtn.addEventListener('click', async () => {
    if (!lastGeneratedVideoFilename) {
        alert('Please generate a video first');
        return;
    }

    uploadYoutubeBtn.classList.add('loading');
    uploadYoutubeBtn.disabled = true;
    uploadResult.innerHTML = '⏳ Uploading to YouTube... This may take a minute.';

    try {
        const formData = new FormData();
        formData.append('video_filename', lastGeneratedVideoFilename);
        formData.append('title', metadataTitle.textContent);
        formData.append('description', metadataDescription.textContent);
        formData.append('playlist_id', document.getElementById('playlistSelect').value);
        formData.append('privacy_status', document.getElementById('privacyStatus').value);

        // Extract tags from description hashtags
        const hashtags = metadataDescription.textContent.match(/#\w+/g);
        if (hashtags) {
            formData.append('tags', hashtags.map(h => h.slice(1)).join(','));
        }

        const response = await fetch('/youtube/upload', {
            method: 'POST',
            body: formData
        });

        const data = await response.json();

        if (data.success) {
            uploadResult.innerHTML = `
                ✅ <strong>Uploaded successfully!</strong><br>
                <a href="${data.video_url}" target="_blank" style="color: #667eea;">
                    🔗 ${data.video_url}
                </a>
            `;
        } else {
            uploadResult.innerHTML = `❌ Upload failed: ${data.error}`;
        }
    } catch (error) {
        uploadResult.innerHTML = '❌ Upload failed. Please try again.';
    } finally {
        uploadYoutubeBtn.classList.remove('loading');
        uploadYoutubeBtn.disabled = false;
    }
});

// Intercept video conversion to capture filename
const _originalHandleConversion = window.handleConversion;

// Override the global handleConversion to also track video filenames
(function () {
    const origMethod = handleConversion;

    // Patch: after video success, extract the video filename from the URL
    const observer = new MutationObserver((mutations) => {
        for (const mutation of mutations) {
            if (mutation.type === 'childList' && resultDiv.classList.contains('success')) {
                const videoSource = resultDiv.querySelector('video source');
                if (videoSource) {
                    const src = videoSource.getAttribute('src');
                    // Extract filename from URL like /download-video/filename.mp4
                    const match = src.match(/\/download-video\/(.+)$/);
                    if (match) {
                        lastGeneratedVideoFilename = match[1];
                        updateUploadButton();
                    }
                }
            }
        }
    });
    observer.observe(resultDiv, { childList: true, subtree: true });
})();

// Initialize YouTube features on page load
document.addEventListener('DOMContentLoaded', () => {
    checkProviderAvailability();
    checkYouTubeAuth();
});
