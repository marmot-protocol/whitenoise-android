@file:Suppress("MatchingDeclarationName") // The dialog and its destination presentation stay together.

package dev.ipf.whitenoise.android.ui.chats.newchat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.AvatarImageLoader
import dev.ipf.whitenoise.android.core.IdentityFormatter
import dev.ipf.whitenoise.android.core.ProfileSanitizer
import dev.ipf.whitenoise.android.core.RecipientSearch
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.EmojiLabel
import dev.ipf.whitenoise.android.ui.account.AccountAvatarButton
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
    val groupAnimation: dev.ipf.whitenoise.android.ui.common.GroupAvatarAnimation? = null,
)

/** Destination and acting account head one compact row per member; actions stay outside the scrolling body. */
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
    description: String? = null,
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
    // Identities are captured once when the review opens; Admin toggles made inside it are intended edits.
    val reviewedMembers = remember { members.map { it.accountIdHex } }
    val reviewedGroups = remember { groups.map { it.id } }
    DisposableEffect(ticket) { onDispose { ticket.invalidate() } }
    val current = groupMembersReviewOwnerCurrent(appState, actingAccount, runtime)

    LaunchedEffect(current) {
        if (!current) {
            ticket.invalidate()
            onDismiss()
        }
    }
    val selectionCurrent = sameReviewSelection(reviewedMembers, reviewedGroups, members, groups)
    LaunchedEffect(selectionCurrent) {
        if (!selectionCurrent) {
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
        title = { GroupMembersReviewTitle(appState, title, groups, invitation, actingAccount) },
        confirmButton = {
            TextButton(
                onClick = {
                    if (ticket.claim(
                            ownerCurrent = { groupMembersReviewOwnerCurrent(appState, actingAccount, runtime) },
                            busy = { liveBusy.value },
                            selectionCurrent = {
                                sameReviewSelection(
                                    reviewedMembers,
                                    reviewedGroups,
                                    liveMembers.value,
                                    liveGroups.value,
                                )
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
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (groups.size > 1) GroupReviewDestinationList(groups)
                description?.let { EmojiLabel(it, Modifier.padding(bottom = 8.dp)) }
                if (groups.isNotEmpty()) {
                    members.forEach { member ->
                        key(member.accountIdHex) {
                            GroupMemberReviewRow(
                                appState = appState,
                                member = member,
                                busy = busy,
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

/** Whether the people and destinations still match the ones the review opened with. */
private fun sameReviewSelection(
    reviewedMembers: List<String>,
    reviewedGroups: List<String>,
    members: List<RecipientSearch.Candidate>,
    groups: List<MemberReviewGroup>,
): Boolean = reviewedMembers == members.map { it.accountIdHex } && reviewedGroups == groups.map { it.id }

/** The action is singular only for one selected person, independent of locale plural categories. */
internal fun groupReviewAddMembersLabel(memberCount: Int): Int {
    if (memberCount == 1) return R.string.group_review_add_member
    return R.string.group_action_add_members
}

/**
 * The action and at most one destination, with the captured actor at the top end as in the app bar.
 * Several destinations are listed in the scrolling body instead, because this slot never scrolls.
 * Large text or a narrow dialog moves both pictures above the headline so it keeps the full width.
 */
@Composable
@Suppress("FunctionNaming")
private fun GroupMembersReviewTitle(
    appState: WhiteNoiseAppState,
    title: String,
    groups: List<MemberReviewGroup>,
    invitation: Boolean,
    actingAccount: String?,
) {
    val single = groups.singleOrNull()
    val destinationTag = if (single != null) Modifier.testTag("group.members.review.destination") else Modifier
    val headline: @Composable (Modifier) -> Unit = { modifier ->
        Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title)
            single?.let {
                EmojiLabel(
                    if (invitation) stringResource(R.string.group_review_destination, it.name) else it.name,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (groupReviewStacked(maxWidth)) {
            Column(destinationTag.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    single?.let { GroupReviewAvatar(it, GroupReviewAvatarSize) }
                    Spacer(Modifier.weight(1f))
                    GroupReviewActingAvatar(appState, actingAccount)
                }
                headline(Modifier.fillMaxWidth())
            }
        } else {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(
                    destinationTag.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(GroupReviewAvatarGap),
                ) {
                    single?.let { GroupReviewAvatar(it, GroupReviewAvatarSize) }
                    headline(Modifier.weight(1f))
                }
                GroupReviewActingAvatar(appState, actingAccount)
            }
        }
    }
}

/**
 * The captured actor's own picture. With a public npub it is a labelled copy button;
 * without one it is a plain picture, so it never offers an action that does nothing.
 */
@Composable
@Suppress("FunctionNaming")
private fun GroupReviewActingAvatar(
    appState: WhiteNoiseAppState,
    actingAccount: String?,
) {
    val account = appState.accounts.firstOrNull { it.label == actingAccount } ?: return
    val name = appState.accountDisplayNameCached(account.accountIdHex)
    val role = stringResource(R.string.group_action_acting_account)
    val npub = appState.npubForDisplay(account.accountIdHex)
    if (npub.isBlank()) {
        Box(
            Modifier
                .offset(x = GroupReviewActorTargetInset)
                .size(48.dp)
                .testTag("group.action.acting_account")
                .semantics { contentDescription = "$role, $name" },
            contentAlignment = Alignment.Center,
        ) {
            Avatar(
                name,
                account.accountIdHex,
                32.dp,
                pictureUrl = ProfileSanitizer.protocolImageUrl(appState.avatarUrl(account.accountIdHex)),
                pictureDomain = AvatarImageLoader.publicProfiles(),
            )
        }
        return
    }
    val clipboard = LocalClipboardManager.current
    AccountAvatarButton(
        title = name,
        seed = account.accountIdHex,
        pictureUrl = appState.avatarUrl(account.accountIdHex),
        size = 32.dp,
        touchTargetSize = 48.dp,
        actionDescription = role,
        onClickLabel = stringResource(R.string.copy),
        onClick = { clipboard.setText(AnnotatedString(npub)) },
        modifier = Modifier.offset(x = GroupReviewActorTargetInset).testTag("group.action.acting_account"),
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
            val stacked = groupReviewStacked(maxWidth)
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

/** Destination and member pictures share one size and gap so their names start on the same line. */
internal val GroupReviewAvatarSize = 40.dp
internal val GroupReviewAvatarGap = 12.dp

/** The actor's 32dp picture sits in a 48dp target; this pulls the picture flush with the trailing switches. */
private val GroupReviewActorTargetInset = 8.dp
private val GroupReviewPairedMinWidth = 250.dp

/** One rule for every review layout: large text or a narrow column stacks content instead of sharing a row. */
@Composable
internal fun groupReviewStacked(maxWidth: Dp): Boolean =
    LocalDensity.current.fontScale > GROUP_REVIEW_LARGE_FONT_SCALE ||
        MaterialTheme.typography.bodyMedium.fontSize >= 20.sp ||
        maxWidth < GroupReviewPairedMinWidth

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
