package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.TimelineEditSummaryFfi
import dev.ipf.whitenoise.android.core.EditState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class TimelineOptimisticEditsTest {
    @Test
    fun pendingEditShowsItsBodyWithoutChangingTheConfirmedAggregate() {
        val confirmed = mapOf("target" to EditState("before", 3, emptyList()))
        val edit = OptimisticEdit("after", "before", MessageStatus.Pending)

        val displayed = applyOptimisticEdits(confirmed, mapOf("target" to edit))

        assertEquals("after", displayed.getValue("target").latestText)
        assertEquals(3, displayed.getValue("target").count)
        assertEquals("before", confirmed.getValue("target").latestText)
        assertFalse(isConfirmedOptimisticEdit(edit, displayed["target"], null))
        assertTrue(isConfirmedOptimisticEdit(edit.copy(status = MessageStatus.Sent), displayed["target"], null))
    }

    @Test
    fun failedEditRestoresPreviousTextWithoutInventingAnEditedBadge() {
        val edit = OptimisticEdit("failed revision", "previous revision", MessageStatus.Failed)
        val confirmed = mapOf("edited" to EditState("failed revision", 2, emptyList()))

        val displayed = applyOptimisticEdits(confirmed, mapOf("edited" to edit, "original" to edit))

        assertEquals(setOf("edited"), displayed.keys)
        assertEquals("previous revision", displayed.getValue("edited").latestText)
        assertEquals(2, displayed.getValue("edited").count)
        assertEquals("failed revision", confirmed.getValue("edited").latestText)
    }

    @Test
    fun firstPendingEditGetsAnEditedIndicatorAndLeavesOtherMessagesAlone() {
        val other = EditState("unrelated", 4, emptyList())
        val displayed =
            applyOptimisticEdits(
                mapOf("other" to other),
                mapOf("target" to OptimisticEdit("after", "before", MessageStatus.Pending)),
            )

        assertEquals(EditState("after", 1, emptyList()), displayed["target"])
        assertSame(other, displayed["other"])
    }

    @Test
    fun nativeConfirmationRequiresThePublishedExactRevisionAndBody() {
        val edit = OptimisticEdit("after", "before", MessageStatus.Sent, nativeEditMessageId = "edit-token")
        val projected =
            requireNotNull(projectedTimelineMessage(timelineAppMessage("target")).projected).copy(
                plaintext = "after",
                edit = TimelineEditSummaryFfi(1uL, "edit-token", 2uL),
            )

        assertTrue(isConfirmedOptimisticEdit(edit, null, projected))
        assertFalse(isConfirmedOptimisticEdit(edit.copy(status = MessageStatus.Pending), null, projected))
        assertFalse(isConfirmedOptimisticEdit(edit, null, projected.copy(plaintext = "older revision")))
        val olderRevision = projected.copy(edit = TimelineEditSummaryFfi(1uL, "older-token", 1uL))
        assertFalse(isConfirmedOptimisticEdit(edit, null, olderRevision))
        assertFalse(isConfirmedOptimisticEdit(edit.copy(nativeEditMessageId = null), null, projected))
    }

    @Test
    fun sentMessageShowsEditProgressWithoutBecomingAPendingOriginalSend() {
        val message = localTimelineMessage(timelineAppMessage("target")).copy(status = MessageStatus.Sent)
        val edit = OptimisticEdit("after", "before", MessageStatus.Pending)
        val displayed = message.withOptimisticEditStatus(edit)

        assertEquals(MessageStatus.Pending, displayed.status)
        assertTrue(displayed.statusFromEdit)
        assertFalse(displayed.isPendingSend)
        val failed = message.withOptimisticEditStatus(edit.copy(status = MessageStatus.Failed))
        assertEquals(MessageStatus.Failed, failed.status)
        assertTrue(failed.statusFromEdit)
        assertSame(message, message.withOptimisticEditStatus(edit.copy(status = MessageStatus.Sent)))
    }

    @Test
    fun durablePendingOriginalUsesEditFailureWithoutLosingItsSendIdentity() {
        val message = localTimelineMessage(timelineAppMessage("target")).copy(status = MessageStatus.Pending)
        val edit =
            OptimisticEdit(
                "after",
                "before",
                MessageStatus.Failed,
                durableIntent = DurablePendingEditIntent("original-token", "edit-token"),
            )

        val displayed = message.withOptimisticEditStatus(edit)

        assertEquals(MessageStatus.Failed, displayed.status)
        assertFalse(displayed.statusFromEdit)
        assertSame(message, message.withOptimisticEditStatus(edit.copy(durableIntent = null)))
        val failedOriginal = message.copy(status = MessageStatus.Failed)
        assertSame(failedOriginal, failedOriginal.withOptimisticEditStatus(edit.copy(status = MessageStatus.Pending)))
    }

    @Test
    fun absentOverlaysPreserveTheOriginalSnapshots() {
        val confirmed = mapOf("target" to EditState("original", 1, emptyList()))
        val message = localTimelineMessage(timelineAppMessage("target"))

        assertSame(confirmed, applyOptimisticEdits(confirmed, emptyMap()))
        assertSame(message, message.withOptimisticEditStatus(null))
        assertFalse(isConfirmedOptimisticEdit(OptimisticEdit("after", "before", MessageStatus.Sent), null, null))
    }
}
