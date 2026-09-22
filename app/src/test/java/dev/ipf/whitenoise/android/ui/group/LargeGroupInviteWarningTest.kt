package dev.ipf.whitenoise.android.ui.group

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LargeGroupInviteWarningTest {
    @Test
    fun thresholdCasesUseTheAuthoritativeRosterAndStagedRecipients() {
        assertFalse(projection(existing = 48, selected = listOf("new-a")).shouldWarn)
        assertTrue(projection(existing = 49, selected = listOf("new-a")).shouldWarn)
        assertTrue(projection(existing = 49, selected = listOf("new-a", "new-b")).shouldWarn)
        assertTrue(projection(existing = 50).shouldWarn)
        assertTrue(projection(existing = 50, selected = listOf("new-a")).shouldWarn)
    }

    @Test
    fun existingPendingSelectedAndActiveMembersAreCountedOnce() {
        val result =
            largeGroupInviteProjection(
                rosterReady = true,
                authoritativeMemberIds = memberIds(48) + "EXISTING",
                activeAccountIdHex = "MEMBER-0",
                pendingInviteMemberIds = listOf("pending", "PENDING", "existing"),
                stagedRecipientIds = listOf("new-a", "NEW-A", "pending", "existing"),
            )

        assertEquals(51, result?.memberCount)
        assertTrue(result?.shouldWarn == true)
    }

    @Test
    fun activeAccountIsIncludedWhenTheRosterDoesNotContainIt() {
        val result =
            largeGroupInviteProjection(
                rosterReady = true,
                authoritativeMemberIds = memberIds(48),
                activeAccountIdHex = "self",
                pendingInviteMemberIds = emptyList(),
                stagedRecipientIds = listOf("new-a"),
            )

        assertEquals(50, result?.memberCount)
        assertTrue(result?.shouldWarn == true)
    }

    @Test
    fun unresolvedRosterNeverProducesAMisleadingCount() {
        assertNull(
            largeGroupInviteProjection(
                rosterReady = false,
                authoritativeMemberIds = memberIds(50),
                activeAccountIdHex = "member-0",
                pendingInviteMemberIds = listOf("pending"),
                stagedRecipientIds = listOf("new-a"),
            ),
        )
    }

    private fun projection(
        existing: Int,
        selected: List<String> = emptyList(),
    ): LargeGroupInviteProjection =
        checkNotNull(
            largeGroupInviteProjection(
                rosterReady = true,
                authoritativeMemberIds = memberIds(existing),
                activeAccountIdHex = "member-0",
                pendingInviteMemberIds = emptyList(),
                stagedRecipientIds = selected,
            ),
        )

    private fun memberIds(count: Int): List<String> = List(count) { index -> "member-$index" }
}
