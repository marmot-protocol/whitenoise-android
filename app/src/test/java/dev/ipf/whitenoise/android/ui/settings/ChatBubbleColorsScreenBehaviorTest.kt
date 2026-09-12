package dev.ipf.whitenoise.android.ui.settings

import android.content.Context
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import dev.ipf.whitenoise.android.state.AppThemeMode
import dev.ipf.whitenoise.android.state.BubbleSide
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

/** Behaviour contract of the bubble colour editor for theme defaults and per-chat overrides. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-rUS-w360dp-h2000dp-mdpi")
class ChatBubbleColorsScreenBehaviorTest {
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

    /** Both previews render, Save waits for a change, and saving writes both sides for the theme and returns. */
    @Test
    fun saveWritesBothSidesForTheTheme() {
        show()
        composeRule.onNodeWithText("Their message").assertExists()
        composeRule.onNodeWithText("Your message").assertExists()
        composeRule.onNodeWithTag("bubble_colors.save").assertIsNotEnabled()
        composeRule.onNode(swatch("#B91C1C", "mine")).performScrollTo().performClick()
        composeRule.onNode(swatch("#15803D", "other")).performScrollTo().performClick()
        composeRule.onNodeWithTag("bubble_colors.save").assertIsEnabled().performClick()
        composeRule.runOnIdle {
            assertEquals(0xFFB91C1CL, appState.globalBubbleColorArgb(BubbleTheme.Light, BubbleSide.Mine))
            assertEquals(0xFF15803DL, appState.globalBubbleColorArgb(BubbleTheme.Light, BubbleSide.Other))
            assertEquals(1, backCount)
        }
    }

    /** The overflow menu's reset clears both drafts; Save then removes the stored colours. */
    @Test
    fun menuResetClearsBothSides() {
        appState.updateGlobalBubbleColor(BubbleTheme.Light, BubbleSide.Mine, 0xFFB91C1CL)
        appState.updateGlobalBubbleColor(BubbleTheme.Light, BubbleSide.Other, 0xFF6D28D9L)
        show()
        composeRule.onNode(swatch("#B91C1C", "mine")).assertIsSelected()
        composeRule.onNodeWithTag("bubble_colors.menu").performClick()
        composeRule.onNodeWithText("Reset to default").performClick()
        composeRule.onNodeWithTag("bubble_colors.save").assertIsEnabled().performClick()
        composeRule.runOnIdle {
            assertNull(appState.globalBubbleColorArgb(BubbleTheme.Light, BubbleSide.Mine))
            assertNull(appState.globalBubbleColorArgb(BubbleTheme.Light, BubbleSide.Other))
        }
    }

    /** A chat override starts from the account defaults and its menu offers a disabled reset to those defaults. */
    @Test
    fun chatOverrideInheritsDefaultsAndLabelsReset() {
        appState.updateGlobalBubbleColor(BubbleTheme.Light, BubbleSide.Mine, 0xFFB91C1CL)
        show(groupIdHex = "group-1")
        composeRule.onNode(swatch("#B91C1C", "mine")).assertIsSelected()
        composeRule.onNodeWithTag("bubble_colors.menu").performClick()
        composeRule.onNodeWithText("Reset to global colors").assertIsNotEnabled()
    }

    /** Saving a chat override writes only the changed side for that chat and leaves the account defaults alone. */
    @Test
    fun chatOverrideSavesOnlyTheChatColours() {
        appState.updateGlobalBubbleColor(BubbleTheme.Light, BubbleSide.Mine, 0xFFB91C1CL)
        show(groupIdHex = "group-1")
        composeRule.onNode(swatch("#0E7490", "other")).performScrollTo().performClick()
        composeRule.onNodeWithTag("bubble_colors.save").assertIsEnabled().performClick()
        composeRule.runOnIdle {
            assertEquals(0xFF0E7490L, appState.chatBubbleColorArgb("group-1", BubbleSide.Other))
            assertNull(appState.chatBubbleColorArgb("group-1", BubbleSide.Mine))
            assertEquals(0xFFB91C1CL, appState.globalBubbleColorArgb(BubbleTheme.Light, BubbleSide.Mine))
            assertEquals(1, backCount)
        }
    }

    /** Renders the editor in the light theme for the account defaults or one chat, counting Back calls. */
    private fun show(groupIdHex: String? = null) {
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = false) {
                ChatBubbleColorsScreen(appState = appState, onBack = { backCount++ }, groupIdHex = groupIdHex)
            }
        }
    }

    /** The preset swatch with that hex inside the named picker. */
    private fun swatch(
        hex: String,
        picker: String,
    ) = hasContentDescription("Color $hex") and hasAnyAncestor(hasTestTag("bubble_colors.$picker.picker"))
}
