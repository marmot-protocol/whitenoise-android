package dev.ipf.whitenoise.android.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.ui.conversation.animatedMenuImeBackdrop
import dev.ipf.whitenoise.android.ui.conversation.awaitMenuImeRestore
import dev.ipf.whitenoise.android.ui.conversation.captureMenuImeBackdrop
import dev.ipf.whitenoise.android.ui.conversation.heldImeBottomPadding
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MenuImeBackdropTest {
    @get:Rule
    val composeRule = createComposeRule()

    private var frozen by mutableStateOf<Int?>(120)
    private var returning by mutableStateOf(false)
    private var live by mutableIntStateOf(0)
    private var displayed = 0

    /**
     * Mounts a measured backdrop fixture with a frozen inset and a manually advanced clock, padded the way
     * ConversationScreen pads its transcript. [onComposed] runs after each composition of the caller.
     */
    private fun content(
        initial: Int?,
        onComposed: () -> Unit = {},
    ) {
        frozen = initial
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                val held =
                    animatedMenuImeBackdrop(frozen, returning, { live }) {
                        frozen = null
                        returning = false
                    }
                SideEffect(onComposed)
                Box(Modifier.size(400.dp).heldImeBottomPadding { held?.invoke() ?: live }) {
                    Box(Modifier.fillMaxSize().onSizeChanged { displayed = 400 - it.height })
                }
            }
        }
        composeRule.waitForIdle()
    }

    /** Advances one Compose frame and returns the measured backdrop height. */
    private fun frame(): Int {
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.waitForIdle()
        return displayed
    }

    /** Checks a continuous handoff to the live inset and release without a final geometry jump. */
    private fun finishAt(target: Int) {
        val samples = mutableListOf(displayed)
        repeat(20) { samples += frame() }
        assertTrue("The handoff must include intermediate geometry", samples.distinct().size > 2)
        assertTrue(
            "No snapshot-to-live jump: $samples",
            samples.zipWithNext().all { (a, b) -> kotlin.math.abs(a - b) < 100 },
        )
        assertEquals(target, displayed)
        composeRule.runOnIdle { assertNull("Ownership releases only at matching geometry", frozen) }
        assertEquals("Dropping the override adds no final jump", target, frame())
    }

    /** Checks that live inset changes do not recompose the caller outside a restore handoff. */
    @Test
    fun insetFramesDoNotRecomposeTheCallerWithoutAnActiveReturn() {
        var compositions = 0
        frozen = null
        composeRule.setContent {
            animatedMenuImeBackdrop(frozen, returning, { live }) {}
            SideEffect { compositions++ }
        }
        for (hold in listOf<Int?>(null, 320)) {
            composeRule.runOnIdle { frozen = hold }
            composeRule.waitForIdle()
            val settled = compositions
            for (height in listOf(0, 80, 160, 300, 0)) {
                composeRule.runOnIdle { live = height }
                composeRule.waitForIdle()
                assertEquals("Inset frames must not subscribe the conversation", settled, compositions)
            }
        }
    }

    /** Checks that an active hand-back animates the held geometry in layout without recomposing its caller. */
    @Test
    fun activeReturnAnimatesGeometryWithoutRecomposingTheCaller() {
        var compositions = 0
        content(320) { compositions++ }
        val before = compositions
        composeRule.runOnIdle {
            live = 180
            returning = true
        }
        // Nine frames stay inside the 160 ms animation, so its release does not recompose the caller either.
        val samples = List(9) { frame() }
        assertTrue("The held geometry must still animate: $samples", samples.distinct().size > 2)
        assertTrue(
            "The hand-back may recompose the caller once, for the returning flag: ${compositions - before}",
            compositions - before <= 1,
        )
    }

    /** Checks that opening with no keyboard leaves geometry tracking live insets. */
    @Test
    fun closedKeyboardCreatesNoHoldAndItsNextRiseTracksLiveGeometry() {
        content(captureMenuImeBackdrop(displayedBottom = null, liveBottom = 0))
        for (height in listOf(0, 80, 160, 300)) {
            composeRule.runOnIdle { live = height }
            // Apply composition, then observe the Android layout callback.
            frame()
            assertEquals(height, frame())
            composeRule.runOnIdle { assertNull(frozen) }
        }
        assertEquals(0, captureMenuImeBackdrop(displayedBottom = 0, liveBottom = 300))
    }

    /** Checks that a partial inset stays frozen until a continuous restore handoff begins. */
    @Test
    fun partialSnapshotStaysFrozenThenMovesContinuouslyToFullKeyboard() {
        content(120)
        composeRule.runOnIdle { live = 300 }
        repeat(3) { assertEquals(120, frame()) }
        composeRule.runOnIdle { returning = true }
        assertEquals("Starting handoff retains the captured geometry", 120, frame())
        finishAt(300)
    }

    /** Checks a continuous handoff when the restored keyboard is shorter than the snapshot. */
    @Test
    fun shorterReturnedKeyboardDoesNotJumpAtRelease() {
        content(320)
        composeRule.runOnIdle {
            live = 180
            returning = true
        }
        assertEquals(320, frame())
        finishAt(180)
    }

    /** Checks the restore wait deadline and a continuous release to a closed keyboard. */
    @Test
    fun failedRestorationWaitIsBoundedAndZeroEndpointReleasesContinuously() =
        runTest {
            val before = testScheduler.currentTime
            awaitMenuImeRestore(readInset = { 0 }, readTargetInset = { 0 }, awaitFrame = {})
            assertEquals(1_500L, testScheduler.currentTime - before)
            content(320)
            composeRule.runOnIdle { returning = true }
            assertEquals(320, frame())
            finishAt(0)
        }

    /** Checks that cancelling a handoff cannot release a later menu snapshot. */
    @Test
    fun cancellationCannotReleaseAReplacementMenu() {
        content(320)
        composeRule.runOnIdle { returning = true }
        repeat(4) { frame() }
        composeRule.runOnIdle {
            frozen = null
            returning = false
        }
        frame()
        composeRule.runOnIdle { frozen = 120 }
        frame()
        repeat(20) { assertEquals(120, frame()) }
        composeRule.runOnIdle { assertEquals(120, frozen) }
    }

    /** Checks retargeting during release and freezing the displayed height when the menu reopens. */
    @Test
    fun timeoutHandoffRetargetsAndReopeningFreezesTheVisibleHeight() {
        content(320)
        // Screen's settle timeout starts this same handoff with no restored IME.
        composeRule.runOnIdle { returning = true }
        assertEquals(320, frame())
        repeat(4) { frame() }
        assertTrue(displayed in 1..319)
        composeRule.runOnIdle { live = 260 }
        frame()
        repeat(3) { frame() }
        val reopened = displayed
        composeRule.runOnIdle {
            frozen = reopened
            returning = false
        }
        repeat(20) { assertEquals("Reopening cancels the old release", reopened, frame()) }
        composeRule.runOnIdle { returning = true }
        finishAt(260)
    }
}
