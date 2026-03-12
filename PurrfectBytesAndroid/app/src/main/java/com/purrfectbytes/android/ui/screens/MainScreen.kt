package com.purrfectbytes.android.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.purrfectbytes.android.ui.components.PhotoTextAnalyzer
import com.purrfectbytes.android.ui.components.PhotoWithTextOverlay
import com.purrfectbytes.android.ui.components.PrecisePhotoTextOverlay
import com.purrfectbytes.android.services.RecognitionScript
import com.purrfectbytes.android.viewmodels.MainViewModel
import com.purrfectbytes.android.viewmodels.YouTubeChannel
import android.accounts.Account
import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.PickVisualMediaRequest
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.services.youtube.YouTubeScopes

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    modifier: Modifier = Modifier,
    viewModel: MainViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val currentStatus by viewModel.currentStatus.collectAsState()
    val generatedAudioFile by viewModel.generatedAudioFile.collectAsState()
    val previewImageFile by viewModel.previewImageFile.collectAsState()
    val capturedPhotoUri by viewModel.capturedPhotoUri.collectAsState()
    val recognizedTextBlocks by viewModel.recognizedTextBlocks.collectAsState()
    val isAnalyzingPhoto by viewModel.isAnalyzingPhoto.collectAsState()
    val showTextAnalyzer by viewModel.showTextAnalyzer.collectAsState()
    val selectedScript by viewModel.selectedScript.collectAsState()
    val youtubeAuthIntent by viewModel.youtubeAuthIntent.collectAsState()

    // YouTube OAuth via GoogleAccountCredential
    val context = LocalContext.current
    // Launcher to handle OAuth browser result
    val youtubeAuthLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        viewModel.handleYouTubeAuthResult(result.data)
    }

    val galleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        uri?.let { viewModel.onPhotoCaptured(it) }

    }

    // Auto-scroll to bottom of text field when new text is added
    val scrollState = rememberScrollState()

    val supportedLanguages = remember { viewModel.supportedLanguages }
    
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Header with Camera Button
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer
            )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "🎵 PurrfectBytes",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Text(
                        text = "Text to Speech Converter (v1.2)",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }

                Row {
                    IconButton(
                        onClick = { galleryLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(
                            Icons.Default.PhotoLibrary,
                            contentDescription = "Open Gallery",
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(32.dp)
                        )
                    }
                    IconButton(
                        onClick = { viewModel.openCamera() },
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(
                            Icons.Default.CameraAlt,
                            contentDescription = "Open Camera",
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(32.dp)
                        )
                    }
                }
            }
        }

        // Set OCR Mode
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Text Extraction Mode",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium
                )
                Spacer(modifier = Modifier.height(8.dp))
                
                var ocrExpanded by remember { mutableStateOf(false) }
                
                ExposedDropdownMenuBox(
                    expanded = ocrExpanded,
                    onExpandedChange = { ocrExpanded = !ocrExpanded },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    OutlinedTextField(
                        value = uiState.ocrMode.displayName,
                        onValueChange = { },
                        readOnly = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor(),
                        enabled = !isLoading,
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = ocrExpanded) }
                    )
                    
                    ExposedDropdownMenu(
                        expanded = ocrExpanded,
                        onDismissRequest = { ocrExpanded = false }
                    ) {
                        com.purrfectbytes.android.viewmodels.OcrMode.values().forEach { mode ->
                            DropdownMenuItem(
                                text = { Text(mode.displayName) },
                                onClick = {
                                    viewModel.updateOcrMode(mode)
                                    ocrExpanded = false
                                }
                            )
                        }
                    }
                }
            }
        }

        // Captured Photo Display with Text Overlay
        capturedPhotoUri?.let { uri ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = "📷 Captured Photo",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onTertiaryContainer
                            )
                            if (recognizedTextBlocks.isNotEmpty()) {
                                Text(
                                    text = "Tap on text to select",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.7f)
                                )
                            }
                        }
                        IconButton(
                            onClick = { viewModel.clearPhoto() },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "Remove Photo",
                                tint = MaterialTheme.colorScheme.onTertiaryContainer
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    if (isAnalyzingPhoto) {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(300.dp)
                        ) {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    CircularProgressIndicator(modifier = Modifier.size(48.dp))
                                    Spacer(modifier = Modifier.height(16.dp))
                                    Text(
                                        text = "Analyzing text in image...",
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                }
                            }
                        }
                    } else {
                        // Photo with precise clickable text overlay
                        PrecisePhotoTextOverlay(
                            photoUri = uri,
                            recognizedBlocks = recognizedTextBlocks,
                            onTextClick = { text ->
                                viewModel.onTextBlockClick(text)
                            }
                        )
                    }

                    // Language selector for re-analysis
                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Text Language:",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onTertiaryContainer
                        )

                        // Script selection chips
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            FilterChip(
                                selected = selectedScript == RecognitionScript.AUTO,
                                onClick = { viewModel.reanalyzeWithScript(RecognitionScript.AUTO) },
                                label = { Text("Auto", style = MaterialTheme.typography.labelSmall) },
                                modifier = Modifier.height(28.dp)
                            )
                            FilterChip(
                                selected = selectedScript == RecognitionScript.LATIN,
                                onClick = { viewModel.reanalyzeWithScript(RecognitionScript.LATIN) },
                                label = { Text("English", style = MaterialTheme.typography.labelSmall) },
                                modifier = Modifier.height(28.dp)
                            )
                            FilterChip(
                                selected = selectedScript == RecognitionScript.JAPANESE,
                                onClick = { viewModel.reanalyzeWithScript(RecognitionScript.JAPANESE) },
                                label = { Text("日本語", style = MaterialTheme.typography.labelSmall) },
                                modifier = Modifier.height(28.dp)
                            )
                            FilterChip(
                                selected = selectedScript == RecognitionScript.CHINESE,
                                onClick = { viewModel.reanalyzeWithScript(RecognitionScript.CHINESE) },
                                label = { Text("中文", style = MaterialTheme.typography.labelSmall) },
                                modifier = Modifier.height(28.dp)
                            )
                            FilterChip(
                                selected = selectedScript == RecognitionScript.KOREAN,
                                onClick = { viewModel.reanalyzeWithScript(RecognitionScript.KOREAN) },
                                label = { Text("한글", style = MaterialTheme.typography.labelSmall) },
                                modifier = Modifier.height(28.dp)
                            )
                        }
                    }
                }
            }
        }

        // Text Input
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Enter your text:",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = uiState.text,
                    onValueChange = { viewModel.updateText(it) },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Type or paste your text here...") },
                    minLines = 4,
                    maxLines = 8,
                    enabled = !isLoading
                )
            }
        }
        
        // Language Selection
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Language:",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium
                )
                Spacer(modifier = Modifier.height(8.dp))
                
                var expanded by remember { mutableStateOf(false) }
                val selectedLanguageName = supportedLanguages.find { it.first == uiState.selectedLanguage }?.second ?: "English"
                
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = { viewModel.autoDetectLanguage() },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF198754)),
                        enabled = !isLoading && !uiState.isDetectingLanguage && uiState.text.isNotBlank(),
                        shape = MaterialTheme.shapes.small
                    ) {
                        if (uiState.isDetectingLanguage) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                color = Color.White
                            )
                        } else {
                            Icon(Icons.Default.FindInPage, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Detect Language")
                        }
                    }

                    ExposedDropdownMenuBox(
                        expanded = expanded,
                        onExpandedChange = { expanded = !expanded },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        OutlinedTextField(
                            value = selectedLanguageName,
                            onValueChange = { },
                            readOnly = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .menuAnchor(),
                            enabled = !isLoading,
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) }
                        )
                        
                        ExposedDropdownMenu(
                            expanded = expanded,
                            onDismissRequest = { expanded = false }
                        ) {
                            supportedLanguages.forEach { (code, name) ->
                                DropdownMenuItem(
                                    text = { Text(name) },
                                    onClick = {
                                        viewModel.updateLanguage(code)
                                        expanded = false
                                    }
                                )
                            }
                        }
                    }

                    uiState.detectedLanguageNotice?.let { notice ->
                        Text(
                            text = notice,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (uiState.isDetectingLanguageError) Color(0xFFE74C3C) else Color(0xFF27AE60)
                        )
                    }
                }
            }
        }
        
        // Options
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                
                // TTS Engine Option
                Text(
                    text = "TTS Engine",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium
                )
                Spacer(modifier = Modifier.height(8.dp))
                
                var engineExpanded by remember { mutableStateOf(false) }
                val selectedEngineName = viewModel.supportedTtsEngines.find { it.first == uiState.selectedTtsEngine }?.second ?: "Microsoft Edge TTS - Natural neural voices (Best quality)"
                
                ExposedDropdownMenuBox(
                    expanded = engineExpanded,
                    onExpandedChange = { engineExpanded = !engineExpanded },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    OutlinedTextField(
                        value = selectedEngineName,
                        onValueChange = { },
                        readOnly = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor(),
                        enabled = !isLoading,
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = engineExpanded) }
                    )
                    
                    ExposedDropdownMenu(
                        expanded = engineExpanded,
                        onDismissRequest = { engineExpanded = false }
                    ) {
                        viewModel.supportedTtsEngines.forEach { (code, name) ->
                            DropdownMenuItem(
                                text = { Text(name) },
                                onClick = {
                                    viewModel.updateTtsEngine(code)
                                    engineExpanded = false
                                }
                            )
                        }
                    }
                }
                
                Spacer(modifier = Modifier.height(16.dp))

                // Slow Speech Option
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Slow speech speed",
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Switch(
                        checked = uiState.isSlowSpeech,
                        onCheckedChange = { viewModel.updateSlowSpeech(it) },
                        enabled = !isLoading
                    )
                }
                
                Spacer(modifier = Modifier.height(16.dp))
                
                // Repetitions
                Text(
                    text = "Number of Repetitions:",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = uiState.repetitions.toString(),
                    onValueChange = { 
                        val repetitions = it.toIntOrNull()?.coerceIn(1, 100) ?: 1
                        viewModel.updateRepetitions(repetitions)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("1 - 100") },
                    enabled = !isLoading,
                    supportingText = { 
                        Text(
                            text = "Generate and concatenate the text this many times (1 = no concatenation)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                )
            }
        }
        // Action Buttons
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = { viewModel.generatePreview() },
                modifier = Modifier.weight(1f),
                enabled = !isLoading && uiState.text.isNotBlank() && !uiState.isConvertingVideo,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
            ) {
                Icon(Icons.Default.Image, contentDescription = null)
                Spacer(modifier = Modifier.width(4.dp))
                Text("Preview")
            }
            
            Button(
                onClick = { viewModel.generateNativeVideo() },
                modifier = Modifier.weight(1f),
                enabled = !isLoading && uiState.text.isNotBlank() && !uiState.isConvertingVideo,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
            ) {
                if (uiState.isConvertingVideo) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = MaterialTheme.colorScheme.onPrimary,
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Rendering")
                } else {
                    Icon(Icons.Default.Movie, contentDescription = null)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Render MP4")
                }
            }
        }

        val generatedVideoFile by viewModel.generatedVideoFile.collectAsState()
        
        // Preview Image
        if (previewImageFile != null) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "👀 Video Preview",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onTertiaryContainer
                        )
                        IconButton(onClick = { viewModel.dismissPreview() }) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "Dismiss preview",
                                tint = MaterialTheme.colorScheme.onTertiaryContainer
                            )
                        }
                    }
                    
                    Spacer(modifier = Modifier.height(12.dp))
                    
                    AsyncImage(
                        model = previewImageFile,
                        contentDescription = "Video preview frame",
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(16f / 9f)
                            .clip(MaterialTheme.shapes.medium),
                        contentScale = ContentScale.Fit
                    )
                }
            }
        }
        
        if (generatedVideoFile != null) {
            var showVideoDialog by remember { mutableStateOf(false) }

            // Compact card - never blocks scrolling
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "🎬 Video Ready!",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Text(
                            text = "Tap Play to watch",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { showVideoDialog = true },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary
                            )
                        ) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Play")
                        }
                        IconButton(onClick = { viewModel.dismissVideo() }) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "Dismiss video",
                                tint = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }
                }
            }

            // Full-screen dialog for playback - doesn't affect main scroll
            if (showVideoDialog) {
                androidx.compose.ui.window.Dialog(
                    onDismissRequest = { showVideoDialog = false },
                    properties = androidx.compose.ui.window.DialogProperties(
                        usePlatformDefaultWidth = false
                    )
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black)
                    ) {
                        var exoPlayer by remember { mutableStateOf<ExoPlayer?>(null) }

                        DisposableEffect(generatedVideoFile) {
                            val player = ExoPlayer.Builder(context).build().apply {
                                setMediaItem(MediaItem.fromUri(android.net.Uri.fromFile(generatedVideoFile)))
                                prepare()
                                playWhenReady = true
                            }
                            exoPlayer = player
                            onDispose { player.release() }
                        }

                        AndroidView(
                            factory = { ctx ->
                                PlayerView(ctx).apply {
                                    useController = true
                                    setShowNextButton(false)
                                    setShowPreviousButton(false)
                                    resizeMode = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
                                }
                            },
                            update = { view -> view.player = exoPlayer },
                            modifier = Modifier
                                .fillMaxWidth()
                                .align(Alignment.Center)
                        )

                        IconButton(
                            onClick = { showVideoDialog = false },
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(8.dp)
                        ) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "Close player",
                                tint = Color.White,
                                modifier = Modifier.size(32.dp)
                            )
                        }
                    }
                }
            }
        }

        
        // YouTube Section
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "🚀 Upload to YouTube",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    // Connection Button
                    if (uiState.isYouTubeConnected) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Button(
                                onClick = { youtubeAuthLauncher.launch(viewModel.startYouTubeAuth()) },
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1976D2))
                            ) {
                                Icon(Icons.Default.Check, contentDescription = null, tint = Color.White)
                                Spacer(Modifier.width(8.dp))
                                if (uiState.isFetchingChannels) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        color = Color.White,
                                        strokeWidth = 2.dp
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text("Fetching channels...", color = Color.White)
                                } else if (uiState.selectedChannelName != null) {
                                    Text("Channel: ${uiState.selectedChannelName}", color = Color.White)
                                } else {
                                    Text("YouTube Connected", color = Color.White)
                                }
                            }
                            // Show channel selection if there are multiple channels
                            if (uiState.youtubeChannels.size > 1) {
                                Spacer(modifier = Modifier.height(4.dp))
                                TextButton(
                                    onClick = { viewModel.showChannelPickerDialog() },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("Switch Channel (${uiState.youtubeChannels.size} available)")
                                }
                            }
                        }
                    } else {
                        OutlinedButton(
                            onClick = { youtubeAuthLauncher.launch(viewModel.startYouTubeAuth()) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Connect to YouTube")
                        }
                    }

                    // Channel Picker Dialog
                    if (uiState.showChannelPicker) {
                        AlertDialog(
                            onDismissRequest = { viewModel.dismissChannelPicker() },
                            title = { Text("Select YouTube Channel") },
                            text = {
                                Column {
                                    uiState.youtubeChannels.forEach { channel ->
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clickable { viewModel.selectYouTubeChannel(channel) }
                                                .padding(vertical = 8.dp)
                                                .let { mod ->
                                                    if (channel.id == uiState.selectedChannelId)
                                                        mod.background(
                                                            MaterialTheme.colorScheme.primaryContainer,
                                                            MaterialTheme.shapes.small
                                                        )
                                                    else mod
                                                }
                                                .padding(horizontal = 12.dp, vertical = 8.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Icon(
                                                if (channel.id == uiState.selectedChannelId) Icons.Default.RadioButtonChecked
                                                else Icons.Default.RadioButtonUnchecked,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.primary
                                            )
                                            Spacer(modifier = Modifier.width(12.dp))
                                            Text(
                                                text = channel.title,
                                                style = MaterialTheme.typography.bodyLarge,
                                                fontWeight = if (channel.id == uiState.selectedChannelId) FontWeight.Bold else FontWeight.Normal
                                            )
                                        }
                                    }
                                }
                            },
                            confirmButton = {
                                TextButton(onClick = { viewModel.dismissChannelPicker() }) {
                                    Text("Done")
                                }
                            }
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    OutlinedTextField(
                        value = uiState.youtubeTitle,
                        onValueChange = { viewModel.updateYoutubeTitle(it) },
                        label = { Text("YouTube Title") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedTextField(
                        value = uiState.youtubeDescription,
                        onValueChange = { viewModel.updateYoutubeDescription(it) },
                        label = { Text("YouTube Description") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 100.dp),
                        maxLines = 5
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("🤖 AI Powered by:", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        
                        FilterChip(
                            selected = uiState.metadataProvider == "gemini",
                            onClick = { viewModel.updateMetadataProvider("gemini") },
                            label = { Text("Gemini") },
                            leadingIcon = if (uiState.metadataProvider == "gemini") {
                                { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
                            } else null
                        )

                        FilterChip(
                            selected = uiState.metadataProvider == "anthropic",
                            onClick = { viewModel.updateMetadataProvider("anthropic") },
                            label = { Text("Claude") },
                            leadingIcon = if (uiState.metadataProvider == "anthropic") {
                                { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
                            } else null
                        )
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    OutlinedButton(
                        onClick = { viewModel.generateMetadata() },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !uiState.isGeneratingMetadata && uiState.text.isNotBlank()
                    ) {
                        if (uiState.isGeneratingMetadata) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Generating...")
                        } else {
                            Icon(Icons.Default.AutoAwesome, contentDescription = null)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Auto-Fill Title & Description")
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Playlist Dropdown
                    var playlistExpanded by remember { mutableStateOf(false) }
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Text("Playlist (optional):", style = MaterialTheme.typography.bodySmall)
                        Spacer(modifier = Modifier.height(4.dp))
                        ExposedDropdownMenuBox(
                            expanded = playlistExpanded,
                            onExpandedChange = { playlistExpanded = !playlistExpanded },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            OutlinedTextField(
                                value = uiState.selectedPlaylistName,
                                onValueChange = { },
                                readOnly = true,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .menuAnchor(),
                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = playlistExpanded) },
                                colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors()
                            )
                            ExposedDropdownMenu(
                                expanded = playlistExpanded,
                                onDismissRequest = { playlistExpanded = false }
                            ) {
                                if (uiState.isFetchingPlaylists) {
                                    DropdownMenuItem(
                                        text = { Text("Loading playlists...") },
                                        onClick = { }
                                    )
                                } else if (uiState.availablePlaylists.isEmpty()) {
                                    DropdownMenuItem(
                                        text = { Text("No playlists found") },
                                        onClick = { }
                                    )
                                } else {
                                    uiState.availablePlaylists.forEach { playlist ->
                                        DropdownMenuItem(
                                            text = { Text(playlist.title) },
                                            onClick = {
                                                viewModel.updateYouTubePlaylist(playlist)
                                                playlistExpanded = false
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Privacy Dropdown
                    var privacyExpanded by remember { mutableStateOf(false) }
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Text("Privacy:", style = MaterialTheme.typography.bodySmall)
                        Spacer(modifier = Modifier.height(4.dp))
                        ExposedDropdownMenuBox(
                            expanded = privacyExpanded,
                            onExpandedChange = { privacyExpanded = !privacyExpanded },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            OutlinedTextField(
                                value = uiState.selectedPrivacy,
                                onValueChange = { },
                                readOnly = true,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .menuAnchor(),
                                leadingIcon = { Icon(Icons.Default.Public, contentDescription = null) },
                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = privacyExpanded) },
                                colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors()
                            )
                            ExposedDropdownMenu(
                                expanded = privacyExpanded,
                                onDismissRequest = { privacyExpanded = false }
                            ) {
                                listOf("Public", "Unlisted", "Private").forEach { privacy ->
                                    DropdownMenuItem(
                                        text = { Text(privacy) },
                                        onClick = {
                                            viewModel.updateYouTubePrivacy(privacy)
                                            privacyExpanded = false
                                        }
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Final Upload Button
                    Button(
                        onClick = { viewModel.uploadToYouTube() },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = uiState.isYouTubeConnected && !uiState.isUploadingToYouTube && uiState.youtubeTitle.isNotBlank() && uiState.youtubeDescription.isNotBlank() && viewModel.generatedVideoFile.collectAsState().value != null,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF66BB6A)) // Green
                    ) {
                        if (uiState.isUploadingToYouTube) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), color = Color.White)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Uploading...")
                        } else {
                            Icon(Icons.Default.CloudUpload, contentDescription = null, tint = Color.White)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Upload to YouTube", color = Color.White)
                        }
                    }
                }
            }
        

        // Audio Player
        if (generatedAudioFile != null) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "🎵 Audio Generated Successfully!",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                    
                    if (uiState.repetitions > 1) {
                        Text(
                            text = "${uiState.repetitions} repetitions created",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = { viewModel.playAudio() },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Play")
                        }
                        
                        OutlinedButton(
                            onClick = { viewModel.stopAudio() },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.Stop, contentDescription = null)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Stop")
                        }
                    }
                }
            }
        }
        // Error Message
        uiState.errorMessage?.let { error ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer
                )
            ) {
                Text(
                    text = "❌ $error",
                    modifier = Modifier.padding(16.dp),
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            
            LaunchedEffect(error) {
                kotlinx.coroutines.delay(15000)
                viewModel.clearMessages()
            }
        }
        
        // Success Message
        uiState.successMessage?.let { success ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = Color(0xFF4CAF50).copy(alpha = 0.1f)
                )
            ) {
                Text(
                    text = "✅ $success",
                    modifier = Modifier.padding(16.dp),
                    color = Color(0xFF2E7D32),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            
            LaunchedEffect(success) {
                kotlinx.coroutines.delay(3000)
                viewModel.clearMessages()
            }
        }
}
    }