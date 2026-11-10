package dev.ipf.whitenoise.android.ui.chats.newchat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.AvatarImageLoader
import dev.ipf.whitenoise.android.core.IdentityFormatter
import dev.ipf.whitenoise.android.core.ProfileSanitizer
import dev.ipf.whitenoise.android.core.RecipientSearch
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.presentationNpubFromReference
import dev.ipf.whitenoise.android.ui.EmojiLabel
import dev.ipf.whitenoise.android.ui.common.Avatar

/** Public identity without repeating technical labels or exposing operational references. */
@Composable
@Suppress("FunctionNaming")
internal fun GroupReviewPersonIdentity(
    appState: WhiteNoiseAppState,
    member: RecipientSearch.Candidate,
    role: String,
    modifier: Modifier = Modifier,
) {
    val person = rememberGroupReviewPerson(appState, member)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Avatar(
            person.publicName ?: person.identifier,
            member.accountIdHex,
            48.dp,
            pictureUrl = person.picture,
            pictureDomain = AvatarImageLoader.publicProfiles(),
        )
        GroupReviewPersonText(person, role)
    }
}

/** Public facts shown for one reviewed person; a local nickname never replaces the public name. */
internal data class GroupReviewPerson(
    val identifier: String,
    val publicName: String?,
    val nickname: String?,
    val picture: String?,
)

/** The member's public name, picture and npub as the active account currently sees them. */
@Composable
internal fun rememberGroupReviewPerson(
    appState: WhiteNoiseAppState,
    member: RecipientSearch.Candidate,
): GroupReviewPerson {
    val revision = appState.profileRevisionForCompose
    val profile = remember(appState, member.accountIdHex, revision) { appState.userProfile(member.accountIdHex) }
    val identifier = groupReviewPublicIdentifier(appState, member)
    val publicName =
        reviewMemberPublicName(profile?.name)
            ?: reviewMemberPublicName(profile?.displayName)
            ?: reviewMemberPublicName(member.searchProfile?.name)
            ?: reviewMemberPublicName(member.searchProfile?.displayName)
    val nickname =
        appState
            .contactNickname(member.accountIdHex)
            ?.takeIf { it != publicName }
            ?.let(::reviewMemberPublicName)
    val picture = ProfileSanitizer.protocolImageUrl(profile?.picture ?: member.searchProfile?.picture)
    return GroupReviewPerson(identifier, publicName, nickname, picture)
}

/**
 * Name, local nickname and npub, or one unknown label when nothing public exists. The npub is its own
 * copy target unless [copyable] is false because an enclosing [GroupReviewCopyRow] already copies it.
 */
@Composable
@Suppress("FunctionNaming")
internal fun GroupReviewPersonText(
    person: GroupReviewPerson,
    role: String,
    copyable: Boolean = true,
) {
    person.publicName?.let { Text(it, style = MaterialTheme.typography.titleSmall) }
    person.nickname?.let { Text(stringResource(R.string.group_action_local_nickname, it)) }
    if (person.identifier.isNotBlank()) {
        if (copyable) {
            GroupReviewNpub(person.identifier, role)
        } else {
            Text(groupReviewNpubLabel(person.identifier), style = MaterialTheme.typography.bodySmall)
        }
    } else if (person.publicName == null) {
        Text(stringResource(R.string.unknown), style = MaterialTheme.typography.bodySmall)
    }
}

/** Compact captured actor; the full canonical npub remains accessible and copyable. */
@Composable
@Suppress("FunctionNaming")
internal fun GroupActingAccountIdentity(
    appState: WhiteNoiseAppState,
    accountRef: String? = appState.activeAccountRef,
    modifier: Modifier = Modifier.fillMaxWidth(),
) {
    val account = appState.accounts.firstOrNull { it.label == accountRef } ?: return
    val identifier = appState.npubForDisplay(account.accountIdHex)
    Box(modifier.testTag("group.action.acting_account"), contentAlignment = Alignment.CenterEnd) {
        if (identifier.isNotBlank()) {
            GroupReviewNpub(identifier, stringResource(R.string.group_action_acting_account), compact = true)
        } else {
            Text(
                stringResource(R.string.group_action_account_label, account.label),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

/** A small visible label with a full-size copy target and complete spoken identity. */
@Composable
@Suppress("FunctionNaming")
private fun GroupReviewNpub(
    npub: String,
    role: String,
    compact: Boolean = false,
) {
    GroupReviewCopyRow(npub, role) {
        Text(groupReviewNpubLabel(npub, compact), style = MaterialTheme.typography.bodySmall)
    }
}

/** The shortened npub shown beside a name; the full value is spoken and copied. */
private fun groupReviewNpubLabel(
    npub: String,
    compact: Boolean = false,
): String = IdentityFormatter.short(npub, prefix = if (compact) 8 else 10, suffix = if (compact) 4 else 8)

/**
 * A row of at least 48dp that copies the full [npub], announced once as "[role], npub" with a Copy action.
 * Wrapping a whole member row keeps the touch target without padding the small npub line itself.
 */
@Composable
@Suppress("FunctionNaming")
internal fun GroupReviewCopyRow(
    npub: String,
    role: String,
    modifier: Modifier = Modifier,
    horizontalArrangement: Arrangement.Horizontal = Arrangement.Start,
    content: @Composable RowScope.() -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    val copyLabel = stringResource(R.string.copy)
    Row(
        modifier
            .heightIn(min = 48.dp)
            .clickable(onClickLabel = copyLabel, role = Role.Button) { clipboard.setText(AnnotatedString(npub)) }
            .semantics(mergeDescendants = true) { contentDescription = "$role, $npub" },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = horizontalArrangement,
        content = content,
    )
}

/** Separate destinations retain exact hidden keys without displaying group IDs. */
@Composable
@Suppress("FunctionNaming")
internal fun GroupReviewDestinationIdentity(
    group: MemberReviewGroup,
    actionLabel: String? = null,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Avatar(
            group.name,
            group.id,
            48.dp,
            pictureUrl = group.pictureUrl,
            picture = group.image,
            groupAnimation = group.groupAnimation,
            // A group's picture never decodes in the public-profile worker; without its account, single-use.
            pictureDomain = dev.ipf.whitenoise.android.media.MediaTrustDomain.Unique,
        )
        EmojiLabel(group.name, style = MaterialTheme.typography.titleSmall)
        actionLabel?.let { Text(it) }
    }
}

/** Display only canonical npubs through the existing presentation boundary. */
internal fun groupReviewPublicIdentifier(
    appState: WhiteNoiseAppState,
    member: RecipientSearch.Candidate,
): String =
    presentationNpubFromReference(
        member.npub.ifBlank { member.accountIdHex.takeIf { it.startsWith("npub1") }.orEmpty() },
        appState.profileReferenceAccountIdHex(member.accountIdHex) ?: member.accountIdHex,
        appState::npubForDisplay,
    )
