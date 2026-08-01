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
const voiceSelect = document.getElementById('voiceSelect');
const engineDescription = document.getElementById('engineDescription');
const engineStatus = document.getElementById('engineStatus');

let detectionTimeout = null;
let availableEngines = {};
let lastGeneratedVideoFilename = null;
let lastUploadedFilename = null;

// Engine descriptions
const engineDescriptions = {
    'gtts': 'Simple and reliable, but monotonic voice. Requires internet.',
    'edge': '✨ Natural neural voices - Best quality for English! Requires internet.',
    'piper': '⚠️ Requires voice models to be downloaded. See piper docs for setup.'
};

// ========== Toast notifications (instead of alert()) ==========

const toastEl = document.getElementById('toast');
let toastTimer = null;

function showToast(message, isError = true) {
    toastEl.textContent = message;
    toastEl.className = isError ? 'toast error show' : 'toast show';
    clearTimeout(toastTimer);
    toastTimer = setTimeout(() => toastEl.classList.remove('show'), 4000);
}

// Read an error message from a fetch Response body ({error} or FastAPI {detail})
async function errorDetail(response, fallback) {
    try {
        const data = await response.json();
        return data.error || data.detail || fallback;
    } catch (e) {
        return fallback;
    }
}

// ========== Languages ==========

async function loadLanguages() {
    try {
        const response = await fetch('/supported-languages');
        const data = await response.json();
        if (!data.languages) return;

        const current = languageSelect.value;
        const entries = Object.entries(data.languages)
            .sort((a, b) => a[1].name.localeCompare(b[1].name));

        languageSelect.innerHTML = '';
        for (const [code, info] of entries) {
            const opt = document.createElement('option');
            opt.value = code;
            opt.textContent = info.name;
            languageSelect.appendChild(opt);
        }
        languageSelect.value = data.languages[current] ? current : 'en';
    } catch (error) {
        console.error('Failed to load languages:', error);
    }
}

// ========== TTS engines & voices ==========

async function checkEngineAvailability() {
    try {
        const response = await fetch('/tts-engines');
        const data = await response.json();

        if (data.engines) {
            availableEngines = {};
            data.engines.forEach(engine => {
                availableEngines[engine.id] = engine.available;
            });
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

const voiceCache = {};

async function loadVoices() {
    const engine = ttsEngineSelect.value;
    const language = languageSelect.value;
    const cacheKey = `${engine}:${language}`;

    const applyVoices = (voices) => {
        voiceSelect.innerHTML = '<option value="">Default voice</option>';
        voices.forEach(v => {
            const opt = document.createElement('option');
            opt.value = v.id;
            opt.textContent = v.name;
            voiceSelect.appendChild(opt);
        });
    };

    if (voiceCache[cacheKey]) {
        applyVoices(voiceCache[cacheKey]);
        return;
    }

    voiceSelect.innerHTML = '<option value="">Loading voices…</option>';
    try {
        const response = await fetch(`/tts-voices/${engine}?language=${encodeURIComponent(language)}`);
        const data = await response.json();
        const voices = data.voices || [];
        voiceCache[cacheKey] = voices;
        applyVoices(voices);
    } catch (error) {
        console.error('Failed to load voices:', error);
        voiceSelect.innerHTML = '<option value="">Default voice</option>';
    }
}

ttsEngineSelect.addEventListener('change', () => {
    updateEngineDescription();
    loadVoices();
});
languageSelect.addEventListener('change', loadVoices);

// ========== Language detection ==========

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
            const previous = languageSelect.value;
            languageSelect.value = data.language;
            if (languageSelect.value !== previous) {
                loadVoices();
            }

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
        showToast('Please enter some text first');
        return;
    }

    autoDetectBtn.disabled = true;
    autoDetectBtn.innerHTML = '🔄 Detecting...';

    detectLanguage(text).finally(() => {
        autoDetectBtn.disabled = false;
        autoDetectBtn.innerHTML = '🔍 Auto-Detect';
    });
});

// ========== Conversion ==========

async function handleConversion(endpoint, isVideo = false) {
    const button = isVideo ? videoBtn : audioBtn;
    button.classList.add('loading');
    button.disabled = true;
    audioBtn.disabled = true;
    videoBtn.disabled = true;

    // Show elapsed time while the server generates (video can take a while)
    const startTime = Date.now();
    resultDiv.className = 'result show';
    resultDiv.innerHTML = `<p>⏳ Generating ${isVideo ? 'video' : 'audio'}… <span id="elapsedSeconds">0</span>s elapsed</p>`;
    const elapsedTimer = setInterval(() => {
        const el = document.getElementById('elapsedSeconds');
        if (el) el.textContent = Math.round((Date.now() - startTime) / 1000);
    }, 1000);

    const formData = new FormData();
    const repetitions = parseInt(document.getElementById('repetitions').value) || 1;
    const fontSize = parseInt(document.getElementById('fontSize').value) || 48;

    formData.append('text', textArea.value);
    formData.append('language', languageSelect.value);
    formData.append('slow', document.getElementById('slow').checked ? 'true' : 'false');
    formData.append('repetitions', repetitions);
    formData.append('engine', ttsEngineSelect.value);
    if (voiceSelect.value) {
        formData.append('voice', voiceSelect.value);
    }

    if (isVideo) {
        formData.append('font_size', fontSize);
        formData.append('show_qr_code', document.getElementById('showQrCode').checked ? 'true' : 'false');
    }

    try {
        const response = await fetch(endpoint, {
            method: 'POST',
            body: formData
        });

        const data = await response.json();

        if (data.success) {
            resultDiv.className = 'result success show';
            const isRepeat = repetitions > 1;

            if (isVideo) {
                const videoUrl = data.video_url || data.download_url;
                const audioUrl = data.audio_url;

                // Track for the YouTube upload section
                if (data.video_filename) {
                    lastGeneratedVideoFilename = data.video_filename;
                    updateUploadButton();
                }

                resultDiv.innerHTML = `
                    <h3>🎬 Video Generated Successfully! ${isRepeat ? `(${repetitions} repetitions)` : ''}</h3>
                    ${data.message ? `<p style="color: #666; margin: 5px 0;">${data.message}</p>` : ''}
                    ${data.duration ? `<p style="color: #666; margin: 5px 0;">Total duration: ${data.duration.toFixed(2)} seconds</p>` : ''}
                    <video controls style="width: 100%; margin-top: 15px;">
                        <source src="${videoUrl}" type="video/mp4">
                        Your browser does not support the video element.
                    </video>
                    <div class="button-row" style="margin-top: 15px;">
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

            loadRecentFiles();
        } else {
            resultDiv.className = 'result error show';
            resultDiv.innerHTML = `
                <h3>❌ Error</h3>
                <p>${data.error || data.detail || 'An error occurred during conversion.'}</p>
            `;
        }
    } catch (error) {
        resultDiv.className = 'result error show';
        resultDiv.innerHTML = `
            <h3>❌ Error</h3>
            <p>Failed to connect to the server. Please try again.</p>
        `;
    } finally {
        clearInterval(elapsedTimer);
        button.classList.remove('loading');
        button.disabled = false;
        audioBtn.disabled = false;
        videoBtn.disabled = false;
    }
}

form.addEventListener('submit', async (e) => {
    e.preventDefault();
    // Repetitions on audio are handled by the /repeat-audio endpoint
    const repetitions = parseInt(document.getElementById('repetitions').value) || 1;
    handleConversion(repetitions > 1 ? '/repeat-audio' : '/convert', false);
});

videoBtn.addEventListener('click', async (e) => {
    e.preventDefault();
    if (!form.checkValidity()) {
        form.reportValidity();
        return;
    }
    handleConversion('/convert-to-video', true);
});

// ========== Preview ==========

const previewBtn = document.getElementById('previewBtn');
const previewContainer = document.getElementById('previewContainer');
const previewImage = document.getElementById('previewImage');
let previewObjectUrl = null;

previewBtn.addEventListener('click', async (e) => {
    e.preventDefault();

    if (!form.checkValidity()) {
        form.reportValidity();
        return;
    }

    previewBtn.classList.add('loading');
    previewBtn.disabled = true;
    previewContainer.style.display = 'none';

    const formData = new FormData();
    formData.append('text', textArea.value);
    formData.append('font_size', parseInt(document.getElementById('fontSize').value) || 48);
    formData.append('show_qr_code', document.getElementById('showQrCode').checked ? 'true' : 'false');
    formData.append('highlight_position', 0);  // Highlight first character

    try {
        const response = await fetch('/preview', {
            method: 'POST',
            body: formData
        });

        if (response.ok) {
            // The server returns the PNG directly — no file round-trip
            const blob = await response.blob();
            if (previewObjectUrl) {
                URL.revokeObjectURL(previewObjectUrl);
            }
            previewObjectUrl = URL.createObjectURL(blob);
            previewImage.innerHTML = `<img src="${previewObjectUrl}" alt="Video Preview">`;
            previewContainer.style.display = 'block';
            previewContainer.scrollIntoView({ behavior: 'smooth', block: 'nearest' });
        } else {
            showToast(`Preview failed: ${await errorDetail(response, 'Unknown error')}`);
        }
    } catch (error) {
        console.error('Preview error:', error);
        showToast('Failed to generate preview. Please try again.');
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

// ========== Recent files ==========

const fileList = document.getElementById('fileList');
const refreshFilesBtn = document.getElementById('refreshFilesBtn');

function formatSize(bytes) {
    if (bytes >= 1024 * 1024) return (bytes / (1024 * 1024)).toFixed(1) + ' MB';
    if (bytes >= 1024) return Math.round(bytes / 1024) + ' KB';
    return bytes + ' B';
}

// Turn "repeat_3x_Practice-makes-perfect_a1b2c3d4.mp4" into "3× Practice makes perfect"
function displayName(filename) {
    let name = filename.replace(/\.[a-z0-9]+$/i, '');   // drop extension
    let repeat = '';

    const repMatch = name.match(/^repeat_(\d+)x_/);
    if (repMatch) {
        repeat = `${repMatch[1]}× `;
        name = name.slice(repMatch[0].length);
    }

    name = name.replace(/^(edge|gtts|piper|concat)_/, '');
    name = name.replace(/_[0-9a-f]{8}$/, '');           // drop unique suffix

    // Old-style pure-UUID filenames: nothing readable to extract
    if (/^[0-9a-f]{8}-[0-9a-f-]{27}$/i.test(name) || !name) {
        return repeat + filename;
    }

    return repeat + name.replace(/-/g, ' ');
}

async function loadRecentFiles() {
    try {
        const response = await fetch('/files');
        const data = await response.json();
        if (!data.success) return;

        if (!data.files.length) {
            fileList.innerHTML = '<p class="hint">No files yet — generate some audio or video above.</p>';
            return;
        }

        fileList.innerHTML = '';
        data.files.forEach(file => {
            const item = document.createElement('div');
            item.className = 'file-item';

            const icon = document.createElement('span');
            icon.textContent = file.kind === 'video' ? '🎬' : '🎵';

            const name = document.createElement('span');
            name.className = 'file-name';
            name.textContent = displayName(file.filename);
            name.title = file.filename;

            const meta = document.createElement('span');
            meta.className = 'file-meta';
            meta.textContent = `${formatSize(file.size)} · ${new Date(file.modified * 1000).toLocaleString()}`;

            const download = document.createElement('a');
            download.href = file.url;
            download.download = file.filename;
            download.textContent = '📥 Download';

            item.append(icon, name, meta, download);

            if (file.kind === 'video') {
                const useBtn = document.createElement('button');
                useBtn.type = 'button';
                useBtn.textContent = '📤 Use for upload';
                useBtn.addEventListener('click', () => {
                    lastGeneratedVideoFilename = file.filename;
                    updateUploadButton();
                    showToast(`Selected for upload: ${file.filename}`, false);
                });
                item.append(useBtn);
            }

            fileList.appendChild(item);
        });
    } catch (error) {
        console.error('Failed to load recent files:', error);
    }
}

refreshFilesBtn.addEventListener('click', loadRecentFiles);

// ========== YouTube Metadata ==========

// Copy to clipboard
document.querySelectorAll('.copy-btn').forEach(btn => {
    btn.addEventListener('click', () => {
        const el = document.getElementById(btn.dataset.copyTarget);
        const text = el.textContent || el.innerText;
        navigator.clipboard.writeText(text).then(() => {
            const original = btn.textContent;
            btn.textContent = '✅ Copied!';
            setTimeout(() => btn.textContent = original, 1500);
        });
    });
});

const generateMetadataBtn = document.getElementById('generateMetadataBtn');
const metadataResult = document.getElementById('metadataResult');
const metadataTitle = document.getElementById('metadataTitle');
const metadataDescription = document.getElementById('metadataDescription');
const llmProvider = document.getElementById('llmProvider');

generateMetadataBtn.addEventListener('click', async () => {
    const text = textArea.value.trim();
    if (!text) {
        showToast('Please enter some text first');
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

            updateUploadButton();
        } else {
            showToast(`Generation failed: ${data.error || data.detail || 'Unknown error'}`);
        }
    } catch (error) {
        console.error('Metadata generation error:', error);
        showToast('Failed to generate metadata. Please check your API key and try again.');
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
            const availability = {};
            const statuses = data.providers.map(p => {
                availability[p.id] = p.available;
                return `${p.available ? '✅' : '❌'} ${p.name}`;
            }).join(' | ');
            providerStatus.innerHTML = statuses;

            // Disable unavailable providers instead of failing on request
            let firstAvailable = null;
            for (const opt of llmProvider.options) {
                opt.disabled = availability[opt.value] === false;
                if (!opt.disabled && firstAvailable === null) {
                    firstAvailable = opt.value;
                }
            }
            if (llmProvider.selectedOptions[0]?.disabled && firstAvailable) {
                llmProvider.value = firstAvailable;
            }
        }
    } catch (e) {
        console.error('Failed to check providers:', e);
    }
}

// ========== YouTube OAuth & Upload ==========

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
            showToast(`YouTube setup error: ${data.error || 'unknown'}`);
        }
    } catch (error) {
        showToast('Failed to start YouTube authentication');
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
    const alreadyUploaded = hasVideo && lastGeneratedVideoFilename === lastUploadedFilename;

    uploadBtn.disabled = !(hasVideo && hasMetadata) || alreadyUploaded;
    if (!hasVideo) {
        uploadBtn.title = 'Generate a video first (or pick one from Recent Files)';
    } else if (!hasMetadata) {
        uploadBtn.title = 'Generate YouTube metadata first';
    } else if (alreadyUploaded) {
        uploadBtn.title = 'This video was already uploaded — select or generate another one';
    } else {
        uploadBtn.title = '';
    }
}

const uploadYoutubeBtn = document.getElementById('uploadYoutubeBtn');
const uploadResult = document.getElementById('uploadResult');

uploadYoutubeBtn.addEventListener('click', async () => {
    if (!lastGeneratedVideoFilename) {
        showToast('Please generate a video first');
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

        // Extract tags from description hashtags (unicode-aware for CJK tags)
        const hashtags = metadataDescription.textContent.match(/#[\p{L}\p{N}_]+/gu);
        if (hashtags) {
            formData.append('tags', hashtags.map(h => h.slice(1)).join(','));
        }

        const response = await fetch('/youtube/upload', {
            method: 'POST',
            body: formData
        });

        const data = await response.json();

        if (data.success) {
            lastUploadedFilename = lastGeneratedVideoFilename;
            uploadResult.innerHTML = `
                ✅ <strong>Uploaded successfully!</strong><br>
                <a href="${data.video_url}" target="_blank" style="color: #667eea;">
                    🔗 ${data.video_url}
                </a>
            `;
        } else {
            uploadResult.innerHTML = `❌ Upload failed: ${data.error || data.detail || 'Unknown error'}`;
        }
    } catch (error) {
        uploadResult.innerHTML = '❌ Upload failed. Please try again.';
    } finally {
        uploadYoutubeBtn.classList.remove('loading');
        updateUploadButton();
    }
});

// ========== Init ==========

document.addEventListener('DOMContentLoaded', () => {
    loadLanguages().then(loadVoices);
    checkEngineAvailability();
    checkProviderAvailability();
    checkYouTubeAuth();
    loadRecentFiles();
});
