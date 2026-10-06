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
const voiceSelectB = document.getElementById('voiceSelectB');
const conversationToggle = document.getElementById('conversationToggle');
const conversationOptions = document.getElementById('conversationOptions');
const voicedToggle = document.getElementById('voicedToggle');
const voicedOptions = document.getElementById('voicedOptions');
const voicedTextArea = document.getElementById('voicedText');
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

// The voices the user actually picked, remembered apart from what the dropdown
// can show right now. Changing engine or language rebuilds the option list, and
// a voice the new list doesn't offer must not erase the choice — reading it
// back off the <select> would, because the browser silently drops a value that
// isn't among the options. Kept here, the pick reappears on the way back.
const preferredVoices = ['', ''];

// Only a newer request may repaint the list: a slow reply for the engine or
// language the user has already moved on from must not overwrite the current one.
let voiceRequestSeq = 0;

// A voice list that arrived while its dropdown was open, held until it closes.
const pendingVoiceRepaint = [null, null];

// Never rebuild a list the user is reading: the options would change under
// them mid-choice. (Rebuilding does not itself close the list - that was an
// earlier mis-diagnosis - but swapping the choices out from under a reader is
// its own bug.)
const listIsOpen = (select) => document.activeElement === select;

// Replaces one dropdown's options and re-applies the remembered pick.
function repaintVoiceSelect(select, index, voices) {
    select.innerHTML = '<option value="">Default voice</option>';
    voices.forEach(v => {
        const opt = document.createElement('option');
        opt.value = v.id;
        opt.textContent = v.name;
        select.appendChild(opt);
    });
    select.value = preferredVoices[index];
    if (select.value !== preferredVoices[index]) {
        // Not offered here — show Default, but keep the preference so
        // switching back to the other engine/language restores it.
        select.value = '';
    }
}

async function loadVoices() {
    const engine = ttsEngineSelect.value;
    const language = languageSelect.value;
    const cacheKey = `${engine}:${language}`;
    const requestSeq = ++voiceRequestSeq;

    const applyVoices = (voices) => {
        [voiceSelect, voiceSelectB].forEach((select, index) => {
            if (listIsOpen(select)) {
                // Its option list is open under the pointer right now, and
                // replacing the options would snap it shut — which is what
                // detection firing 1.5s after you stop typing used to do.
                pendingVoiceRepaint[index] = voices;
                return;
            }
            repaintVoiceSelect(select, index, voices);
        });
    };

    if (voiceCache[cacheKey]) {
        applyVoices(voiceCache[cacheKey]);
        return;
    }

    if (!listIsOpen(voiceSelect)) {
        // Skipped while the list is open: the placeholder would close it, and
        // keeping the previous voices on screen for a moment is no worse.
        voiceSelect.innerHTML = '<option value="">Loading voices…</option>';
    }
    try {
        const response = await fetch(`/tts-voices/${engine}?language=${encodeURIComponent(language)}`);
        const data = await response.json();
        const voices = data.voices || [];
        voiceCache[cacheKey] = voices;
        if (requestSeq !== voiceRequestSeq) return;
        applyVoices(voices);
    } catch (error) {
        console.error('Failed to load voices:', error);
        if (requestSeq !== voiceRequestSeq) return;
        applyVoices([]);  // Default only, still honouring the remembered pick
    }
}

// A pick by hand is the only thing that changes the preference; the fallbacks
// above set .value programmatically, which fires no change event.
[voiceSelect, voiceSelectB].forEach((select, index) => {
    select.addEventListener('change', () => {
        preferredVoices[index] = select.value;
    });
    // Change fires before blur, so a pick made just now is already remembered
    // and survives the repaint that was waiting for the list to close.
    select.addEventListener('blur', () => {
        const held = pendingVoiceRepaint[index];
        if (held) {
            pendingVoiceRepaint[index] = null;
            repaintVoiceSelect(select, index, held);
        }
    });
});

ttsEngineSelect.addEventListener('change', () => {
    updateEngineDescription();
    loadVoices();
    updateConversationAvailability();
});
// Detection while typing may report, but must never silently replace a
// language the user picked by hand; the Auto-Detect button always applies.
let languageChosenByUser = false;
languageSelect.addEventListener('change', () => {
    languageChosenByUser = true;
    loadVoices();
});

// ========== Language detection ==========

async function detectLanguage(text, applySelection = true) {
    if (!text || text.trim().length < 3) {
        detectionResult.innerHTML = '';
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
            if (applySelection) {
                languageSelect.value = data.language;
            }
            if (languageSelect.value !== previous) {
                loadVoices();
            }

            const kept = !applySelection && data.language !== previous;
            detectionResult.innerHTML = `
                ✓ Detected: <strong>${data.language_name}</strong>
                ${data.confidence ? `(${Math.round(data.confidence * 100)}% confidence)` : ''}
                ${kept ? '<br><small>Keeping the language you picked — press 🔍 Auto-Detect to switch</small>' : ''}
                ${data.note ? `<br><small style="color: #f39c12;">${data.note}</small>` : ''}
            `;
            detectionResult.style.color = data.error ? '#e74c3c' : '#27ae60';
        }
    } catch (error) {
        console.error('Language detection failed:', error);
        detectionResult.innerHTML = '❌ Detection failed';
        detectionResult.style.color = '#e74c3c';
    }
}

// Auto-detect on typing (debounced)
textArea.addEventListener('input', function () {
    clearTimeout(detectionTimeout);
    const text = this.value;

    invalidateExtractedItems();

    if (text.trim().length >= 10) {  // Only detect after 10+ characters
        detectionTimeout = setTimeout(() => {
            detectLanguage(text, !languageChosenByUser);
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
    languageChosenByUser = false;

    detectLanguage(text).finally(() => {
        autoDetectBtn.disabled = false;
        autoDetectBtn.innerHTML = '🔍 Auto-Detect';
    });
});

// ========== Speed sequence builder ==========

const sequenceToggle = document.getElementById('sequenceToggle');
const sequenceBuilder = document.getElementById('sequenceBuilder');
const sequenceRows = document.getElementById('sequenceRows');

function addSequenceRow(count = 1, speed = 'n') {
    const row = document.createElement('div');
    row.className = 'sequence-row';

    const countInput = document.createElement('input');
    countInput.type = 'number';
    countInput.className = 'seq-count';
    countInput.min = 1;
    countInput.max = 100;
    countInput.value = count;

    const speedSelect = document.createElement('select');
    speedSelect.className = 'seq-speed';
    speedSelect.innerHTML = `
        <option value="n">Normal speed</option>
        <option value="s">Slow speed</option>
    `;
    speedSelect.value = speed;

    const removeBtn = document.createElement('button');
    removeBtn.type = 'button';
    removeBtn.className = 'seq-remove';
    removeBtn.textContent = '✕';
    removeBtn.title = 'Remove step';

    row.append(countInput, speedSelect, removeBtn);
    sequenceRows.appendChild(row);
}

sequenceRows.addEventListener('click', (e) => {
    if (e.target.classList.contains('seq-remove')) {
        e.target.closest('.sequence-row').remove();
    }
});

document.getElementById('addStepBtn').addEventListener('click', () => addSequenceRow());

sequenceToggle.addEventListener('change', () => {
    const on = sequenceToggle.checked;
    sequenceBuilder.style.display = on ? 'block' : 'none';

    const repetitionsInput = document.getElementById('repetitions');
    const slowCheckbox = document.getElementById('slow');
    repetitionsInput.disabled = on;
    slowCheckbox.disabled = on;
    repetitionsInput.closest('.form-group').classList.toggle('is-overridden', on);
    slowCheckbox.closest('.checkbox-group').classList.toggle('is-overridden', on);

    if (on && !sequenceRows.children.length) {
        addSequenceRow(3, 'n');
        addSequenceRow(4, 's');
        addSequenceRow(3, 'n');
    }
});

// Returns "2n,3s" or null (with a toast) when the rows are invalid
function serializeSequence() {
    const rows = [...sequenceRows.querySelectorAll('.sequence-row')];
    if (!rows.length) {
        showToast('Add at least one sequence step');
        return null;
    }

    const parts = [];
    let total = 0;
    for (const row of rows) {
        const count = parseInt(row.querySelector('.seq-count').value);
        if (!(count >= 1 && count <= 100)) {
            showToast('Each step count must be between 1 and 100');
            return null;
        }
        total += count;
        parts.push(`${count}${row.querySelector('.seq-speed').value}`);
    }
    if (total > 100) {
        showToast(`Sequence totals ${total} repetitions (max 100)`);
        return null;
    }
    return parts.join(',');
}

// ========== Conversation mode (two voices) ==========

// gTTS ignores voice selection entirely, so two voices are impossible there
function updateConversationAvailability() {
    const supported = ttsEngineSelect.value !== 'gtts';
    conversationToggle.disabled = !supported;
    conversationToggle.closest('.checkbox-group').classList.toggle('is-overridden', !supported);
    conversationToggle.closest('.checkbox-group').title =
        supported ? '' : 'Conversation mode needs a voice-capable engine (e.g. Edge TTS)';
    if (!supported && conversationToggle.checked) {
        conversationToggle.checked = false;
        conversationToggle.dispatchEvent(new Event('change'));
    }
}

conversationToggle.addEventListener('change', () => {
    conversationOptions.style.display = conversationToggle.checked ? 'block' : 'none';
});

voicedToggle.addEventListener('change', () => {
    voicedOptions.style.display = voicedToggle.checked ? 'block' : 'none';
});

// The voiced text the current settings would send, or null when disabled/empty
function voicedOverride() {
    if (!voicedToggle.checked) return null;
    return voicedTextArea.value.trim() || null;
}

// ========== Conversion ==========

async function handleConversion(endpoint, isVideo = false) {
    const sequence = sequenceToggle.checked ? serializeSequence() : null;
    if (sequenceToggle.checked && !sequence) {
        return;   // invalid rows — toast already shown
    }

    const conversation = conversationToggle.checked;
    const voiced = voicedOverride();
    if (conversation) {
        const lines = textArea.value.split('\n').map(l => l.trim()).filter(Boolean);
        if (lines.length < 2) {
            showToast('Conversation mode needs at least 2 lines of text');
            return;
        }
        if (voiced) {
            const voicedLines = voiced.split('\n').map(l => l.trim()).filter(Boolean);
            if (voicedLines.length !== lines.length) {
                showToast(`Pronunciation override needs the same number of lines as the text (${lines.length})`);
                return;
            }
        }
    }

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
    const repetitions = parseInt(document.getElementById('repetitions').value) || 10;
    const fontSize = parseInt(document.getElementById('fontSize').value) || 48;

    formData.append('text', textArea.value);
    if (voiced) {
        formData.append('voiced_text', voiced);
    }
    formData.append('language', languageSelect.value);
    if (sequence) {
        formData.append('sequence', sequence);
    } else {
        formData.append('slow', document.getElementById('slow').checked ? 'true' : 'false');
        formData.append('repetitions', repetitions);
    }
    formData.append('engine', ttsEngineSelect.value);
    if (voiceSelect.value) {
        formData.append('voice', voiceSelect.value);
    }
    if (conversation) {
        formData.append('conversation', 'true');
        if (voiceSelectB.value) {
            formData.append('voice_b', voiceSelectB.value);
        }
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
            const repeatLabel = sequence ? `(sequence ${sequence})` : (repetitions > 1 ? `(${repetitions} repetitions)` : '');

            if (isVideo) {
                const videoUrl = data.video_url || data.download_url;
                const audioUrl = data.audio_url;

                // Track for the YouTube upload section
                if (data.video_filename) {
                    lastGeneratedVideoFilename = data.video_filename;
                    updateUploadButton();
                }

                resultDiv.innerHTML = `
                    <h3>🎬 Video Generated Successfully! ${repeatLabel}</h3>
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
                    <h3>✅ Audio Generated Successfully! ${repeatLabel}</h3>
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
    // Repetitions, sequences and conversations on audio are handled by /repeat-audio
    const repetitions = parseInt(document.getElementById('repetitions').value) || 10;
    const useRepeatEndpoint = sequenceToggle.checked || conversationToggle.checked || repetitions > 1;
    handleConversion(useRepeatEndpoint ? '/repeat-audio' : '/convert', false);
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
    if (conversationToggle.checked) {
        formData.append('conversation', 'true');
    }
    if (document.getElementById('slow').checked) {
        formData.append('slow', 'true');
    }

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

    // "seq_2n-3s_..." → "[2n,3s] ..."
    const seqMatch = name.match(/^seq_(\d+[ns](?:-\d+[ns])*)_/);
    if (seqMatch) {
        repeat = `[${seqMatch[1].replace(/-/g, ',')}] `;
        name = name.slice(seqMatch[0].length);
    }

    // "conv_4lines_..." → "[dialogue] ..." ("conv_4lines_2n-3s_..." → "[dialogue 2n,3s] ...")
    const convMatch = name.match(/^conv_(\d+)lines_(?:(\d+[ns](?:-\d+[ns])*)_)?/);
    if (convMatch) {
        repeat += convMatch[2] ? `[dialogue ${convMatch[2].replace(/-/g, ',')}] ` : '[dialogue] ';
        name = name.slice(convMatch[0].length);
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
        const text = el.value ?? (el.textContent || el.innerText);
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
const titleCharCount = document.getElementById('titleCharCount');
const llmProvider = document.getElementById('llmProvider');

function updateTitleCharCount() {
    titleCharCount.textContent = metadataTitle.value.length;
    titleCharCount.style.color = metadataTitle.value.length > 100 ? '#c0392b' : '';
}

metadataTitle.addEventListener('input', updateTitleCharCount);

// ========== Vocabulary/grammar extraction (review before generating) ==========

const extractItemsBtn = document.getElementById('extractItemsBtn');
const itemsCard = document.getElementById('itemsCard');
const itemsVocabList = document.getElementById('itemsVocabList');
const itemsGrammarList = document.getElementById('itemsGrammarList');

let extractedItems = null;      // items returned by /extract-youtube-items
let extractedForText = null;    // the exact sentence they were extracted for

function invalidateExtractedItems() {
    if (extractedForText !== null && textArea.value.trim() !== extractedForText) {
        extractedItems = null;
        extractedForText = null;
        itemsCard.style.display = 'none';
        itemsVocabList.innerHTML = '';
        itemsGrammarList.innerHTML = '';
    }
}

function renderExtractedItems() {
    itemsVocabList.innerHTML = '';
    itemsGrammarList.innerHTML = '';
    extractedItems.forEach((item, index) => {
        const label = document.createElement('label');
        label.className = 'checkbox-inline item-row';
        const checkbox = document.createElement('input');
        checkbox.type = 'checkbox';
        checkbox.checked = true;
        checkbox.dataset.index = index;
        const span = document.createElement('span');
        const phonetics = item.phonetics ? ` (${item.phonetics})` : '';
        span.textContent = `${item.term}${phonetics} = ${item.meaning}`;
        label.appendChild(checkbox);
        label.appendChild(span);
        (item.kind === 'grammar' ? itemsGrammarList : itemsVocabList).appendChild(label);
    });
    itemsCard.style.display = 'block';
}

// The ticked subset for the current sentence, or null when no valid checklist
function tickedItems() {
    if (!extractedItems || extractedForText !== textArea.value.trim()) {
        return null;
    }
    const ticked = [];
    itemsCard.querySelectorAll('input[type="checkbox"]').forEach(cb => {
        if (cb.checked) {
            ticked.push(extractedItems[parseInt(cb.dataset.index, 10)]);
        }
    });
    return ticked;
}

extractItemsBtn.addEventListener('click', async () => {
    const text = textArea.value.trim();
    if (!text) {
        showToast('Please enter some text first');
        return;
    }

    extractItemsBtn.classList.add('loading');
    extractItemsBtn.disabled = true;

    try {
        const formData = new FormData();
        formData.append('text', text);
        formData.append('provider', llmProvider.value);

        const response = await fetch('/extract-youtube-items', {
            method: 'POST',
            body: formData
        });

        const data = await response.json();

        if (data.success) {
            extractedItems = data.items;
            extractedForText = text;
            renderExtractedItems();
            itemsCard.scrollIntoView({ behavior: 'smooth', block: 'nearest' });
        } else {
            showToast(`Extraction failed: ${data.error || data.detail || 'Unknown error'}`);
        }
    } catch (error) {
        console.error('Item extraction error:', error);
        showToast('Failed to extract items. Please check your API key and try again.');
    } finally {
        extractItemsBtn.classList.remove('loading');
        extractItemsBtn.disabled = false;
    }
});

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
        if (sourceSelect.value) {
            formData.append('source_id', sourceSelect.value);
        }
        const ticked = tickedItems();
        if (ticked !== null) {
            formData.append('items', JSON.stringify(ticked));
        }

        const response = await fetch('/generate-youtube-metadata', {
            method: 'POST',
            body: formData
        });

        const data = await response.json();

        if (data.success) {
            metadataTitle.value = data.title;
            metadataDescription.value = data.description;
            updateTitleCharCount();
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

// ========== Saved text sources (credit lines) ==========

const sourceSelect = document.getElementById('sourceSelect');
const sourceForm = document.getElementById('sourceForm');
const sourceNameInput = document.getElementById('sourceName');
const sourceCreditInput = document.getElementById('sourceCredit');
const addSourceBtn = document.getElementById('addSourceBtn');
const saveSourceBtn = document.getElementById('saveSourceBtn');
const deleteSourceBtn = document.getElementById('deleteSourceBtn');

async function loadSavedSources(selectId = null) {
    try {
        const response = await fetch('/sources');
        const data = await response.json();
        if (!data.success) return;

        const current = selectId || sourceSelect.value;
        sourceSelect.innerHTML = '<option value="">— Generic credit —</option>';
        data.sources.forEach(s => {
            const opt = document.createElement('option');
            opt.value = s.id;
            opt.textContent = s.name;
            opt.title = s.credit;
            sourceSelect.appendChild(opt);
        });
        sourceSelect.value = current;
        if (sourceSelect.value !== current) {
            sourceSelect.value = '';
        }
    } catch (e) {
        console.error('Failed to load saved sources:', e);
    }
}

addSourceBtn.addEventListener('click', () => {
    sourceForm.style.display = sourceForm.style.display === 'none' ? 'block' : 'none';
});

saveSourceBtn.addEventListener('click', async () => {
    const name = sourceNameInput.value.trim();
    const credit = sourceCreditInput.value.trim();
    if (!name || !credit) {
        showToast('Both a source name and a credit line are required');
        return;
    }

    try {
        const formData = new FormData();
        formData.append('name', name);
        formData.append('credit', credit);
        const response = await fetch('/sources', { method: 'POST', body: formData });
        const data = await response.json();
        if (data.success) {
            sourceNameInput.value = '';
            sourceCreditInput.value = '';
            sourceForm.style.display = 'none';
            await loadSavedSources(data.source.id);
            showToast(`Saved source: ${data.source.name}`, false);
        } else {
            showToast(`Failed to save source: ${data.error || 'Unknown error'}`);
        }
    } catch (e) {
        console.error('Failed to save source:', e);
        showToast('Failed to save source. Please try again.');
    }
});

deleteSourceBtn.addEventListener('click', async () => {
    if (!sourceSelect.value) {
        showToast('Select a saved source to delete');
        return;
    }

    try {
        const response = await fetch(`/sources/${encodeURIComponent(sourceSelect.value)}`, { method: 'DELETE' });
        const data = await response.json();
        if (data.success) {
            showToast('Source deleted', false);
            sourceSelect.value = '';
            await loadSavedSources();
        } else {
            showToast(`Failed to delete source: ${data.error || 'Unknown error'}`);
        }
    } catch (e) {
        console.error('Failed to delete source:', e);
        showToast('Failed to delete source. Please try again.');
    }
});

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
            // Keep the "No Playlist" option and the chosen playlist, add fetched ones
            const chosen = playlistSelect.value;
            playlistSelect.innerHTML = '<option value="">— No Playlist —</option>';
            data.playlists.forEach(pl => {
                const opt = document.createElement('option');
                opt.value = pl.id;
                opt.textContent = pl.title;
                playlistSelect.appendChild(opt);
            });
            playlistSelect.value = chosen;
            if (playlistSelect.value !== chosen) {
                playlistSelect.value = '';  // that playlist is gone from the account
            }
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
    if (!metadataTitle.value.trim()) {
        showToast('The title is empty — write one or regenerate the metadata');
        return;
    }

    uploadYoutubeBtn.classList.add('loading');
    uploadYoutubeBtn.disabled = true;
    uploadResult.innerHTML = '⏳ Uploading to YouTube... This may take a minute.';

    try {
        const formData = new FormData();
        formData.append('video_filename', lastGeneratedVideoFilename);
        formData.append('title', metadataTitle.value);
        formData.append('description', metadataDescription.value);
        formData.append('playlist_id', document.getElementById('playlistSelect').value);
        formData.append('privacy_status', document.getElementById('privacyStatus').value);

        // Extract tags from description hashtags (unicode-aware for CJK tags)
        const hashtags = metadataDescription.value.match(/#[\p{L}\p{N}_]+/gu);
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
                🎬 <code>${lastUploadedFilename}</code><br>
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
    updateConversationAvailability();
    checkProviderAvailability();
    loadSavedSources();
    checkYouTubeAuth();
    loadRecentFiles();
});
