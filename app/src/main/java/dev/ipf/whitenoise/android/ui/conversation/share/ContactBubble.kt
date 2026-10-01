package dev.ipf.whitenoise.android.ui.conversation.share

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R

/**
 * Contact-card bubble drawn from the share caption (name + phone), so no vCard
 * blob fetch is needed to render it. The portable `.vcf` still rides the
 * message as a saveable attachment for any client. Actions request that exact
 * encrypted attachment only after the reader taps; the caption stays visible meanwhile.
 */
@Composable
internal fun ContactMessageBubble(
    contact: SharedContact,
    busy: Boolean,
    error: String?,
    onView: () -> Unit,
    onAdd: () -> Unit,
    onSave: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier =
            modifier
                .width(260.dp)
                .clip(RoundedCornerShape(16.dp)),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer) {
                    Icon(
                        Icons.Default.Person,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.padding(10.dp).size(24.dp),
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(
                        contact.displayName,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    contact.phone?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    contact.email?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            ContactCardActions(busy, error, onView, onAdd, onSave)
        }
    }
}

/** Keeps progress, retry feedback, and system contact actions on the same card as its caption. */
@Composable
@Suppress("FunctionNaming")
private fun ContactCardActions(
    busy: Boolean,
    error: String?,
    onView: () -> Unit,
    onAdd: () -> Unit,
    onSave: () -> Unit,
) {
    if (busy) CircularProgressIndicator(Modifier.padding(top = 8.dp).size(20.dp))
    if (error != null) {
        Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        ContactCardAction(Modifier.weight(1f), R.string.contact_view_short, R.string.contact_view, !busy, onView)
        ContactCardAction(Modifier.weight(1f), R.string.contact_add_short, R.string.contact_add, !busy, onAdd)
        ContactCardAction(Modifier.weight(1f), R.string.contact_save_short, R.string.contact_save_vcf, !busy, onSave)
    }
}

/** Compact visible labels keep all actions in one row; the full names remain available to accessibility. */
@Composable
@Suppress("FunctionNaming")
private fun ContactCardAction(
    modifier: Modifier,
    @StringRes labelRes: Int,
    @StringRes descriptionRes: Int,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val description = stringResource(descriptionRes)
    TextButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = 48.dp).semantics { contentDescription = description },
        contentPadding = PaddingValues(horizontal = 0.dp),
    ) {
        Text(
            stringResource(labelRes),
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
