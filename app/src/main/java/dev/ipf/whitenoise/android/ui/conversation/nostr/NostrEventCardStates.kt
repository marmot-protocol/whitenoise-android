@file:Suppress("FunctionNaming")

package dev.ipf.whitenoise.android.ui.conversation.nostr

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R

@Composable
internal fun EventCardActions(
    contentColor: Color,
    copyDescription: String,
    openDescription: String,
    onCopy: () -> Unit,
    onOpen: () -> Unit,
    openIcon: ImageVector = Icons.AutoMirrored.Outlined.OpenInNew,
) {
    Row {
        IconButton(onClick = onCopy, modifier = Modifier.size(48.dp)) {
            Icon(
                Icons.Outlined.ContentCopy,
                contentDescription = copyDescription,
                modifier = Modifier.size(18.dp),
                tint = contentColor.copy(alpha = 0.8f),
            )
        }
        IconButton(onClick = onOpen, modifier = Modifier.size(48.dp)) {
            Icon(
                openIcon,
                contentDescription = openDescription,
                modifier = Modifier.size(18.dp),
                tint = contentColor,
            )
        }
    }
}

@Composable
internal fun EventReferenceLabel(
    referenceLabel: String?,
    contentColor: Color,
) {
    referenceLabel?.takeIf(String::isNotBlank)?.let { reference ->
        Spacer(Modifier.height(3.dp))
        Text(
            text = reference,
            style = MaterialTheme.typography.labelSmall,
            color = contentColor.copy(alpha = 0.52f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
internal fun EventCardStatus(
    text: String,
    referenceLabel: String?,
    contentColor: Color,
    progress: Boolean,
    copyDescription: String,
    openDescription: String,
    onCopy: () -> Unit,
    onOpen: () -> Unit,
) {
    Column(modifier = Modifier.padding(start = 10.dp, top = 4.dp, end = 4.dp, bottom = 6.dp)) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (progress) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    color = contentColor,
                    strokeWidth = 2.dp,
                )
            }
            Text(
                text = text,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                color = contentColor,
            )
            EventCardActions(contentColor, copyDescription, openDescription, onCopy, onOpen)
        }
        EventReferenceLabel(referenceLabel, contentColor)
    }
}

@Composable
internal fun EventCardFailure(
    text: String,
    referenceLabel: String?,
    contentColor: Color,
    copyDescription: String,
    openDescription: String,
    onRetry: () -> Unit,
    onCopy: () -> Unit,
    onOpen: () -> Unit,
) {
    Column(
        modifier = Modifier.padding(start = 8.dp, top = 4.dp, end = 4.dp, bottom = 4.dp),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Outlined.ErrorOutline,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = contentColor,
            )
            Text(
                text = text,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                color = contentColor,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            IconButton(
                onClick = onRetry,
                modifier = Modifier.size(48.dp),
            ) {
                Icon(
                    Icons.Outlined.Refresh,
                    contentDescription = stringResource(R.string.retry),
                    modifier = Modifier.size(18.dp),
                    tint = contentColor,
                )
            }
        }
        EventReferenceLabel(referenceLabel, contentColor)
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            EventCardActions(contentColor, copyDescription, openDescription, onCopy, onOpen)
        }
    }
}
