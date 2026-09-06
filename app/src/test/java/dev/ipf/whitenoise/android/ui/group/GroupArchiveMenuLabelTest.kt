package dev.ipf.whitenoise.android.ui.group

import dev.ipf.whitenoise.android.R
import org.junit.Assert.assertEquals
import org.junit.Test

/** The archive menu copy follows the optimistic presented state directly. */
class GroupArchiveMenuLabelTest {
    @Test
    fun settledStatesOfferTheOppositeAction() {
        assertEquals(
            R.string.archive_chat,
            archiveMenuLabel(archiveMutationInFlight = false, presentedArchived = false),
        )
        assertEquals(
            R.string.unarchive_chat,
            archiveMenuLabel(archiveMutationInFlight = false, presentedArchived = true),
        )
    }

    @Test
    fun inFlightStatesDescribeTheAcceptedTarget() {
        assertEquals(
            R.string.archiving_chat,
            archiveMenuLabel(archiveMutationInFlight = true, presentedArchived = true),
        )
        assertEquals(
            R.string.restoring_chat,
            archiveMenuLabel(archiveMutationInFlight = true, presentedArchived = false),
        )
    }

    /**
     * The dispatch gap between recording the tap and the mutation coroutine
     * staging the optimistic intent must already read as the requested
     * direction, not the stale presented state.
     */
    @Test
    fun aPendingTargetOwnsTheLabelBeforeTheOptimisticIntentStages() {
        assertEquals(
            R.string.archiving_chat,
            archiveMenuLabelForTarget(pendingArchiveTarget = true, presentedArchived = false),
        )
        assertEquals(
            R.string.restoring_chat,
            archiveMenuLabelForTarget(pendingArchiveTarget = false, presentedArchived = true),
        )
    }

    /** Without a pending target the label falls back to the settled presentation. */
    @Test
    fun noPendingTargetFallsBackToThePresentedState() {
        assertEquals(
            R.string.archive_chat,
            archiveMenuLabelForTarget(pendingArchiveTarget = null, presentedArchived = false),
        )
        assertEquals(
            R.string.unarchive_chat,
            archiveMenuLabelForTarget(pendingArchiveTarget = null, presentedArchived = true),
        )
    }
}
