package dev.ipf.whitenoise.android.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
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

    private fun content(initial: Int?) {
        frozen = initial
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                val held =
                    animatedMenuImeBackdrop(frozen, returning, live) {
                        frozen = null
                        returning = false
                    }
                Box(Modifier.size(400.dp).padding(bottom = (held ?: live).dp)) {
                    Box(Modifier.fillMaxSize().onSizeChanged { displayed = 400 - it.height })
                }
            }
        }
        composeRule.waitForIdle()
    }

    private fun frame(): Int {
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.waitForIdle()
        return displayed
    }

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

    @Test
    fun partialSnapshotStaysFrozenThenMovesContinuouslyToFullKeyboard() {
        content(120)
        composeRule.runOnIdle { live = 300 }
        repeat(3) { assertEquals(120, frame()) }
        composeRule.runOnIdle { returning = true }
        assertEquals("Starting handoff retains the captured geometry", 120, frame())
        finishAt(300)
    }

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
