package dev.ipf.whitenoise.android.state

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WarmResumeOfflineRehydrationTest {
    @Test
    fun authoritativeEmptyLocalSnapshotIsStillRetainedAcrossActivityRebind() {
        assertTrue(
            shouldPreserveChatListProjection(
                hasSeededLocalSnapshot = false,
                preserveLoadedContent = true,
                hasLoadedLocalSnapshot = true,
            ),
        )
    }

    @Test
    fun freshControllerCannotPretendItsEmptyProjectionIsAuthoritative() {
        assertFalse(
            shouldPreserveChatListProjection(
                hasSeededLocalSnapshot = false,
                preserveLoadedContent = true,
                hasLoadedLocalSnapshot = false,
            ),
        )
    }

    @Test
    fun accountSwitchSeedIsImmediatelyUsefulWithoutNetworkEnrichment() {
        assertTrue(
            shouldPreserveChatListProjection(
                hasSeededLocalSnapshot = true,
                preserveLoadedContent = false,
                hasLoadedLocalSnapshot = false,
            ),
        )
    }
}
