package dev.ipf.whitenoise.android.ui.chats.newchat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.wrapContentHeight
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
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Avatar(
            publicName ?: identifier,
            member.accountIdHex,
            48.dp,
            pictureUrl = picture,
            pictureDomain = AvatarImageLoader.publicProfiles(),
        )
        publicName?.let { Text(it, style = MaterialTheme.typography.titleSmall) }
        nickname?.let { Text(stringResource(R.string.group_action_local_nickname, it)) }
        if (identifier.isNotBlank()) {
            GroupReviewNpub(identifier, role)
        } else if (publicName == null) {
            Text(stringResource(R.string.unknown), style = MaterialTheme.typography.bodySmall)
        }
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
    val clipboard = LocalClipboardManager.current
    val copyLabel = stringResource(R.string.copy)
    Text(
        IdentityFormatter.short(npub, prefix = if (compact) 8 else 10, suffix = if (compact) 4 else 8),
        style = MaterialTheme.typography.bodySmall,
        modifier =
            Modifier
                .heightIn(min = 48.dp)
                .clickable(onClickLabel = copyLabel, role = Role.Button) { clipboard.setText(AnnotatedString(npub)) }
                .semantics(mergeDescendants = true) { contentDescription = "$role, $npub" }
                .wrapContentHeight(Alignment.CenterVertically),
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
