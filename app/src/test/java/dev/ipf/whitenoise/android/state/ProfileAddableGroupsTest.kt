package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.AppBlobEndpointFfi
import dev.ipf.marmotkit.AppGroupEncryptedMediaComponentFfi
import dev.ipf.marmotkit.AppGroupMemberRecordFfi
import dev.ipf.marmotkit.AppGroupRecordFfi
import dev.ipf.whitenoise.android.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileAddableGroupsTest {
    @Test
    fun includesOnlyAdminGroupsWhereTargetIsMissing() {
        val self = "self"
        val target = "target"
        val eligible = item("eligible", name = "Friends", admins = listOf(self), members = listOf(self, "bob"))
        val notAdmin =
            item("not-admin", name = "Nope", admins = emptyList(), members = listOf(self, "bob"))
        val alreadyMember =
            item("already", name = "Already", admins = listOf(self), members = listOf(self, target))
        val pendingInvite =
            item(
                "pending",
                name = "Pending",
                admins = listOf(self),
                members = listOf(self),
                pending = true,
            )
        val directMessage = item("dm", name = "", admins = listOf(self), members = listOf(self, "bob"))

        assertEquals(
            listOf("eligible"),
            profileAddableGroupItems(
                items = listOf(eligible, notAdmin, alreadyMember, pendingInvite, directMessage),
                targetAccountIdHex = target,
                activeAccountIdHex = self,
            ).map { it.group.groupIdHex },
        )
    }

    @Test
    fun selfProfileHasNoAddableGroups() {
        assertTrue(
            profileAddableGroupItems(
                items =
                    listOf(
                        item(
                            "group",
                            name = "Friends",
                            admins = listOf("self"),
                            members = listOf("self", "bob"),
                        ),
                    ),
                targetAccountIdHex = "SELF",
                activeAccountIdHex = "self",
            ).isEmpty(),
        )
    }

    @Test
    fun inviteToastCoversSuccessPartialAndFailureCounts() {
        assertInviteToast(
            outcome = ProfileGroupInviteOutcome(attempted = 1, failures = 0),
            messageRes = R.string.toast_invite_sent,
        )
        assertInviteToast(
            outcome = ProfileGroupInviteOutcome(attempted = 3, failures = 0),
            messageRes = R.string.toast_invites_sent_to_groups,
        )

        val failure = AppText.Plain("relay unavailable")
        assertInviteToast(
            outcome = ProfileGroupInviteOutcome(attempted = 3, failures = 1, firstFailure = failure),
            messageRes = R.string.toast_invites_sent_to_groups_partial,
            detail = failure,
        )
        assertInviteToast(
            outcome = ProfileGroupInviteOutcome(attempted = 2, failures = 2, firstFailure = failure),
            messageRes = R.string.toast_couldnt_add_members,
            detail = failure,
        )
    }

    @Test
    fun inviteOutcomeDismissesOnlyAfterCompleteSuccess() {
        assertTrue(ProfileGroupInviteOutcome(attempted = 2, failures = 0).completedSuccessfully)
        assertFalse(ProfileGroupInviteOutcome(attempted = 2, failures = 1).completedSuccessfully)
        assertFalse(ProfileGroupInviteOutcome(attempted = 2, failures = 2).completedSuccessfully)
        assertFalse(ProfileGroupInviteOutcome(attempted = 0, failures = 0).completedSuccessfully)
    }

    @Test
    fun noInviteToastWhenNothingWasAttempted() {
        assertNull(profileGroupInviteToast(ProfileGroupInviteOutcome(attempted = 0, failures = 0)))
    }

    private fun assertInviteToast(
        outcome: ProfileGroupInviteOutcome,
        messageRes: Int,
        detail: AppText? = null,
    ) {
        val toast = profileGroupInviteToast(outcome)
        assertEquals(messageRes, toast?.messageRes)
        assertEquals(detail, toast?.detail)
    }

    private fun item(
        groupId: String,
        name: String,
        admins: List<String>,
        members: List<String>,
        pending: Boolean = false,
    ) = ChatListItem(
        group = group(groupId, name, admins, pending),
        latest = null,
        otherMemberAccount = null,
        memberCount = members.size,
        memberSnapshot = GroupMemberSnapshot(members.map { member(it) }),
    )

    private fun group(
        groupId: String,
        name: String,
        admins: List<String>,
        pending: Boolean,
    ) = AppGroupRecordFfi(
        groupIdHex = groupId,
        endpoint = "endpoint",
        name = name,
        description = "",
        admins = admins,
        relays = listOf("wss://relay.example"),
        nostrGroupIdHex = "nostr-$groupId",
        avatarUrl = null,
        avatarDim = null,
        avatarThumbhash = null,
        encryptedMedia = encryptedMedia(),
        archived = false,
        pendingConfirmation = pending,
        welcomerAccountIdHex = null,
        viaWelcomeMessageIdHex = null,
        disappearingMessageSecs = 0uL,
    )

    private fun member(memberId: String) =
        AppGroupMemberRecordFfi(
            memberIdHex = memberId,
            account = memberId,
            local = false,
        )

    private fun encryptedMedia() =
        AppGroupEncryptedMediaComponentFfi(
            componentId = 0x8008u,
            component = "marmot.group.encrypted-media.v1",
            required = true,
            mediaFormat = "encrypted-media-v1",
            allowedLocatorKinds = listOf("blossom-v1"),
            defaultBlobEndpoints =
                listOf(
                    AppBlobEndpointFfi(
                        locatorKind = "blossom-v1",
                        baseUrl = "https://blossom.primal.net",
                    ),
                ),
        )
}
