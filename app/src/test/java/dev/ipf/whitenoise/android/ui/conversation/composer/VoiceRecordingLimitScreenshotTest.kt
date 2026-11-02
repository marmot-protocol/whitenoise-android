package dev.ipf.whitenoise.android.ui.conversation.composer

import android.content.Context
import android.view.View
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.audio.VoiceRecordingController
import dev.ipf.whitenoise.android.audio.VoiceRecordingResult
import dev.ipf.whitenoise.android.audio.VoiceRecordingSession
import dev.ipf.whitenoise.android.core.MessageTextCopy
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.nio.file.Files

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-rUS-w360dp-h780dp-mdpi")
class VoiceRecordingLimitScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    /** Captures the ordinary held-recording pill and checks that its entire bright outline survives. */
    @Test
    fun heldAmoledOutline() = runTest { verifyHeldOutline(largeRtl = false) }

    /** Exercises the held-recording outline with narrow RTL layout and enlarged text. */
    @Test
    fun heldNarrowLargeAmoledRtlOutline() = runTest { verifyHeldOutline(largeRtl = true) }

    /** Keeps the pointer hold active through recording-strip recomposition before finalizing its take. */
    private fun TestScope.verifyHeldOutline(largeRtl: Boolean) {
        withRecording {
            render(controller, dark = true, largeRtl = largeRtl)
            val context = ApplicationProvider.getApplicationContext<Context>()
            composeRule
                .onNodeWithContentDescription(context.getString(R.string.voice_message_record))
                .performTouchInput { down(center) }
            runCurrent()
            settleRecordingState()
            assertTrue(controller.isRecording)
            assertFalse(controller.locked)
            composeRule.onNodeWithTag("recording-strip").captureRoboImage(
                "src/test/snapshots/voice_recording_held_${if (largeRtl) "large_rtl_" else ""}amoled.png",
            )
            assertCompleteAmoledOutline()
            // The active hold must survive the recomposition that inserts recording content.
            composeRule
                .onNodeWithContentDescription(context.getString(R.string.voice_recording_stop))
                .performTouchInput { up() }
            advanceTimeBy(VoiceRecordingController.RECORDING_TAIL_MS)
            runCurrent()
            assertFalse(controller.isRecording)
            assertEquals(1, completed.size)
        }
    }

    /** Samples all four pill edges so an opaque recording strip cannot hide part of the outline. */
    private fun assertCompleteAmoledOutline() {
        val pixels = composeRule.onNodeWithTag(COMPOSER_PILL_SURFACE_TAG).captureToImage().toPixelMap()
        val middleX = pixels.width / 2
        val middleY = pixels.height / 2

        // Sample the enclosing pill, not the separate rounded recording strip.
        // A one-pixel curved stroke is antialiased (243/255 at the side midpoint).

        /** Recognizes the bright composer stroke in antialiased edge samples. */
        fun Color.isOutline() = red > 0.9f && green > 0.9f && blue > 0.9f
        assertTrue(
            "Top outline must remain visible",
            (0..1).any { pixels[middleX, it].isOutline() },
        )
        assertTrue(
            "Bottom outline must remain visible",
            (0..1).any { pixels[middleX, pixels.height - 1 - it].isOutline() },
        )
        assertTrue(
            "Left outline must remain visible",
            (0..1).any { pixels[it, middleY].isOutline() },
        )
        assertTrue(
            "Right outline must remain visible",
            (0..1).any { pixels[pixels.width - 1 - it, middleY].isOutline() },
        )
    }

    /** Captures the thirty-second warning in light mode and verifies explicit Stop offers one take. */
    @Test
    fun countdownLight() =
        runTest {
            withRecording {
                render(controller)
                controller.start()
                controller.lock()
                runCurrent()
                advanceTimeBy(270_000L)
                runCurrent()
                settleRecordingState()
                composeRule.onNodeWithContentDescription("Stops in 0:30").assertIsDisplayed()
                assertRecordingControls()
                composeRule
                    .onNodeWithTag("recording-strip")
                    .captureRoboImage("src/test/snapshots/voice_recording_countdown_light.png")
                val context = ApplicationProvider.getApplicationContext<Context>()
                composeRule
                    .onNodeWithContentDescription(context.getString(R.string.voice_recording_stop))
                    .performClick()
                advanceTimeBy(VoiceRecordingController.RECORDING_TAIL_MS)
                runCurrent()
                assertEquals(1, completed.size)
            }
        }

    /** Captures the ten-second warning in narrow RTL AMOLED and verifies Cancel offers no take. */
    @Test
    fun countdownNarrowLargeAmoledRtl() =
        runTest {
            withRecording {
                render(controller, dark = true, largeRtl = true)
                controller.start()
                controller.lock()
                runCurrent()
                advanceTimeBy(290_000L)
                runCurrent()
                settleRecordingState()
                composeRule.onNodeWithContentDescription("Stops in 0:10").assertIsDisplayed()
                assertRecordingControls()
                composeRule
                    .onNodeWithTag("recording-strip")
                    .captureRoboImage("src/test/snapshots/voice_recording_countdown_large_amoled_rtl.png")
                val context = ApplicationProvider.getApplicationContext<Context>()
                composeRule
                    .onNodeWithContentDescription(context.getString(R.string.voice_message_cancel))
                    .performClick()
                runCurrent()
                assertFalse(controller.isRecording)
                assertTrue(completed.isEmpty())
            }
        }

    /** Pause releases screen-wake ownership and delivers one take without the ordinary stop tail. */
    @Test
    fun foregroundRecordingKeepsScreenOnAndPausePreservesReviewWithoutATail() =
        runTest {
            withRecording {
                val owner =
                    object : LifecycleOwner {
                        val registry = LifecycleRegistry(this)
                        override val lifecycle: Lifecycle get() = registry
                    }
                lateinit var view: View
                composeRule.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
                composeRule.setContent {
                    CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                        val currentView = LocalView.current
                        SideEffect { view = currentView }
                        VoiceRecordingLifecycleEffect(controller)
                        Box {}
                    }
                }
                val previous = view.keepScreenOn
                controller.start()
                runCurrent()
                composeRule.runOnIdle { assertTrue(view.keepScreenOn) }
                composeRule.runOnIdle { owner.registry.currentState = Lifecycle.State.STARTED }
                runCurrent()
                composeRule.runOnIdle { assertEquals(previous, view.keepScreenOn) }
                assertFalse(controller.isRecording)
                assertEquals(1, completed.size)
                assertEquals("captured audio", completed.single().readText())
                controller.stop()
                runCurrent()
                assertEquals(1, completed.size)
            }
        }

    /** Flushes virtual-time state changes into Compose before UI assertions and image capture. */
    private fun settleRecordingState() {
        // Controller virtual time writes outside Compose's frozen frame clock.
        composeRule.runOnUiThread { Snapshot.sendApplyNotifications() }
        composeRule.mainClock.advanceTimeBy(350L)
        composeRule.waitForIdle()
    }

    /** Requires visible Stop and Cancel actions while the recording strip is displayed. */
    private fun assertRecordingControls() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        composeRule
            .onNodeWithContentDescription(context.getString(R.string.voice_message_cancel))
            .assertIsDisplayed()
        composeRule
            .onNodeWithContentDescription(context.getString(R.string.voice_recording_stop))
            .assertIsDisplayed()
    }

    /** Renders the recording composer under the requested theme, text scale and layout direction. */
    private fun render(
        controller: VoiceRecordingController,
        dark: Boolean = false,
        largeRtl: Boolean = false,
    ) {
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            CompositionLocalProvider(
                LocalDensity provides Density(1f, if (largeRtl) 2f else 1f),
                LocalLayoutDirection provides if (largeRtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
            ) {
                WhiteNoiseTheme(darkTheme = dark, amoled = dark) {
                    Surface(Modifier.width(if (largeRtl) 280.dp else 320.dp).testTag("recording-strip")) {
                        ComposerBar(
                            replyingTo = null,
                            messageTextCopy = MessageTextCopy.Default,
                            onCancelReply = {},
                            onSend = { _, _ -> },
                            voiceRecordingController = controller,
                        )
                    }
                }
            }
        }
        composeRule.mainClock.advanceTimeBy(350L)
    }

    /** Releases the fake recorder and its temporary output after each composed recording scenario. */
    private inline fun TestScope.withRecording(block: Fixture.() -> Unit) {
        val fixture = Fixture(this)
        try {
            fixture.block()
        } finally {
            fixture.controller.release()
            runCurrent()
            fixture.output.deleteRecursively()
        }
    }

    private class Fixture(
        scope: TestScope,
    ) {
        val output: File = Files.createTempDirectory("voice-limit-ui").toFile()
        val completed = mutableListOf<File>()
        private val dispatcher = StandardTestDispatcher(scope.testScheduler)
        val controller =
            VoiceRecordingController(
                context = ApplicationProvider.getApplicationContext<Context>(),
                outputDirectory = output,
                scope = scope,
                onPermissionRequest = { true },
                onRecordingComplete = { file, _ -> completed.add(file) },
                onError = { throw it },
                recorderFactory = { _, file, _ ->
                    object : VoiceRecordingSession {
                        /** Creates deterministic payload bytes for UI scenarios without a platform microphone. */
                        override fun start() = file.writeText("captured audio")

                        /** Returns a fixed-duration fake take so UI tests depend only on controller timing. */
                        override fun stop() = VoiceRecordingResult(file, 1_000L)

                        /** Leaves fake payload cleanup to the enclosing fixture after the UI scenario completes. */
                        override fun cancel() = Unit
                    }
                },
                mainDispatcher = dispatcher,
                recorderDispatcher = StandardTestDispatcher(scope.testScheduler, name = "voice-recorder-io"),
                elapsedRealtime = { scope.testScheduler.currentTime },
            )
    }
}
