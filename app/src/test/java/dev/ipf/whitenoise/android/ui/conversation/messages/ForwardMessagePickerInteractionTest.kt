package dev.ipf.whitenoise.android.ui.conversation.messages

import androidx.compose.material3.Surface
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.ipf.whitenoise.android.ui.share.ACCOUNT_HEX
import dev.ipf.whitenoise.android.ui.share.ACCOUNT_REF
import dev.ipf.whitenoise.android.ui.share.appStateWithDirectChats
import dev.ipf.whitenoise.android.ui.share.profile
import dev.ipf.whitenoise.android.ui.share.testAccount
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h780dp-mdpi")
class ForwardMessagePickerInteractionTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun chatRowRemainsSelectableAndUnselectableAcrossRepeatedTaps() {
        val groupId = "20".repeat(32)
        val peerId = "40".repeat(32)
        val appState =
            appStateWithDirectChats(
                groupId to peerId,
                profiles = mutableMapOf(peerId to profile("Person 1")),
                accounts = listOf(testAccount(ACCOUNT_REF, ACCOUNT_HEX)),
            )
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = true) {
                Surface {
                    ForwardMessagePickerContent(
                        appState = appState,
                        messageCount = 2,
                        attachmentCount = 1,
                        originGroupIdHex = "ff".repeat(32),
                        sourceAccountRef = ACCOUNT_REF,
                        onDismiss = {},
                        onForward = { _, _ -> true },
                    )
                }
            }
        }

        val row = composeRule.onNodeWithText("Person 1")
        repeat(10) { tap ->
            row.performClick()
            if (tap % 2 == 0) row.assertIsSelected() else row.assertIsNotSelected()
        }
    }
}
