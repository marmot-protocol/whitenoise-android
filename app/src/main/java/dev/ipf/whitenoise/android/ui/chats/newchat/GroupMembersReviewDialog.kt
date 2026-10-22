@file:Suppress("MatchingDeclarationName") // The dialog and its destination presentation stay together.

package dev.ipf.whitenoise.android.ui.chats.newchat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
    initialAdminIds: Set<String> = emptySet(),
    onInitialAdminChange: ((RecipientSearch.Candidate, Boolean) -> Unit)? = null,
    title: String = stringResource(groupReviewAddMembersLabel(members.size)),
    description: String? = stringResource(R.string.group_review_invitation_visibility),
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
    val reviewedMembers = members.map { it.accountIdHex }
    val reviewedGroups = groups.map { it.id }
    DisposableEffect(ticket) { onDispose { ticket.invalidate() } }
    val current = groupMembersReviewOwnerCurrent(appState, actingAccount, runtime)

    LaunchedEffect(current) {
        if (!current) {
            ticket.invalidate()
            onDismiss()
        }
    }
    WhiteNoiseAlertDialog(
        modifier = Modifier.testTag("group.members.review.dialog"),
        onDismissRequest = {
            if (dismissEnabled && ticket.active) {
                ticket.invalidate()
                onDismiss()
            }
        },
        title = { GroupMembersReviewTitle(appState, title, actingAccount) },
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
                    ticket.invalidate()
                    onDismiss()
                }
            }, enabled = dismissEnabled) { Text(stringResource(R.string.cancel)) }
        },
        text = {
            Column(
                Modifier.fillMaxWidth().fadingVerticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
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

/** The action is singular only for one selected person, independent of locale plural categories. */
internal fun groupReviewAddMembersLabel(memberCount: Int): Int {
    if (memberCount == 1) return R.string.group_review_add_member
    return R.string.group_action_add_members
}

/** Keep the captured actor at the top end and reflow a title that cannot share its line. */
@Composable
@Suppress("FunctionNaming")
private fun GroupMembersReviewTitle(
    appState: WhiteNoiseAppState,
    title: String,
    actingAccount: String?,
) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val fontScale = LocalDensity.current.fontScale
        var titleNeedsLine by remember(title, actingAccount, fontScale, maxWidth) { mutableStateOf(false) }
        val largeText =
            fontScale > GROUP_REVIEW_LARGE_FONT_SCALE || MaterialTheme.typography.bodyMedium.fontSize >= 20.sp
        if (largeText || maxWidth < GroupReviewPairedMinWidth || titleNeedsLine) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                GroupActingAccountIdentity(appState, actingAccount)
                Text(title)
            }
        } else {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    title,
                    Modifier.weight(1f),
                    maxLines = 1,
                    onTextLayout = { if (it.hasVisualOverflow) titleNeedsLine = true },
                )
                GroupActingAccountIdentity(appState, actingAccount, Modifier)
            }
        }
    }
}

/** Shows one public identity beside its destinations; arrows are reserved for invitations. */
@Composable
@Suppress("FunctionNaming", "LongMethod", "LongParameterList")
internal fun GroupMemberReviewPair(
    appState: WhiteNoiseAppState,
    group: MemberReviewGroup,
    member: RecipientSearch.Candidate,
    busy: Boolean = false,
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
    val label = name ?: shortIdentifier.ifBlank { stringResource(R.string.unknown) }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val stacked =
                LocalDensity.current.fontScale > GROUP_REVIEW_LARGE_FONT_SCALE ||
                    MaterialTheme.typography.bodyMedium.fontSize >= 20.sp ||
                    maxWidth < GroupReviewPairedMinWidth
            val person: @Composable () -> Unit = {
                GroupReviewPersonIdentity(
                    appState = appState,
                    member = member,
                    role = stringResource(R.string.member),
                    modifier = Modifier.testTag("group.members.review.member"),
                )
            }
            val destinations: @Composable () -> Unit = {
                GroupReviewDestinations(listOf(group) + otherGroups)
            }
            if (stacked) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    person()
                    destinations()
                }
            } else {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Column(Modifier.weight(1f)) { person() }
                    if (showDirection) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowForward,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp).align(Alignment.CenterVertically),
                        )
                    }
                    Column(Modifier.weight(1f)) { destinations() }
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
    }
}

/** Preserve each frozen destination and its order, even when names are identical. */
@Composable
@Suppress("FunctionNaming")
private fun GroupReviewDestinations(groups: List<MemberReviewGroup>) {
    Column(
        Modifier.testTag("group.members.review.destination"),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        groups.forEach { destination ->
            key(destination.id) { GroupReviewDestinationIdentity(destination) }
        }
    }
}

private const val GROUP_REVIEW_LARGE_FONT_SCALE = 1.3f
private val GroupReviewPairedMinWidth = 250.dp

/** Rejects identity placeholders as names so an unnamed person has one abbreviated identifier. */
internal fun reviewMemberPublicName(value: String?): String? {
    val publicName = ProfileSanitizer.displayName(value) ?: return null
    return publicName.takeUnless(IdentityFormatter::isNostrIdentityFallback)
}

/** Single-use presentation ownership shared by disposal, dismissal and confirmation. */
internal class GroupMembersReviewTicket {
    var active: Boolean = true
        private set

    /** Revokes callbacks retained from a presentation that has ended. */
    fun invalidate() {
        active = false
    }

    fun claim(
        ownerCurrent: () -> Boolean,
        busy: () -> Boolean,
        selectionCurrent: () -> Boolean,
    ): Boolean =
        when {
            !ownerCurrent() -> false
            !active || busy() -> false
            !selectionCurrent() -> false
            else -> {
                active = false
                true
            }
        }
}

private fun groupMembersReviewConfirmEnabled(
    current: Boolean,
    busy: Boolean,
    hasMembers: Boolean,
    hasGroups: Boolean,
): Boolean = current && !busy && hasMembers && hasGroups

/** The frozen review belongs to one active account and runtime. */
private fun groupMembersReviewOwnerCurrent(
    appState: WhiteNoiseAppState,
    actingAccount: String?,
    runtime: Int,
): Boolean =
    when {
        actingAccount == null || actingAccount != appState.activeAccountRef -> false
        runtime != appState.runtimeGeneration -> false
        appState.signOutInProgress || appState.wipeInProgress -> false
        else -> appState.retainedAccountReactivationRef == null
    }
