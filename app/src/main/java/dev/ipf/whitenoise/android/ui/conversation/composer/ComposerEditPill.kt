package dev.ipf.whitenoise.android.ui.conversation.composer

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R

internal const val COMPOSER_EDIT_CANCEL_TAG = "conversation.composer.edit.cancel"

/** Quiet "Edit message" pill that sits beside Send, as tall as the Send disc, with its cancel X. */
@Composable
@Suppress("FunctionNaming")
internal fun ComposerEditPill(
    onCancelEdit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier =
            modifier
                .heightIn(min = 32.dp)
                .clip(CircleShape)
                // A faint tint over the composer surface keeps Send the strongest control in the row.
                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f))
                .padding(start = 12.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(16.dp), tint = color)
        Spacer(Modifier.width(6.dp))
        Text(
            stringResource(R.string.edit_message),
            modifier = Modifier.weight(1f, fill = false),
            style = MaterialTheme.typography.labelLarge,
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.width(2.dp))
        val description = stringResource(R.string.cancel_edit)
        // Compose extends this small target to the 48dp minimum for touch without growing the pill.
        Box(
            modifier =
                Modifier
                    .size(24.dp)
                    .clip(CircleShape)
                    .clickable(role = Role.Button, onClick = onCancelEdit)
                    .semantics { contentDescription = description }
                    .testTag(COMPOSER_EDIT_CANCEL_TAG),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_close),
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = color,
            )
        }
    }
}
