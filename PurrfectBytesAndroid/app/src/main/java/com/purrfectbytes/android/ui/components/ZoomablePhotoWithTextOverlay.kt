package com.purrfectbytes.android.ui.components

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.purrfectbytes.android.services.RecognizedTextBlock
import java.io.InputStream

@Composable
fun ZoomablePhotoWithTextOverlay(
    photoUri: Uri,
    recognizedBlocks: List<RecognizedTextBlock>,
    onTextClick: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val density = LocalDensity.current

    var scale by remember { mutableStateOf(1f) }
    var offsetX by remember { mutableStateOf(0f) }
    var offsetY by remember { mutableStateOf(0f) }

    var imageViewSize by remember { mutableStateOf(IntSize.Zero) }
    var selectedBlock by remember { mutableStateOf<RecognizedTextBlock?>(null) }
    var imageBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var displayedImageSize by remember { mutableStateOf(IntSize.Zero) }

    // Load the image bitmap
    LaunchedEffect(photoUri) {
        try {
            val inputStream: InputStream? = context.contentResolver.openInputStream(photoUri)
            imageBitmap = BitmapFactory.decodeStream(inputStream)
            inputStream?.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // Calculate displayed image size
    LaunchedEffect(imageViewSize, imageBitmap) {
        imageBitmap?.let { bitmap ->
            if (imageViewSize != IntSize.Zero) {
                val imageAspectRatio = bitmap.width.toFloat() / bitmap.height.toFloat()
                val viewAspectRatio = imageViewSize.width.toFloat() / imageViewSize.height.toFloat()

                val (displayWidth, displayHeight) = if (imageAspectRatio > viewAspectRatio) {
                    val width = imageViewSize.width
                    val height = (width / imageAspectRatio).toInt()
                    width to height
                } else {
                    val height = imageViewSize.height
                    val width = (height * imageAspectRatio).toInt()
                    width to height
                }

                displayedImageSize = IntSize(displayWidth, displayHeight)
            }
        }
    }

    Column(modifier = modifier.fillMaxWidth()) {
        // Zoom controls
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            Button(
                onClick = {
                    scale = (scale * 1.2f).coerceIn(0.5f, 3f)
                },
                modifier = Modifier.weight(1f).padding(horizontal = 4.dp)
            ) {
                Text("Zoom In")
            }

            Button(
                onClick = { scale = 1f; offsetX = 0f; offsetY = 0f },
                modifier = Modifier.weight(1f).padding(horizontal = 4.dp)
            ) {
                Text("Reset")
            }

            Button(
                onClick = {
                    scale = (scale * 0.8f).coerceIn(0.5f, 3f)
                },
                modifier = Modifier.weight(1f).padding(horizontal = 4.dp)
            ) {
                Text("Zoom Out")
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        scale = (scale * zoom).coerceIn(0.5f, 3f)
                        val maxX = (size.width * (scale - 1) / 2)
                        val maxY = (size.height * (scale - 1) / 2)
                        offsetX = (offsetX + pan.x).coerceIn(-maxX, maxX)
                        offsetY = (offsetY + pan.y).coerceIn(-maxY, maxY)
                    }
                }
        ) {
            // Display the image with zoom and pan
            AsyncImage(
                model = photoUri,
                contentDescription = "Zoomable photo",
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer(
                        scaleX = scale,
                        scaleY = scale,
                        translationX = offsetX,
                        translationY = offsetY
                    )
                    .onGloballyPositioned { coordinates ->
                        imageViewSize = coordinates.size
                    },
                contentScale = ContentScale.Fit
            )

            // Overlay for text blocks
            val bitmap = imageBitmap
            if (displayedImageSize != IntSize.Zero && bitmap != null) {
                val baseScaleX = displayedImageSize.width.toFloat() / bitmap.width.toFloat()
                val baseScaleY = displayedImageSize.height.toFloat() / bitmap.height.toFloat()
                val baseOffsetX = (imageViewSize.width - displayedImageSize.width) / 2f
                val baseOffsetY = (imageViewSize.height - displayedImageSize.height) / 2f

                Canvas(
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer(
                            scaleX = scale,
                            scaleY = scale,
                            translationX = offsetX,
                            translationY = offsetY
                        )
                ) {
                    recognizedBlocks.forEach { block ->
                        block.boundingBox?.let { rect ->
                            val left = rect.left * baseScaleX + baseOffsetX
                            val top = rect.top * baseScaleY + baseOffsetY
                            val width = rect.width() * baseScaleX
                            val height = rect.height() * baseScaleY

                            // Draw thicker, more visible boxes
                            drawRect(
                                color = if (selectedBlock == block) Color.Green else Color.Magenta,
                                topLeft = Offset(left, top),
                                size = Size(width, height),
                                style = Stroke(width = 3.dp.toPx() / scale)
                            )

                            if (selectedBlock == block) {
                                drawRect(
                                    color = Color.Green.copy(alpha = 0.3f),
                                    topLeft = Offset(left, top),
                                    size = Size(width, height)
                                )
                            }
                        }
                    }
                }

                // Clickable areas (adjusted for zoom/pan)
                recognizedBlocks.forEach { block ->
                    block.boundingBox?.let { rect ->
                        val left = (rect.left * baseScaleX + baseOffsetX) * scale + offsetX
                        val top = (rect.top * baseScaleY + baseOffsetY) * scale + offsetY
                        val width = rect.width() * baseScaleX * scale
                        val height = rect.height() * baseScaleY * scale

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

        // Selected text display
        selectedBlock?.let { block ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Selected Text:",
                            style = MaterialTheme.typography.labelMedium
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
                        modifier = Modifier.padding(vertical = 4.dp)
                    )
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