package dev.ipf.whitenoise.android.ui.conversation.messages

import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class TtsLinkTapCoordinatorTest {
    @Test
    fun inactiveReadAloudOpensLinkImmediately() =
        runTest {
            var activations = 0
            val coordinator = TtsLinkTapCoordinator(this, { false }, 300)

            coordinator.activate { activations += 1 }

            assertEquals(1, activations)
        }

    @Test
    fun activeReadAloudDefersSingleTapUntilDoubleTapWindowCloses() =
        runTest {
            var activations = 0
            val coordinator = TtsLinkTapCoordinator(this, { true }, 300)

            coordinator.beginPointerActivation()
            coordinator.activate { activations += 1 }
            advanceTimeBy(299)
            runCurrent()
            assertEquals(0, activations)

            advanceTimeBy(1)
            runCurrent()
            assertEquals(1, activations)
        }

    @Test
    fun doubleTapCancellationSwallowsPendingLinkActivation() =
        runTest {
            var activations = 0
            val coordinator = TtsLinkTapCoordinator(this, { true }, 300)

            coordinator.beginPointerActivation()
            coordinator.activate { activations += 1 }
            coordinator.beginPointerActivation()
            coordinator.activate { activations += 1 }
            coordinator.cancelPendingActivation()
            advanceTimeBy(300)
            runCurrent()

            assertEquals(0, activations)
        }

    @Test
    fun acceptedPointerActivationStillOpensWhenReadAloudEndsDuringTheWindow() =
        runTest {
            var active = true
            var activations = 0
            val coordinator = TtsLinkTapCoordinator(this, { active }, 300)

            coordinator.beginPointerActivation()
            coordinator.activate { activations += 1 }
            active = false
            advanceTimeBy(300)
            runCurrent()

            assertEquals(1, activations)
        }

    @Test
    fun accessibilityActivationBypassesPointerDoubleTapDelay() =
        runTest {
            var activations = 0
            val coordinator = TtsLinkTapCoordinator(this, { true }, 300)

            coordinator.activate { activations += 1 }

            assertEquals(1, activations)
        }
}
