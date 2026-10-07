@file:Suppress("FunctionNaming") // Jetpack Compose functions use UpperCamelCase.

package dev.ipf.whitenoise.android.ui.conversation.media

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.theme.amoledSurfaceBorderStroke

private val ADD_MORE_TILE = 56.dp

/**
 * Caption and send affordance for the legacy staging route, which is the only path that still sends
 * from this screen. The redesigned preview opened from the composer shelf has no bottom bar at all.
 */
@Composable
internal fun MediaPreviewSendBar(
    initialCaption: String,
    sending: Boolean,
    sendEnabled: Boolean,
    onSend: (String) -> Unit,
) {
    // Seeded from the composer draft so text typed before attaching carries
    // into the caption instead of silently waiting behind the send.
    var caption by rememberSaveable { mutableStateOf(initialCaption) }
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .imePadding()
                .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedTextField(
            value = caption,
            onValueChange = { caption = it },
            modifier = Modifier.weight(1f),
            placeholder = { Text(stringResource(R.string.add_caption)) },
            maxLines = 4,
            enabled = !sending,
            colors = previewCaptionFieldColors(),
        )
        FloatingActionButton(
            onClick = { if (sendEnabled) onSend(caption) },
            modifier = Modifier.semantics { if (!sendEnabled) disabled() },
            shape = CircleShape,
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
        ) {
            Icon(
                painterResource(R.drawable.ic_arrow_upward),
                contentDescription = stringResource(R.string.send),
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/** Pairs native caption, cursor and outline colors with the current viewer surface. */
@Composable
private fun previewCaptionFieldColors() =
    OutlinedTextFieldDefaults.colors(
        focusedTextColor = MaterialTheme.colorScheme.onBackground,
        unfocusedTextColor = MaterialTheme.colorScheme.onBackground,
        disabledTextColor = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
        cursorColor = MaterialTheme.colorScheme.primary,
        focusedBorderColor = MaterialTheme.colorScheme.primary,
        unfocusedBorderColor = MaterialTheme.colorScheme.outline,
        focusedPlaceholderColor = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
        unfocusedPlaceholderColor = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
    )

/** Retains the native photo/document acquisition choices beside the staged send-order thumbnails. */
@Composable
internal fun AddMoreThumb(
    enabled: Boolean,
    onAddPhotos: () -> Unit,
    onAddDocuments: () -> Unit,
) {
    // Anchor a DropdownMenu to the tile so the user can add either kind to a
    // mixed shelf — the tile alone can't know which the user wants to append.
    var menuOpen by remember { mutableStateOf(false) }
    Box {
        IconButton(
            onClick = { if (enabled) menuOpen = true },
            enabled = enabled,
            modifier =
                Modifier
                    .size(ADD_MORE_TILE)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh),
        ) {
            Icon(
                painterResource(R.drawable.ic_add),
                contentDescription = stringResource(R.string.media_attachment_add_more),
                tint = MaterialTheme.colorScheme.onBackground,
            )
        }
        DropdownMenu(
            expanded = menuOpen,
            onDismissRequest = { menuOpen = false },
            shape = MenuDefaults.shape,
            border = amoledSurfaceBorderStroke(),
        ) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.attach_photo_library)) },
                onClick = {
                    menuOpen = false
                    onAddPhotos()
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.attach_document)) },
                onClick = {
                    menuOpen = false
                    onAddDocuments()
                },
            )
        }
    }
}
