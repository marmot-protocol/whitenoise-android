package dev.ipf.whitenoise.android.ui.settings

import android.content.Context
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ApplicationProvider
import dev.ipf.whitenoise.android.state.AppThemeMode
import dev.ipf.whitenoise.android.state.BubbleTheme
import dev.ipf.whitenoise.android.state.DraftStore
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Behaviour contract of the Action colour editor: drafts stay local until Save writes the theme's colour. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-rUS-w360dp-h1600dp-mdpi")
class ActionColorScreenBehaviorTest {
    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var appState: WhiteNoiseAppState
    private var backCount = 0

    /** Start from cleared preferences, an active account and the light theme. */
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
                activeAccountRef = "alice",
            )
        appState.updateThemeMode(AppThemeMode.Light)
    }

    /** Save stays disabled until a swatch changes the draft; saving writes the light colour and returns once. */
    @Test
    fun saveWritesTheDraftAndReturns() {
        show()
        composeRule.onNodeWithTag("action_color.save").assertIsNotEnabled()
        composeRule.onNodeWithContentDescription("Color #1D4ED8").performClick()
        composeRule.onNodeWithTag("action_color.save").assertIsEnabled().performClick()
        composeRule.runOnIdle {
            assertEquals(0xFF1D4ED8L, appState.actionColorArgb(BubbleTheme.Light))
            assertEquals(1, backCount)
        }
    }

    /** Back without Save leaves the stored colour untouched. */
    @Test
    fun backWithoutSaveKeepsTheStoredColour() {
        appState.updateActionColor(BubbleTheme.Light, 0xFFB91C1CL)
        show()
        composeRule.onNodeWithContentDescription("Color #15803D").performClick()
        composeRule.onNodeWithContentDescription("Back").performClick()
        composeRule.runOnIdle {
            assertEquals(0xFFB91C1CL, appState.actionColorArgb(BubbleTheme.Light))
            assertEquals(1, backCount)
        }
    }

    /** The stored colour opens selected; Reset clears the draft and saving then removes the stored colour. */
    @Test
    fun resetThenSaveRemovesTheStoredColour() {
        appState.updateActionColor(BubbleTheme.Light, 0xFFB91C1CL)
        show()
        composeRule.onNodeWithContentDescription("Color #B91C1C").assertIsSelected()
        composeRule.onNodeWithTag("action_color.save").assertIsNotEnabled()
        composeRule.onNodeWithTag("action_color.reset").assertIsEnabled().performClick()
        composeRule.onNodeWithTag("action_color.save").assertIsEnabled().performClick()
        composeRule.runOnIdle { assertNull(appState.actionColorArgb(BubbleTheme.Light)) }
    }

    /** An invalid hex holds Save and explains the format until the field is valid again. */
    @Test
    fun invalidHexHoldsSave() {
        show()
        composeRule.onNodeWithTag("color.hex").performTextReplacement("#XYZ")
        composeRule.onNodeWithTag("action_color.save").assertIsNotEnabled()
        composeRule.onNodeWithText("Enter six hexadecimal digits, such as #1D4ED8.").assertExists()
        composeRule.onNodeWithTag("color.hex").performTextReplacement("#0E7490")
        composeRule.onNodeWithTag("action_color.save").assertIsEnabled()
    }

    /** AMOLED shows the fixed-colours notice instead of the editor. */
    @Test
    fun amoledShowsTheFixedColoursNotice() {
        appState.updateThemeMode(AppThemeMode.Amoled)
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = true, amoled = true) {
                ActionColorScreen(appState = appState, onBack = { backCount++ })
            }
        }
        composeRule
            .onNodeWithText("AMOLED uses fixed white action and bubble colors. Switch themes to customize colors.")
            .assertExists()
        composeRule.onNodeWithTag("action_color.save").assertDoesNotExist()
    }

    /** Renders the editor in the light theme, counting Back calls. */
    private fun show() {
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = false) {
                ActionColorScreen(appState = appState, onBack = { backCount++ })
            }
        }
    }
}
