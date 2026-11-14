package dev.ipf.whitenoise.android.ui.navigation

import android.content.Context
import android.provider.Settings
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.captureScreenRoboImage
import dev.ipf.whitenoise.android.audio.ConversationDictationState
import dev.ipf.whitenoise.android.ui.conversation.composer.APP_DICTATION_CONTROL_TAG
import dev.ipf.whitenoise.android.ui.navigation.DictationNavigationFixtureValues.origin
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** An ended floating session shrinks into its corner instead of vanishing between frames. */
@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h780dp-mdpi")
internal class DictationControlExitScreenshotTest : DictationNavigationScenarioFixture() {
    protected override fun captureGolden(path: String) {
        composeRule.onRoot().captureRoboImage(path)
    }

    @After
    fun releaseMotionVoiceFixture() = releasePreviewVoiceController()

    /** Cancel ends the session; the frozen capsule shrinks without reopening any command. */
    @Test
    fun cancelShrinksTheCapsuleAwayLight() = captureExit(action = "Cancel", dark = false, largeRtl = false)

    /** Paste ends when transcription finishes; the same exit holds under doubled fonts and RTL. */
    @Test
    fun pasteShrinksTheCapsuleAwayLargeRtlDark() {
        captureExit(action = transcriptPasteLabel, dark = true, largeRtl = true)
    }

    /** System-disabled motion removes the capsule on the first ended frame. */
    @Test
    fun reducedMotionRemovesTheCapsuleAtOnce() {
        val resolver = ApplicationProvider.getApplicationContext<Context>().contentResolver
        val old = Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        try {
            Settings.Global.putFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
            val fixture = showFloatingCapsule(dark = false, largeRtl = false)
            endWith(fixture, "Cancel")
            captureGolden("src/test/snapshots/dictation_control_exit_reduced_motion.png")
        } finally {
            Settings.Global.putFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, old)
        }
    }

    /** Export the real floating-control exit independently of its conversation entrance. */
    @Test
    fun captureFloatingDictationExitFrames() {
        org.junit.Assume.assumeTrue(System.getenv("ANIMATION_CAPTURE_FRAMES") == "1")
        val fixture = showFloatingCapsule(dark = true, largeRtl = false)
        val folder = File("build/animation-frames/dictation-floating-exit/exit").apply { mkdirs() }
        composeRule.mainClock.autoAdvance = false
        captureScreenRoboImage(File(folder, "source.png").path)
        endWith(fixture, "Cancel")
        repeat(32) { frame ->
            captureScreenRoboImage(File(folder, "frame-%03d.png".format(frame)).path)
            composeRule.mainClock.advanceTimeBy(16)
            composeRule.waitForIdle()
        }
        composeRule.onAllNodes(hasTestTag(APP_DICTATION_CONTROL_TAG)).assertCountEquals(0)
    }

    private fun captureExit(
        action: String,
        dark: Boolean,
        largeRtl: Boolean,
    ) {
        val fixture = showFloatingCapsule(dark, largeRtl)
        val variant = if (largeRtl) "large_rtl_dark" else "light"
        endWith(fixture, action)
        // The ended capsule is painted, but no longer offers commands to touch or accessibility.
        composeRule.onAllNodes(hasTestTag(APP_DICTATION_CONTROL_TAG)).assertCountEquals(0)
        composeRule.mainClock.advanceTimeBy(DICTATION_CONTROL_EXIT_MILLIS / 2L)
        captureGolden("src/test/snapshots/dictation_control_exit_mid_$variant.png")
        composeRule.mainClock.advanceTimeBy(DICTATION_CONTROL_EXIT_MILLIS.toLong())
        composeRule.waitForIdle()
        captureGolden("src/test/snapshots/dictation_control_exit_settled_$variant.png")
    }

    private fun showFloatingCapsule(
        dark: Boolean,
        largeRtl: Boolean,
    ): Fixture {
        val fixture = render(dark = dark, largeRtl = largeRtl)
        composeRule.runOnIdle { fixture.route = origin.copy(selectedChatId = null, selectedGroupIdHex = null) }
        composeRule.waitForIdle()
        assertSingleActionSet()
        return fixture
    }

    private fun endWith(
        fixture: Fixture,
        action: String,
    ) {
        composeRule.mainClock.autoAdvance = false
        composeRule
            .onNode(hasContentDescription(action) and hasAnyAncestor(hasTestTag(APP_DICTATION_CONTROL_TAG)))
            .performClick()
        scheduler.runCurrent()
        composeRule.waitForIdle()
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.waitForIdle()
        if (fixture.controller.state !is ConversationDictationState.Idle) {
            // Paste waits for the provider's final segment: transcription finishing is the exit.
            composeRule.runOnUiThread { fixture.platform.listener.onResult("See you at noon") }
            scheduler.runCurrent()
            composeRule.waitForIdle()
            composeRule.mainClock.advanceTimeByFrame()
            composeRule.waitForIdle()
        }
        assertTrue(
            "The action ends the session",
            fixture.controller.state is ConversationDictationState.Idle,
        )
    }
}
