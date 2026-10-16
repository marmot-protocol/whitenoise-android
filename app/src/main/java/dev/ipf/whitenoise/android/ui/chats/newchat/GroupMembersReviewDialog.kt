@file:Suppress("MatchingDeclarationName") // The dialog and its destination presentation stay together.

package dev.ipf.whitenoise.android.ui.chats.newchat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.IdentityFormatter
import dev.ipf.whitenoise.android.core.ProfileSanitizer
import dev.ipf.whitenoise.android.core.RecipientSearch
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.presentationNpubFromReference
import dev.ipf.whitenoise.android.ui.EmojiLabel
import dev.ipf.whitenoise.android.ui.common.Avatar
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseAlertDialog
import dev.ipf.whitenoise.android.ui.common.fadingVerticalScroll
import dev.ipf.whitenoise.android.ui.icons.Icons
import dev.ipf.whitenoise.android.ui.icons.automirrored.filled.ArrowForward

/** Frozen destination identity for reviewing a group action. */
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
    title: String = stringResource(R.string.add_member),
    description: String? =
        stringResource(
            R.string.profile_add_to_groups_confirm_message,
            stringResource(if (members.size == 1) R.string.member else R.string.members),
            groups.joinToString { it.name },
        ),
    invitation: Boolean = true,
    dismissEnabled: Boolean = !busy,
    showProgress: Boolean = busy,
    destructive: Boolean = false,
) {
    WhiteNoiseAlertDialog(
        modifier = Modifier.testTag("group.members.review.dialog"),
        onDismissRequest = { if (dismissEnabled) onDismiss() },
        title = { Text(title) },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                colors =
                    ButtonDefaults.textButtonColors(
                        contentColor = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    ),
                enabled = !busy && members.isNotEmpty() && groups.isNotEmpty(),
                modifier = Modifier.testTag(SELECTED_MEMBERS_CONFIRM_TAG),
            ) {
                if (showProgress) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Text(confirmLabel)
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = dismissEnabled) { Text(stringResource(R.string.cancel)) }
        },
        text = {
            Column(
                Modifier.fillMaxWidth().fadingVerticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                description?.let { EmojiLabel(it) }
                groups.firstOrNull()?.let { firstGroup ->
                    members.forEachIndexed { index, member ->
                        key(member.accountIdHex) {
                            if (index > 0) HorizontalDivider()
                            GroupMemberReviewPair(
                                appState = appState,
                                group = firstGroup,
                                member = member,
                                busy = busy,
                                onRemove = onRemove,
                                initialAdminIds = initialAdminIds,
                                onInitialAdminChange = onInitialAdminChange,
                                otherGroups = groups.drop(1),
                                showDirection = invitation,
                            )
                        }
                    }
                }
            }
        },
    )
}

/** Shows one public identity beside its destinations; arrows are reserved for invitations. */
@Composable
@Suppress("FunctionNaming", "LongMethod", "LongParameterList")
internal fun GroupMemberReviewPair(
    appState: WhiteNoiseAppState,
    group: MemberReviewGroup,
    member: RecipientSearch.Candidate,
    busy: Boolean = false,
    onRemove: ((RecipientSearch.Candidate) -> Unit)? = null,
    initialAdminIds: Set<String> = emptySet(),
    onInitialAdminChange: ((RecipientSearch.Candidate, Boolean) -> Unit)? = null,
    otherGroups: List<MemberReviewGroup> = emptyList(),
    showDirection: Boolean = false,
) {
    val revision = appState.profileRevisionForCompose
    val profile = remember(appState, member.accountIdHex, revision) { appState.userProfile(member.accountIdHex) }
    val identifier = presentationNpubFromReference(member.npub, member.accountIdHex, appState::npubForDisplay)
    val name =
        reviewMemberPublicName(profile?.name)
            ?: reviewMemberPublicName(profile?.displayName)
            ?: reviewMemberPublicName(member.searchProfile?.name)
            ?: reviewMemberPublicName(member.searchProfile?.displayName)
    val shortIdentifier = IdentityFormatter.short(identifier, prefix = 10, suffix = 8)
    val label = name ?: shortIdentifier
    val picture = ProfileSanitizer.protocolImageUrl(profile?.picture ?: member.searchProfile?.picture)
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Column(
                Modifier.weight(1f).testTag("group.members.review.member"),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(stringResource(R.string.member), style = MaterialTheme.typography.labelMedium)
                Avatar(label, member.accountIdHex, 48.dp, pictureUrl = picture)
                name?.let { Text(it, style = MaterialTheme.typography.titleSmall) }
                if (shortIdentifier.isNotBlank()) {
                    Text(
                        shortIdentifier,
                        style = MaterialTheme.typography.labelSmall.copy(textDirection = TextDirection.Ltr),
                    )
                }
            }
            if (showDirection) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp).align(Alignment.CenterVertically),
                )
            } else {
                Spacer(Modifier.width(20.dp))
            }
            Column(
                Modifier.weight(1f).testTag("group.members.review.destination"),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    stringResource(if (otherGroups.isEmpty()) R.string.group_name else R.string.chat_list_search_groups),
                    style = MaterialTheme.typography.labelMedium,
                )
                (listOf(group) + otherGroups).forEach { destination ->
                    key(destination.id) {
                        Avatar(destination.name, destination.id, 48.dp, pictureUrl = destination.pictureUrl, picture = destination.image)
                        EmojiLabel(destination.name, style = MaterialTheme.typography.titleSmall)
                    }
                }
            }
        }
        onInitialAdminChange?.let { change ->
            SelectedMemberRoleControl(
                name = label,
                admin = initialAdminIds.any { it.equals(member.accountIdHex, ignoreCase = true) },
                enabled = !busy,
                onChange = { change(member, it) },
            )
        }
        onRemove?.let { remove ->
            val removeDescription = stringResource(R.string.remove_member_named, label)
            TextButton(
                onClick = { remove(member) },
                enabled = !busy,
                modifier = Modifier.semantics { contentDescription = removeDescription },
            ) {
                Text(stringResource(R.string.remove_member))
            }
        }
    }
}

/** Rejects identity placeholders as names so an unnamed person has one abbreviated identifier. */
internal fun reviewMemberPublicName(value: String?): String? = ProfileSanitizer.displayName(value)?.takeUnless(IdentityFormatter::isNostrIdentityFallback)
