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

// A single-line NIP-21 `nostr:npub1…` reference, or a bare `npub1…`. Bech32,
// so the data part is limited to its charset.
private val NPUB_LINE = Regex("""^(?:nostr:)?(npub1[023456789acdefghjklmnpqrstuvwxyz]{20,})$""", RegexOption.IGNORE_CASE)

/** The `nostr:` reference we send; a peer on any Nostr client sees a profile link. */
internal fun formatUserShareText(
    name: String?,
    npub: String,
): String {
    val ref = "nostr:$npub"
    val cleanName = name?.trim()?.takeIf { it.isNotEmpty() && it != npub }
    return if (cleanName != null) "$cleanName\n$ref" else ref
}

/**
 * Recognizes a user-share message: the whole body is just an `npub` reference
 * (optionally a name line above it), nothing else. Deliberately strict — a
 * longer message that merely mentions an npub in prose stays plain text and is
 * not hijacked into a user card.
 */
internal fun parseSharedUserFromText(text: String): SharedUser? {
    val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
    if (lines.isEmpty() || lines.size > 2) return null
    val refLine = lines.firstOrNull { NPUB_LINE.matches(it) } ?: return null
    val npub = NPUB_LINE.find(refLine)?.groupValues?.get(1) ?: return null
    val name = lines.firstOrNull { it != refLine }
    return SharedUser(npub = npub, name = name)
}

private fun shortNpub(npub: String): String = if (npub.length > 20) "${npub.take(12)}…${npub.takeLast(5)}" else npub

@Composable
internal fun UserMessageBubble(
    user: SharedUser,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
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
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer) {
                Icon(
                    Icons.Default.Person,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.padding(10.dp).size(24.dp),
                )
            }
            Spacer(Modifier.width(12.dp))
            Column {
                Text(
                    user.name?.takeIf { it.isNotBlank() } ?: shortNpub(user.npub),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    stringResource(R.string.share_user_view),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}
