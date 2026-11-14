package dev.ipf.whitenoise.android.ui.conversation.composer

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.performClick
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Name editors deliberately keep the actual emoji sheet open for multiple insertions. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
class EmojiSheetTerminalTest {
    @get:Rule val rule = createComposeRule()

    @Test fun nonterminalPickerAcceptsSeveralChoicesWithoutHiding() {
        var picks = 0
        var dismissals = 0
        rule.setContent {
            WhiteNoiseTheme(darkTheme = true, amoled = true) {
                EmojiPickerSheet(onDismissRequest = { dismissals++ }, onEmojiPicked = { picks++ }, closeOnPick = false)
            }
        }
        val emoji =
            SemanticsMatcher("emoji choice") {
                it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("emoji.picker.item.") == true
            }
        rule.waitUntil(5_000) { rule.onAllNodes(emoji).fetchSemanticsNodes().isNotEmpty() }
        rule.onAllNodes(emoji)[0].assertIsDisplayed().performClick()
        rule.runOnIdle {
            assertEquals(1, picks)
            assertEquals(0, dismissals)
        }
        rule.onAllNodes(emoji)[0].assertIsDisplayed().performClick()
        rule.onAllNodes(emoji)[0].assertIsDisplayed()
        rule.runOnIdle {
            assertEquals(2, picks)
            assertEquals(0, dismissals)
        }
        rule.mainClock.advanceTimeBy(800)
        rule.onAllNodes(emoji)[0].assertIsDisplayed()
        rule.runOnIdle {
            assertEquals(2, picks)
            assertEquals(0, dismissals)
        }
    }
}
