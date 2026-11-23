package dev.ipf.whitenoise.android.ui.navigation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.audio.ConversationDictationDeliveryMode
import dev.ipf.whitenoise.android.audio.ConversationDictationState
import dev.ipf.whitenoise.android.ui.common.LocalSystemAnimationsEnabled
import dev.ipf.whitenoise.android.ui.conversation.composer.APP_DICTATION_CONTROL_TAG
import dev.ipf.whitenoise.android.ui.conversation.composer.ConversationDictationPersistentControl
import dev.ipf.whitenoise.android.ui.conversation.composer.DICTATION_CONTINUE_TAG
import dev.ipf.whitenoise.android.ui.conversation.composer.DICTATION_LISTENING_INDICATOR_TAG
import dev.ipf.whitenoise.android.ui.conversation.composer.DictationControlFacts
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
internal class DictationFollowUpScreenshotTest : DictationNavigationRenderFixture() {
    @Test
    fun followUpLight() = capture(dark = false)

    @Test
    fun followUpAmoledLargeRtl() = capture(dark = true)

    @Test
    fun pasteKeepsActionsFixedWithMotion() = assertStableHandoff(transcriptPasteLabel, motion = true)

    @Test
    fun sendKeepsActionsFixedWithMotion() = assertStableHandoff("Send", motion = true)

    @Test
    fun pasteKeepsActionsFixedWithoutMotion() = assertStableHandoff(transcriptPasteLabel, motion = false)

    @Test
    fun sendKeepsActionsFixedWithoutMotion() = assertStableHandoff("Send", motion = false)

    private fun assertStableHandoff(
        action: String,
        motion: Boolean,
    ) {
        val fixture =
            Fixture().apply {
                platform.callerAudio = true
                platform.deferCaptureClose = true
            }
        val controller = fixture.controller
        controller.requestStart(
            DictationNavigationFixtureValues.ACCOUNT,
            DictationNavigationFixtureValues.GROUP,
            fixture.draft,
        )
        fixture.platform.listener.onReady()
        composeRule.setContent {
            WhiteNoiseTheme {
                CompositionLocalProvider(LocalSystemAnimationsEnabled provides motion) {
                    ConversationDictationPersistentControl(controller.state, controller)
                }
            }
        }

        val capsule = composeRule.onNodeWithTag(APP_DICTATION_CONTROL_TAG)
        val commands = listOf("Cancel", transcriptPasteLabel, "Send").map { composeRule.onNodeWithContentDescription(it) }
        val measured = listOf(capsule) + commands
        val originalBounds = measured.map { it.getUnclippedBoundsInRoot() }
        val resume = composeRule.onNodeWithTag(DICTATION_CONTINUE_TAG, useUnmergedTree = true)
        composeRule.mainClock.autoAdvance = false
        composeRule.onNodeWithContentDescription(action).performClick()

        // The recorder has not acknowledged closure. No leading command can take over yet.
        composeRule.mainClock.advanceTimeBy(256)
        resume.assertDoesNotExist()
        assertEquals(originalBounds, measured.map { it.getUnclippedBoundsInRoot() })

        composeRule.runOnIdle { checkNotNull(fixture.platform.closeCapture).invoke() }
        repeat(16) {
            composeRule.mainClock.advanceTimeByFrame()
            assertEquals(originalBounds, measured.map { it.getUnclippedBoundsInRoot() })
        }
        resume.assertIsDisplayed().performClick()
        repeat(16) {
            composeRule.mainClock.advanceTimeByFrame()
            assertEquals(originalBounds, measured.map { it.getUnclippedBoundsInRoot() })
        }
        resume.assertDoesNotExist()
        composeRule.onNodeWithTag(DICTATION_LISTENING_INDICATOR_TAG, useUnmergedTree = true).assertIsDisplayed()
    }

    private fun capture(dark: Boolean) {
        val fixture = Fixture()
        val controller = fixture.controller
        controller.requestStart(
            DictationNavigationFixtureValues.ACCOUNT,
            DictationNavigationFixtureValues.GROUP,
            fixture.draft,
        )
        val state = ConversationDictationState.Processing(1L, requireNotNull(controller.state.target))
        val processing =
            DictationControlFacts(
                captureInProgress = false,
                deliveryInProgress = false,
                completionActionsEnabled = false,
                processingDeliveryMode = ConversationDictationDeliveryMode.PasteIntoDraft,
                recoveryHandedToComposer = false,
                continueAvailable = true,
                keepsLeadingSlot = true,
            )
        composeRule.setContent {
            CompositionLocalProvider(
                LocalDensity provides Density(1f, if (dark) 2f else 1f),
                LocalLayoutDirection provides if (dark) LayoutDirection.Rtl else LayoutDirection.Ltr,
            ) {
                WhiteNoiseTheme(darkTheme = dark, amoled = dark) {
                    Surface {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            ConversationDictationPersistentControl(
                                state,
                                controller,
                                facts = processing.copy(continueAvailable = false),
                            )
                            ConversationDictationPersistentControl(state, controller, facts = processing)
                            ConversationDictationPersistentControl(
                                state,
                                controller,
                                facts =
                                    processing.copy(
                                        processingDeliveryMode = ConversationDictationDeliveryMode.SendOnFinish,
                                    ),
                            )
                            ConversationDictationPersistentControl(
                                state,
                                controller,
                                facts =
                                    processing.copy(
                                        captureInProgress = true,
                                        completionActionsEnabled = true,
                                        processingDeliveryMode = null,
                                        continueAvailable = false,
                                        previousPending = true,
                                    ),
                            )
                        }
                    }
                }
            }
        }
        val variant = if (dark) "amoled_large_rtl" else "light"
        composeRule.onRoot().captureRoboImage("src/test/snapshots/dictation_follow_up_$variant.png")
    }
}
