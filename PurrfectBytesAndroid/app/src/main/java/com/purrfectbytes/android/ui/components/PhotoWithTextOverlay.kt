package com.purrfectbytes.android.ui.components

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import coil.compose.AsyncImage
import coil.compose.AsyncImagePainter
import coil.compose.rememberAsyncImagePainter
import com.purrfectbytes.android.services.RecognizedTextBlock
import java.io.InputStream
import kotlin.math.min

@Composable
fun PhotoWithTextOverlay(
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
    var displayedImageSize by remember { mutableStateOf(IntSize.Zero) }

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

    // Calculate the actual displayed image size within the view
    LaunchedEffect(imageViewSize, imageBitmap) {
        imageBitmap?.let { bitmap ->
            if (imageViewSize != IntSize.Zero) {
                val imageAspectRatio = bitmap.width.toFloat() / bitmap.height.toFloat()
                val viewAspectRatio = imageViewSize.width.toFloat() / imageViewSize.height.toFloat()

                val (displayWidth, displayHeight) = if (imageAspectRatio > viewAspectRatio) {
                    // Image is wider than view - fit by width
                    val width = imageViewSize.width
                    val height = (width / imageAspectRatio).toInt()
                    width to height
                } else {
                    // Image is taller than view - fit by height
                    val height = imageViewSize.height
                    val width = (height * imageAspectRatio).toInt()
                    width to height
                }

                displayedImageSize = IntSize(displayWidth, displayHeight)
            }
        }
    }

    Box(modifier = modifier.fillMaxWidth()) {
        // Display the image
        AsyncImage(
            model = photoUri,
            contentDescription = "Photo with text overlay",
            modifier = Modifier
                .fillMaxWidth()
                .onGloballyPositioned { coordinates ->
                    imageViewSize = coordinates.size
                },
            contentScale = ContentScale.Fit
        )

        // Overlay clickable regions for text blocks
        val bitmap = imageBitmap
        if (displayedImageSize != IntSize.Zero && bitmap != null) {
            val scaleX = displayedImageSize.width.toFloat() / bitmap.width.toFloat()
            val scaleY = displayedImageSize.height.toFloat() / bitmap.height.toFloat()

            // Calculate offset to center the image in the view
            val offsetX = (imageViewSize.width - displayedImageSize.width) / 2f
            val offsetY = (imageViewSize.height - displayedImageSize.height) / 2f

            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(with(density) { imageViewSize.height.toDp() })
            ) {
                recognizedBlocks.forEach { block ->
                    block.boundingBox?.let { rect ->
                        val scaledLeft = rect.left * scaleX + offsetX
                        val scaledTop = rect.top * scaleY + offsetY
                        val scaledWidth = rect.width() * scaleX
                        val scaledHeight = rect.height() * scaleY

                        // Draw bounding box
                        drawRect(
                            color = if (selectedBlock == block) Color.Green else Color.Cyan,
                            topLeft = Offset(scaledLeft, scaledTop),
                            size = Size(scaledWidth, scaledHeight),
                            style = Stroke(width = 2.dp.toPx())
                        )

                        // Draw semi-transparent overlay for selected block
                        if (selectedBlock == block) {
                            drawRect(
                                color = Color.Green.copy(alpha = 0.2f),
                                topLeft = Offset(scaledLeft, scaledTop),
                                size = Size(scaledWidth, scaledHeight)
                            )
                        }
                    }
                }
            }

            // Invisible clickable areas
            recognizedBlocks.forEach { block ->
                block.boundingBox?.let { rect ->
                    val scaledLeft = rect.left * scaleX + offsetX
                    val scaledTop = rect.top * scaleY + offsetY
                    val scaledWidth = rect.width() * scaleX
                    val scaledHeight = rect.height() * scaleY

                    Box(
                        modifier = Modifier
                            .offset(
                                x = with(density) { scaledLeft.toDp() },
                                y = with(density) { scaledTop.toDp() }
                            )
                            .size(
                                width = with(density) { scaledWidth.toDp() },
                                height = with(density) { scaledHeight.toDp() }
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

    // Show selected text at the bottom
    selectedBlock?.let { block ->
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer
            )
        ) {
            Column(
                modifier = Modifier.padding(12.dp)
            ) {
                Text(
                    text = "Selected Text:",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Text(
                    text = block.text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.padding(top = 4.dp)
                )

                Button(
                    onClick = { onTextClick(block.text) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                ) {
                    Text("Use This Text")
                }
            }
        }
    }
}