package dev.ipf.whitenoise.android.ui.conversation.media

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material.icons.outlined.OpenInFull
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R

/** Load failure state of the viewer with a retry action. */
@Composable
@Suppress("FunctionNaming") // Jetpack Compose functions use UpperCamelCase.
internal fun MediaViewerLoadFailed(
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    contentColor: Color = MaterialTheme.colorScheme.onBackground,
) {
    Column(
        modifier = modifier.padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            Icons.Default.BrokenImage,
            contentDescription = null,
            tint = contentColor,
            modifier = Modifier.size(48.dp),
        )
        Text(
            stringResource(R.string.media_couldnt_open),
            color = contentColor,
        )
        TextButton(onClick = onRetry) {
            Text(stringResource(R.string.media_tap_to_retry), color = contentColor)
        }
    }
}

/**
 * Viewer state for a verified image whose plaintext exceeds the presentation budget. Unlike
 * [MediaViewerLoadFailed] it offers no Retry, because another transfer cannot make the image smaller, the
 * chrome's Save and Share remain the way to view it with another app.
 */
@Composable
@Suppress("FunctionNaming") // Jetpack Compose functions use UpperCamelCase.
internal fun MediaViewerTooLargeToPreview(
    modifier: Modifier = Modifier,
    contentColor: Color = MaterialTheme.colorScheme.onBackground,
) {
    Column(
        modifier = modifier.padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            Icons.Outlined.OpenInFull,
            contentDescription = null,
            tint = contentColor,
            modifier = Modifier.size(48.dp),
        )
        Text(
            stringResource(R.string.media_too_large_to_preview),
            color = contentColor,
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
        Text(
            stringResource(R.string.media_too_large_to_preview_hint),
            color = contentColor,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
    }
}
