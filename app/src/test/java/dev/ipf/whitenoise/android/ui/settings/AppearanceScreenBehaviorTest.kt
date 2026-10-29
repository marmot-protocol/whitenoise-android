package dev.ipf.whitenoise.android.ui.settings

import android.content.Context
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontFamily
import androidx.test.core.app.ApplicationProvider
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.AppFont
import dev.ipf.whitenoise.android.state.AppFontScale
import dev.ipf.whitenoise.android.state.AppThemeMode
import dev.ipf.whitenoise.android.state.DraftStore
import dev.ipf.whitenoise.android.state.EnterKeyBehavior
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import dev.ipf.whitenoise.android.ui.theme.fontFamilyOrNull
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Behaviour contract of the Appearance screen: every callback and preference write a restyle must preserve.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-rUS-w360dp-h1200dp-mdpi")
class AppearanceScreenBehaviorTest {
    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var appState: WhiteNoiseAppState
    private var backCount = 0
    private var actionColorCount = 0
    private var bubbleColorsCount = 0
    private var languageCount = 0
    private var customEmojiCount = 0

    /** Start each test from default appearance preferences and fresh callback counters. */
    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context
            .getSharedPreferences("whitenoise", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        appState =
            WhiteNoiseAppState(
                context = context,
                draftStore = DraftStore.forContext(context),
                accountIdHexResolver = { null },
                accounts = emptyList(),
                activeAccountRef = "missing-account",
            )
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = false, fontScale = appState.fontScale.factor, appFont = appState.appFont) {
                AppearanceScreen(
                    appState = appState,
                    onBack = { backCount++ },
                    onOpenActionColor = { actionColorCount++ },
                    onOpenChatBubbleColors = { bubbleColorsCount++ },
                    onOpenLanguage = { languageCount++ },
                    onOpenCustomEmoji = { customEmojiCount++ },
                )
            }
        }
    }

    /** Back, the colour editor, custom emoji and language rows each invoke their caller exactly once per tap. */
    @Test
    fun navigationCallbacksFireOncePerTap() {
        composeRule.onNodeWithContentDescription("Back").performClick()
        composeRule.onNodeWithText("Action color").performClick()
        composeRule.onNodeWithText("Chat bubble colors").performClick()
        composeRule.onNodeWithText("Custom emoji").performClick()
        composeRule.onNodeWithText("Language").performClick()
        composeRule.runOnIdle {
            assertEquals(1, backCount)
            assertEquals(1, actionColorCount)
            assertEquals(1, bubbleColorsCount)
            assertEquals(1, customEmojiCount)
            assertEquals(1, languageCount)
            assertEquals("", appState.languageTag)
        }
    }

    /** AMOLED retains global bubble customization while keeping action buttons fixed white. */
    @Test
    fun amoledKeepsBubbleColorsEnabledAndHidesOnlyActionColor() {
        composeRule.onNodeWithTag("appearance.colors.group").assertExists()
        composeRule.onNodeWithText("AMOLED").performClick()
        composeRule.runOnIdle { assertEquals(AppThemeMode.Amoled, appState.themeMode) }
        composeRule.onNodeWithTag("appearance.colors.group").assertExists()
        composeRule.onNodeWithText("Action color").assertDoesNotExist()
        composeRule.onNodeWithText("Chat bubble colors").assertIsEnabled().performClick()
        composeRule
            .onNodeWithText("AMOLED uses fixed white action and bubble colors. Switch themes to customize colors.")
            .assertDoesNotExist()
        composeRule.runOnIdle {
            assertEquals(0, actionColorCount)
            assertEquals(1, bubbleColorsCount)
        }
        composeRule.onNodeWithText("Light").performClick()
        composeRule.onNodeWithText("Action color").assertIsEnabled()
        composeRule.onNodeWithText("Chat bubble colors").assertIsEnabled()
    }

    /** Tapping a theme row selects it and writes the mode, including the AMOLED mode. */
    @Test
    fun themeModeCardsWriteTheSelectedMode() {
        assertEquals(AppThemeMode.System, appState.themeMode)
        composeRule.onNode(themeRow("System default")).assertIsSelected()
        composeRule.onNodeWithText("Dark").performClick()
        composeRule.runOnIdle { assertEquals(AppThemeMode.Dark, appState.themeMode) }
        composeRule.onNodeWithText("AMOLED").performClick()
        composeRule.runOnIdle { assertEquals(AppThemeMode.Amoled, appState.themeMode) }
        composeRule.onNodeWithText("AMOLED").assertIsSelected()
    }

    /** Each saved step updates the live sample and stays selected until the picker is dismissed. */
    @Test
    fun fontSizeSheetPreviewsEveryStepUntilDone() {
        composeRule.onNodeWithText("Font size").performClick()
        composeRule.onNodeWithText("Text size adds to your device font-size setting.").assertExists()
        for (scale in AppFontScale.entries) {
            composeRule.onNodeWithTag("font_size.${scale.name}").performClick()
            composeRule.runOnIdle { assertEquals(scale, appState.fontScale) }
            composeRule.onNodeWithTag("font_size.preview").assertExists()
            composeRule.onNodeWithTag("font_size.${scale.name}").assertIsSelected()
        }
        composeRule.onNodeWithTag("font_size.Default").performClick()
        composeRule.runOnIdle { assertEquals(AppFontScale.Default, appState.fontScale) }
        composeRule.onNodeWithText("Done").performClick()
        composeRule.onNodeWithTag("font_size.preview").assertDoesNotExist()
    }

    /** Every font restyles the actual live sample; System restores the platform face before Done. */
    @Test
    fun appFontSheetPreviewsEveryFamilyUntilDone() {
        assertEquals(AppFont.System, appState.appFont)
        composeRule.onNodeWithText("App font").performClick()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val sampleText = context.getString(R.string.font_size_sample_received)

        fun sampleFamily(): FontFamily? {
            val layouts = mutableListOf<TextLayoutResult>()
            composeRule
                .onNode(
                    hasText(sampleText) and hasAnyAncestor(hasTestTag("font_family.preview")),
                    useUnmergedTree = true,
                ).performScrollTo()
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            return layouts
                .single()
                .layoutInput
                .style
                .fontFamily
        }

        val systemFamily = sampleFamily()
        for (font in AppFont.entries) {
            composeRule
                .onNodeWithTag("font_family.${font.name}")
                .performScrollTo()
                .performClick()
            composeRule.runOnIdle { assertEquals(font, appState.appFont) }
            composeRule.onNodeWithTag("font_family.preview").assertExists()
            composeRule.onNodeWithTag("font_family.${font.name}").assertIsSelected()
            assertEquals(font.fontFamilyOrNull() ?: systemFamily, sampleFamily())
        }
        composeRule
            .onNodeWithTag("font_family.System")
            .performScrollTo()
            .performClick()
        composeRule.runOnIdle { assertEquals(AppFont.System, appState.appFont) }
        assertEquals(systemFamily, sampleFamily())
        composeRule.onNodeWithText("Done").performClick()
        composeRule.onNodeWithTag("font_family.preview").assertDoesNotExist()
    }

    /** The Enter key dialog explains Shift+Enter, applies on tap, and Cancel keeps the stored choice. */
    @Test
    fun enterKeyDialogAppliesOnTapAndCancelKeepsIt() {
        composeRule.onNodeWithText("Enter key behavior").performClick()
        composeRule.onNodeWithText("Shift+Enter always inserts a new line on a hardware keyboard.").assertExists()
        composeRule.onNodeWithText("Send message").performClick()
        composeRule.runOnIdle { assertEquals(EnterKeyBehavior.SendMessage, appState.enterKeyBehavior) }
        composeRule.onNodeWithText("Enter key behavior").performClick()
        composeRule.onNodeWithText("Cancel").performClick()
        composeRule.runOnIdle { assertEquals(EnterKeyBehavior.SendMessage, appState.enterKeyBehavior) }
        composeRule.onNodeWithText("New line").assertDoesNotExist()
    }

    /** The language row shows the current choice and is a destination, so no list opens in place. */
    @Test
    fun languageRowShowsTheCurrentChoiceAndOpensNothingInPlace() {
        composeRule.onNode(hasText("Language") and hasText("System default")).assertExists()
        composeRule.onNodeWithText("Language").performClick()
        composeRule.onNodeWithText("Deutsch").assertDoesNotExist()
        composeRule.runOnIdle { assertEquals(1, languageCount) }
    }

    /** Sample switches and reset affect only the inline sample, never the persistent appearance owner. */
    @Test
    fun accessibilityPreviewResetsWithoutWritingAppearancePreferences() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val rtl = context.getString(R.string.appearance_preview_rtl)
        val large = context.getString(R.string.appearance_preview_large_text)
        val reset = context.getString(R.string.appearance_preview_reset)
        val preferences = context.getSharedPreferences("whitenoise", Context.MODE_PRIVATE)
        val before = preferences.all.toMap()
        composeRule.onNodeWithTag("settings.list").performScrollToNode(hasText(rtl))
        composeRule
            .onNodeWithText(rtl)
            .performScrollTo()
            .assertIsOff()
            .performClick()
        composeRule.onNodeWithText(rtl).assertIsOn()
        composeRule
            .onNodeWithText(large)
            .performScrollTo()
            .assertIsOff()
            .performClick()
        composeRule.onNodeWithText(large).assertIsOn()
        composeRule.onNodeWithText(reset).performScrollTo().performClick()
        composeRule.onNodeWithText(rtl).performScrollTo().assertIsOff()
        composeRule.onNodeWithText(large).performScrollTo().assertIsOff()
        composeRule.runOnIdle {
            assertEquals(before, preferences.all)
            assertEquals(AppFont.System, appState.appFont)
            assertEquals(AppFontScale.Default, appState.fontScale)
            assertEquals(AppThemeMode.System, appState.themeMode)
        }
    }

    /** Lazy disposal must not reset sample-only switches while the Appearance route stays open. */
    @Test
    @Config(qualifiers = "en-rUS-w360dp-h640dp-mdpi")
    fun previewSwitchesSurviveScrollingAwayAndBack() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val preview = hasTestTag("appearance.accessibility.preview")
        val rtl = context.getString(R.string.appearance_preview_rtl)
        val large = context.getString(R.string.appearance_preview_large_text)
        composeRule.onNodeWithTag("settings.list").performScrollToNode(preview)
        composeRule
            .onNodeWithText(rtl)
            .performScrollTo()
            .performClick()
            .assertIsOn()
        composeRule
            .onNodeWithText(large)
            .performScrollTo()
            .performClick()
            .assertIsOn()
        composeRule.onNodeWithTag("settings.list").performScrollToIndex(0)
        composeRule.onNode(preview).assertDoesNotExist()
        composeRule.onNodeWithTag("settings.list").performScrollToNode(preview)
        composeRule.onNodeWithText(rtl).performScrollTo().assertIsOn()
        composeRule.onNodeWithText(large).performScrollTo().assertIsOn()
    }

    /** A theme row is the node with that label inside the theme group, not the language value. */
    private fun themeRow(label: String) = hasText(label) and hasAnyAncestor(hasTestTag("appearance.theme.group"))
}
