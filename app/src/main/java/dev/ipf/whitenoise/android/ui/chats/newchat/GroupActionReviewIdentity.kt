package dev.ipf.whitenoise.android.ui.chats.newchat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.IdentityFormatter
import dev.ipf.whitenoise.android.core.ProfileSanitizer
import dev.ipf.whitenoise.android.core.RecipientSearch
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.presentationNpubFromReference
import dev.ipf.whitenoise.android.ui.EmojiLabel
import dev.ipf.whitenoise.android.ui.common.Avatar
import dev.ipf.whitenoise.android.ui.common.CopyableValueRow

/** The same public identity presentation for an affected person and the account performing the action. */
@Composable
@Suppress("FunctionNaming")
internal fun GroupReviewPersonIdentity(
    appState: WhiteNoiseAppState,
    member: RecipientSearch.Candidate,
    role: String,
    modifier: Modifier = Modifier,
    localAccountLabel: String? = null,
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
    val shortIdentifier = IdentityFormatter.short(identifier, prefix = 10, suffix = 8)
    val picture = ProfileSanitizer.protocolImageUrl(profile?.picture ?: member.searchProfile?.picture)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(role, style = MaterialTheme.typography.labelMedium)
        Avatar(publicName ?: shortIdentifier, member.accountIdHex, 48.dp, pictureUrl = picture)
        publicName?.let { Text(it, style = MaterialTheme.typography.titleSmall) }
        nickname?.let { Text(stringResource(R.string.group_action_local_nickname, it)) }
        if (identifier.isNotBlank()) {
            CopyableValueRow(
                label =
                    stringResource(
                        if (identifier.startsWith("npub1")) {
                            R.string.public_key
                        } else {
                            R.string.group_action_public_key_hex
                        },
                    ),
                value = identifier,
                displayValue = shortIdentifier,
                clipboard = LocalClipboardManager.current,
            )
        } else {
            localAccountLabel?.takeIf { it.isNotBlank() }?.let {
                Text(stringResource(R.string.group_action_account_label, it))
            }
        }
    }
}

/** Identifies the captured acting account without inventing a profile name or verification status. */
@Composable
@Suppress("FunctionNaming")
internal fun GroupActingAccountIdentity(
    appState: WhiteNoiseAppState,
    accountRef: String? = appState.activeAccountRef,
) {
    val account = appState.accounts.firstOrNull { it.label == accountRef } ?: return
    GroupReviewPersonIdentity(
        appState = appState,
        member = RecipientSearch.Candidate(account.accountIdHex, "", appState.npubForDisplay(account.accountIdHex)),
        role = stringResource(R.string.group_action_acting_account),
        modifier = Modifier.testTag("group.action.acting_account"),
        localAccountLabel = account.label,
    )
}

/** One individually readable destination, retaining the public group ID when names are duplicated. */
@Composable
@Suppress("FunctionNaming")
internal fun GroupReviewDestinationIdentity(
    group: MemberReviewGroup,
    actionLabel: String? = null,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Avatar(group.name, group.id, 48.dp, pictureUrl = group.pictureUrl, picture = group.image)
        EmojiLabel(group.name, style = MaterialTheme.typography.titleSmall)
        actionLabel?.let { Text(it) }
        CopyableValueRow(
            label = stringResource(R.string.group_id),
            value = group.id,
            displayValue = IdentityFormatter.short(group.id, prefix = 8, suffix = 8),
            clipboard = LocalClipboardManager.current,
        )
    }
}

/** Public-key display only: use the existing npub formatter, or disclose an exact known public hex key. */
internal fun groupReviewPublicIdentifier(
    appState: WhiteNoiseAppState,
    member: RecipientSearch.Candidate,
): String =
    presentationNpubFromReference(
        member.npub.ifBlank { member.accountIdHex.takeIf { it.startsWith("npub1") }.orEmpty() },
        appState.profileReferenceAccountIdHex(member.accountIdHex) ?: member.accountIdHex,
        appState::npubForDisplay,
    ).ifBlank {
        (appState.profileReferenceAccountIdHex(member.accountIdHex) ?: member.accountIdHex)
            .takeIf { value ->
                value.length == GROUP_REVIEW_PUBLIC_KEY_HEX_LENGTH &&
                    value.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }
            }.orEmpty()
    }

private const val GROUP_REVIEW_PUBLIC_KEY_HEX_LENGTH = 64
