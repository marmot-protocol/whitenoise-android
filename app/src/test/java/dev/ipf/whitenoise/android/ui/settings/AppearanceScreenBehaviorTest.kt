package dev.ipf.whitenoise.android.ui.settings

import android.content.Context
import androidx.compose.ui.test.assertIsSelected
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
 * Pre-change behaviour contract of the Appearance screen: every callback and preference write
 * the M123 pilot must preserve, captured against the current presentation.
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

    /** Start each test from default appearance preferences and fresh callback counters. */
    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("whitenoise", Context.MODE_PRIVATE).edit().clear().commit()
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
                )
            }
        }
    }

    /** Back and the two colour editor rows each invoke their caller exactly once per tap. */
    @Test
    fun navigationCallbacksFireOncePerTap() {
        composeRule.onNodeWithContentDescription("Back").performClick()
        composeRule.onNodeWithText("App accent color").performClick()
        composeRule.onNodeWithText("Chat bubble colors").performClick()
        composeRule.runOnIdle {
            assertEquals(1, backCount)
            assertEquals(1, actionColorCount)
            assertEquals(1, bubbleColorsCount)
        }
    }

    /** Tapping a theme card selects it and writes the mode, including the AMOLED mode. */
    @Test
    fun themeModeCardsWriteTheSelectedMode() {
        assertEquals(AppThemeMode.System, appState.themeMode)
        composeRule.onNodeWithText("Dark").performClick()
        composeRule.runOnIdle { assertEquals(AppThemeMode.Dark, appState.themeMode) }
        composeRule.onNodeWithText("AMOLED").performClick()
        composeRule.runOnIdle { assertEquals(AppThemeMode.Amoled, appState.themeMode) }
        composeRule.onNodeWithText("AMOLED").assertIsSelected()
    }

    /** The font size sheet writes the chosen step, closes, and the row shows the new value. */
    @Test
    fun fontSizeSheetWritesScaleAndCloses() {
        composeRule.onNodeWithText("Font size").performClick()
        composeRule.onNodeWithText("Extra large").performClick()
        composeRule.runOnIdle { assertEquals(AppFontScale.ExtraLarge, appState.fontScale) }
        composeRule.onNodeWithText("Small").assertDoesNotExist()
        composeRule.onNodeWithText("Extra large").assertExists()
    }

    /** The app font sheet writes the chosen family and closes. */
    @Test
    fun appFontSheetWritesFontAndCloses() {
        composeRule.onNodeWithText("App font").performClick()
        composeRule.onNodeWithText("Outfit").performClick()
        composeRule.runOnIdle { assertEquals(AppFont.Outfit, appState.appFont) }
        composeRule.onNodeWithText("Urbanist").assertDoesNotExist()
    }

    /** The Enter key dialog applies a choice on tap, and Cancel leaves the stored choice alone. */
    @Test
    fun enterKeyDialogAppliesOnTapAndCancelKeepsIt() {
        composeRule.onNodeWithText("Enter key behavior").performClick()
        composeRule.onNodeWithText("Send message").performClick()
        composeRule.runOnIdle { assertEquals(EnterKeyBehavior.SendMessage, appState.enterKeyBehavior) }
        composeRule.onNodeWithText("Enter key behavior").performClick()
        composeRule.onNodeWithText("Cancel").performClick()
        composeRule.runOnIdle { assertEquals(EnterKeyBehavior.SendMessage, appState.enterKeyBehavior) }
        composeRule.onNodeWithText("New line").assertDoesNotExist()
    }

    /** The language sheet writes the selected tag and closes. */
    @Test
    fun languageSheetWritesTagAndCloses() {
        composeRule.onNodeWithText("Language").performClick()
        composeRule.onNodeWithText("Deutsch").performClick()
        composeRule.runOnIdle { assertEquals("de", appState.languageTag) }
        composeRule.onNodeWithText("Español").assertDoesNotExist()
    }
}
