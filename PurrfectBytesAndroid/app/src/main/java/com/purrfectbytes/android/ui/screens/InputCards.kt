package com.purrfectbytes.android.ui.screens

import android.net.Uri
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.purrfectbytes.android.BuildConfig
import com.purrfectbytes.android.services.RecognitionScript
import com.purrfectbytes.android.services.RecognizedText
import com.purrfectbytes.android.ui.components.PrecisePhotoTextOverlay
import com.purrfectbytes.android.viewmodels.OcrMode

/** The cards of the main screen that take input: photo, text and language. */

@Composable
internal fun HeaderCard(
    onOpenGallery: () -> Unit,
    onOpenCamera: () -> Unit
) {
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
                    text = "Text to Speech Converter (v${BuildConfig.VERSION_NAME})",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }

            Row {
                IconButton(
                    onClick = onOpenGallery,
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
                    onClick = onOpenCamera,
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
}

/** A read-only field that opens a list to choose from. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun <T> DropdownField(
    value: String,
    options: List<T>,
    optionLabel: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leadingIcon: @Composable (() -> Unit)? = null,
    colors: TextFieldColors = OutlinedTextFieldDefaults.colors(),
    /** Shown in the list when there is nothing to choose from. */
    emptyLabel: String? = null
) {
    var expanded by remember { mutableStateOf(false) }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = !expanded },
        modifier = modifier.fillMaxWidth()
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = { },
            readOnly = true,
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(),
            enabled = enabled,
            leadingIcon = leadingIcon,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            colors = colors
        )

        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            if (options.isEmpty() && emptyLabel != null) {
                DropdownMenuItem(
                    text = { Text(emptyLabel) },
                    onClick = { expanded = false },
                    enabled = false
                )
            }
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(optionLabel(option)) },
                    onClick = {
                        onSelect(option)
                        expanded = false
                    }
                )
            }
        }
    }
}

/** A switch with its label; a tap anywhere on the row flips it. */
@Composable
internal fun SwitchRow(
    label: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = if (enabled) Color.Unspecified else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
            modifier = Modifier.weight(1f)
        )
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

/** A few words of explanation under what they explain. */
@Composable
internal fun Hint(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier
    )
}

@Composable
internal fun OcrModeCard(
    mode: OcrMode,
    enabled: Boolean,
    onModeChange: (OcrMode) -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Text Extraction Mode",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium
            )
            Spacer(modifier = Modifier.height(8.dp))

            DropdownField(
                value = mode.displayName,
                options = OcrMode.values().toList(),
                optionLabel = { it.displayName },
                onSelect = onModeChange,
                enabled = enabled
            )
        }
    }
}

private val SCRIPT_CHIPS = listOf(
    RecognitionScript.AUTO to "Auto",
    RecognitionScript.LATIN to "English",
    RecognitionScript.JAPANESE to "日本語",
    RecognitionScript.CHINESE to "中文",
    RecognitionScript.KOREAN to "한글",
    RecognitionScript.DEVANAGARI to "हिन्दी"
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CapturedPhotoCard(
    photoUri: Uri,
    recognized: RecognizedText?,
    isAnalyzing: Boolean,
    selectedScript: RecognitionScript,
    onTextClick: (String) -> Unit,
    onScriptSelected: (RecognitionScript) -> Unit,
    onRemove: () -> Unit
) {
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
                    if (!recognized?.blocks.isNullOrEmpty()) {
                        Text(
                            text = "Tap on text to select",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.7f)
                        )
                    }
                }
                IconButton(
                    onClick = onRemove,
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

            if (isAnalyzing) {
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
                    photoUri = photoUri,
                    recognized = recognized,
                    onTextClick = onTextClick
                )
            }

            // Language selector for re-analysis
            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Text Language:",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer
                )

                // Script selection chips; they scroll sideways when the screen is narrow
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    SCRIPT_CHIPS.forEach { (script, label) ->
                        FilterChip(
                            selected = selectedScript == script,
                            onClick = { onScriptSelected(script) },
                            label = { Text(label, style = MaterialTheme.typography.labelSmall) },
                            modifier = Modifier.height(28.dp)
                        )
                    }
                }
            }
        }
    }
}

/**
 * The text, and below it the text that is spoken in its place where the engine misreads
 * a word: the video still shows the first.
 */
@Composable
internal fun TextInputCard(
    text: String,
    enabled: Boolean,
    onTextChange: (String) -> Unit,
    useVoicedText: Boolean = false,
    voicedText: String = "",
    onVoicedTextUseChange: (Boolean) -> Unit = {},
    onVoicedTextChange: (String) -> Unit = {}
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Enter your text:",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium
            )
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = text,
                onValueChange = onTextChange,
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Type or paste your text here...") },
                minLines = 4,
                maxLines = 8,
                enabled = enabled
            )

            Spacer(modifier = Modifier.height(12.dp))
            SwitchRow(
                label = "Pronunciation override",
                checked = useVoicedText,
                enabled = enabled,
                onCheckedChange = onVoicedTextUseChange
            )

            if (useVoicedText) {
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = voicedText,
                    onValueChange = onVoicedTextChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Text to voice instead") },
                    placeholder = { Text("The text above with the misread words respelled, e.g. 行った → おこなった") },
                    minLines = 2,
                    maxLines = 8,
                    enabled = enabled
                )
                Spacer(modifier = Modifier.height(4.dp))
                Hint(
                    "This text is spoken while the video still shows the text above. The highlight " +
                        "follows the words that are unchanged. In conversation mode, keep the same " +
                        "number of lines."
                )
            }
        }
    }
}

@Composable
internal fun LanguageCard(
    languages: List<Pair<String, String>>,
    selectedLanguage: String,
    notice: String?,
    noticeIsError: Boolean,
    isDetecting: Boolean,
    canDetect: Boolean,
    enabled: Boolean,
    onDetect: () -> Unit,
    onLanguageChange: (String) -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Language:",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium
            )
            Spacer(modifier = Modifier.height(8.dp))

            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onDetect,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF198754)),
                    enabled = enabled && !isDetecting && canDetect,
                    shape = MaterialTheme.shapes.small
                ) {
                    if (isDetecting) {
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

                DropdownField(
                    value = languages.find { it.first == selectedLanguage }?.second ?: "English",
                    options = languages,
                    optionLabel = { it.second },
                    onSelect = { onLanguageChange(it.first) },
                    enabled = enabled
                )

                notice?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (noticeIsError) Color(0xFFE74C3C) else Color(0xFF27AE60)
                    )
                }
            }
        }
    }
}

/** [progress] says what a render that is running is doing. */
@Composable
internal fun ActionButtons(
    canStart: Boolean,
    isGeneratingPreview: Boolean,
    isConvertingVideo: Boolean,
    onPreview: () -> Unit,
    onRender: () -> Unit,
    progress: String? = null
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        ActionButtonRow(canStart, isGeneratingPreview, isConvertingVideo, onPreview, onRender)

        if (isConvertingVideo) {
            Spacer(modifier = Modifier.height(4.dp))
            RenderProgress(progress)
        }
    }
}

/** What the render is doing and for how long it has been running. */
@Composable
private fun RenderProgress(progress: String?) {
    var seconds by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        val startedAt = System.currentTimeMillis()
        while (true) {
            kotlinx.coroutines.delay(1000)
            seconds = ((System.currentTimeMillis() - startedAt) / 1000).toInt()
        }
    }

    Hint(
        text = "⏳ ${progress ?: "Starting"}… ${seconds}s elapsed",
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun ActionButtonRow(
    canStart: Boolean,
    isGeneratingPreview: Boolean,
    isConvertingVideo: Boolean,
    onPreview: () -> Unit,
    onRender: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Button(
            onClick = onPreview,
            modifier = Modifier.weight(1f),
            enabled = canStart && !isGeneratingPreview,
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
        ) {
            if (isGeneratingPreview) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    color = MaterialTheme.colorScheme.onSecondary,
                    strokeWidth = 2.dp
                )
            } else {
                Icon(Icons.Default.Image, contentDescription = null)
            }
            Spacer(modifier = Modifier.width(4.dp))
            Text("Preview")
        }

        Button(
            onClick = onRender,
            modifier = Modifier.weight(1f),
            enabled = canStart,
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
        ) {
            if (isConvertingVideo) {
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
}
