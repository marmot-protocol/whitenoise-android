package dev.ipf.whitenoise.android.ui.conversation.messages

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Built-in shortcodes in a kept message draw their artwork in both card states, themes and typography. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-rUS-w360dp-h780dp-mdpi")
class KeptMessageEmojiScreenshotTest {
    @get:Rule val composeRule = createComposeRule()

    /** The collapsed preview draws the Marmot and White Noise artwork inline, leaving the code literal as text. */
    @Test
    fun collapsedLight() = capture("kept_emoji_collapsed_light")

    /** The expanded card draws artwork in the author name and the full body on the dark surface. */
    @Test
    fun expandedDark() = capture("kept_emoji_expanded_dark", dark = true, expanded = true)

    /** AMOLED keeps the inline artwork legible against black. */
    @Test
    fun collapsedAmoled() = capture("kept_emoji_collapsed_amoled", dark = true, amoled = true)

    /** RTL at 200% text keeps the artwork sized to its line and the preview bounded. */
    @Test
    fun expandedRtlLargeText() = capture("kept_emoji_expanded_rtl_large", expanded = true, rtl = true)

    /** Hosts the production overlay with one kept message whose author and body carry built-in shortcodes. */
    @Suppress("LongParameterList") // Independent fixture choices cover the visual matrix without shared mutable state.
    private fun capture(
        name: String,
        dark: Boolean = false,
        amoled: Boolean = false,
        expanded: Boolean = false,
        rtl: Boolean = false,
    ) {
        val key = KeptMessageKey(SWIPE_TEST_ACCOUNT_REF, SWIPE_TEST_GROUP_ID, SWIPE_TEST_MESSAGE_ID)
        // A live text row keeps the entry resolvable; the card draws the fixed presentation below.
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val item = swipeTestSurface(context, reacted = false, mine = false, media = false).item
        composeRule.setContent {
            val direction = if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr
            CompositionLocalProvider(LocalLayoutDirection provides direction) {
                WhiteNoiseTheme(darkTheme = dark, amoled = amoled, fontScale = if (rtl) 2f else 1f) {
                    val controller = remember { KeptMessagesController().apply { keep(key) } }
                    Box(Modifier.fillMaxSize()) {
                        val entries = rememberKeptMessageEntries(controller, SWIPE_TEST_ACCOUNT_REF) { item }
                        KeptMessagesOverlay(
                            state = KeptMessagesOverlayState(entries, controller, SWIPE_TEST_ACCOUNT_REF),
                            composerHeight = 96.dp,
                            presentation = { PRESENTATION },
                            onOpenMessage = {},
                        )
                    }
                }
            }
        }
        composeRule.waitForIdle()
        if (expanded) {
            composeRule.onNodeWithTag(KEPT_MESSAGE_CARD_TAG).performTouchInput { longClick() }
            composeRule.waitForIdle()
        }
        composeRule.onNodeWithTag(KEPT_MESSAGE_CARD_TAG).captureRoboImage("src/test/snapshots/$name.png")
    }

    private companion object {
        val PRESENTATION =
            KeptMessagePresentation(
                authorName = "Alice :wn:",
                body = "Ship it :marmot: :wn: tonight, and keep `:marmot:` as typed.",
                chatTitle = "Design",
                timeLabel = "12:34",
            )
    }
}
