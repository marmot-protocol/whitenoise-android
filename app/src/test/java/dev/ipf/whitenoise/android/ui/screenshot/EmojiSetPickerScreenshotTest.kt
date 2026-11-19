package dev.ipf.whitenoise.android.ui.screenshot

import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.ui.BuiltinEmojiSet
import dev.ipf.whitenoise.android.ui.EmojiCategory
import dev.ipf.whitenoise.android.ui.EmojiPickerTab
import dev.ipf.whitenoise.android.ui.LocalHiddenBuiltinEmojiSets
import dev.ipf.whitenoise.android.ui.conversation.composer.ComposerEmojiPaneHorizontalPadding
import dev.ipf.whitenoise.android.ui.conversation.composer.ComposerEmojiPickerFallbackHeight
import dev.ipf.whitenoise.android.ui.conversation.composer.ComposerEmojiPickerPane
import dev.ipf.whitenoise.android.ui.conversation.composer.emojiPickerCategoryTestTag
import dev.ipf.whitenoise.android.ui.conversation.composer.emojiPickerHeaderTestTag
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The composer pane gives each pre-installed set its own section and tab, after Recent and before Smileys. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w392dp-h780dp-mdpi")
class EmojiSetPickerScreenshotTest {
    @get:Rule val composeRule = createComposeRule()

    @Test fun setsLight() = capture("light", dark = false)

    @Test fun setsAmoled() = capture("amoled", dark = true)

    @Test fun setsLargeFontRtl() = capture("large_rtl", dark = false, rtl = true)

    @Test
    fun setSectionsSitBetweenRecentAndSmileys() {
        render()
        waitForHeader(BuiltinEmojiSet.Marmot)
        val tops =
            listOf(EmojiCategory.Recent, BuiltinEmojiSet.WhiteNoise, BuiltinEmojiSet.Marmot)
                .map { headerTop(it) }
        assertTrue("Sections run Recent, White Noise, Marmot: $tops", tops == tops.sorted() && tops.distinct() == tops)
        BuiltinEmojiSet.entries.forEach { composeRule.onNodeWithTag(emojiPickerCategoryTestTag(it)).assertExists() }
    }

    @Test
    fun setTabScrollsToItsSection() {
        render()
        waitForHeader(BuiltinEmojiSet.Marmot)
        composeRule.onNodeWithTag(emojiPickerCategoryTestTag(EmojiCategory.SmileysAndPeople)).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(emojiPickerCategoryTestTag(BuiltinEmojiSet.Marmot)).performClick()
        waitForHeader(BuiltinEmojiSet.Marmot)
        val pane = composeRule.onNodeWithTag(PANE_TAG).getBoundsInRoot()
        assertTrue("The Marmot section scrolls to the pane's top", headerTop(BuiltinEmojiSet.Marmot) - pane.top < 48.dp)
    }

    /** A removed set loses its section and tab; the other set keeps both. */
    @Test
    fun removedSetLeavesThePicker() {
        render(hidden = setOf(BuiltinEmojiSet.Marmot))
        waitForHeader(BuiltinEmojiSet.WhiteNoise)
        composeRule.onNodeWithTag(emojiPickerHeaderTestTag(BuiltinEmojiSet.Marmot)).assertDoesNotExist()
        composeRule.onNodeWithTag(emojiPickerCategoryTestTag(BuiltinEmojiSet.Marmot)).assertDoesNotExist()
        composeRule.onNodeWithTag(emojiPickerCategoryTestTag(BuiltinEmojiSet.WhiteNoise)).assertExists()
    }

    private fun capture(
        name: String,
        dark: Boolean,
        rtl: Boolean = false,
    ) {
        render(dark = dark, rtl = rtl)
        waitForHeader(BuiltinEmojiSet.Marmot)
        composeRule.onNodeWithTag(PANE_TAG).captureRoboImage("src/test/snapshots/emoji_set_picker_$name.png")
    }

    private fun render(
        dark: Boolean = false,
        rtl: Boolean = false,
        hidden: Set<BuiltinEmojiSet> = emptySet(),
    ) {
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = dark, amoled = dark) {
                val base = LocalDensity.current
                CompositionLocalProvider(
                    LocalDensity provides Density(base.density, fontScale = if (rtl) 2f else 1f),
                    LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
                    LocalHiddenBuiltinEmojiSets provides hidden,
                ) {
                    ComposerEmojiPickerPane(
                        height = ComposerEmojiPickerFallbackHeight,
                        alpha = 1f,
                        recentEmojis = listOf("👍", "❤️", "😂"),
                        onEmojiUsed = {},
                        onEmojiPicked = {},
                        onBackspace = {},
                        onSearchActiveChange = {},
                        modifier = Modifier.width(360.dp + ComposerEmojiPaneHorizontalPadding * 2).testTag(PANE_TAG),
                    )
                }
            }
        }
    }

    private fun waitForHeader(tab: EmojiPickerTab) {
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithTag(emojiPickerHeaderTestTag(tab), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun headerTop(tab: EmojiPickerTab) = composeRule.onNodeWithTag(emojiPickerHeaderTestTag(tab), useUnmergedTree = true).getBoundsInRoot().top

    private companion object {
        const val PANE_TAG = "emoji-set-picker-pane"
    }
}
