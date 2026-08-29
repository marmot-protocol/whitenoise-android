package dev.ipf.whitenoise.android.ui

import dev.ipf.whitenoise.android.ui.conversation.composer.COMPOSER_EXPANSION_ANIMATION_MILLIS
import dev.ipf.whitenoise.android.ui.conversation.composer.ComposerExpansionMode
import dev.ipf.whitenoise.android.ui.conversation.composer.ComposerExpansionState
import dev.ipf.whitenoise.android.ui.conversation.composer.collapseComposer
import dev.ipf.whitenoise.android.ui.conversation.composer.composerHeightAnimationDurationMillis
import dev.ipf.whitenoise.android.ui.conversation.composer.composerHeightPx
import dev.ipf.whitenoise.android.ui.conversation.composer.dragComposerHeight
import dev.ipf.whitenoise.android.ui.conversation.composer.settleComposerHeight
import dev.ipf.whitenoise.android.ui.conversation.composer.toggleComposerFullScreen
import org.junit.Assert.assertEquals
import org.junit.Test

class ComposerExpansionTest {
    @Test
    fun onlyDiscreteHeightChangesAnimateOutsideThePill() {
        assertEquals(
            0,
            composerHeightAnimationDurationMillis(
                mode = ComposerExpansionMode.Automatic,
                dragActive = false,
                discreteTransitionActive = false,
            ),
        )
        assertEquals(
            COMPOSER_EXPANSION_ANIMATION_MILLIS,
            composerHeightAnimationDurationMillis(
                mode = ComposerExpansionMode.Automatic,
                dragActive = false,
                discreteTransitionActive = true,
            ),
        )
        assertEquals(
            COMPOSER_EXPANSION_ANIMATION_MILLIS,
            composerHeightAnimationDurationMillis(
                mode = ComposerExpansionMode.FullScreen,
                dragActive = false,
                discreteTransitionActive = true,
            ),
        )
        assertEquals(
            0,
            composerHeightAnimationDurationMillis(
                mode = ComposerExpansionMode.Manual,
                dragActive = true,
                discreteTransitionActive = false,
            ),
        )
    }

    @Test
    fun automaticHeightFollowsTextAndNeverExceedsTheViewport() {
        assertEquals(240f, composerHeightPx(ComposerExpansionState(), 240f, 140f, 600f))
        assertEquals(600f, composerHeightPx(ComposerExpansionState(), 700f, 140f, 600f))
    }

    @Test
    fun dragUsesContinuousPixelsAndClampsAtBothEnds() {
        val expanded = dragComposerHeight(ComposerExpansionState(), -73f, 200f, 140f, 600f)
        assertEquals(ComposerExpansionMode.Manual, expanded.mode)
        assertEquals(273f, expanded.manualHeightPx)

        val full = dragComposerHeight(expanded, -1_000f, 200f, 140f, 600f)
        assertEquals(600f, full.manualHeightPx)

        val collapsed = dragComposerHeight(full, 1_000f, 200f, 140f, 600f)
        assertEquals(140f, collapsed.manualHeightPx)
    }

    @Test
    fun dragNormalizesAnAutomaticHeightAboveTheAvailableViewport() {
        val dragged = dragComposerHeight(ComposerExpansionState(), -40f, 700f, 140f, 600f)

        assertEquals(ComposerExpansionMode.Manual, dragged.mode)
        assertEquals(600f, dragged.manualHeightPx)
    }

    @Test
    fun manualHeightCanShrinkBelowALongDraftsAutomaticHeight() {
        val shrunk = dragComposerHeight(ComposerExpansionState(), 260f, 420f, 140f, 600f)

        assertEquals(ComposerExpansionMode.Manual, shrunk.mode)
        assertEquals(160f, shrunk.manualHeightPx)
        assertEquals(160f, composerHeightPx(shrunk, 420f, 140f, 600f))
    }

    @Test
    fun releaseOnlySnapsInsideTheEndpointDeadband() {
        val middle = ComposerExpansionState(ComposerExpansionMode.Manual, 351f)
        assertEquals(middle, settleComposerHeight(middle, 200f, 140f, 600f, 20f))

        assertEquals(
            ComposerExpansionState(),
            settleComposerHeight(middle.copy(manualHeightPx = 214f), 200f, 140f, 600f, 20f),
        )
        assertEquals(
            ComposerExpansionMode.FullScreen,
            settleComposerHeight(middle.copy(manualHeightPx = 585f), 200f, 140f, 600f, 20f).mode,
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
