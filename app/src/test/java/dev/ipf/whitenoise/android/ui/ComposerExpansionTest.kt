package dev.ipf.whitenoise.android.ui

import dev.ipf.whitenoise.android.ui.conversation.composer.ComposerExpansionMode
import dev.ipf.whitenoise.android.ui.conversation.composer.ComposerExpansionState
import dev.ipf.whitenoise.android.ui.conversation.composer.collapseComposer
import dev.ipf.whitenoise.android.ui.conversation.composer.composerHeightPx
import dev.ipf.whitenoise.android.ui.conversation.composer.dragComposerHeight
import dev.ipf.whitenoise.android.ui.conversation.composer.settleComposerHeight
import dev.ipf.whitenoise.android.ui.conversation.composer.toggleComposerFullScreen
import org.junit.Assert.assertEquals
import org.junit.Test

class ComposerExpansionTest {
    @Test
    fun automaticHeightFollowsTextAndNeverExceedsTheViewport() {
        assertEquals(240f, composerHeightPx(ComposerExpansionState(), 240f, 600f))
        assertEquals(600f, composerHeightPx(ComposerExpansionState(), 700f, 600f))
    }

    @Test
    fun dragUsesContinuousPixelsAndClampsAtBothEnds() {
        val expanded = dragComposerHeight(ComposerExpansionState(), -73f, 200f, 600f)
        assertEquals(ComposerExpansionMode.Manual, expanded.mode)
        assertEquals(273f, expanded.manualHeightPx)

        val full = dragComposerHeight(expanded, -1_000f, 200f, 600f)
        assertEquals(600f, full.manualHeightPx)

        val collapsed = dragComposerHeight(full, 1_000f, 200f, 600f)
        assertEquals(200f, collapsed.manualHeightPx)
    }

    @Test
    fun dragNormalizesAnAutomaticHeightAboveTheAvailableViewport() {
        val dragged = dragComposerHeight(ComposerExpansionState(), -40f, 700f, 600f)

        assertEquals(ComposerExpansionMode.Manual, dragged.mode)
        assertEquals(600f, dragged.manualHeightPx)
    }

    @Test
    fun releaseOnlySnapsInsideTheEndpointDeadband() {
        val middle = ComposerExpansionState(ComposerExpansionMode.Manual, 351f)
        assertEquals(middle, settleComposerHeight(middle, 200f, 600f, 20f))

        assertEquals(
            ComposerExpansionState(),
            settleComposerHeight(middle.copy(manualHeightPx = 214f), 200f, 600f, 20f),
        )
        assertEquals(
            ComposerExpansionMode.FullScreen,
            settleComposerHeight(middle.copy(manualHeightPx = 585f), 200f, 600f, 20f).mode,
        )
    }

    @Test
    fun tapAndBackReturnToTheNaturalAutoGrownHeight() {
        val full = toggleComposerFullScreen(ComposerExpansionState(ComposerExpansionMode.Manual, 320f))
        assertEquals(ComposerExpansionMode.FullScreen, full.mode)
        assertEquals(ComposerExpansionState(), toggleComposerFullScreen(full))
        assertEquals(ComposerExpansionState(), collapseComposer(full))
    }
}
