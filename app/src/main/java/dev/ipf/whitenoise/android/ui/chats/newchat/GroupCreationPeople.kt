// The file names its primary composable; its row/action model stays colocated.
@file:Suppress("MatchingDeclarationName")

package dev.ipf.whitenoise.android.ui.chats.newchat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.AvatarImageLoader
import dev.ipf.whitenoise.android.core.RecipientSearch
import dev.ipf.whitenoise.android.ui.EmojiLabel
import dev.ipf.whitenoise.android.ui.common.Avatar
import dev.ipf.whitenoise.android.ui.common.PublicKeyCopyText
import dev.ipf.whitenoise.android.ui.common.abbreviatesPublicKey
import dev.ipf.whitenoise.android.ui.common.rememberMeasuredControlClick

/** Current native person reference plus sanitized display data; this is not a discovery cache. */
internal data class GroupCreationPerson(
    val candidate: RecipientSearch.Candidate,
    val subtitle: String?,
    val avatarUrl: String?,
)

/** Native checked rows preserve long-press profile preview; setup rows are full-contrast and read-only. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
@Suppress("FunctionNaming", "LongMethod") // Native measurement and input callbacks belong to the same row.
internal fun GroupCreationPersonRow(
    person: GroupCreationPerson,
    index: Int,
    count: Int,
    selected: Boolean?,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    onLongClick: ((Rect?) -> Unit)? = null,
) {
    val candidate = person.candidate
    val key = personKeyPlacement(candidate.displayName, person.subtitle, candidate.npub)
    val (profileModifier, profileClick) = rememberMeasuredControlClick { origin -> onLongClick?.invoke(origin) }
    PersonRow(
        title = candidate.displayName,
        subtitle = key.subtitle,
        avatarSeed = candidate.accountIdHex,
        avatarUrl = person.avatarUrl,
        modifier = profileModifier.testTag("new_group.person.${candidate.accountIdHex}"),
        index = index,
        count = count,
        isFollowed = candidate.isFollowing,
        followedAccount = candidate.accountIdHex,
        selectionState = selected,
        enabled = enabled,
        publicKeyCopyEnabled = true,
        nativeDestination = candidate.accountIdHex,
        onClick = onClick,
        onLongClick = profileClick.takeIf { onLongClick != null },
        publicKey = key.publicKey,
        publicKeyInTitle = key.inTitle,
        trailing = if (selected == true) ({ SelectedPersonCheck() }) else null,
        avatarDomain = AvatarImageLoader.publicProfiles(),
    )
}

/** The prototype marks a chosen person with the primary check glyph instead of a checkbox. */
@Composable
@Suppress("FunctionNaming") // Compose naming follows the framework convention.
private fun SelectedPersonCheck() {
    Icon(
        painter = painterResource(R.drawable.ic_check),
        contentDescription = null,
        tint = MaterialTheme.colorScheme.primary,
    )
}

/** Prototype 80dp selected-person chip; the complete chip is an accessible remove action. */
@Composable
@Suppress("FunctionNaming") // Compose naming follows the framework convention.
internal fun GroupSelectedPerson(
    person: GroupCreationPerson,
    onRemove: () -> Unit,
) {
    val candidate = person.candidate
    val interaction = remember(candidate.accountIdHex) { MutableInteractionSource() }
    val remove = stringResource(R.string.remove_member_named, candidate.displayName)
    val titleIsKey = abbreviatesPublicKey(candidate.displayName, candidate.npub)
    Column(
        (if (titleIsKey) Modifier.widthIn(min = 80.dp, max = 280.dp) else Modifier.width(80.dp))
            .clickable(interaction, null, role = Role.Button, onClick = onRemove)
            .semantics { contentDescription = remove }
            .testTag("new_group.selected.${candidate.accountIdHex}"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(Modifier.size(72.dp)) {
            Box(Modifier.align(Alignment.BottomStart)) {
                Avatar(
                    candidate.displayName,
                    candidate.accountIdHex,
                    64.dp,
                    pictureUrl = person.avatarUrl,
                    pictureDomain = AvatarImageLoader.publicProfiles(),
                    followedAccount = candidate.accountIdHex,
                )
            }
            Surface(
                Modifier.size(24.dp).align(Alignment.TopEnd),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(painterResource(R.drawable.ic_close), null, Modifier.size(16.dp))
                }
            }
        }
        if (titleIsKey) {
            PublicKeyCopyText(candidate.displayName, candidate.npub, style = MaterialTheme.typography.labelMedium)
        } else {
            EmojiLabel(
                candidate.displayName,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.labelMedium,
                textAlign = TextAlign.Center,
            )
        }
    }
}
