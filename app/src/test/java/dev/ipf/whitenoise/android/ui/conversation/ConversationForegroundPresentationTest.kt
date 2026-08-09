package dev.ipf.whitenoise.android.ui.conversation

import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
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
            ConversationForegroundSettleState(closed, imeTargetBottomPx = 0, bottomChromeMeasured = true)
                .isSettled(expectedImeVisible = true),
        )
        assertFalse(
            ConversationForegroundSettleState(animating, imeTargetBottomPx = 300, bottomChromeMeasured = true)
                .isSettled(expectedImeVisible = true),
        )
        assertTrue(
            ConversationForegroundSettleState(open, imeTargetBottomPx = 300, bottomChromeMeasured = true)
                .isSettled(expectedImeVisible = true),
        )
    }

    @Test
    fun closedImeSettleRejectsAStaleOpenTargetAndUnmeasuredLayout() {
        assertFalse(
            ConversationForegroundSettleState(
                geometry = ConversationForegroundGeometry(720, 0, 96),
                imeTargetBottomPx = 300,
                bottomChromeMeasured = true,
            ).isSettled(expectedImeVisible = false),
        )
        assertFalse(
            ConversationForegroundSettleState(
                geometry = ConversationForegroundGeometry(0, 0, 0),
                imeTargetBottomPx = 0,
                bottomChromeMeasured = false,
            ).isSettled(expectedImeVisible = false),
        )
        assertTrue(
            ConversationForegroundSettleState(
                geometry = ConversationForegroundGeometry(720, 0, 96),
                imeTargetBottomPx = 0,
                bottomChromeMeasured = true,
            ).isSettled(expectedImeVisible = false),
        )
    }

    @Test
    fun measuredZeroHeightChromeIsASettledLayout() {
        assertTrue(
            ConversationForegroundSettleState(
                geometry = ConversationForegroundGeometry(720, 0, 0),
                imeTargetBottomPx = 0,
                bottomChromeMeasured = true,
            ).isSettled(expectedImeVisible = false),
        )
    }

    @Test
    fun zeroHeightChromeMustBeMeasuredBeforeTheLayoutSettles() {
        assertFalse(
            ConversationForegroundSettleState(
                geometry = ConversationForegroundGeometry(720, 0, 0),
                imeTargetBottomPx = 0,
                bottomChromeMeasured = false,
            ).isSettled(expectedImeVisible = false),
        )
    }

    @Test
    fun imeVisibilityTimeoutAcceptsOnlyCoherentMeasuredGeometry() {
        val deniedImeRequest =
            ConversationForegroundSettleState(
                geometry = ConversationForegroundGeometry(720, 0, 96),
                imeTargetBottomPx = 0,
                bottomChromeMeasured = true,
            )
        val animatingIme =
            ConversationForegroundSettleState(
                geometry = ConversationForegroundGeometry(520, 200, 296),
                imeTargetBottomPx = 300,
                bottomChromeMeasured = true,
            )

        assertFalse(deniedImeRequest.isSettled(expectedImeVisible = true))
        assertTrue(deniedImeRequest.isGeometrySettled())
        assertFalse(animatingIme.isGeometrySettled())
    }

    @Test
    fun imeVisibilityTimeoutCommitsASettledDeniedImeRequest() =
        runTest {
            val signals = Channel<Unit>(capacity = Channel.CONFLATED)
            val deniedImeRequest =
                ConversationForegroundSettleState(
                    geometry = ConversationForegroundGeometry(720, 0, 96),
                    imeTargetBottomPx = 0,
                    bottomChromeMeasured = true,
                )

            val result =
                awaitConversationForegroundPresentation(
                    preDrawSignals = signals,
                    currentState = { deniedImeRequest },
                    expectedImeVisible = true,
                    expectedVisibilityTimeoutMillis = 0,
                )

            assertEquals(deniedImeRequest, result)
        }

    @Test
    fun imeVisibilityTimeoutKeepsPresentationBlockedUntilGeometrySettles() =
        runTest {
            val signals = Channel<Unit>(capacity = Channel.CONFLATED)
            var current =
                ConversationForegroundSettleState(
                    geometry = ConversationForegroundGeometry(520, 200, 296),
                    imeTargetBottomPx = 300,
                    bottomChromeMeasured = true,
                )
            val settled =
                ConversationForegroundSettleState(
                    geometry = ConversationForegroundGeometry(420, 300, 396),
                    imeTargetBottomPx = 300,
                    bottomChromeMeasured = true,
                )

            val result =
                async {
                    awaitConversationForegroundPresentation(
                        preDrawSignals = signals,
                        currentState = { current },
                        expectedImeVisible = false,
                        expectedVisibilityTimeoutMillis = 0,
                    )
                }
            yield()
            assertFalse(result.isCompleted)

            current = settled
            signals.trySend(Unit)

            assertEquals(settled, result.await())
        }

    @Test
    fun foregroundWaitRemainsCancellableWhenGeometryNeverSettles() =
        runTest {
            val signals = Channel<Unit>(capacity = Channel.CONFLATED)
            val animating =
                ConversationForegroundSettleState(
                    geometry = ConversationForegroundGeometry(520, 200, 296),
                    imeTargetBottomPx = 300,
                    bottomChromeMeasured = true,
                )
            val wait =
                launch {
                    awaitConversationForegroundPresentation(
                        preDrawSignals = signals,
                        currentState = { animating },
                        expectedImeVisible = true,
                        expectedVisibilityTimeoutMillis = 1_500,
                    )
                }
            yield()

            assertFalse(wait.isCompleted)
            wait.cancelAndJoin()
            assertTrue(wait.isCancelled)
        }
}
