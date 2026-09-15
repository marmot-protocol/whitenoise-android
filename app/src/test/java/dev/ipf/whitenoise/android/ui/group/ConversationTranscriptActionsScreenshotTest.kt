package dev.ipf.whitenoise.android.ui.group

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.ui.theme.Dimens
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Captures the same paired action leaf mounted by the developer section in real chat details. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-rUS-w360dp-h240dp-mdpi")
class ConversationTranscriptActionsScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    /** Available actions light deliver each native callback once. */
    @Test
    fun availableActionsLightDeliverEachNativeCallbackOnce() {
        var shares = 0
        var saves = 0
        showActions(onShare = { shares++ }, onSave = { saves++ })
        composeRule
            .onNodeWithTag("chat_info.share_transcript")
            .assertIsDisplayed()
            .assertIsEnabled()
            .performClick()
        composeRule
            .onNodeWithTag("chat_info.save_transcript")
            .assertIsDisplayed()
            .assertIsEnabled()
            .performClick()
        assertEquals(1, shares)
        assertEquals(1, saves)
        composeRule.onRoot().captureRoboImage("src/test/snapshots/chat_transcript_actions_available_light.png")
    }

    /** Saving dark shows progress and disables both actions. */
    @Test
    fun savingDarkShowsProgressAndDisablesBothActions() {
        showActions(dark = true, saveInFlight = true)
        composeRule.onNodeWithTag("chat_info.share_transcript").assertIsDisplayed().assertIsNotEnabled()
        composeRule.onNodeWithTag("chat_info.save_transcript").assertIsDisplayed().assertIsNotEnabled()
        composeRule.onRoot().captureRoboImage("src/test/snapshots/chat_transcript_actions_saving_dark.png")
    }

    /** Pending share disables save until the native share result returns. */
    @Test
    fun pendingShareDisablesSaveUntilTheNativeShareResultReturns() {
        showActions(sharePending = true)
        composeRule.onNodeWithTag("chat_info.share_transcript").assertIsEnabled()
        composeRule.onNodeWithTag("chat_info.save_transcript").assertIsNotEnabled()
    }

    /** Missing account disables both destinations. */
    @Test
    fun missingAccountDisablesBothDestinations() {
        showActions(accountAvailable = false)
        composeRule.onNodeWithTag("chat_info.share_transcript").assertIsNotEnabled()
        composeRule.onNodeWithTag("chat_info.save_transcript").assertIsNotEnabled()
    }

    /** Shows actions. */
    private fun showActions(
        dark: Boolean = false,
        saveInFlight: Boolean = false,
        sharePending: Boolean = false,
        accountAvailable: Boolean = true,
        onShare: () -> Unit = {},
        onSave: () -> Unit = {},
    ) {
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = dark) {
                Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Column(
                        Modifier.padding(horizontal = Dimens.spaceLg),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        ConversationTranscriptActions(
                            shareInFlight = false,
                            saveInFlight = saveInFlight,
                            sharePending = sharePending,
                            accountAvailable = accountAvailable,
                            onShare = onShare,
                            onSave = onSave,
                        )
                    }
                }
            }
        }
    }
}
