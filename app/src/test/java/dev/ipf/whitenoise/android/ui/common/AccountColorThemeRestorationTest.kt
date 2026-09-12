package dev.ipf.whitenoise.android.ui.common

import android.content.Context
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import dev.ipf.whitenoise.android.state.AppThemeMode
import dev.ipf.whitenoise.android.state.BubbleSide
import dev.ipf.whitenoise.android.state.BubbleTheme
import dev.ipf.whitenoise.android.state.DraftStore
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.conversation.messages.messageBubblePresentation
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Stored account and chat colours survive AMOLED even though the rendered surfaces ignore them. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AccountColorThemeRestorationTest {
    @get:Rule
    val composeRule = createComposeRule()

    /** Theme transitions suppress legacy AMOLED accents and restore the saved light-theme account/chat colours. */
    @Test
    fun amoledSuppressesStoredColorsAndLightRestoresThem() {
        val appState = stateWithSavedColors()
        var renderedAction = Color.Unspecified
        var renderedBubble = 0L
        var renderedBorder: Long? = null
        composeRule.setContent {
            val amoled = appState.themeMode == AppThemeMode.Amoled
            val theme = if (amoled) BubbleTheme.Amoled else BubbleTheme.Light
            WhiteNoiseTheme(
                darkTheme = amoled,
                amoled = amoled,
                accentColorArgb = appState.actionColorArgb(theme),
            ) {
                val action = accountActionColors(appState)
                val bubble =
                    messageBubblePresentation(
                        deleted = false,
                        mine = true,
                        customArgb = appState.effectiveBubbleColorArgb(theme, BubbleSide.Mine, "chat"),
                    )
                SideEffect {
                    renderedAction = action.container
                    renderedBubble = bubble.backgroundArgb
                    renderedBorder = bubble.borderOverrideArgb
                }
            }
        }
        composeRule.runOnIdle {
            assertEquals(Color(LIGHT_ACCENT), renderedAction)
            assertEquals(CHAT_COLOR, renderedBubble)
            appState.updateThemeMode(AppThemeMode.Amoled)
        }
        composeRule.runOnIdle {
            assertEquals(Color.White, renderedAction)
            assertEquals(0xFF000000L, renderedBubble)
            assertNull(renderedBorder)
            assertEquals(AMOLED_ACCENT, appState.actionColorArgb(BubbleTheme.Amoled))
            assertEquals(AMOLED_ACCENT, appState.globalBubbleColorArgb(BubbleTheme.Amoled, BubbleSide.Mine))
            assertEquals(CHAT_COLOR, appState.chatBubbleColorArgb("chat", BubbleSide.Mine))
            appState.updateThemeMode(AppThemeMode.Light)
        }
        composeRule.runOnIdle {
            assertEquals(Color(LIGHT_ACCENT), renderedAction)
            assertEquals(CHAT_COLOR, renderedBubble)
            assertEquals(LIGHT_ACCENT, appState.actionColorArgb(BubbleTheme.Light))
        }
    }

    /** Seeds existing preferences so the test measures rendering without mutating those saved values. */
    private fun stateWithSavedColors(): WhiteNoiseAppState {
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
                activeAccountRef = "alice",
            )
        appState.updateThemeMode(AppThemeMode.Light)
        appState.updateActionColor(BubbleTheme.Light, LIGHT_ACCENT)
        appState.updateActionColor(BubbleTheme.Amoled, AMOLED_ACCENT)
        appState.updateGlobalBubbleColor(BubbleTheme.Amoled, BubbleSide.Mine, AMOLED_ACCENT)
        appState.updateChatBubbleColor("chat", BubbleSide.Mine, CHAT_COLOR)
        return appState
    }

    private companion object {
        const val LIGHT_ACCENT = 0xFFB91C1CL
        const val AMOLED_ACCENT = 0xFF15803DL
        const val CHAT_COLOR = 0xFF0E7490L
    }
}
