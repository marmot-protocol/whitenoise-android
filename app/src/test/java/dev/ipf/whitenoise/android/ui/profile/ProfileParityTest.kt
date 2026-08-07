package dev.ipf.whitenoise.android.ui.profile

import dev.ipf.marmotkit.UserProfileMetadataFfi
import dev.ipf.whitenoise.android.core.RecipientSearch
import dev.ipf.whitenoise.android.ui.chats.newchat.selectedMemberAvatarUrl
import dev.ipf.whitenoise.android.ui.chats.newchat.selectedMemberDisplayName
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileParityTest {
    @Test
    fun followLoadFailureKeepsPreviousStateAndCancellationPropagates() =
        runTest {
            assertEquals(true, loadProfileFollowing(previous = true) { error("binding unavailable") })
            // An initial read failure stays unknown: reporting "not following"
            // would offer a follow to someone who may already be followed.
            assertNull(loadProfileFollowing(previous = null) { error("binding unavailable") })
            var failure: Throwable? = null
            try {
                loadProfileFollowing(previous = false) { throw CancellationException("closed") }
            } catch (error: Throwable) {
                failure = error
            }
            assertTrue(failure is CancellationException)
        }

    @Test
    fun noActiveAccountStaysUnknownRatherThanAssertingNotFollowing() =
        runTest {
            // The read returns null without an active account, matching the write
            // side, which throws — a false would enable a follow that must fail.
            assertNull(loadProfileFollowing(previous = null) { null })
            assertNull(loadProfileFollowing(previous = true) { null })
            val row = profileFollowRowState(following = null, loading = false, busy = false, creatingChat = false)
            assertFalse(row.enabled)
            assertFalse(row.inProgress)
            assertFalse(row.showsUnfollow)
        }

    @Test
    fun unknownFollowStatusDisablesTheRowWithoutSpinningOrPromisingAFollow() =
        runTest {
            val unknown = loadProfileFollowing(previous = null) { error("binding unavailable") }
            val row =
                profileFollowRowState(
                    following = unknown,
                    loading = false,
                    busy = false,
                    creatingChat = false,
                )
            assertFalse(row.enabled)
            assertFalse(row.inProgress)
            assertFalse(row.showsUnfollow)
        }

    @Test
    fun knownFollowStatusEnablesTheRowAndPicksTheOppositeAction() {
        val following =
            profileFollowRowState(following = true, loading = false, busy = false, creatingChat = false)
        assertTrue(following.enabled)
        assertTrue(following.showsUnfollow)

        val notFollowing =
            profileFollowRowState(following = false, loading = false, busy = false, creatingChat = false)
        assertTrue(notFollowing.enabled)
        assertFalse(notFollowing.showsUnfollow)

        val loading =
            profileFollowRowState(following = null, loading = true, busy = false, creatingChat = false)
        assertFalse(loading.enabled)
        assertTrue(loading.inProgress)
    }

    @Test
    fun namedTwoPersonGroupIsVisibleButUnnamedDmIsNot() {
        assertTrue(profileSharedGroupVisible(memberCount = 2, groupName = "Project"))
        assertFalse(profileSharedGroupVisible(memberCount = 2, groupName = ""))
        assertTrue(profileSharedGroupVisible(memberCount = 3, groupName = ""))
    }

    @Test
    fun conversationChoiceRequiresTheExactTwoPersonRoster() {
        val me = "a".repeat(64)
        val target = "b".repeat(64)
        assertTrue(eligible(listOf(me, target), target, me))
        assertFalse(eligible(listOf(me, target, "c".repeat(64)), target, me))
        assertFalse(eligible(listOf(me, "c".repeat(64)), target, me))
    }

    @Test
    fun conversationChoiceSkipsAnUnacceptedInvite() {
        val me = "a".repeat(64)
        val target = "b".repeat(64)
        assertFalse(eligible(listOf(me, target), target, me, pendingConfirmation = true))
    }

    @Test
    fun selectedDiscoveryCandidateKeepsEphemeralNameAndAvatar() {
        val remoteAvatar = "https://example.com/jack.png"
        val candidate =
            RecipientSearch.Candidate(
                accountIdHex = "b".repeat(64),
                displayName = "Jack",
                npub = "npub1jack",
                searchProfile =
                    UserProfileMetadataFfi(
                        name = "jack",
                        displayName = "Jack",
                        about = null,
                        picture = remoteAvatar,
                        banner = null,
                        nip05 = null,
                        lud16 = null,
                    ),
            )

        assertEquals("Jack", selectedMemberDisplayName(candidate, liveDisplayName = null))
        assertEquals(remoteAvatar, selectedMemberAvatarUrl(candidate, localAvatarUrl = null))
        assertEquals(
            "https://example.com/local.png",
            selectedMemberAvatarUrl(candidate, "https://example.com/local.png"),
        )
    }

    @Test
    fun selectedMemberNameFollowsALateResolvingProfile() {
        // What ContactPickerScreen bakes in when a pasted npub is selected before
        // its profile has loaded.
        val frozen =
            RecipientSearch.Candidate(
                accountIdHex = "c".repeat(64),
                displayName = "npub1abcde…vwxyz",
                npub = "npub1abcdefghijklmnopqrstuvwxyz",
            )

        assertEquals("npub1abcde…vwxyz", selectedMemberDisplayName(frozen, liveDisplayName = null))
        assertEquals("npub1abcde…vwxyz", selectedMemberDisplayName(frozen, liveDisplayName = "  "))
        assertEquals("Camille", selectedMemberDisplayName(frozen, liveDisplayName = "Camille"))
    }

    private fun eligible(
        memberIds: List<String>,
        target: String,
        active: String,
        pendingConfirmation: Boolean = false,
    ): Boolean =
        profileConversationChoiceEligible(
            memberIds = memberIds,
            targetAccountIdHex = target,
            activeAccountIdHex = active,
            pendingConfirmation = pendingConfirmation,
        )
}
