package dev.ipf.whitenoise.android.ui.conversation.reactions

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.LayoutDirection
import com.github.takahirom.roborazzi.captureScreenRoboImage
import dev.ipf.whitenoise.android.core.RecentEmojiList
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The quick-reaction editor keeps the six standard slots and lets users add or remove two optional ones (#3279). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-rUS-w360dp-h780dp-mdpi")
class ConfigureReactionsSheetScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    /** Restores the platform font scale that the large-text capture raises. */
    @After
    fun restorePlatformFontScale() {
        RuntimeEnvironment.setFontScale(1f)
    }

    /** With six choices the add slot asks the caller to fill the seventh slot from the current draft. */
    @Test
    fun addSlotPicksTheNextOptionalSlot() {
        val picks = mutableListOf<Pair<Int, List<String>>>()
        render(current = RecentEmojiList.DefaultQuickChoices, onPickSlot = { index, draft -> picks += index to draft })

        composeRule.onNodeWithContentDescription("Add a reaction").assertIsDisplayed().performClick()

        assertEquals(listOf(6 to RecentEmojiList.DefaultQuickChoices), picks)
        captureScreenRoboImage("src/test/snapshots/configure_reactions_six_light.png")
    }

    /** Eight choices fill every slot, so the add slot disappears and both optional slots offer removal. */
    @Test
    fun removingAnOptionalSlotAppliesTheRemainingChoicesInOrder() {
        val applied = mutableListOf<List<String>>()
        render(current = EIGHT_CHOICES, darkTheme = true, onApply = { applied += it })

        composeRule.onNodeWithTag(CONFIGURE_REACTIONS_ADD_SLOT_TAG).assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Remove reaction 8, ✅").assertIsDisplayed()
        captureScreenRoboImage("src/test/snapshots/configure_reactions_eight_dark.png")
        composeRule.onNodeWithContentDescription("Remove reaction 7, 🚀").performClick()
        composeRule.onNodeWithText("Done").performClick()

        assertEquals(listOf(RecentEmojiList.DefaultQuickChoices + "✅"), applied)
    }

    /** The six standard slots are replace-only, so they never offer a remove button. */
    @Test
    fun standardSlotsCannotBeRemoved() {
        render(current = RecentEmojiList.DefaultQuickChoices)

        RecentEmojiList.DefaultQuickChoices.forEachIndexed { index, emoji ->
            composeRule.onNodeWithContentDescription("Remove reaction ${index + 1}, $emoji").assertDoesNotExist()
        }
    }

    /** An optional shortcode slot's remove button names the emoji readably, never as a raw `:shortcode:`. */
    @Test
    fun optionalShortcodeSlotRemoveButtonUsesItsReadableName() {
        render(current = RecentEmojiList.DefaultQuickChoices + listOf(":marmot:", ":party_parrot:"))

        composeRule.onNodeWithContentDescription("Remove reaction 7, Marmot").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Remove reaction 8, party parrot").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Remove reaction 7, :marmot:").assertDoesNotExist()
    }

    /** Reset drops the optional slots and returns to exactly the six defaults. */
    @Test
    fun resetReturnsToTheSixDefaults() {
        val applied = mutableListOf<List<String>>()
        render(current = EIGHT_CHOICES, onApply = { applied += it })

        composeRule.onNodeWithText("Reset").performClick()
        composeRule.onNodeWithTag(CONFIGURE_REACTIONS_ADD_SLOT_TAG).assertIsDisplayed()
        composeRule.onNodeWithText("Done").performClick()

        assertEquals(listOf(RecentEmojiList.DefaultQuickChoices), applied)
    }

    /** AMOLED keeps the optional slots and their remove buttons outlined against black. */
    @Test
    fun eightChoicesAmoled() {
        render(current = EIGHT_CHOICES, darkTheme = true, amoled = true)
        captureScreenRoboImage("src/test/snapshots/configure_reactions_eight_amoled.png")
    }

    /** RTL at a large font scale wraps every slot, the remove buttons and the add slot inside the sheet. */
    @Test
    fun sevenChoicesRtlLargeFont() {
        RuntimeEnvironment.setFontScale(LARGE_FONT_SCALE)
        render(
            current = EIGHT_CHOICES.take(7),
            fontScale = LARGE_FONT_SCALE,
            layoutDirection = LayoutDirection.Rtl,
        )

        composeRule.onNodeWithTag(CONFIGURE_REACTIONS_ADD_SLOT_TAG).assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Remove reaction 7, 🚀").assertIsDisplayed()
        captureScreenRoboImage("src/test/snapshots/configure_reactions_seven_rtl_large_font.png")
    }

    /** Hosts the production sheet with the given draft and callbacks. */
    private fun render(
        current: List<String>,
        darkTheme: Boolean = false,
        amoled: Boolean = false,
        fontScale: Float = 1f,
        layoutDirection: LayoutDirection = LayoutDirection.Ltr,
        onApply: (List<String>) -> Unit = {},
        onPickSlot: (Int, List<String>) -> Unit = { _, _ -> },
    ) {
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = darkTheme, amoled = amoled, fontScale = fontScale) {
                CompositionLocalProvider(LocalLayoutDirection provides layoutDirection) {
                    ConfigureReactionsSheet(
                        current = current,
                        onDismiss = {},
                        onApply = onApply,
                        onPickSlot = onPickSlot,
                    )
                }
            }
        }
        composeRule.waitForIdle()
    }

    private companion object {
        const val LARGE_FONT_SCALE = 1.5f
        val EIGHT_CHOICES = RecentEmojiList.DefaultQuickChoices + listOf("🚀", "✅")
    }
}
