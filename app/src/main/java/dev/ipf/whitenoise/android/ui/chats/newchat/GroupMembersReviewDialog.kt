@file:Suppress("MatchingDeclarationName") // The dialog and its destination presentation stay together.

package dev.ipf.whitenoise.android.ui.chats.newchat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.ProfileSanitizer
import dev.ipf.whitenoise.android.core.RecipientSearch
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.presentationNpubFromReference
import dev.ipf.whitenoise.android.ui.EmojiLabel
import dev.ipf.whitenoise.android.ui.common.Avatar
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseAlertDialog
import dev.ipf.whitenoise.android.ui.common.fadingVerticalScroll
import dev.ipf.whitenoise.android.ui.icons.Icons
import dev.ipf.whitenoise.android.ui.icons.automirrored.filled.ArrowBack

/** Frozen destination identity for reviewing an existing-group invitation. */
internal data class MemberReviewGroup(
    val id: String,
    val name: String,
    val pictureUrl: String? = null,
    val image: ImageBitmap? = null,
)

/** Shows the destination opposite each public member identity; actions stay outside the scrolling body. */
@Composable
@Suppress("FunctionNaming", "LongMethod", "LongParameterList")
internal fun GroupMembersReviewDialog(
    appState: WhiteNoiseAppState,
    groups: List<MemberReviewGroup>,
    members: List<RecipientSearch.Candidate>,
    busy: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    confirmLabel: String,
    onRemove: ((RecipientSearch.Candidate) -> Unit)? = null,
    initialAdminIds: Set<String> = emptySet(),
    onInitialAdminChange: ((RecipientSearch.Candidate, Boolean) -> Unit)? = null,
) {
    WhiteNoiseAlertDialog(
        modifier = Modifier.testTag("group.members.review.dialog"),
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(R.string.review_selection)) },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                enabled = !busy && members.isNotEmpty() && groups.isNotEmpty(),
                modifier = Modifier.testTag(SELECTED_MEMBERS_CONFIRM_TAG),
            ) {
                if (busy) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Text(confirmLabel)
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) { Text(stringResource(R.string.cancel)) }
        },
        text = {
            Column(
                Modifier.fillMaxWidth().fadingVerticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                groups.forEach { group ->
                    members.forEach { member ->
                        key(group.id, member.accountIdHex) {
                            GroupMemberReviewPair(
                                appState = appState,
                                group = group,
                                member = member,
                                busy = busy,
                                onRemove = onRemove,
                                initialAdminIds = initialAdminIds,
                                onInitialAdminChange = onInitialAdminChange,
                            )
                        }
                    }
                }
            }
        },
    )
}

/** Shows the destination opposite the public identity of the member being invited. */
@Composable
@Suppress("FunctionNaming", "LongMethod", "LongParameterList")
private fun GroupMemberReviewPair(
    appState: WhiteNoiseAppState,
    group: MemberReviewGroup,
    member: RecipientSearch.Candidate,
    busy: Boolean,
    onRemove: ((RecipientSearch.Candidate) -> Unit)?,
    initialAdminIds: Set<String>,
    onInitialAdminChange: ((RecipientSearch.Candidate, Boolean) -> Unit)?,
) {
    val revision = appState.profileRevisionForCompose
    val profile = remember(appState, member.accountIdHex, revision) { appState.userProfile(member.accountIdHex) }
    val identifier = presentationNpubFromReference(member.npub, member.accountIdHex, appState::npubForDisplay)
    val name =
        ProfileSanitizer.displayName(profile?.name)
            ?: ProfileSanitizer.displayName(profile?.displayName)
            ?: ProfileSanitizer.displayName(member.searchProfile?.name)
            ?: ProfileSanitizer.displayName(member.searchProfile?.displayName)
            ?: identifier
    val picture = ProfileSanitizer.protocolImageUrl(profile?.picture ?: member.searchProfile?.picture)
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Column(
            Modifier.weight(1f).testTag("group.members.review.destination"),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Avatar(group.name, group.id, 48.dp, pictureUrl = group.pictureUrl, picture = group.image)
            EmojiLabel(group.name, style = MaterialTheme.typography.titleSmall)
        }
        Icon(
            Icons.AutoMirrored.Filled.ArrowBack,
            contentDescription = null,
            modifier = Modifier.size(20.dp).align(Alignment.CenterVertically),
        )
        Column(
            Modifier.weight(1f).testTag("group.members.review.member"),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Avatar(name, member.accountIdHex, 48.dp, pictureUrl = picture)
            Text(name, style = MaterialTheme.typography.titleSmall)
            Text(
                identifier,
                style = MaterialTheme.typography.labelSmall.copy(textDirection = TextDirection.Ltr),
            )
            onInitialAdminChange?.let { change ->
                SelectedMemberRoleMenu(
                    name = name,
                    identifier = identifier,
                    admin = initialAdminIds.any { it.equals(member.accountIdHex, ignoreCase = true) },
                    enabled = !busy,
                    onChange = { change(member, it) },
                )
            }
            onRemove?.let { remove ->
                TextButton(onClick = { remove(member) }, enabled = !busy) {
                    Text(stringResource(R.string.remove_member_named, name))
                }
            }
        }
    }
}
