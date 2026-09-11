package dev.ipf.whitenoise.android.ui.settings

import android.content.Context
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import dev.ipf.whitenoise.android.state.AppFont
import dev.ipf.whitenoise.android.state.AppFontScale
import dev.ipf.whitenoise.android.state.AppThemeMode
import dev.ipf.whitenoise.android.state.DraftStore
import dev.ipf.whitenoise.android.state.EnterKeyBehavior
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
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
            WhiteNoiseTheme(darkTheme = false) {
                AppearanceScreen(
                    appState = appState,
                    onBack = { backCount++ },
                    onOpenActionColor = { actionColorCount++ },
                    onOpenChatBubbleColors = { bubbleColorsCount++ },
                    onOpenLanguage = { languageCount++ },
                )
            }
        }
    }

    /** Back, the two colour editor rows and the language row each invoke their caller exactly once per tap. */
    @Test
    fun navigationCallbacksFireOncePerTap() {
        composeRule.onNodeWithContentDescription("Back").performClick()
        composeRule.onNodeWithText("Action color").performClick()
        composeRule.onNodeWithText("Chat bubble colors").performClick()
        composeRule.onNodeWithText("Language").performClick()
        composeRule.runOnIdle {
            assertEquals(1, backCount)
            assertEquals(1, actionColorCount)
            assertEquals(1, bubbleColorsCount)
            assertEquals(1, languageCount)
            assertEquals("", appState.languageTag)
        }
    }

    /** AMOLED fixes the palette: both colour editors disable, explain why, and never open. */
    @Test
    fun amoledDisablesColourEditorsWithAFixedColoursNotice() {
        composeRule.onNodeWithText("AMOLED").performClick()
        composeRule.runOnIdle { assertEquals(AppThemeMode.Amoled, appState.themeMode) }
        composeRule.onNodeWithText("Action color").assertIsNotEnabled().performClick()
        composeRule.onNodeWithText("Chat bubble colors").assertIsNotEnabled().performClick()
        composeRule
            .onNodeWithText("AMOLED uses fixed white action and bubble colors. Switch themes to customize colors.")
            .assertExists()
        composeRule.runOnIdle {
            assertEquals(0, actionColorCount)
            assertEquals(0, bubbleColorsCount)
        }
        composeRule.onNodeWithText("Light").performClick()
        composeRule.onNodeWithText("Action color").assertIsEnabled()
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

    /** The font size dialog explains the scale, writes the chosen step, closes, and the row shows the new value. */
    @Test
    fun fontSizeSheetWritesScaleAndCloses() {
        composeRule.onNodeWithText("Font size").performClick()
        composeRule.onNodeWithText("Text size adds to your device font-size setting.").assertExists()
        composeRule.onNodeWithText("Extra large").performClick()
        composeRule.runOnIdle { assertEquals(AppFontScale.ExtraLarge, appState.fontScale) }
        composeRule.onNodeWithText("Small").assertDoesNotExist()
        composeRule.onNodeWithText("Extra large").assertExists()
    }

    /** The app font dialog writes the chosen family and closes; the system face is the default. */
    @Test
    fun appFontSheetWritesFontAndCloses() {
        assertEquals(AppFont.System, appState.appFont)
        composeRule.onNodeWithText("App font").performClick()
        composeRule.onNodeWithText("Outfit").performClick()
        composeRule.runOnIdle { assertEquals(AppFont.Outfit, appState.appFont) }
        composeRule.onNodeWithText("Urbanist").assertDoesNotExist()
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

    /** A theme row is the node with that label inside the theme group, not the language value. */
    private fun themeRow(label: String) = hasText(label) and hasAnyAncestor(hasTestTag("appearance.theme.group"))
}
