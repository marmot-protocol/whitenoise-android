package dev.ipf.whitenoise.android.ui.settings

import android.content.Context
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import dev.ipf.whitenoise.android.state.DraftStore
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The language destination writes the chosen tag immediately and keeps the list open. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-rUS-w360dp-h1200dp-mdpi")
class LanguageScreenBehaviorTest {
    @get:Rule
    val composeRule = createComposeRule()

    /** Choosing a language writes its tag, selects its row, and Back returns to the caller once. */
    @Test
    fun choosingALanguageWritesTheTagAndStaysOnScreen() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context
            .getSharedPreferences("whitenoise", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        val appState =
            WhiteNoiseAppState(
                context = context,
                draftStore = DraftStore.forContext(context),
                accountIdHexResolver = { null },
                accounts = emptyList(),
                activeAccountRef = "missing-account",
            )
        var backCount = 0
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = false) {
                LanguageScreen(appState = appState, onBack = { backCount++ })
            }
        }

        composeRule.onNodeWithText("System default").assertIsSelected()
        composeRule.onNodeWithText("Deutsch").performClick()
        composeRule.runOnIdle { assertEquals("de", appState.languageTag) }
        composeRule.onNodeWithText("Deutsch").assertIsSelected()
        composeRule.onNodeWithContentDescription("Back").performClick()
        composeRule.runOnIdle { assertEquals(1, backCount) }
    }
}
