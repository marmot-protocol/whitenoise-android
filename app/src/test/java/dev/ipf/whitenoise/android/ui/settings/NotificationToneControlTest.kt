package dev.ipf.whitenoise.android.ui.settings

import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowMediaPlayer

/** Media errors stay with their audition and a successful retry restores the preview affordance. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-w360dp-h1200dp-mdpi")
class NotificationToneControlTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun previewFailureDoesNotReplaceImportFeedbackAndSuccessfulRetryClearsIt() {
        val players = mutableListOf<ShadowMediaPlayer>()
        ShadowMediaPlayer.setMediaInfoProvider { ShadowMediaPlayer.MediaInfo(60_000, 100) }
        ShadowMediaPlayer.setCreateListener { _, shadow -> players += shadow }
        try {
            composeRule.setContent { WhiteNoiseTheme { NotificationToneControl() } }
            composeRule.onNodeWithTag("notification-tone-ding-v1").performClick()
            composeRule.runOnIdle { players.last().invokeErrorListener(1, 0) }
            composeRule
                .onNodeWithTag("notification-tone-ding-v1")
                .assertTextContains("Could not play the preview. Try again.")
            composeRule.onNodeWithTag("notification-tone-pulse-v1").assertTextContains("Tap to preview")
            composeRule
                .onNodeWithTag("notification-tone-import")
                .assertTextContains("Make these tones available in Android’s sound picker.")
            composeRule.onNodeWithTag("notification-tone-ding-v1").performClick()
            composeRule.runOnIdle {
                players.last().setState(ShadowMediaPlayer.State.PREPARED)
                players.last().invokePreparedListener()
                players.last().invokeCompletionListener()
            }
            composeRule.onNodeWithTag("notification-tone-ding-v1").assertTextContains("Tap to preview")
        } finally {
            ShadowMediaPlayer.resetStaticState()
        }
    }
}
