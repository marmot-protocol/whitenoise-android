package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.AppGroupMemberIdsFfi
import dev.ipf.marmotkit.ChatConversationKindFfi
import dev.ipf.marmotkit.ChatListRowFfi
import dev.ipf.marmotkit.GroupLifecycleStateFfi
import dev.ipf.marmotkit.SelfMembershipFfi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class AccountSwitchIdentityPreloadTest {
    @Test
    fun memberPreloadIncludesOnlyRowsWhoseFirstFrameIdentityDependsOnMembers() {
        val namedGroups = (1..200).map { index -> row("named-$index", "Group $index") }
        val direct = row("direct", "", ChatConversationKindFfi.DIRECT)
        val unnamedGroup = row("unnamed-group", "", ChatConversationKindFfi.GROUP)

        val required = accountSwitchFirstFrameMemberGroupIds(namedGroups + direct + unnamedGroup)

        assertEquals(listOf("direct", "unnamed-group"), required)
    }

    @Test
    fun duplicateIdentityRowsAreRequestedOnlyOnceIgnoringHexCase() {
        val lower = row("abcdef", "", ChatConversationKindFfi.DIRECT)
        val upper = row("ABCDEF", "", ChatConversationKindFfi.DIRECT)

        assertEquals(listOf("abcdef"), accountSwitchFirstFrameMemberGroupIds(listOf(lower, upper)))
    }

    @Test
    fun groupNameThatSanitizesToEmptyStillPreloadsItsMemberDerivedFallback() {
        val spoofOnlyName = row("sanitized-empty", "\u202E\u200B", ChatConversationKindFfi.GROUP)

        assertEquals(listOf("sanitized-empty"), accountSwitchFirstFrameMemberGroupIds(listOf(spoofOnlyName)))
    }

    @Test
    fun readinessTraceReportsCountsByProducerWithoutIdentityValues() {
        val named = row("named-secret", "Secret planning").copy(avatarUrl = SECRET_AVATAR)
        val direct = row("direct-secret", "", ChatConversationKindFfi.DIRECT)
        val unnamed = row("unnamed-secret", "", ChatConversationKindFfi.GROUP)
        val memberIds =
            listOf(
                AppGroupMemberIdsFfi("direct-secret", listOf(SELF, PEER), emptyList()),
                AppGroupMemberIdsFfi("unnamed-secret", listOf(SELF, PEER, OTHER), emptyList()),
            )
        val profiles =
            listOf(
                AccountSwitchProfileSeed(PEER, null, "Secret Alice", SECRET_PEER_AVATAR),
                AccountSwitchProfileSeed(TOP_BAR_READY, null, "Secret work", null),
            )

        val counts =
            accountSwitchIdentityStateCounts(
                rows = listOf(named, direct, unnamed),
                memberIds = memberIds,
                profiles = profiles,
                activeAccountIdHex = SELF,
                topBarProfileIds = listOf(TOP_BAR_READY, TOP_BAR_MISSING),
            )

        assertEquals(
            AccountSwitchIdentityStateCounts(
                namedGroupTitleReady = 1,
                namedGroupTitleMissing = 1,
                directPeerPresentationReady = 1,
                directPeerPresentationMissing = 0,
                topBarProfileReady = 1,
                topBarProfileMissing = 1,
                memberDerivedPresentationReady = 2,
                memberDerivedPresentationMissing = 0,
                avatarIdentityKeyReady = 2,
                avatarIdentityKeyMissing = 1,
            ),
            counts,
        )
        val trace = counts.privacySafeTrace()
        listOf("Secret", "named-secret", PEER, SECRET_AVATAR, SECRET_PEER_AVATAR).forEach { privateValue ->
            assertFalse("trace leaked $privateValue", privateValue in trace)
        }
    }

    private fun row(
        groupIdHex: String,
        groupName: String,
        kind: ChatConversationKindFfi = ChatConversationKindFfi.GROUP,
    ) = ChatListRowFfi(
        selfMembership = SelfMembershipFfi.MEMBER,
        unreadMentionCount = 0uL,
        unreadMention = false,
        groupIdHex = groupIdHex,
        archived = false,
        pendingConfirmation = false,
        title = groupName,
        groupName = groupName,
        avatarUrl = null,
        avatar = null,
        lastMessage = null,
        unreadCount = 0uL,
        hasUnread = false,
        firstUnreadMessageIdHex = null,
        lastReadMessageIdHex = null,
        lastReadTimelineAt = null,
        conversationCreatedAt = 1uL,
        activitySortAt = 1uL,
        updatedAt = 1uL,
        leaveRequestPending = false,
        leaveRequestedAtMs = null,
        manuallyMarkedUnread = false,
        conversationKind = kind,
        muted = false,
        mutedUntilMs = null,
        pinned = false,
        pinnedPosition = null,
        lifecycleState = GroupLifecycleStateFfi.STABLE,
        disbanding = false,
        disbandRequest = null,
    )

    private companion object {
        val SELF = "11".repeat(32)
        val PEER = "22".repeat(32)
        val OTHER = "33".repeat(32)
        val TOP_BAR_READY = "44".repeat(32)
        val TOP_BAR_MISSING = "55".repeat(32)
        const val SECRET_AVATAR = "https://private.example/group-secret.png"
        const val SECRET_PEER_AVATAR = "https://private.example/peer-secret.png"
    }
}
