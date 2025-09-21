package com.purrfectbytes.android.ui.components

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import coil.compose.AsyncImage
import com.purrfectbytes.android.services.RecognizedTextBlock
import java.io.InputStream
import kotlin.math.min

data class ImageTransformation(
    val scaleX: Float,
    val scaleY: Float,
    val offsetX: Float,
    val offsetY: Float,
    val displayWidth: Int,
    val displayHeight: Int
)

@Composable
fun PrecisePhotoTextOverlay(
    photoUri: Uri,
    recognizedBlocks: List<RecognizedTextBlock>,
    onTextClick: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    var imageViewSize by remember { mutableStateOf(IntSize.Zero) }
    var selectedBlock by remember { mutableStateOf<RecognizedTextBlock?>(null) }
    var imageBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var transformation by remember { mutableStateOf<ImageTransformation?>(null) }
    var showDebug by remember { mutableStateOf(false) }

    // Load the image bitmap to get original dimensions
    LaunchedEffect(photoUri) {
        try {
            val inputStream: InputStream? = context.contentResolver.openInputStream(photoUri)
            imageBitmap = BitmapFactory.decodeStream(inputStream)
            inputStream?.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // Calculate precise transformation when both view size and bitmap are available
    LaunchedEffect(imageViewSize, imageBitmap) {
        val bitmap = imageBitmap
        if (imageViewSize != IntSize.Zero && bitmap != null) {
            // Calculate how ContentScale.Fit will display the image
            val imageAspectRatio = bitmap.width.toFloat() / bitmap.height.toFloat()
            val viewAspectRatio = imageViewSize.width.toFloat() / imageViewSize.height.toFloat()

            val (displayWidth, displayHeight, scale) = if (imageAspectRatio > viewAspectRatio) {
                // Image is wider - constrained by width
                val displayWidth = imageViewSize.width
                val displayHeight = (displayWidth / imageAspectRatio).toInt()
                val scale = displayWidth.toFloat() / bitmap.width.toFloat()
                Triple(displayWidth, displayHeight, scale)
            } else {
                // Image is taller - constrained by height
                val displayHeight = imageViewSize.height
                val displayWidth = (displayHeight * imageAspectRatio).toInt()
                val scale = displayHeight.toFloat() / bitmap.height.toFloat()
                Triple(displayWidth, displayHeight, scale)
            }

            val offsetX = (imageViewSize.width - displayWidth) / 2f
            val offsetY = (imageViewSize.height - displayHeight) / 2f

            transformation = ImageTransformation(
                scaleX = scale,
                scaleY = scale,
                offsetX = offsetX,
                offsetY = offsetY,
                displayWidth = displayWidth,
                displayHeight = displayHeight
            )
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
                text = if (recognizedBlocks.isNotEmpty()) "${recognizedBlocks.size} text blocks found" else "No text detected",
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
                val bitmap = imageBitmap
                if (bitmap != null) {
                    Canvas(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(with(density) { imageViewSize.height.toDp() })
                    ) {
                        recognizedBlocks.forEach { block ->
                            block.boundingBox?.let { rect ->
                                // Transform coordinates from original image to display coordinates
                                val left = rect.left * transform.scaleX + transform.offsetX
                                val top = rect.top * transform.scaleY + transform.offsetY
                                val width = rect.width() * transform.scaleX
                                val height = rect.height() * transform.scaleY

                                // Draw bounding box with different colors for better visibility
                                val color = when {
                                    selectedBlock == block -> Color.Green
                                    block.detectedLanguage?.contains("japanese") == true -> Color.Red
                                    block.detectedLanguage?.contains("chinese") == true -> Color.Blue
                                    block.detectedLanguage?.contains("korean") == true -> Color.Magenta
                                    else -> Color.Cyan
                                }

                                drawRect(
                                    color = color,
                                    topLeft = Offset(left, top),
                                    size = Size(width, height),
                                    style = Stroke(width = 2.dp.toPx())
                                )

                                // Draw semi-transparent overlay for selected block
                                if (selectedBlock == block) {
                                    drawRect(
                                        color = Color.Green.copy(alpha = 0.2f),
                                        topLeft = Offset(left, top),
                                        size = Size(width, height)
                                    )
                                }

                                // Debug mode: show coordinates
                                if (showDebug) {
                                    drawRect(
                                        color = Color.White.copy(alpha = 0.8f),
                                        topLeft = Offset(left, top - 20.dp.toPx()),
                                        size = Size(100.dp.toPx(), 15.dp.toPx())
                                    )
                                }
                            }
                        }

                        // Debug info overlay
                        if (showDebug) {
                            drawRect(
                                color = Color.Black.copy(alpha = 0.7f),
                                topLeft = Offset(10.dp.toPx(), 10.dp.toPx()),
                                size = Size(250.dp.toPx(), 80.dp.toPx())
                            )
                        }
                    }

                    // Invisible clickable areas with precise coordinates
                    recognizedBlocks.forEach { block ->
                        block.boundingBox?.let { rect ->
                            val left = rect.left * transform.scaleX + transform.offsetX
                            val top = rect.top * transform.scaleY + transform.offsetY
                            val width = rect.width() * transform.scaleX
                            val height = rect.height() * transform.scaleY

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
                        Text(
                            text = "View: ${imageViewSize.width}x${imageViewSize.height}",
                            color = Color.White,
                            style = MaterialTheme.typography.bodySmall
                        )
                        Text(
                            text = "Display: ${transform.displayWidth}x${transform.displayHeight}",
                            color = Color.White,
                            style = MaterialTheme.typography.bodySmall
                        )
                        Text(
                            text = "Scale: ${String.format("%.3f", transform.scaleX)}x, ${String.format("%.3f", transform.scaleY)}y",
                            color = Color.White,
                            style = MaterialTheme.typography.bodySmall
                        )
                        Text(
                            text = "Offset: ${String.format("%.1f", transform.offsetX)}, ${String.format("%.1f", transform.offsetY)}",
                            color = Color.White,
                            style = MaterialTheme.typography.bodySmall
                        )
                        imageBitmap?.let { bitmap ->
                            Text(
                                text = "Original: ${bitmap.width}x${bitmap.height}",
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