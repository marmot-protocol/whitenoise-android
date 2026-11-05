package dev.ipf.whitenoise.android.ui.conversation.composer

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import dev.ipf.whitenoise.android.state.ComposerShortcut
import dev.ipf.whitenoise.android.state.ComposerShortcutSide
import dev.ipf.whitenoise.android.state.ComposerToolbarSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ComposerToolbarEditTest {
    private val more = ComposerShortcut.More
    private val camera = ComposerShortcut.Camera
    private val paste = ComposerShortcut.Paste

    private fun editor(): ComposerToolbarEditState =
        ComposerToolbarEditState(
            ComposerToolbarSettings(listOf(more, camera, paste), rightShortcuts = setOf(paste)),
        ).apply {
            selectedArea = Rect(0f, 0f, 300f, 100f)
            record(more, Rect(0f, 0f, 36f, 48f))
            record(camera, Rect(36f, 0f, 72f, 48f))
            record(paste, Rect(264f, 0f, 300f, 48f))
        }

    @Test
    fun offCenterGrabKeepsTheGlyphUnderItsOriginalContactPoint() {
        val edit = editor()
        edit.start(camera, Offset(40f, 10f))
        assertEquals(Offset(54f, 24f), edit.dragCenter)
        edit.drag(Offset(220f, 0f))
        assertEquals(Offset(274f, 24f), edit.dragCenter)
        edit.drop()
        val released = edit.release!!
        assertEquals(camera, released.shortcut)
        assertEquals(Offset(274f, 24f), released.from)
        edit.finishRelease(released)
        assertNull(edit.release)
    }

    @Test
    fun releaseTargetRequiresItsOwnPlacedGeometryAndRejectsStaleCallbacks() {
        val edit = editor()
        edit.start(camera, Offset(54f, 24f))
        edit.drag(Offset(240f, 0f))
        edit.drop()
        val first = edit.release!!
        val target = Rect(264f, 48f, 300f, 96f)
        edit.record(camera, target, first)
        assertNull(first.target)
        edit.placed(camera, first)
        edit.record(camera, target)
        assertNull(first.target)
        edit.record(camera, target, first)
        assertEquals(target.center, first.target)
        edit.start(camera, Offset(280f, 60f))
        edit.drop()
        val second = edit.release!!
        edit.placed(camera, first)
        edit.record(camera, target, first)
        assertNull(second.target)
    }

    @Test
    fun emptyPlacedBoundsRetireOnlyTheVisualReleaseWithoutChangingTheCommittedDrop() {
        val edit = editor()
        var saves = 0
        edit.onChange = { saves++ }
        edit.start(camera, Offset(54f, 24f))
        edit.drag(Offset(-50f, 0f))
        edit.drop()
        val released = edit.release!!
        val committed = edit.draft
        assertEquals(1, saves)
        edit.record(camera, Rect.Zero, released)
        assertTrue("Empty geometry before placement does not invent a fallback", edit.release === released)
        edit.placed(camera, released)
        edit.record(camera, Rect.Zero, released)
        assertNull(edit.release)
        assertNull(released.target)
        assertEquals(committed, edit.draft)
        assertEquals("Clipping does not save again or roll back the drop", 1, saves)
    }

    @Test
    fun staleEmptyBoundsCannotCancelANewDragOrRetireItsRelease() {
        val edit = editor()
        edit.start(camera, Offset(54f, 24f))
        edit.drag(Offset(-50f, 0f))
        edit.drop()
        val first = edit.release!!
        edit.placed(camera, first)
        edit.record(camera, Rect(0f, 0f, 36f, 48f), first)
        edit.start(camera, Offset(18f, 24f))
        val committed = edit.draft
        val pointer = edit.pointer
        val projection = edit.previewSettings
        edit.record(camera, Rect.Zero, first)
        assertEquals(camera, edit.dragging)
        assertEquals(pointer, edit.pointer)
        assertEquals(projection, edit.previewSettings)
        assertEquals(committed, edit.draft)
        edit.drop()
        val second = edit.release!!
        edit.placed(camera, second)
        edit.record(camera, Rect.Zero, first)
        assertTrue("A stale clipped callback cannot retire the current release", edit.release === second)
        assertNull(second.target)
    }

    @Test
    fun staleSettleCannotRetireTheNextDragAndCancelNeverCommits() {
        val edit = editor()
        edit.start(camera, Offset(54f, 24f))
        edit.drag(Offset(-50f, 0f))
        edit.drop()
        val released = edit.release!!
        val saved = edit.draft
        edit.start(camera, Offset(10f, 10f))
        edit.finishRelease(released)
        assertEquals(camera, edit.dragging)
        edit.drag(Offset(240f, 0f))
        edit.cancelDrag()
        assertEquals(saved, edit.draft)
        assertNull(edit.release)
    }

    @Test
    fun editingOnlyChangesWorkingCopyAndRetainsRecoveryPosition() {
        val original = ComposerToolbarSettings()
        val edit = ComposerToolbarEditState(original)
        edit.remove(ComposerShortcut.Emoji)
        edit.add(camera)
        edit.remove(more)
        assertEquals(ComposerToolbarSettings(), original)
        assertFalse(ComposerShortcut.Emoji in edit.draft.shortcuts)
        assertTrue(camera in edit.draft.shortcuts)
        assertTrue(more in edit.draft.shortcuts)
        edit.change(original)
        assertEquals(original, edit.draft)
    }

    @Test
    fun heldDragProjectsNeighborSlotsButOnlyDropCommits() {
        val edit = editor()
        val before = edit.draft
        assertTrue(edit.startNearest(Offset(54f, 24f)))
        edit.drag(Offset(-50f, 0f))
        assertEquals(before, edit.draft)
        assertEquals(listOf(camera, more), edit.previewSettings.onSide(ComposerShortcutSide.Left))
        edit.drop()
        assertEquals(listOf(camera, more), edit.draft.onSide(ComposerShortcutSide.Left))
        assertNull(edit.dragging)
    }

    @Test
    fun crossSideDropMovesMembershipAndPreservesOtherSideOrder() {
        val edit = editor()
        edit.start(camera, Offset(54f, 24f))
        edit.drag(Offset(240f, 0f))
        assertEquals(ComposerShortcutSide.Right, edit.insertionSide)
        assertEquals(listOf(paste, camera), edit.previewSettings.onSide(ComposerShortcutSide.Right))
        edit.drop()
        assertEquals(listOf(more), edit.draft.onSide(ComposerShortcutSide.Left))
        assertEquals(listOf(paste, camera), edit.draft.onSide(ComposerShortcutSide.Right))
    }

    @Test
    fun reflowAndSlotReparentDoNotCancelHeldPointerOrChangeFrozenTargets() {
        val edit = editor()
        edit.start(camera, Offset(54f, 24f))
        edit.drag(Offset(-50f, 0f))
        val projection = edit.previewSettings
        edit.forget(camera)
        edit.record(more, Rect(36f, 0f, 72f, 48f))
        edit.record(camera, Rect(0f, 0f, 36f, 48f))
        assertEquals(camera, edit.dragging)
        assertEquals(projection, edit.previewSettings)
        edit.cancelDrag()
        assertEquals(listOf(more, camera), edit.draft.onSide(ComposerShortcutSide.Left))
    }

    @Test
    fun cancelledOutsideAndReplacedDraftNeverCommitPendingDrop() {
        val edit = editor()
        val before = edit.draft
        edit.start(camera, Offset(54f, 24f))
        edit.drag(Offset(240f, 0f))
        edit.cancelDrag()
        assertEquals(before, edit.draft)
        edit.start(camera, Offset(54f, 24f))
        edit.drag(Offset(0f, 150f))
        edit.drop()
        assertEquals(before, edit.draft)
        edit.start(camera, Offset(54f, 24f))
        edit.change(before.withSide(camera, ComposerShortcutSide.Right))
        edit.drop()
        assertEquals(ComposerShortcutSide.Right, edit.draft.side(camera))
    }

    @Test
    fun physicalSideDoesNotReverseWithRtlButWithinSideOrderDoes() {
        val edit = editor()
        edit.previewRtl = true
        edit.record(more, Rect(36f, 0f, 72f, 48f))
        edit.record(camera, Rect(0f, 0f, 36f, 48f))
        edit.start(camera, Offset(18f, 24f))
        edit.drag(Offset(50f, 0f))
        edit.drop()
        assertEquals(ComposerShortcutSide.Left, edit.draft.side(camera))
        assertEquals(listOf(camera, more), edit.draft.onSide(ComposerShortcutSide.Left))
    }

    @Test
    fun accessibilityReorderDoesNotMoveIntoOtherSide() {
        val edit = editor()
        edit.move(camera, -1)
        assertEquals(listOf(camera, more), edit.draft.onSide(ComposerShortcutSide.Left))
        assertEquals(listOf(paste), edit.draft.onSide(ComposerShortcutSide.Right))
    }

    @Test
    fun emptySpaceCannotStartDragAndClippedSlotsDoNotBecomeTargets() {
        val edit = editor()
        assertFalse(edit.startNearest(Offset(150f, 24f)))
        edit.record(paste, Rect.Zero)
        edit.start(camera, Offset(54f, 24f))
        edit.drag(Offset(-50f, 0f))
        assertEquals(0, edit.insertion)
    }
}
