package dev.ipf.whitenoise.android.ui.share

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** A no-op account selection must still retire its accepted sheet, never leave a hidden modal. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
class ShareAccountSheetTerminalTest {
    @get:Rule val rule = createComposeRule()

    @Test fun selectingTheCurrentAccountClosesEvenWhenTheParentRejectsTheSelection() {
        val accounts = listOf(testAccount(ACCOUNT_REF, ACCOUNT_HEX))
        val state = appStateWithDirectChats(GROUP_A to PEER_A, accounts = accounts)
        val shown = mutableStateOf(true)
        var dismissals = 0
        rule.setContent {
            WhiteNoiseTheme(darkTheme = true, amoled = true) {
                if (shown.value) {
                    ShareChatPickerAccountSheet(
                        appState = state,
                        accounts = accounts,
                        selectedAccountRef = ACCOUNT_REF,
                        onChooseAccount = {},
                        onDismiss = {
                            dismissals++
                            shown.value = false
                        },
                    )
                }
            }
        }
        rule.waitForIdle()
        rule.mainClock.autoAdvance = false
        rule.onNode(isSelected() and hasClickAction()).performClick()
        rule.mainClock.advanceTimeBy(80)
        rule.runOnIdle { assertEquals(0, dismissals) }
        rule.mainClock.advanceTimeBy(800)
        rule.runOnIdle { assertEquals(1, dismissals) }
        rule.onNodeWithTag(SHARE_CHAT_PICKER_ACCOUNT_SHEET_TEST_TAG).assertDoesNotExist()
    }
}
