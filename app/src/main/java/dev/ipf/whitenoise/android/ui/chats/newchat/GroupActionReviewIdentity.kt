package dev.ipf.whitenoise.android.ui.chats.newchat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
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
import dev.ipf.whitenoise.android.ui.common.animatePublicKeyExpansion
import dev.ipf.whitenoise.android.ui.settings.publicKeyDisplayGroups

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
            followedAccount = member.accountIdHex,
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
    expanded: Boolean = false,
) {
    person.publicName?.let { Text(it, style = MaterialTheme.typography.titleSmall) }
    groupReviewNicknameLabel(person)?.let { Text(it) }
    if (person.identifier.isNotBlank()) {
        if (copyable) {
            GroupReviewNpub(person.identifier, role)
        } else {
            GroupReviewKeyText(person.identifier, expanded)
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
    showRole: Boolean = false,
) {
    val account = appState.accounts.firstOrNull { it.label == accountRef } ?: return
    val identifier = appState.npubForDisplay(account.accountIdHex)
    val alignment = if (showRole) Alignment.CenterStart else Alignment.CenterEnd
    Box(modifier.testTag("group.action.acting_account"), contentAlignment = alignment) {
        if (identifier.isNotBlank()) {
            GroupReviewNpub(
                identifier,
                stringResource(R.string.group_action_acting_account),
                compact = true,
                showRole = showRole,
            )
        } else {
            Text(
                stringResource(R.string.group_action_account_label, account.label),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

/** The local nickname line, kept separate from the public name so it is never mistaken for one. */
@Composable
private fun groupReviewNicknameLabel(person: GroupReviewPerson): String? {
    val nickname = person.nickname ?: return null
    return stringResource(R.string.group_action_local_nickname, nickname)
}

/** What [GroupReviewPersonText] shows, spoken in order with the [role] and full npub, for a merged row. */
@Composable
internal fun groupReviewPersonDescription(
    person: GroupReviewPerson,
    role: String,
): String {
    val nickname = groupReviewNicknameLabel(person)
    return listOfNotNull(person.publicName, nickname, role, person.identifier).joinToString(", ")
}

/** A small visible label with a full-size copy target and complete spoken identity. */
@Composable
@Suppress("FunctionNaming")
private fun GroupReviewNpub(
    npub: String,
    role: String,
    compact: Boolean = false,
    showRole: Boolean = false,
) {
    GroupReviewCopyRow(npub, "$role, $npub") { expanded ->
        if (showRole) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    role,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                GroupReviewKeyText(npub, expanded, compact, textDirection = TextDirection.Ltr)
            }
        } else {
            GroupReviewKeyText(npub, expanded, compact)
        }
    }
}

@Composable
@Suppress("FunctionNaming")
private fun GroupReviewKeyText(
    npub: String,
    expanded: Boolean,
    compact: Boolean = false,
    textDirection: TextDirection = MaterialTheme.typography.bodySmall.textDirection,
) {
    Text(
        text =
            if (expanded) {
                publicKeyDisplayGroups(npub).joinToString(" ")
            } else {
                IdentityFormatter.short(npub, prefix = if (compact) 8 else 10, suffix = if (compact) 4 else 8)
            },
        modifier = Modifier.animatePublicKeyExpansion(expanded),
        style = MaterialTheme.typography.bodySmall.copy(textDirection = textDirection),
        fontFamily = if (expanded) FontFamily.Monospace else null,
        textAlign = if (expanded) TextAlign.Center else null,
    )
}

/**
 * A row of at least 48dp that copies the full [npub], announced once as [description] with a Copy action.
 * The merged description replaces the children's text, so it must name everything the row shows.
 */
@Composable
@Suppress("FunctionNaming")
internal fun GroupReviewCopyRow(
    npub: String,
    description: String,
    modifier: Modifier = Modifier,
    horizontalArrangement: Arrangement.Horizontal = Arrangement.Start,
    content: @Composable RowScope.(Boolean) -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    val copyLabel = stringResource(R.string.copy)
    var expanded by rememberSaveable(npub) { mutableStateOf(false) }
    Row(
        modifier
            .heightIn(min = 48.dp)
            .clickable(onClickLabel = copyLabel, role = Role.Button) {
                clipboard.setText(AnnotatedString(npub))
                expanded = true
            }.semantics(mergeDescendants = true) { contentDescription = description },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = horizontalArrangement,
    ) { content(expanded) }
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
