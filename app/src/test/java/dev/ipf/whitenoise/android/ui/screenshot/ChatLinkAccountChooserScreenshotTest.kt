package dev.ipf.whitenoise.android.ui.screenshot

import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.ui.ChatLinkAccountChoice
import dev.ipf.whitenoise.android.ui.ChatLinkAccountChooserDialog
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Pins the account chooser a chat link shows when several inactive accounts hold the chat. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h780dp-mdpi")
class ChatLinkAccountChooserScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    /** Light chooser lists each holding account with nothing preselected. */
    @Test
    fun chatLinkAccountChooserLight() {
        render(darkTheme = false)
        capture("light")
    }

    /** AMOLED chooser stays legible on the pure-black surface. */
    @Test
    fun chatLinkAccountChooserAmoled() {
        render(darkTheme = true, amoled = true)
        capture("amoled")
    }

    private fun render(
        darkTheme: Boolean,
        amoled: Boolean = false,
    ) {
        val names = mapOf("acct-a" to "Work", "acct-b" to "Personal")
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = darkTheme, amoled = amoled) {
                ChatLinkAccountChooserDialog(
                    choice = ChatLinkAccountChoice(groupIdHex = "ab".repeat(32), accountRefs = names.keys.toList()),
                    accountLabel = { names.getValue(it) },
                    onDismiss = {},
                    onSelect = {},
                )
            }
        }
    }

    private fun capture(variant: String) {
        composeRule.onNode(isDialog()).captureRoboImage("src/test/snapshots/chat_link_account_chooser_$variant.png")
    }
}
