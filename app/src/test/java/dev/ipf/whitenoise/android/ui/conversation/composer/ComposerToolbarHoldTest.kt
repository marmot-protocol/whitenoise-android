package dev.ipf.whitenoise.android.ui.conversation.composer

import android.content.Context
import android.os.Looper
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.audio.VoiceRecordingController
import dev.ipf.whitenoise.android.audio.VoiceRecordingResult
import dev.ipf.whitenoise.android.audio.VoiceRecordingSession
import dev.ipf.whitenoise.android.core.MessageTextCopy
import dev.ipf.whitenoise.android.state.ComposerShortcut
import dev.ipf.whitenoise.android.state.ComposerToolbarLayout
import dev.ipf.whitenoise.android.state.ComposerToolbarSettings
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.share.emptyAppState
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
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** The real recorder controller and pointer node cross the idle-to-recording composition boundary. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-w420dp-h780dp-mdpi")
@OptIn(ExperimentalCoroutinesApi::class)
class ComposerToolbarHoldTest {
    @get:Rule val composeRule = createComposeRule()

    @get:Rule val files = TemporaryFolder()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val voice get() = composeRule.onNodeWithTag("composer.voice.launcher")

    @Test
    fun reorderedHoldKeepsItsPositionThroughAutosaveAndParentResize() =
        runTest {
            val fixture = render(this)
            try {
                val before = voice.fetchSemanticsNode().boundsInRoot
                voice.performTouchInput { down(center) }
                runCurrent()
                composeRule.waitForIdle()
                assertTrue(fixture.controller.isRecording)
                assertEquals(1, fixture.starts)
                val changed = ComposerToolbarSettings(listOf(ComposerShortcut.Camera, ComposerShortcut.More))
                var saved: Boolean? = null
                composeRule.runOnIdle {
                    fixture.appState.composerToolbarPreference.save(changed) { saved = it }
                }
                composeRule.waitUntil(5_000) {
                    shadowOf(Looper.getMainLooper()).idle()
                    saved != null
                }
                assertEquals(true, saved)
                assertEquals(changed, fixture.appState.composerToolbarPreference.value)
                val afterSave = voice.fetchSemanticsNode().boundsInRoot
                assertEquals(before.left, afterSave.left, 1f)
                assertEquals(before.top, afterSave.top, 1f)
                assertTrue(fixture.controller.isRecording)
                composeRule.runOnIdle { fixture.width.value = 400.dp }
                composeRule.waitForIdle()
                val held = voice.fetchSemanticsNode().boundsInRoot
                assertEquals(before.left, held.left, 1f)
                assertEquals(before.top, held.top, 1f)
                voice.performTouchInput {
                    moveBy(Offset(-12f, 0f))
                    up()
                }
                advanceTimeBy(VoiceRecordingController.RECORDING_TAIL_MS)
                runCurrent()
                assertFalse(fixture.controller.isRecording)
                assertEquals(1, fixture.stops)
                assertEquals(1, fixture.cancels) // Successful finalization also disposes the recorder session.
                assertEquals(1, fixture.completes)
            } finally {
                fixture.controller.release()
                runCurrent()
            }
        }

    @Test
    fun reorderedHoldSlideToCancelDiscardsExactlyOneTake() =
        runTest {
            val fixture = render(this)
            try {
                voice.performTouchInput { down(center) }
                runCurrent()
                composeRule.waitForIdle()
                voice.performTouchInput {
                    moveBy(Offset(-140f, 0f))
                    up()
                }
                runCurrent()
                assertFalse(fixture.controller.isRecording)
                assertEquals(1, fixture.starts)
                assertEquals(1, fixture.cancels)
                assertEquals(0, fixture.completes)
            } finally {
                fixture.controller.release()
                runCurrent()
            }
        }

    @Test
    fun lockedRecordingKeepsStopAndCancelAfterAnotherSurfaceHidesItsLauncher() =
        runTest {
            val fixture = render(this)
            try {
                voice.performTouchInput { down(center) }
                runCurrent()
                composeRule.waitForIdle()
                voice.performTouchInput {
                    moveBy(Offset(0f, -100f))
                    up()
                }
                composeRule.waitForIdle()
                assertTrue(fixture.controller.locked)
                var saved: Boolean? = null
                composeRule.runOnIdle {
                    fixture.appState.composerToolbarPreference.save(
                        ComposerToolbarSettings(listOf(ComposerShortcut.More)),
                    ) { saved = it }
                }
                composeRule.waitUntil(5_000) {
                    shadowOf(Looper.getMainLooper()).idle()
                    saved != null
                }
                assertEquals(true, saved)
                composeRule
                    .onNodeWithContentDescription(
                        context.getString(R.string.voice_recording_stop),
                    ).assertIsDisplayed()
                composeRule
                    .onNodeWithContentDescription(context.getString(R.string.voice_message_cancel))
                    .assertIsDisplayed()
                    .performClick()
                runCurrent()
                assertFalse(fixture.controller.isRecording)
                assertEquals(1, fixture.cancels)
                assertEquals(0, fixture.completes)
            } finally {
                fixture.controller.release()
                runCurrent()
            }
        }

    @Suppress("LongMethod") // One shared production fixture owns the fake recorder and its real controller.
    private fun render(scope: TestScope): Fixture {
        val settings =
            ComposerToolbarSettings(
                listOf(
                    ComposerShortcut.VoiceNote,
                    ComposerShortcut.More,
                    ComposerShortcut.Paste,
                    ComposerShortcut.Camera,
                ),
                ComposerToolbarLayout.AlwaysExpanded,
            )
        context
            .getSharedPreferences("whitenoise", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .putString("composer_toolbar_v1", settings.encode())
            .commit()
        val fixture = Fixture(emptyAppState())
        val dispatcher = StandardTestDispatcher(scope.testScheduler)
        val output = files.newFolder()
        val take = File(output, "synthetic.m4a").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        fixture.controller =
            VoiceRecordingController(
                context = context,
                outputDirectory = output,
                scope = scope,
                onPermissionRequest = { true },
                onRecordingComplete = { _, _ -> fixture.completes++ },
                onError = { throw it },
                recorderFactory = { _, _, _ ->
                    object : VoiceRecordingSession {
                        override fun start() {
                            fixture.starts++
                        }

                        override fun stop(): VoiceRecordingResult {
                            fixture.stops++
                            return VoiceRecordingResult(take, 1_500L)
                        }

                        override fun cancel() {
                            fixture.cancels++
                        }
                    }
                },
                mainDispatcher = dispatcher,
                recorderDispatcher = dispatcher,
            )
        val textState = ComposerTextState(TextFieldValue())
        composeRule.setContent {
            WhiteNoiseTheme {
                Surface {
                    Box(Modifier.width(fixture.width.value).height(600.dp), contentAlignment = Alignment.BottomCenter) {
                        ComposerBar(
                            appState = fixture.appState,
                            replyingTo = null,
                            messageTextCopy = MessageTextCopy.Default,
                            onCancelReply = {},
                            onSend = { _, _ -> },
                            onCaptureFromCamera = {},
                            voiceRecordingController = fixture.controller,
                            textState = textState,
                        )
                    }
                }
            }
        }
        return fixture
    }

    private class Fixture(
        val appState: WhiteNoiseAppState,
    ) {
        lateinit var controller: VoiceRecordingController
        val width = mutableStateOf(360.dp)
        var starts = 0
        var stops = 0
        var cancels = 0
        var completes = 0
    }
}
