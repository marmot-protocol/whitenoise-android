package dev.ipf.whitenoise.android.ui.navigation

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.ui.navigation.DictationNavigationFixtureValues.origin
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h780dp-mdpi")
internal class CrossChatDictationScreenshotTest : DictationNavigationScenarioFixture() {
    @After
    fun releaseVoiceFixture() = releasePreviewVoiceController()

    override fun captureGolden(path: String) {
        composeRule.onRoot().captureRoboImage(path)
    }

    @Test
    fun dictateAvailableInAnotherChatAmoled() = captureQueueEntry(largeRtl = false, suffix = "amoled")

    @Test
    fun dictateAvailableInAnotherChatLargeRtl() = captureQueueEntry(largeRtl = true, suffix = "large_rtl")

    private fun captureQueueEntry(
        largeRtl: Boolean,
        suffix: String,
    ) {
        val fixture = render(dark = true, amoled = true, largeRtl = largeRtl, configured = true)
        composeRule.runOnIdle {
            fixture.platform.callerAudio = true
            fixture.controller.paste()
            fixture.controller.foregroundMicrophoneRequired = false
            fixture.route =
                origin.copy(
                    selectedChatId = "other-chat",
                    renderedChatId = "other-chat",
                    selectedGroupIdHex = "other-group",
                )
        }

        composeRule.onNodeWithContentDescription("Dictate text").assertIsDisplayed().assertIsEnabled()
        captureGolden("src/test/snapshots/dictation_cross_chat_queue_$suffix.png")
    }
}
