package dev.ipf.whitenoise.android.ui.screenshot

import android.content.Context
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.AppThemeMode
import dev.ipf.whitenoise.android.state.BubbleSide
import dev.ipf.whitenoise.android.state.BubbleTheme
import dev.ipf.whitenoise.android.state.DraftPersistence
import dev.ipf.whitenoise.android.state.DraftStore
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.settings.ChatBubbleColorsScreen
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The bubble colour editor with a saved sent colour: pinned preview, two pickers and the Save action. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h900dp-mdpi")
class ChatBubbleColorsPreviewScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    /** Dark editor; the received preview sits above the sent one, as in the prototype. */
    @Test
    fun chatBubbleColorsScreenDark() = capture(AppThemeMode.Dark, "chat_bubble_colors_screen_dark.png")

    /** Light editor with the same layout. */
    @Test
    fun chatBubbleColorsScreenLight() = capture(AppThemeMode.Light, "chat_bubble_colors_screen_light.png")

    /** Renders the editor, checks the preview order and records the window. */
    private fun capture(
        mode: AppThemeMode,
        name: String,
    ) {
        val appState = testAppState(mode)
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = mode == AppThemeMode.Dark) {
                ChatBubbleColorsScreen(appState = appState, onBack = {})
            }
        }
        composeRule.waitForIdle()

        val otherTop = previewTop(R.string.bubble_preview_other)
        val mineTop = previewTop(R.string.bubble_preview_mine)
        assertTrue("Expected received preview above sent preview", otherTop < mineTop)

        composeRule.onRoot().captureRoboImage("src/test/snapshots/$name")
    }

    /** Top edge of the preview bubble carrying that text. */
    private fun previewTop(textRes: Int): Float =
        composeRule
            .onNodeWithText(context.getString(textRes))
            .fetchSemanticsNode()
            .boundsInRoot.top

    /** An active account on [mode] whose sent bubbles are saved as red for that theme. */
    private fun testAppState(mode: AppThemeMode): WhiteNoiseAppState =
        WhiteNoiseAppState(
            context = context,
            draftStore = DraftStore(InMemoryDraftPersistence()),
            accountIdHexResolver = { null },
            accounts = listOf(activeAccount()),
            activeAccountRef = ACCOUNT_REF,
        ).also {
            it.updateThemeMode(mode)
            val theme = if (mode == AppThemeMode.Dark) BubbleTheme.Dark else BubbleTheme.Light
            it.updateGlobalBubbleColor(theme, BubbleSide.Mine, SAVED_MINE)
        }

    /** The signed-in local account the editor scopes its colours to. */
    private fun activeAccount() =
        AccountSummaryFfi(
            label = ACCOUNT_REF,
            accountIdHex = ACCOUNT_HEX,
            localSigning = true,
            externalSigning = false,
            signedOut = false,
            running = true,
        )

    private companion object {
        const val ACCOUNT_REF = "alice"
        const val ACCOUNT_HEX = "alice"
        const val SAVED_MINE = 0xFFB91C1CL
    }
}

/** Draft storage that never persists, so the screenshot fixture starts clean. */
private class InMemoryDraftPersistence : DraftPersistence {
    override fun read(): Map<String, String> = emptyMap()

    override fun write(
        key: String,
        value: String?,
    ) = Unit
}
