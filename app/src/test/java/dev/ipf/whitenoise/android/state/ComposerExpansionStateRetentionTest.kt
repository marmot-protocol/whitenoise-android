package dev.ipf.whitenoise.android.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ComposerExpansionStateRetentionTest {
    /** A group id is insufficient ownership: account and conversation must both match. */
    @Test
    fun stableAccountConversationLookupRetainsManualAndFullScreenChoices() {
        val retention = ComposerExpansionStateRetention()

        retention.update(ACCOUNT_A, GROUP_A, manual(240f), draftGeneration = 3L)
        retention.update(ACCOUNT_A, GROUP_B, fullScreen(), draftGeneration = 8L)
        retention.update(ACCOUNT_B, GROUP_A, manual(180f), draftGeneration = 5L)

        assertEquals(manual(240f), retention.preferenceFor(ACCOUNT_A, GROUP_A))
        assertEquals(fullScreen(), retention.preferenceFor(ACCOUNT_A, GROUP_B))
        assertEquals(manual(180f), retention.preferenceFor(ACCOUNT_B, GROUP_A))
        assertNull(retention.preferenceFor(ACCOUNT_B, GROUP_B))
    }

    /** Ordinary text mutations advance stale-send fencing without changing the chosen height. */
    @Test
    fun clearingTextAdvancesTheDraftFenceWithoutResettingTheChoice() {
        val retention = ComposerExpansionStateRetention()
        retention.update(ACCOUNT_A, GROUP_A, manual(240f), draftGeneration = 3L)

        retention.onDraftGenerationAdvanced(ACCOUNT_A, GROUP_A, draftGeneration = 4L)

        assertEquals(manual(240f), retention.preferenceFor(ACCOUNT_A, GROUP_A))
    }

    /** Optimistic acceptance is reversible until MDK reports a terminal outcome. */
    @Test
    fun acceptedSendPresentsAutomaticThenTerminalFailureRestoresTheOverride() {
        val retention = ComposerExpansionStateRetention()
        retention.update(ACCOUNT_A, GROUP_A, manual(240f), draftGeneration = 3L)
        val revision = checkNotNull(retention.revisionFor(ACCOUNT_A, GROUP_A))

        assertTrue(retention.onSendAccepted(ACCOUNT_A, GROUP_A, 3L, revision))
        assertNull(retention.preferenceFor(ACCOUNT_A, GROUP_A))
        assertEquals(
            listOf(
                SavedComposerExpansion(
                    ACCOUNT_A,
                    GROUP_A,
                    RetainedComposerExpansionMode.Manual,
                    manualHeightDp = 240f,
                ),
            ),
            retention.savedRecords(),
        )

        assertTrue(retention.onSendTerminalFailure(ACCOUNT_A, GROUP_A, 3L, revision))
        assertEquals(manual(240f), retention.preferenceFor(ACCOUNT_A, GROUP_A))
    }

    /** A durable result removes only the UI revision captured by that send. */
    @Test
    fun durableAcceptanceDeletesOnlyTheCapturedDraftOverride() {
        val retention = ComposerExpansionStateRetention()
        retention.update(ACCOUNT_A, GROUP_A, fullScreen(), draftGeneration = 3L)
        val revision = checkNotNull(retention.revisionFor(ACCOUNT_A, GROUP_A))
        retention.onSendAccepted(ACCOUNT_A, GROUP_A, 3L, revision)

        assertTrue(retention.onSendDurablyAccepted(ACCOUNT_A, GROUP_A, 3L, revision))
        assertNull(retention.preferenceFor(ACCOUNT_A, GROUP_A))
        assertTrue(retention.savedRecords().isEmpty())
    }

    /** Older send callbacks cannot overwrite a newer edit or resize. */
    @Test
    fun newerDraftOrResizeMakesEveryOldSendCallbackStale() {
        val retention = ComposerExpansionStateRetention()
        retention.update(ACCOUNT_A, GROUP_A, manual(240f), draftGeneration = 3L)
        val oldRevision = checkNotNull(retention.revisionFor(ACCOUNT_A, GROUP_A))

        retention.onDraftGenerationAdvanced(ACCOUNT_A, GROUP_A, draftGeneration = 4L)
        retention.update(ACCOUNT_A, GROUP_A, manual(300f), draftGeneration = 4L)
        retention.update(ACCOUNT_A, GROUP_A, manual(180f), draftGeneration = 3L)

        assertFalse(retention.onSendAccepted(ACCOUNT_A, GROUP_A, 3L, oldRevision))
        assertFalse(retention.onSendDurablyAccepted(ACCOUNT_A, GROUP_A, 3L, oldRevision))
        assertFalse(retention.onSendTerminalFailure(ACCOUNT_A, GROUP_A, 3L, oldRevision))
        assertEquals(manual(300f), retention.preferenceFor(ACCOUNT_A, GROUP_A))
    }

    /** Text entered after optimistic acceptance starts with automatic geometry. */
    @Test
    fun typingAfterOptimisticAcceptanceStartsAFreshAutomaticDraft() {
        val retention = ComposerExpansionStateRetention()
        retention.update(ACCOUNT_A, GROUP_A, manual(240f), draftGeneration = 3L)
        val revision = checkNotNull(retention.revisionFor(ACCOUNT_A, GROUP_A))
        retention.onSendAccepted(ACCOUNT_A, GROUP_A, 3L, revision)

        retention.onDraftGenerationAdvanced(ACCOUNT_A, GROUP_A, draftGeneration = 4L)

        assertNull(retention.preferenceFor(ACCOUNT_A, GROUP_A))
        assertFalse(retention.onSendTerminalFailure(ACCOUNT_A, GROUP_A, 3L, revision))
    }

    /** Explicit automatic mode and owner removal delete only their exact records. */
    @Test
    fun automaticToggleAndExplicitLifecycleRemovalDeleteOnlyTheirOwner() {
        val retention = ComposerExpansionStateRetention()
        retention.update(ACCOUNT_A, GROUP_A, manual(240f), draftGeneration = 1L)
        retention.update(ACCOUNT_A, GROUP_B, fullScreen(), draftGeneration = 1L)
        retention.update(ACCOUNT_B, GROUP_A, manual(180f), draftGeneration = 1L)

        retention.update(ACCOUNT_A, GROUP_A, preference = null, draftGeneration = 1L)
        retention.removeGroup(ACCOUNT_A, GROUP_B)

        assertNull(retention.preferenceFor(ACCOUNT_A, GROUP_A))
        assertNull(retention.preferenceFor(ACCOUNT_A, GROUP_B))
        assertEquals(manual(180f), retention.preferenceFor(ACCOUNT_B, GROUP_A))

        retention.removeAccount(ACCOUNT_B)
        assertNull(retention.preferenceFor(ACCOUNT_B, GROUP_A))
    }

    /** Restoration rejects malformed records and retains only the configured LRU bound. */
    @Test
    fun savedStateRestoresOnlyValidatedBoundedUiRecords() {
        val source = ComposerExpansionStateRetention(maxRecords = 2)
        source.update(ACCOUNT_A, GROUP_A, manual(200f), draftGeneration = 1L)
        source.update(ACCOUNT_A, GROUP_B, fullScreen(), draftGeneration = 1L)
        source.update(ACCOUNT_B, GROUP_A, manual(300f), draftGeneration = 1L)

        val saved =
            source.savedRecords() +
                SavedComposerExpansion("", GROUP_A, RetainedComposerExpansionMode.FullScreen, null) +
                SavedComposerExpansion(ACCOUNT_A, "", RetainedComposerExpansionMode.Manual, 200f) +
                SavedComposerExpansion(ACCOUNT_A, "bad-height", RetainedComposerExpansionMode.Manual, Float.NaN)
        val restored = ComposerExpansionStateRetention(maxRecords = 2)

        restored.restoreIfEmpty(saved)

        assertNull(restored.preferenceFor(ACCOUNT_A, GROUP_A))
        assertEquals(fullScreen(), restored.preferenceFor(ACCOUNT_A, GROUP_B))
        assertEquals(manual(300f), restored.preferenceFor(ACCOUNT_B, GROUP_A))
        assertEquals(2, restored.savedRecords().size)
    }

    /** Builds a valid density-independent manual-height preference. */
    private fun manual(heightDp: Float) =
        RetainedComposerExpansion(
            mode = RetainedComposerExpansionMode.Manual,
            manualHeightDp = heightDp,
        )

    /** Builds the full-screen preference, which deliberately carries no height. */
    private fun fullScreen() =
        RetainedComposerExpansion(
            mode = RetainedComposerExpansionMode.FullScreen,
            manualHeightDp = null,
        )

    private companion object {
        const val ACCOUNT_A = "account-a"
        const val ACCOUNT_B = "account-b"
        const val GROUP_A = "group-a"
        const val GROUP_B = "group-b"
    }
}
