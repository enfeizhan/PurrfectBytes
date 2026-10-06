package com.purrfectbytes.android.ui.components

import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.purrfectbytes.android.services.RecognitionScript
import com.purrfectbytes.android.services.RecognizedText
import com.purrfectbytes.android.services.RecognizedTextBlock
import java.util.Locale

/** How a photo is placed inside the view that shows it. */
data class ImageTransformation(
    val scale: Float,
    val offsetX: Float,
    val offsetY: Float,
    val displayWidth: Int,
    val displayHeight: Int
)

/**
 * Where ContentScale.Fit puts a photo of [imageWidth] x [imageHeight] pixels inside a
 * view of [viewWidth] x [viewHeight]: as large as fits, centered. Null when a size is missing.
 */
fun fitTransformation(viewWidth: Int, viewHeight: Int, imageWidth: Int, imageHeight: Int): ImageTransformation? {
    if (viewWidth <= 0 || viewHeight <= 0 || imageWidth <= 0 || imageHeight <= 0) return null

    val scale = minOf(viewWidth.toFloat() / imageWidth, viewHeight.toFloat() / imageHeight)
    val displayWidth = (imageWidth * scale).toInt()
    val displayHeight = (imageHeight * scale).toInt()
    return ImageTransformation(
        scale = scale,
        offsetX = (viewWidth - displayWidth) / 2f,
        offsetY = (viewHeight - displayHeight) / 2f,
        displayWidth = displayWidth,
        displayHeight = displayHeight
    )
}

/**
 * A photo with a box around every block of text found in it. Tapping a box selects its text.
 *
 * The boxes are placed with the size of the photo as the text recognizer saw it
 * (upright, whatever way the camera was held), so they line up with what is shown.
 */
@Composable
fun PrecisePhotoTextOverlay(
    photoUri: Uri,
    recognized: RecognizedText?,
    onTextClick: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val blocks = recognized?.blocks.orEmpty()
    var imageViewSize by remember { mutableStateOf(IntSize.Zero) }
    var selectedBlock by remember(recognized) { mutableStateOf<RecognizedTextBlock?>(null) }
    var showDebug by remember { mutableStateOf(false) }

    val transformation = remember(imageViewSize, recognized) {
        recognized?.let {
            fitTransformation(imageViewSize.width, imageViewSize.height, it.imageWidth, it.imageHeight)
        }
    }

    Column(modifier = modifier.fillMaxWidth()) {
        // Debug toggle
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = if (blocks.isNotEmpty()) "${blocks.size} text blocks found" else "No text detected",
                style = MaterialTheme.typography.bodySmall
            )

            IconButton(
                onClick = { showDebug = !showDebug }
            ) {
                Icon(
                    Icons.Default.BugReport,
                    contentDescription = "Toggle debug",
                    tint = if (showDebug) Color.Red else MaterialTheme.colorScheme.onSurface
                )
            }
        }

        Box(modifier = Modifier.fillMaxWidth()) {
            // Display the image
            AsyncImage(
                model = photoUri,
                contentDescription = "Photo with precise text overlay",
                modifier = Modifier
                    .fillMaxWidth()
                    .onGloballyPositioned { coordinates ->
                        imageViewSize = coordinates.size
                    },
                contentScale = ContentScale.Fit
            )

            // Overlay clickable regions for text blocks
            transformation?.let { transform ->
                Canvas(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(with(density) { imageViewSize.height.toDp() })
                ) {
                    blocks.forEach { block ->
                        block.boundingBox?.let { rect ->
                            // Transform coordinates from original image to display coordinates
                            val topLeft = Offset(
                                rect.left * transform.scale + transform.offsetX,
                                rect.top * transform.scale + transform.offsetY
                            )
                            val size = Size(rect.width() * transform.scale, rect.height() * transform.scale)

                            // Draw bounding box with different colors for better visibility
                            val color = when {
                                selectedBlock == block -> Color.Green
                                block.script == RecognitionScript.JAPANESE -> Color.Red
                                block.script == RecognitionScript.CHINESE -> Color.Blue
                                block.script == RecognitionScript.KOREAN -> Color.Magenta
                                else -> Color.Cyan
                            }

                            drawRect(
                                color = color,
                                topLeft = topLeft,
                                size = size,
                                style = Stroke(width = 2.dp.toPx())
                            )

                            // Draw semi-transparent overlay for selected block
                            if (selectedBlock == block) {
                                drawRect(
                                    color = Color.Green.copy(alpha = 0.2f),
                                    topLeft = topLeft,
                                    size = size
                                )
                            }
                        }
                    }
                }

                // Invisible clickable areas with precise coordinates
                blocks.forEach { block ->
                    block.boundingBox?.let { rect ->
                        val left = rect.left * transform.scale + transform.offsetX
                        val top = rect.top * transform.scale + transform.offsetY
                        val width = rect.width() * transform.scale
                        val height = rect.height() * transform.scale

                        Box(
                            modifier = Modifier
                                .offset(
                                    x = with(density) { left.toDp() },
                                    y = with(density) { top.toDp() }
                                )
                                .size(
                                    width = with(density) { width.toDp() },
                                    height = with(density) { height.toDp() }
                                )
                                .clickable {
                                    selectedBlock = block
                                    onTextClick(block.text)
                                }
                        )
                    }
                }
            }
        }

        // Debug information
        if (showDebug) {
            transformation?.let { transform ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(8.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.Black.copy(alpha = 0.8f))
                ) {
                    Column(modifier = Modifier.padding(8.dp)) {
                        Text(
                            text = "Debug Info:",
                            color = Color.White,
                            style = MaterialTheme.typography.labelMedium
                        )
                        listOf(
                            "View: ${imageViewSize.width}x${imageViewSize.height}",
                            "Display: ${transform.displayWidth}x${transform.displayHeight}",
                            "Scale: ${String.format(Locale.US, "%.3f", transform.scale)}",
                            "Offset: ${String.format(Locale.US, "%.1f", transform.offsetX)}, " +
                                String.format(Locale.US, "%.1f", transform.offsetY),
                            "Photo: ${recognized?.imageWidth}x${recognized?.imageHeight}"
                        ).forEach { line ->
                            Text(
                                text = line,
                                color = Color.White,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            }
        }

        // Show selected text at the bottom
        selectedBlock?.let { block ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            ) {
                Column(
                    modifier = Modifier.padding(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Selected Text:",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        block.detectedLanguage?.let { lang ->
                            Text(
                                text = "($lang)",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                            )
                        }
                    }
                    Text(
                        text = block.text,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(vertical = 4.dp)
                    )

                    // Show bounding box info in debug mode
                    if (showDebug) {
                        block.boundingBox?.let { rect ->
                            Text(
                                text = "Box: (${rect.left}, ${rect.top}) ${rect.width()}x${rect.height()}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                            )
                        }
                    }

                    Button(
                        onClick = { onTextClick(block.text) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Use This Text")
                    }
                }
            }
        }
    }
}
