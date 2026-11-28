package dev.ipf.whitenoise.android.ui.group

import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A pending prune confirmation belongs to the exact protocol timer it was raised against. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-xhdpi")
class DisappearingTimerEditorStaleConfirmTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    /** Another admin moving an out-of-range timer to another out-of-range value drops the open warning. */
    @Test
    fun aChangeBetweenOutOfRangeTimersDropsThePendingWarning() {
        val beyondLong = Long.MAX_VALUE.toULong() + 1uL
        val current = mutableStateOf(beyondLong)
        var applied: Long? = null
        composeRule.setContent {
            WhiteNoiseTheme {
                DisappearingTimerEditor(
                    open = true,
                    currentSecs = current.value,
                    editable = true,
                    identity = null,
                    resetKeys = arrayOf(),
                    onDismiss = {},
                    onApply = { applied = it },
                )
            }
        }
        composeRule.onNodeWithTag(disappearingPresetTag(300L)).performClick()
        composeRule.onNodeWithTag(DISAPPEARING_CONFIRM_TAG).assertIsDisplayed()

        composeRule.runOnIdle { current.value = beyondLong + 1uL }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(DISAPPEARING_CONFIRM_TAG).assertDoesNotExist()
        assertNull(applied)
    }
}
