package com.purrfectbytes.android.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.purrfectbytes.android.services.AppSettings
import com.purrfectbytes.android.services.YouTubeChannel
import com.purrfectbytes.android.services.YouTubeMetadataFormat
import com.purrfectbytes.android.services.YouTubeMetadataGenerator
import com.purrfectbytes.android.services.YouTubePlaylist
import com.purrfectbytes.android.viewmodels.MainUiState

/** What the YouTube card can ask for. */
internal class YouTubeActions(
    val onConnect: () -> Unit,
    val onDisconnect: () -> Unit,
    val onShowChannelPicker: () -> Unit,
    val onDismissChannelPicker: () -> Unit,
    val onSelectChannel: (YouTubeChannel) -> Unit,
    val onTitleChange: (String) -> Unit,
    val onDescriptionChange: (String) -> Unit,
    val onProviderChange: (String) -> Unit,
    val onGenerateMetadata: () -> Unit,
    val onSelectPlaylist: (YouTubePlaylist?) -> Unit,
    val onPrivacyChange: (String) -> Unit,
    val onUpload: () -> Unit,
    /** A source that is null is the general credit. */
    val onSelectSource: (String?) -> Unit = {},
    /** Answers whether the source was saved. */
    val onSaveSource: (name: String, credit: String) -> Boolean = { _, _ -> false },
    val onDeleteSource: () -> Unit = {},
    val onExtractItems: () -> Unit = {},
    val onToggleItem: (Int) -> Unit = {}
)

/** Stands for "no playlist" in the list of playlists. */
private val NO_PLAYLIST = YouTubePlaylist(id = "", title = "No playlist")

/**
 * From the text to the video on YouTube, in the order things are done: who writes, which
 * words to explain, whom to credit, title and description, where to upload.
 *
 * [uploadedUrl] is where the video that is ready is watched on YouTube; a video that is
 * there already is not uploaded again.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun YouTubeCard(
    uiState: MainUiState,
    hasVideo: Boolean,
    actions: YouTubeActions,
    uploadedUrl: String? = null
) {
    val isUploaded = uploadedUrl != null
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

            ConnectionButtons(uiState, actions)

            if (uiState.showChannelPicker) {
                ChannelPickerDialog(
                    channels = uiState.youtubeChannels,
                    selectedChannelId = uiState.selectedChannelId,
                    onSelect = actions.onSelectChannel,
                    onDismiss = actions.onDismissChannelPicker
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("🤖 AI Powered by:", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

                ProviderChip("Gemini", YouTubeMetadataGenerator.PROVIDER_GEMINI, uiState.metadataProvider, actions.onProviderChange)
                ProviderChip("Claude", YouTubeMetadataGenerator.PROVIDER_ANTHROPIC, uiState.metadataProvider, actions.onProviderChange)
            }

            Spacer(modifier = Modifier.height(8.dp))

            OutlinedButton(
                onClick = actions.onExtractItems,
                modifier = Modifier.fillMaxWidth(),
                enabled = !uiState.isExtractingItems && !uiState.isGeneratingMetadata && uiState.text.isNotBlank()
            ) {
                if (uiState.isExtractingItems) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Extracting...")
                } else {
                    Icon(Icons.Default.Search, contentDescription = null)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Extract Vocabulary & Grammar")
                }
            }
            Hint(
                text = "Optional: review what the description explains before it is written",
                modifier = Modifier.fillMaxWidth()
            )

            if (uiState.hasItems) {
                Spacer(modifier = Modifier.height(8.dp))
                StudyItemList(
                    items = uiState.studyItems,
                    approved = uiState.approvedItems,
                    onToggle = actions.onToggleItem
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            SourcePicker(
                sources = uiState.sources,
                selectedId = uiState.selectedSourceId,
                onSelect = actions.onSelectSource,
                onSave = actions.onSaveSource,
                onDelete = actions.onDeleteSource
            )

            Spacer(modifier = Modifier.height(8.dp))

            OutlinedButton(
                onClick = actions.onGenerateMetadata,
                modifier = Modifier.fillMaxWidth(),
                enabled = !uiState.isGeneratingMetadata && !uiState.isExtractingItems && uiState.text.isNotBlank()
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

            Spacer(modifier = Modifier.height(12.dp))

            OutlinedTextField(
                value = uiState.youtubeTitle,
                onValueChange = actions.onTitleChange,
                label = { Text("YouTube Title") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                supportingText = {
                    Text("${uiState.youtubeTitle.length}/${YouTubeMetadataFormat.MAX_TITLE_LENGTH} characters")
                }
            )

            Spacer(modifier = Modifier.height(8.dp))

            OutlinedTextField(
                value = uiState.youtubeDescription,
                onValueChange = actions.onDescriptionChange,
                label = { Text("YouTube Description") },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 100.dp),
                maxLines = 5
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Playlist Dropdown
            Column(modifier = Modifier.fillMaxWidth()) {
                Text("Playlist (optional):", style = MaterialTheme.typography.bodySmall)
                Spacer(modifier = Modifier.height(4.dp))
                DropdownField(
                    value = uiState.selectedPlaylistName,
                    options = if (uiState.availablePlaylists.isEmpty()) emptyList() else listOf(NO_PLAYLIST) + uiState.availablePlaylists,
                    optionLabel = { it.title },
                    onSelect = { actions.onSelectPlaylist(it.takeIf { playlist -> playlist !== NO_PLAYLIST }) },
                    colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(),
                    emptyLabel = if (uiState.isFetchingPlaylists) "Loading playlists..." else "No playlists found"
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Privacy Dropdown
            Column(modifier = Modifier.fillMaxWidth()) {
                Text("Privacy:", style = MaterialTheme.typography.bodySmall)
                Spacer(modifier = Modifier.height(4.dp))
                DropdownField(
                    value = uiState.selectedPrivacy,
                    options = AppSettings.PRIVACY_OPTIONS,
                    optionLabel = { it },
                    onSelect = actions.onPrivacyChange,
                    leadingIcon = {
                        Icon(
                            if (uiState.selectedPrivacy == "Private") Icons.Default.Lock else Icons.Default.Public,
                            contentDescription = null
                        )
                    },
                    colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors()
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            if (uiState.metadataIsForAnotherText) {
                Text(
                    text = "⚠️ The title and description were written for a different text than this video. " +
                        "Auto-fill them again, or check them before uploading.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
            }

            // Final Upload Button
            Button(
                onClick = actions.onUpload,
                modifier = Modifier.fillMaxWidth(),
                enabled = uiState.isYouTubeConnected && !uiState.isUploadingToYouTube && !isUploaded &&
                    uiState.youtubeTitle.isNotBlank() && uiState.youtubeDescription.isNotBlank() && hasVideo,
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

            (uploadedUrl ?: uiState.lastUploadUrl)?.let { url ->
                UploadedVideo(url = url, isThisVideo = isUploaded)
            }
        }
    }
}

/** Where the video that was uploaded last is watched. */
@Composable
private fun UploadedVideo(url: String, isThisVideo: Boolean) {
    val browser = LocalUriHandler.current

    Spacer(modifier = Modifier.height(8.dp))
    Hint(
        text = if (isThisVideo) {
            "✅ This video is on YouTube. Render or choose another one to upload."
        } else {
            "✅ The last upload:"
        },
        modifier = Modifier.fillMaxWidth()
    )
    TextButton(onClick = { browser.openUri(url) }) {
        Text("🔗 $url")
    }
}

@Composable
private fun ConnectionButtons(uiState: MainUiState, actions: YouTubeActions) {
    if (!uiState.isYouTubeConnected) {
        OutlinedButton(
            onClick = actions.onConnect,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Connect to YouTube")
        }
        return
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        // Tapping the button signs in again, which is how another account is chosen
        Button(
            onClick = actions.onConnect,
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

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // Show channel selection if there are multiple channels
            if (uiState.youtubeChannels.size > 1) {
                TextButton(onClick = actions.onShowChannelPicker) {
                    Text("Switch Channel (${uiState.youtubeChannels.size} available)")
                }
            } else {
                Spacer(Modifier.width(1.dp))
            }
            TextButton(
                onClick = actions.onDisconnect,
                enabled = !uiState.isUploadingToYouTube
            ) {
                Text("Disconnect")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProviderChip(
    label: String,
    provider: String,
    selectedProvider: String,
    onSelect: (String) -> Unit
) {
    val selected = provider == selectedProvider
    FilterChip(
        selected = selected,
        onClick = { onSelect(provider) },
        label = { Text(label) },
        leadingIcon = if (selected) {
            { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
        } else null
    )
}

@Composable
private fun ChannelPickerDialog(
    channels: List<YouTubeChannel>,
    selectedChannelId: String?,
    onSelect: (YouTubeChannel) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Select YouTube Channel") },
        text = {
            Column {
                channels.forEach { channel ->
                    val isSelected = channel.id == selectedChannelId
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(channel) }
                            .padding(vertical = 8.dp)
                            .let { mod ->
                                if (isSelected)
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
                            if (isSelected) Icons.Default.RadioButtonChecked
                            else Icons.Default.RadioButtonUnchecked,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = channel.title,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Done")
            }
        }
    )
}
