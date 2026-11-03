@file:Suppress("FunctionNaming")

package dev.ipf.whitenoise.android.ui.conversation.composer

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.AvatarImageLoader
import dev.ipf.whitenoise.android.core.MentionComposer
import dev.ipf.whitenoise.android.ui.EmojiLabel
import dev.ipf.whitenoise.android.ui.common.Avatar
import dev.ipf.whitenoise.android.ui.common.fadingVerticalScroll
import dev.ipf.whitenoise.android.ui.theme.amoledOutlineBorder

/** Keep the existing five-candidate presentation limit; the viewport scrolls when those rows cannot fit. */
internal const val MENTION_PICKER_MAX_ROWS = 5

private val MENTION_ROW_AVATAR = 36.dp

/**
 * Member picker for the group composer's `@`-mention flow (#414). It is a plain
 * sibling surface directly above the composer input row rather than a popup or
 * dropdown, so the keyboard never re-anchors under it. Tapping a row inserts
 * that member's canonical mention token via [MentionComposer.insertMention].
 */
@Composable
internal fun MentionPicker(
    candidates: List<MentionComposer.Candidate>,
    onPick: (MentionComposer.Candidate) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = amoledOutlineBorder(),
    ) {
        val visibleCandidates = candidates.take(MENTION_PICKER_MAX_ROWS)
        // A changed result set starts at the first match; profile artwork updates retain reading position.
        key(visibleCandidates.map { it.accountIdHex }) {
            Column(Modifier.fadingVerticalScroll(rememberScrollState())) {
                visibleCandidates.forEach { candidate ->
                    MentionPickerRow(candidate, onPick)
                }
            }
        }
    }
}

/** One roster row: the display name is the whole line, with the avatar as its only leading content. */
@Composable
private fun MentionPickerRow(
    candidate: MentionComposer.Candidate,
    onPick: (MentionComposer.Candidate) -> Unit,
) {
    val mentionLabel = stringResource(R.string.mention_picker_member, candidate.displayName)
    ListItem(
        headlineContent = {
            EmojiLabel(
                candidate.displayName,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        leadingContent = {
            Avatar(
                title = candidate.displayName,
                seed = candidate.accountIdHex,
                size = MENTION_ROW_AVATAR,
                pictureUrl = candidate.avatarUrl,
                pictureDomain = AvatarImageLoader.publicProfiles(),
            )
        },
        modifier =
            Modifier
                .clickable { onPick(candidate) }
                .semantics { contentDescription = mentionLabel },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}
