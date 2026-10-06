package com.purrfectbytes.android.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

private const val SUCCESS_SHOWN_MILLIS = 4000L

/**
 * Messages that stay in view wherever the page is scrolled to.
 *
 * A success message leaves by itself after a few seconds. An error stays until it is
 * dismissed or the next action starts, so it cannot be missed.
 */
@Composable
fun MessageBanner(
    errorMessage: String?,
    successMessage: String?,
    onDismissError: () -> Unit,
    onDismissSuccess: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (errorMessage == null && successMessage == null) return

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        errorMessage?.let { error ->
            Message(
                text = "❌ $error",
                background = MaterialTheme.colorScheme.errorContainer,
                foreground = MaterialTheme.colorScheme.onErrorContainer,
                dismissLabel = "Dismiss error",
                onDismiss = onDismissError
            )
        }

        successMessage?.let { success ->
            Message(
                text = "✅ $success",
                background = Color(0xFFE8F5E9),
                foreground = Color(0xFF2E7D32),
                dismissLabel = "Dismiss message",
                onDismiss = onDismissSuccess
            )

            LaunchedEffect(success) {
                delay(SUCCESS_SHOWN_MILLIS)
                onDismissSuccess()
            }
        }
    }
}

@Composable
private fun Message(
    text: String,
    background: Color,
    foreground: Color,
    dismissLabel: String,
    onDismiss: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = background),
        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp)
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, top = 4.dp, bottom = 4.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = text,
                modifier = Modifier
                    .weight(1f)
                    .padding(vertical = 8.dp),
                color = foreground,
                style = MaterialTheme.typography.bodyMedium
            )
            IconButton(onClick = onDismiss) {
                Icon(Icons.Default.Close, contentDescription = dismissLabel, tint = foreground)
            }
        }
    }
}
