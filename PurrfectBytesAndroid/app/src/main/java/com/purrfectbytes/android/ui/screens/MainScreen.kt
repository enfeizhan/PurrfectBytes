package com.purrfectbytes.android.ui.screens

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import com.purrfectbytes.android.ui.components.MessageBanner
import com.purrfectbytes.android.viewmodels.MainViewModel

@Composable
fun MainScreen(
    modifier: Modifier = Modifier,
    viewModel: MainViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val generatedAudioFiles by viewModel.generatedAudioFiles.collectAsState()
    val generatedVideoFile by viewModel.generatedVideoFile.collectAsState()
    val previewImageFile by viewModel.previewImageFile.collectAsState()
    val capturedPhotoUri by viewModel.capturedPhotoUri.collectAsState()
    val recognizedText by viewModel.recognizedText.collectAsState()
    val isAnalyzingPhoto by viewModel.isAnalyzingPhoto.collectAsState()
    val selectedScript by viewModel.selectedScript.collectAsState()

    val context = LocalContext.current
    val density = LocalDensity.current

    // The messages lie over the page; the page ends with as much empty space, so that
    // its last card can always be scrolled into view above them
    val hasMessage = uiState.errorMessage != null || uiState.successMessage != null
    var messageHeight by remember { mutableIntStateOf(0) }

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

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            viewModel.openCamera()
        } else {
            // Android stops asking after repeated refusals; from then on only Settings helps
            val canAskAgain = (context as? Activity)?.let {
                ActivityCompat.shouldShowRequestPermissionRationale(it, Manifest.permission.CAMERA)
            } ?: false
            viewModel.onCameraPermissionDenied(blocked = !canAskAgain)
        }
    }

    val youTubeActions = remember(viewModel) {
        YouTubeActions(
            onConnect = { youtubeAuthLauncher.launch(viewModel.startYouTubeAuth()) },
            onDisconnect = viewModel::disconnectYouTube,
            onShowChannelPicker = viewModel::showChannelPickerDialog,
            onDismissChannelPicker = viewModel::dismissChannelPicker,
            onSelectChannel = viewModel::selectYouTubeChannel,
            onTitleChange = viewModel::updateYoutubeTitle,
            onDescriptionChange = viewModel::updateYoutubeDescription,
            onProviderChange = viewModel::updateMetadataProvider,
            onGenerateMetadata = viewModel::generateMetadata,
            onSelectPlaylist = viewModel::updateYouTubePlaylist,
            onPrivacyChange = viewModel::updateYouTubePrivacy,
            onUpload = viewModel::uploadToYouTube,
            onSelectSource = viewModel::selectSource,
            onSaveSource = viewModel::saveSource,
            onDeleteSource = viewModel::deleteSelectedSource,
            onExtractItems = viewModel::extractItems,
            onToggleItem = viewModel::toggleItem
        )
    }

    val speechActions = remember(viewModel) {
        SpeechActions(
            onEngineChange = viewModel::updateTtsEngine,
            onVoiceChange = viewModel::updateVoice,
            onSecondVoiceChange = viewModel::updateSecondVoice,
            onConversationChange = viewModel::updateConversation,
            onSequenceUseChange = viewModel::updateSequenceUse,
            onAddStep = viewModel::addSequenceStep,
            onRemoveStep = viewModel::removeSequenceStep,
            onStepChange = viewModel::updateSequenceStep,
            onSlowSpeechChange = viewModel::updateSlowSpeech,
            onRepetitionsChange = viewModel::updateRepetitions
        )
    }

    // Generating speech switches "loading" on and off for every line of a conversation
    val isBusy = isLoading || uiState.isConvertingVideo

    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            HeaderCard(
                onOpenGallery = {
                    galleryLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                },
                onOpenCamera = {
                    val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                        PackageManager.PERMISSION_GRANTED
                    if (granted) {
                        viewModel.openCamera()
                    } else {
                        cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                    }
                }
            )

            OcrModeCard(
                mode = uiState.ocrMode,
                enabled = !isBusy,
                onModeChange = viewModel::updateOcrMode
            )

            capturedPhotoUri?.let { uri ->
                CapturedPhotoCard(
                    photoUri = uri,
                    recognized = recognizedText,
                    isAnalyzing = isAnalyzingPhoto,
                    selectedScript = selectedScript,
                    onTextClick = viewModel::onTextBlockClick,
                    onScriptSelected = viewModel::reanalyzeWithScript,
                    onRemove = viewModel::clearPhoto
                )
            }

            TextInputCard(
                text = uiState.text,
                enabled = !isBusy,
                onTextChange = viewModel::updateText,
                useVoicedText = uiState.useVoicedText,
                voicedText = uiState.voicedText,
                onVoicedTextUseChange = viewModel::updateVoicedTextUse,
                onVoicedTextChange = viewModel::updateVoicedText
            )

            LanguageCard(
                languages = viewModel.supportedLanguages,
                selectedLanguage = uiState.selectedLanguage,
                notice = uiState.detectedLanguageNotice,
                noticeIsError = uiState.isDetectingLanguageError,
                isDetecting = uiState.isDetectingLanguage,
                canDetect = uiState.text.isNotBlank(),
                enabled = !isBusy,
                onDetect = viewModel::autoDetectLanguage,
                onLanguageChange = viewModel::updateLanguage
            )

            VoiceCard(
                uiState = uiState,
                engines = viewModel.supportedTtsEngines,
                enabled = !isBusy,
                actions = speechActions
            )

            RepetitionCard(
                uiState = uiState,
                enabled = !isBusy,
                actions = speechActions
            )

            ActionButtons(
                canStart = !isBusy && uiState.text.isNotBlank(),
                isGeneratingPreview = uiState.isGeneratingPreview,
                isConvertingVideo = uiState.isConvertingVideo,
                onPreview = viewModel::generatePreview,
                onRender = viewModel::generateNativeVideo,
                progress = uiState.renderProgress
            )

            previewImageFile?.let { image ->
                PreviewCard(image = image, onDismiss = viewModel::dismissPreview)
            }

            generatedVideoFile?.let { video ->
                VideoCard(
                    video = video,
                    summary = uiState.videoSummary,
                    seconds = uiState.videoSeconds,
                    onDismiss = viewModel::dismissVideo
                )
            }

            if (uiState.recentVideos.isNotEmpty()) {
                RecentVideosCard(
                    videos = uiState.recentVideos,
                    selected = generatedVideoFile,
                    onSelect = viewModel::selectVideo,
                    onDelete = viewModel::deleteVideo
                )
            }

            YouTubeCard(
                uiState = uiState,
                hasVideo = generatedVideoFile != null,
                actions = youTubeActions,
                uploadedUrl = uiState.uploadOf(generatedVideoFile)
            )

            if (generatedAudioFiles.isNotEmpty()) {
                AudioCard(
                    onPlay = viewModel::playAudio,
                    onStop = viewModel::stopAudio,
                    lines = generatedAudioFiles.size
                )
            }

            if (hasMessage) {
                Spacer(modifier = Modifier.height(with(density) { messageHeight.toDp() }))
            }
        }

        // Outside the scrolling page, so a message is seen wherever the page is scrolled to
        MessageBanner(
            errorMessage = uiState.errorMessage,
            successMessage = uiState.successMessage,
            onDismissError = viewModel::dismissError,
            onDismissSuccess = viewModel::dismissSuccess,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .onSizeChanged { messageHeight = it.height }
        )
    }
}
