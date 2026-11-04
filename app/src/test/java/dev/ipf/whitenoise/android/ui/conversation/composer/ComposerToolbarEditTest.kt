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
        ComposerToolbarEditState(ComposerToolbarSettings(listOf(more, camera, paste))).apply {
            selectedArea = Rect(0f, 0f, 300f, 100f)
            record(more, Rect(0f, 0f, 36f, 48f))
            record(camera, Rect(36f, 0f, 72f, 48f))
            record(paste, Rect(264f, 0f, 300f, 48f))
        }

    @Test
    fun editingOnlyChangesWorkingCopyAndRetainsRecoveryPosition() {
        val original = ComposerToolbarSettings()
        val edit = ComposerToolbarEditState(original)
        edit.remove(paste)
        edit.add(camera)
        edit.remove(more)
        assertEquals(ComposerToolbarSettings(), original)
        assertFalse(paste in edit.draft.shortcuts)
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
