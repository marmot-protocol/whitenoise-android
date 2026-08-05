package dev.ipf.whitenoise.android.ui.profile

import dev.ipf.marmotkit.UserProfileMetadataFfi
import dev.ipf.whitenoise.android.core.RecipientSearch
import dev.ipf.whitenoise.android.ui.chats.newchat.selectedMemberAvatarUrl
import dev.ipf.whitenoise.android.ui.chats.newchat.selectedMemberDisplayName
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileParityTest {
    @Test
    fun followLoadFailureKeepsPreviousStateAndCancellationPropagates() =
        runTest {
            assertTrue(loadProfileFollowing(previous = true) { error("binding unavailable") })
            assertFalse(loadProfileFollowing(previous = null) { error("binding unavailable") })
            var failure: Throwable? = null
            try {
                loadProfileFollowing(previous = false) { throw CancellationException("closed") }
            } catch (error: Throwable) {
                failure = error
            }
            assertTrue(failure is CancellationException)
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
        assertTrue(profileConversationChoiceEligible(listOf(me, target), target, me))
        assertFalse(profileConversationChoiceEligible(listOf(me, target, "c".repeat(64)), target, me))
        assertFalse(profileConversationChoiceEligible(listOf(me, "c".repeat(64)), target, me))
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

        assertEquals("Jack", selectedMemberDisplayName(candidate))
        assertEquals(remoteAvatar, selectedMemberAvatarUrl(candidate, localAvatarUrl = null))
        assertEquals(
            "https://example.com/local.png",
            selectedMemberAvatarUrl(candidate, "https://example.com/local.png"),
        )
    }
}
