package dev.ipf.whitenoise.android.ui.chats

import androidx.activity.ComponentActivity
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en")
class ChatListUnreadHintTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    /** Pull progress is read in the feedback child's composition, without invalidating its parent. */
    @Test fun changingPullProgressRecomposesFeedbackWithoutRecomposingParent() {
        val progress = mutableFloatStateOf(0f)
        var parentCompositions = 0
        rule.setContent {
            WhiteNoiseTheme {
                SideEffect { parentCompositions++ }
                ChatListUnreadHint(progress = { progress.floatValue }, unreadOnly = false)
            }
        }
        rule.waitForIdle()
        val initial = parentCompositions
        rule.runOnIdle { progress.floatValue = 0.5f }
        rule.onNodeWithText(rule.activity.getString(R.string.pull_unread_hint)).assertExists()
        assertEquals(initial, parentCompositions)
        rule.runOnIdle { progress.floatValue = 1f }
        rule.onNodeWithText(rule.activity.getString(R.string.pull_unread_release)).assertExists()
        assertEquals(initial, parentCompositions)
        rule.runOnIdle { progress.floatValue = 0f }
        rule.onNodeWithText(rule.activity.getString(R.string.pull_unread_release)).assertDoesNotExist()
        assertEquals(initial, parentCompositions)
    }
}
