package dev.ipf.whitenoise.android.ui.conversation.composer

import android.content.Context
import android.view.View
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.audio.VoiceRecordingController
import dev.ipf.whitenoise.android.audio.VoiceRecordingResult
import dev.ipf.whitenoise.android.audio.VoiceRecordingSession
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class VoiceRecordingWarningEffectsTest {
    @get:Rule
    val composeRule = createComposeRule()

    /** Warns once at each threshold and resets both warnings for the next take. */
    @Test
    fun warningsFireOnlyAtTheirThresholdsAndResetForTheNextTake() =
        runTest {
            withWarnings {
                repeat(2) { take ->
                    start()
                    assertWarnings(take, take)
                    advance(269_950L)
                    assertWarnings(take, take)
                    advance(50L)
                    assertWarnings(take + 1, take)
                    advance(19_950L)
                    assertWarnings(take + 1, take)
                    advance(50L)
                    assertWarnings(take + 1, take + 1)
                    advance(1_000L)
                    assertWarnings(take + 1, take + 1)
                    cancel()
                    assertWarnings(take + 1, take + 1)
                }
            }
        }

    /** Delivers controller writes to Compose without advancing recorder time. */
    private fun settleRecordingState() {
        composeRule.runOnUiThread { Snapshot.sendApplyNotifications() }
        composeRule.mainClock.advanceTimeBy(350L)
        composeRule.waitForIdle()
    }

    /** Releases synthetic capture and files even when a warning assertion fails. */
    private inline fun TestScope.withWarnings(block: Fixture.() -> Unit) {
        val fixture = Fixture(this)
        try {
            fixture.render()
            fixture.block()
        } finally {
            fixture.controller.release()
            runCurrent()
            fixture.output.deleteRecursively()
        }
    }

    /** Runs production warning effects with recorded outputs and deterministic capture timing. */
    private inner class Fixture(
        private val scope: TestScope,
    ) {
        private val context = ApplicationProvider.getApplicationContext<Context>()
        val output: File = Files.createTempDirectory("voice-warning-effects").toFile()
        private val announcements = mutableListOf<String>()
        private val haptics = mutableListOf<HapticFeedbackType>()
        private val view =
            object : View(context) {
                /** Records the lifecycle effect's announcement without platform accessibility delivery. */
                @Suppress("DEPRECATION")
                override fun announceForAccessibility(text: CharSequence) {
                    announcements.add(text.toString())
                }
            }
        private val feedback =
            object : HapticFeedback {
                /** Records the Compose haptic request without hardware vibration. */
                override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) {
                    haptics.add(hapticFeedbackType)
                }
            }
        private val dispatcher = StandardTestDispatcher(scope.testScheduler)
        val controller =
            VoiceRecordingController(
                context = context,
                outputDirectory = output,
                scope = scope,
                onPermissionRequest = { true },
                onRecordingComplete = { _, _ -> },
                onError = { throw it },
                recorderFactory = { _, file, _ -> WarningRecorder(file) },
                mainDispatcher = dispatcher,
                recorderDispatcher = dispatcher,
                elapsedRealtime = { scope.testScheduler.currentTime },
            )

        /** Hosts the actual warning effect with observable announcement and haptic boundaries. */
        fun render() {
            composeRule.mainClock.autoAdvance = false
            composeRule.setContent {
                CompositionLocalProvider(LocalView provides view, LocalHapticFeedback provides feedback) {
                    VoiceRecordingLifecycleEffect(controller)
                    Box {}
                }
            }
        }

        /** Starts a take and flushes preparation before timing assertions. */
        fun start() {
            controller.start()
            advance(0L)
        }

        /** Advances the recorder's clock, then delivers its new state to Compose. */
        fun advance(durationMs: Long) {
            scope.advanceTimeBy(durationMs)
            scope.runCurrent()
            settleRecordingState()
        }

        /** Cancels the take and flushes cleanup before checking for extra feedback. */
        fun cancel() {
            controller.cancel()
            advance(0L)
        }

        /** Checks exact warning content and haptic type as well as their independent counts. */
        fun assertWarnings(
            announcementCount: Int,
            hapticCount: Int,
        ) {
            val warning = context.getString(R.string.voice_recording_limit_warning)
            assertEquals(List(announcementCount) { warning }, announcements)
            assertEquals(List(hapticCount) { HapticFeedbackType.LongPress }, haptics)
        }
    }

    /** Supplies deterministic capture ownership while the production warning effects run. */
    private class WarningRecorder(
        private val file: File,
    ) : VoiceRecordingSession {
        /** Creates a synthetic take so cancellation can exercise normal file cleanup. */
        override fun start() = file.writeText("synthetic warning test")

        /** Returns the synthetic take when the controller requests finalization. */
        override fun stop() = VoiceRecordingResult(file, 1_000L)

        /** Deletes only the synthetic take owned by this recorder. */
        override fun cancel() {
            file.delete()
        }
    }
}
