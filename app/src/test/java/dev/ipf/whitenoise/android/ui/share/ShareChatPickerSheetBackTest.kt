package dev.ipf.whitenoise.android.ui.share

import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.material3.Surface
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Back must fully cancel the inbound share recipient sheet without reaching
 * handlers composed underneath (issue #1721). ModalBottomSheet dismissal is
 * pinned structurally in [ShareChatPickerSheetBackCoverageTest]; these tests
 * exercise the routed Back handler directly on the JVM.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h780dp-mdpi")
class ShareChatPickerSheetBackTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun backHandlerInvokesDismissCallback() {
        var backs = 0
        composeRule.setContent {
            ShareChatPickerBackHandler(enabled = true) { backs++ }
        }
        composeRule.waitForIdle()
        pressBack()
        assertEquals(1, backs)
    }

    @Test
    fun backHandlerWinsOverEarlierSiblingHandler() {
        var overlayBacks = 0
        var siblingBacks = 0
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = true) {
                Surface {
                    BackHandler { siblingBacks++ }
                    ShareChatPickerBackHandler(enabled = true) { overlayBacks++ }
                }
            }
        }
        composeRule.waitForIdle()
        pressBack()
        assertEquals(1, overlayBacks)
        assertEquals("Back must not reach the underlying route", 0, siblingBacks)
    }

    @Test
    fun dismissActionClearsFocusHidesImeAndHidesSheet() {
        var clearedFocus = false
        var hidKeyboard = false
        var hidSheet = false
        runShareChatPickerDismissal(
            clearFocus = { clearedFocus = true },
            hideKeyboard = { hidKeyboard = true },
            hideSheet = { hidSheet = true },
        )
        assertEquals(true, clearedFocus)
        assertEquals(true, hidKeyboard)
        assertEquals(true, hidSheet)
    }

    private fun pressBack() {
        composeRule.runOnUiThread {
            composeRule.activity.onBackPressedDispatcher.onBackPressed()
        }
        composeRule.waitForIdle()
    }
}
