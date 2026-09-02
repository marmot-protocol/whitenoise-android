package dev.ipf.whitenoise.android.ui.conversation

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Compact-height decisions follow the measured post-inset viewport, never the
 * orientation label, and the compact composer ceiling guarantees a viable
 * composer without changing regular portrait geometry.
 */
class ConversationCompactHeightTest {
    @Test
    fun portraitWithImeOpenStaysRegular() {
        // 780dp window, 24dp status bar, ~320dp IME: ~436dp remains.
        assertFalse(
            conversationUsesCompactHeight(
                containerHeightPx = 780,
                statusBarTopPx = 24,
                imeTargetBottomPx = 320,
                navigationBottomPx = 48,
                compactThresholdPx = 240f,
            ),
        )
    }

    @Test
    fun landscapeWithImeOpenIsCompact() {
        // 411dp window, ~220dp IME: ~167dp remains.
        assertTrue(
            conversationUsesCompactHeight(
                containerHeightPx = 411,
                statusBarTopPx = 24,
                imeTargetBottomPx = 220,
                navigationBottomPx = 48,
                compactThresholdPx = 240f,
            ),
        )
    }

    @Test
    fun landscapeWithoutImeStaysRegular() {
        assertFalse(
            conversationUsesCompactHeight(
                containerHeightPx = 411,
                statusBarTopPx = 24,
                imeTargetBottomPx = 0,
                navigationBottomPx = 48,
                compactThresholdPx = 240f,
            ),
        )
    }

    @Test
    fun theLargerOfImeAndNavigationInsetsDrivesTheDecision() {
        // Three-button navigation taller than a collapsed IME must not read as
        // extra viewport.
        assertTrue(
            conversationUsesCompactHeight(
                containerHeightPx = 300,
                statusBarTopPx = 24,
                imeTargetBottomPx = 0,
                navigationBottomPx = 48,
                compactThresholdPx = 240f,
            ),
        )
    }

    @Test
    fun anUnmeasuredContainerNeverReportsCompact() {
        assertFalse(
            conversationUsesCompactHeight(
                containerHeightPx = 0,
                statusBarTopPx = 0,
                imeTargetBottomPx = 0,
                navigationBottomPx = 0,
                compactThresholdPx = 240f,
            ),
        )
    }

    @Test
    fun regularViewportsKeepTheHalfRemainderCeiling() {
        assertEquals(300.dp, resolveAutomaticComposerCeiling(600.dp))
        assertEquals(146.dp, resolveAutomaticComposerCeiling(292.dp))
    }

    @Test
    fun compactViewportsGuaranteeAViableComposerInsteadOfHalfOfNothing() {
        // A 150dp post-IME remainder used to cap automatic growth at 75dp;
        // banners plus the editor need the viable allowance.
        assertEquals(CompactViableComposerHeight, resolveAutomaticComposerCeiling(150.dp))
    }

    @Test
    fun tinyViewportsUseTheWholeRemainder() {
        assertEquals(90.dp, resolveAutomaticComposerCeiling(90.dp))
    }

    @Test
    fun aRemainderSmallerThanOneLineCannotInventSpace() {
        assertEquals(30.dp, resolveAutomaticComposerCeiling(30.dp))
    }

    @Test
    fun theOneLineFloorHoldsWhenTheRemainderAllowsIt() {
        assertEquals(48.dp, resolveAutomaticComposerCeiling(48.dp))
    }

    /**
     * Suppression keys on the resolved ceiling itself, so any remainder whose
     * ceiling clamps to the compact viable allowance — including the zone just
     * above the window-level compact threshold — pins the inline controls.
     */
    @Test
    fun aClampedCeilingSuppressesTheExpandedControlLayout() {
        assertTrue(composerMultilineControlsSuppressed(resolveAutomaticComposerCeiling(112.dp)))
        assertTrue(composerMultilineControlsSuppressed(resolveAutomaticComposerCeiling(252.dp)))
        assertTrue(composerMultilineControlsSuppressed(resolveAutomaticComposerCeiling(264.dp)))
    }

    @Test
    fun anUnclampedCeilingKeepsTheExpandedControlLayoutAvailable() {
        assertFalse(composerMultilineControlsSuppressed(resolveAutomaticComposerCeiling(266.dp)))
        assertFalse(composerMultilineControlsSuppressed(resolveAutomaticComposerCeiling(600.dp)))
    }
}
