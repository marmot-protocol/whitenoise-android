package dev.ipf.whitenoise.android.ui.conversation.share

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.ProfileLink
import dev.ipf.whitenoise.android.ui.common.Avatar

/**
 * A shared Nostr user — the actionable, identity-native counterpart to a phone
 * contact. Carries the `npub` (what a recipient acts on) and an optional
 * display name for the card header. Kept out of the send path so a structured
 * profile message can replace the `nostr:` text reference later.
 */
internal data class SharedUser(
    val npub: String,
    val name: String?,
)

/**
 * Returns the unambiguous whole-body shape used by the share-a-user flow.
 * The reference is intentionally the only line so authored prose cannot be
 * mistaken for a display name by the receiver.
 */
internal fun formatUserShareText(npub: String): String = "nostr:$npub"

/**
 * Recognizes a user-share message only when the whole body is one `npub`
 * reference. Any additional non-empty line makes the body ordinary message
 * text so user-authored prose cannot be relabeled as a card title.
 */
internal fun parseSharedUserFromText(text: String): SharedUser? {
    val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
    if (lines.size != 1) return null
    val refLine = lines.single()
    if (!refLine.startsWith("npub1", ignoreCase = true) && !refLine.startsWith("nostr:npub1", ignoreCase = true)) {
        return null
    }
    val npub = ProfileLink.parse(refLine)?.npub ?: return null
    return SharedUser(npub = npub, name = null)
}

/** Produces a display-only identity summary without changing the actionable full npub. */
private fun shortNpub(npub: String): String = if (npub.length > 20) "${npub.take(12)}…${npub.takeLast(5)}" else npub

/**
 * Renders one unambiguous shared-user reference as an actionable profile card.
 *
 * [displayName] and [pictureUrl] come from the reader's own resolution of the npub, so a shared
 * contact reads like a person rather than a key. The truncated npub stays on the card whenever a
 * name is shown: a display name is self-asserted and could be anyone's, so it never replaces the
 * identity the recipient would act on.
 */
@Composable
internal fun UserMessageBubble(
    user: SharedUser,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
    displayName: String? = null,
    pictureUrl: String? = null,
) {
    val shortened = shortNpub(user.npub)
    val resolvedName =
        (displayName ?: user.name)
            ?.trim()
            ?.takeIf { it.isNotEmpty() && it != shortened && it != user.npub }
    val title = resolvedName ?: shortened
    Surface(
        modifier =
            modifier
                .width(260.dp)
                .clip(RoundedCornerShape(16.dp))
                .clickable(onClick = onOpen),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (resolvedName != null || pictureUrl != null) {
                Avatar(title = title, seed = user.npub, size = 44.dp, pictureUrl = pictureUrl)
            } else {
                // An unresolved reference gets a neutral glyph: an initial taken from "npub1"
                // would read as a name the sender never supplied.
                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer) {
                    Icon(
                        Icons.Default.Person,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(10.dp).size(24.dp),
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Column {
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (resolvedName != null) {
                    Text(
                        shortened,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    stringResource(R.string.share_user_view),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}
