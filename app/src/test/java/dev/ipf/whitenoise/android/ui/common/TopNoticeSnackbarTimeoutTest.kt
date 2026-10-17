package dev.ipf.whitenoise.android.ui.common

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A queued snackbar's lifetime belongs to the app, not to whichever slot happens to be composed (#3312). The
 * material host counts down only while composed, so without a root clock a short confirmation queued under app
 * lock, or while a modal took the banners over, would show for its whole duration again once a slot picked it up.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w360dp-h780dp-mdpi")
class TopNoticeSnackbarTimeoutTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val locked = mutableStateOf(false)
    private val modalOpen = mutableStateOf(false)
    private lateinit var host: TopNoticeHost
    private var result: SnackbarResult? = null

    /** A short confirmation queued under app lock times out on schedule, so unlocking does not show it again. */
    @Test
    fun aShortSnackbarQueuedUnderLockTimesOutWithoutASlot() {
        locked.value = true
        render(SnackbarDuration.Short)
        composeRule.onNodeWithText(TEXT).assertDoesNotExist()
        assertNotNull("the snackbar is queued while hidden", host.snackbarHostState.currentSnackbarData)

        composeRule.mainClock.advanceTimeBy(SNACKBAR_SHORT_MILLIS - STEP_MILLIS)
        assertNotNull("the snackbar is not retired early", host.snackbarHostState.currentSnackbarData)
        composeRule.mainClock.advanceTimeBy(2 * STEP_MILLIS)
        assertNull("the hidden snackbar timed out", host.snackbarHostState.currentSnackbarData)
        assertEquals(SnackbarResult.Dismissed, result)

        // A write from outside a frame is delivered to the composition by the looper, which only an idle
        // drains while the clock is paused, so drain it before running the frames that recompose.
        composeRule.runOnUiThread { locked.value = false }
        composeRule.waitForIdle()
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.onNodeWithText(TEXT).assertDoesNotExist()
    }

    /** An indefinite error keeps its contract: hidden under lock, never timed out, shown again after unlock. */
    @Test
    fun anIndefiniteErrorOutlivesTheLockAndShowsAgainAfterUnlock() {
        locked.value = true
        render(SnackbarDuration.Indefinite)
        composeRule.onNodeWithText(TEXT).assertDoesNotExist()

        composeRule.mainClock.advanceTimeBy(LONG_WAIT_MILLIS)
        assertNotNull("an error waits for its dismissal", host.snackbarHostState.currentSnackbarData)
        assertNull(result)

        composeRule.runOnUiThread { locked.value = false }
        composeRule.waitForIdle()
        // The material host fades a snackbar in, so give it its entrance before asking whether it shows.
        composeRule.mainClock.advanceTimeBy(SNACKBAR_ENTRANCE_MILLIS)
        composeRule.onNodeWithText(TEXT).assertIsDisplayed()
    }

    /** A modal taking the banners over midway does not restart the clock: the snackbar still ends on schedule. */
    @Test
    fun aSlotChangeMidwayDoesNotRestartTheTimeout() {
        render(SnackbarDuration.Short)
        composeRule.onNodeWithText(TEXT).assertIsDisplayed()

        composeRule.mainClock.advanceTimeBy(SNACKBAR_SHORT_MILLIS / 2)
        composeRule.runOnUiThread { modalOpen.value = true }
        composeRule.waitForIdle()
        composeRule.mainClock.advanceTimeBy(SNACKBAR_ENTRANCE_MILLIS)
        composeRule.onNodeWithText(TEXT).assertIsDisplayed()

        composeRule.mainClock.advanceTimeBy(SNACKBAR_SHORT_MILLIS / 2 - SNACKBAR_ENTRANCE_MILLIS + STEP_MILLIS)
        assertNull("the clock ran on through the slot change", host.snackbarHostState.currentSnackbarData)
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.onNodeWithText(TEXT).assertDoesNotExist()
    }

    /** Renders the root clock and two slots, a screen and an optional modal, under one lock-gated host. */
    private fun render(duration: SnackbarDuration) {
        composeRule.mainClock.autoAdvance = false
        host = TopNoticeHost(SnackbarHostState(), notice = { null }, presentationAllowed = { !locked.value })
        composeRule.setContent {
            CompositionLocalProvider(LocalTopNoticeHost provides host) {
                WhiteNoiseTheme {
                    TopNoticeSnackbarTimeoutEffect(host.snackbarHostState)
                    LaunchedEffect(Unit) {
                        result = host.snackbarHostState.showSnackbar(TEXT, duration = duration)
                    }
                    Box(Modifier.fillMaxSize()) {
                        Column(Modifier.fillMaxSize()) { TopNoticeSlot() }
                        if (modalOpen.value) Column(Modifier.fillMaxSize()) { TopNoticeSlot() }
                    }
                }
            }
        }
        composeRule.mainClock.advanceTimeByFrame()
    }

    private companion object {
        const val TEXT = "Profile saved"
        const val STEP_MILLIS = 100L
        const val LONG_WAIT_MILLIS = 60_000L

        // Longer than the material host's fade-in, far shorter than the short duration.
        const val SNACKBAR_ENTRANCE_MILLIS = 500L
    }
}
