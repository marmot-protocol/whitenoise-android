package dev.ipf.whitenoise.android.ui.conversation

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ConversationForegroundPresentationTest {
    @Test
    fun drawGateBlocksOnlyWhileTheForegroundTransactionOwnsPresentation() {
        var blocked = false
        var preDrawSignals = 0
        val gate =
            ConversationForegroundDrawGate(
                isBlocked = { blocked },
                onPreDrawSignal = { preDrawSignals++ },
            )

        assertTrue(gate.onPreDraw())
        assertTrue(preDrawSignals == 1)
        blocked = true
        assertFalse(gate.onPreDraw())
        assertTrue(preDrawSignals == 2)
        blocked = false
        assertTrue(gate.onPreDraw())
        assertTrue(preDrawSignals == 3)
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

    /** A denied IME request releases the gate once coherent geometry exists. */
    @Test
    fun imeVisibilityTimeoutCommitsASettledDeniedImeRequest() =
        runTest {
            val signals = Channel<Unit>(capacity = Channel.CONFLATED)
            var deadlineExpirations = 0
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
                    onSettleDeadlineExpired = { deadlineExpirations++ },
                )

            assertEquals(deniedImeRequest, result)
            assertEquals(1, deadlineExpirations)
        }

    /** A released gate leaves transient geometry armed for its late settle. */
    @Test
    fun imeVisibilityTimeoutKeepsCorrectionArmedUntilGeometrySettles() =
        runTest {
            val signals = Channel<Unit>(capacity = Channel.CONFLATED)
            var deadlineExpirations = 0
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
                        expectedVisibilityTimeoutMillis = 1_000,
                        onSettleDeadlineExpired = { deadlineExpirations++ },
                    )
                }
            yield()
            assertFalse(result.isCompleted)

            current = settled
            signals.trySend(Unit)

            assertEquals(settled, result.await())
            assertEquals(1, deadlineExpirations)
        }

    /** One liveness window opens the gate while preserving a deferred correction. */
    @Test
    fun settleDeadlineOpensTheGateAndKeepsTheCorrectionArmed() =
        runTest {
            val signals = Channel<Unit>(capacity = Channel.CONFLATED)
            var deadlineExpirations = 0
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
                        expectedImeVisible = true,
                        expectedVisibilityTimeoutMillis = 1_000,
                        onSettleDeadlineExpired = { deadlineExpirations++ },
                    )
                }
            advanceTimeBy(1_001)
            runCurrent()

            assertEquals(1, deadlineExpirations)
            assertFalse(result.isCompleted)

            current = settled
            signals.trySend(Unit)

            assertEquals(settled, result.await())
            assertEquals(1, deadlineExpirations)
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
