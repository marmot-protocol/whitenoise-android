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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R

internal const val COMPOSER_EDIT_CANCEL_TAG = "conversation.composer.edit.cancel"
internal const val COMPOSER_EDIT_PENCIL_TAG = "conversation.composer.edit.pencil"
private val PencilSize = 16.dp

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
        // Weighted children measure last, so a narrow pill drops the pencil and label before Cancel.
        Row(
            modifier = Modifier.weight(1f, fill = false),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painterResource(R.drawable.ic_edit),
                contentDescription = null,
                modifier = Modifier.testTag(COMPOSER_EDIT_PENCIL_TAG).wholeOrNothing(PencilSize).size(PencilSize),
                tint = color,
            )
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
        }
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

/** Lays out nothing when less than [size] is available, so a squeezed icon never draws as a sliver. */
private fun Modifier.wholeOrNothing(size: Dp): Modifier =
    layout { measurable, constraints ->
        if (constraints.maxWidth < size.roundToPx()) {
            layout(0, 0) {}
        } else {
            val placeable = measurable.measure(constraints)
            layout(placeable.width, placeable.height) { placeable.place(0, 0) }
        }
    }
