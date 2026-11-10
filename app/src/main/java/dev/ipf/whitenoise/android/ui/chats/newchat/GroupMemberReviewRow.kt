package dev.ipf.whitenoise.android.ui.chats.newchat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.AvatarImageLoader
import dev.ipf.whitenoise.android.core.IdentityFormatter
import dev.ipf.whitenoise.android.core.RecipientSearch
import dev.ipf.whitenoise.android.media.MediaTrustDomain
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.EmojiLabel
import dev.ipf.whitenoise.android.ui.common.Avatar

/** One compact member row; the admin switch trails it, or drops below when text is large or space is narrow. */
@Composable
@Suppress("FunctionNaming")
internal fun GroupMemberReviewRow(
    appState: WhiteNoiseAppState,
    member: RecipientSearch.Candidate,
    busy: Boolean,
    initialAdminIds: Set<String>,
    onInitialAdminChange: ((RecipientSearch.Candidate, Boolean) -> Unit)?,
) {
    val person = rememberGroupReviewPerson(appState, member)
    val label =
        person.publicName
            ?: IdentityFormatter.short(person.identifier, prefix = 10, suffix = 8).ifBlank {
                stringResource(R.string.unknown)
            }
    val memberRole = stringResource(R.string.member)
    val admin = initialAdminIds.any { it.equals(member.accountIdHex, ignoreCase = true) }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val stacked = groupReviewStacked(maxWidth)
        val identity: @Composable (Modifier) -> Unit = { modifier ->
            val content: @Composable RowScope.() -> Unit = {
                Avatar(
                    person.publicName ?: person.identifier,
                    member.accountIdHex,
                    GroupReviewAvatarSize,
                    pictureUrl = person.picture,
                    pictureDomain = AvatarImageLoader.publicProfiles(),
                )
                Column(Modifier.weight(1f)) {
                    GroupReviewPersonText(person, memberRole, copyable = false)
                }
            }
            val tagged = modifier.testTag("group.members.review.member")
            val gap = Arrangement.spacedBy(GroupReviewAvatarGap)
            if (person.identifier.isNotBlank()) {
                GroupReviewCopyRow(person.identifier, memberRole, tagged, gap, content)
            } else {
                Row(tagged.heightIn(min = 48.dp), gap, Alignment.CenterVertically, content)
            }
        }
        val change = onInitialAdminChange
        when {
            change == null -> identity(Modifier.fillMaxWidth())
            stacked ->
                Column {
                    identity(Modifier.fillMaxWidth())
                    SelectedMemberRoleControl(label, admin, !busy, inset = 0.dp) { change(member, it) }
                }
            else ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    identity(Modifier.weight(1f))
                    SelectedMemberRoleControl(label, admin, !busy, compact = true) { change(member, it) }
                }
        }
    }
}

/**
 * Several destinations at the top of the scrolling body, in their frozen order with their own pictures.
 * The dialog title cannot scroll, so a long list there would push the actions off screen.
 */
@Composable
@Suppress("FunctionNaming")
internal fun GroupReviewDestinationList(groups: List<MemberReviewGroup>) {
    Column(
        Modifier.fillMaxWidth().padding(bottom = 8.dp).testTag("group.members.review.destination"),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        groups.forEach { destination ->
            key(destination.id) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    GroupReviewAvatar(destination, 24.dp)
                    EmojiLabel(
                        destination.name,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** A destination group's own picture, decoded single-use because no public-profile worker owns it. */
@Composable
@Suppress("FunctionNaming")
internal fun GroupReviewAvatar(
    group: MemberReviewGroup,
    size: Dp,
) {
    Avatar(
        group.name,
        group.id,
        size,
        pictureUrl = group.pictureUrl,
        picture = group.image,
        groupAnimation = group.groupAnimation,
        pictureDomain = MediaTrustDomain.Unique,
    )
}
