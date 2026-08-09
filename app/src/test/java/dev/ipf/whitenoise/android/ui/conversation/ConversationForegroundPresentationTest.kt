package dev.ipf.whitenoise.android.ui.conversation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationForegroundPresentationTest {
    @Test
    fun drawGateBlocksOnlyWhileTheForegroundTransactionOwnsPresentation() {
        var blocked = false
        var blockedPreDraws = 0
        val gate =
            ConversationForegroundDrawGate(
                isBlocked = { blocked },
                onBlockedPreDraw = { blockedPreDraws++ },
            )

        assertTrue(gate.onPreDraw())
        assertTrue(blockedPreDraws == 0)
        blocked = true
        assertFalse(gate.onPreDraw())
        assertTrue(blockedPreDraws == 1)
        blocked = false
        assertTrue(gate.onPreDraw())
        assertTrue(blockedPreDraws == 1)
    }

    @Test
    fun openImeSettleRequiresTheRequestedTargetAndFinalInset() {
        val closed = ConversationForegroundGeometry(720, 0, 96)
        val animating = ConversationForegroundGeometry(520, 200, 296)
        val open = ConversationForegroundGeometry(420, 300, 396)

        assertFalse(
            ConversationForegroundSettleState(closed, imeTargetBottomPx = 0)
                .isSettled(expectedImeVisible = true),
        )
        assertFalse(
            ConversationForegroundSettleState(animating, imeTargetBottomPx = 300)
                .isSettled(expectedImeVisible = true),
        )
        assertTrue(
            ConversationForegroundSettleState(open, imeTargetBottomPx = 300)
                .isSettled(expectedImeVisible = true),
        )
    }

    @Test
    fun closedImeSettleRejectsAStaleOpenTargetAndUnmeasuredLayout() {
        assertFalse(
            ConversationForegroundSettleState(
                geometry = ConversationForegroundGeometry(720, 0, 96),
                imeTargetBottomPx = 300,
            ).isSettled(expectedImeVisible = false),
        )
        assertFalse(
            ConversationForegroundSettleState(
                geometry = ConversationForegroundGeometry(0, 0, 0),
                imeTargetBottomPx = 0,
            ).isSettled(expectedImeVisible = false),
        )
        assertTrue(
            ConversationForegroundSettleState(
                geometry = ConversationForegroundGeometry(720, 0, 96),
                imeTargetBottomPx = 0,
            ).isSettled(expectedImeVisible = false),
        )
    }
}
