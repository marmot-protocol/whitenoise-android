package dev.ipf.whitenoise.android.ui.group

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LargeGroupInviteWarningTest {
    /** Covers the boundary cases required by the issue without relying on UI state. */
    @Test
    fun thresholdCasesUseTheAuthoritativeRosterAndStagedRecipients() {
        assertFalse(projection(existing = 48, selected = listOf("new-a")).shouldWarn)
        assertTrue(projection(existing = 49, selected = listOf("new-a")).shouldWarn)
        assertTrue(projection(existing = 49, selected = listOf("new-a", "new-b")).shouldWarn)
        assertTrue(projection(existing = 50).shouldWarn)
        assertTrue(projection(existing = 50, selected = listOf("new-a")).shouldWarn)
    }

    /** Proves normalization prevents existing, pending, and repeated staged identities from being counted twice. */
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

    /** Ensures the active account contributes to the projection when an incomplete roster omits it. */
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

    /** Rejects a projection until the controller has supplied an authoritative roster. */
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

    /** Builds a standard authoritative projection for concise threshold assertions. */
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

    /** Generates stable unique member identities for projection fixtures. */
    private fun memberIds(count: Int): List<String> = List(count) { index -> "member-$index" }
}
