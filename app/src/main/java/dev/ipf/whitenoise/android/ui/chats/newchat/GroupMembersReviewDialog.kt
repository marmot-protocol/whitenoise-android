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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.IdentityFormatter
import dev.ipf.whitenoise.android.core.ProfileSanitizer
import dev.ipf.whitenoise.android.core.RecipientSearch
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.EmojiLabel
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
    val actingAccount = remember(appState) { appState.activeAccountRef }
    val runtime = remember(appState) { appState.runtimeGeneration }
    val ticket = remember { GroupMembersReviewTicket() }
    val liveBusy = rememberUpdatedState(busy)
    val liveMembers = rememberUpdatedState(members)
    val liveGroups = rememberUpdatedState(groups)
    val liveAdmins = rememberUpdatedState(initialAdminIds)
    val reviewedAdmins = initialAdminIds.toSet()
    val reviewedMembers = members.map { it.accountIdHex }.toList()
    val reviewedGroups = groups.map { it.id }.toList()
    DisposableEffect(ticket) { onDispose { ticket.active = false } }
    val current = groupMembersReviewOwnerCurrent(appState, actingAccount, runtime)

    LaunchedEffect(current) {
        if (!current) {
            ticket.active = false
            onDismiss()
        }
    }
    WhiteNoiseAlertDialog(
        modifier = Modifier.testTag("group.members.review.dialog"),
        onDismissRequest = {
            if (dismissEnabled && ticket.active) {
                ticket.active = false
                onDismiss()
            }
        },
        title = { Text(title) },
        confirmButton = {
            TextButton(
                onClick = {
                    if (ticket.claim(
                            ownerCurrent = { groupMembersReviewOwnerCurrent(appState, actingAccount, runtime) },
                            busy = { liveBusy.value },
                            selectionCurrent = {
                                reviewedMembers == liveMembers.value.map { it.accountIdHex } &&
                                    reviewedGroups == liveGroups.value.map { it.id } &&
                                    reviewedAdmins == liveAdmins.value
                            },
                        )
                    ) {
                        onConfirm()
                    }
                },
                colors =
                    ButtonDefaults.textButtonColors(
                        contentColor =
                            if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    ),
                enabled = groupMembersReviewConfirmEnabled(current, busy, members.isNotEmpty(), groups.isNotEmpty()),
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
            TextButton(onClick = {
                if (ticket.active) {
                    ticket.active = false
                    onDismiss()
                }
            }, enabled = dismissEnabled) { Text(stringResource(R.string.cancel)) }
        },
        text = {
            Column(
                Modifier.fillMaxWidth().fadingVerticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                description?.let { EmojiLabel(it) }
                GroupActingAccountIdentity(appState, actingAccount)
                Text(pluralStringResource(R.plurals.selected_members_count, members.size, members.size))
                Text(pluralStringResource(R.plurals.group_action_groups_count, groups.size, groups.size))
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
    val identifier = groupReviewPublicIdentifier(appState, member)
    val name =
        reviewMemberPublicName(profile?.name)
            ?: reviewMemberPublicName(profile?.displayName)
            ?: reviewMemberPublicName(member.searchProfile?.name)
            ?: reviewMemberPublicName(member.searchProfile?.displayName)
    val shortIdentifier = IdentityFormatter.short(identifier, prefix = 10, suffix = 8)
    val label = name ?: shortIdentifier
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.Top,
        ) {
            GroupReviewPersonIdentity(
                appState = appState,
                member = member,
                role = stringResource(R.string.member),
                modifier = Modifier.weight(1f).testTag("group.members.review.member"),
            )
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
                    stringResource(
                        if (otherGroups.isEmpty()) R.string.group_name else R.string.chat_list_search_groups,
                    ),
                    style = MaterialTheme.typography.labelMedium,
                )
                (listOf(group) + otherGroups).forEach { destination ->
                    key(destination.id) {
                        GroupReviewDestinationIdentity(destination)
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
internal fun reviewMemberPublicName(value: String?): String? {
    val publicName = ProfileSanitizer.displayName(value) ?: return null
    return publicName.takeUnless(IdentityFormatter::isNostrIdentityFallback)
}

private class GroupMembersReviewTicket {
    var active: Boolean = true

    fun claim(
        ownerCurrent: () -> Boolean,
        busy: () -> Boolean,
        selectionCurrent: () -> Boolean,
    ): Boolean {
        if (!ownerCurrent()) return false
        if (!active || busy()) return false
        if (!selectionCurrent()) return false
        active = false
        return true
    }
}

private fun groupMembersReviewConfirmEnabled(
    current: Boolean,
    busy: Boolean,
    hasMembers: Boolean,
    hasGroups: Boolean,
): Boolean {
    if (!current || busy || !hasMembers) return false
    return hasGroups
}

/** The frozen review belongs to one active account and runtime. */
private fun groupMembersReviewOwnerCurrent(
    appState: WhiteNoiseAppState,
    actingAccount: String?,
    runtime: Int,
): Boolean {
    if (actingAccount == null || actingAccount != appState.activeAccountRef) return false
    if (runtime != appState.runtimeGeneration) return false
    if (appState.signOutInProgress || appState.wipeInProgress) return false
    return appState.retainedAccountReactivationRef == null
}
