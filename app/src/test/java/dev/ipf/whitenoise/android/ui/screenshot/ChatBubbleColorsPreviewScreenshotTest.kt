package dev.ipf.whitenoise.android.ui.screenshot

import android.content.Context
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.AppThemeMode
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

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h780dp-mdpi")
class ChatBubbleColorsPreviewScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun chatBubbleColorsScreenShowsSentPreviewFirst() {
        val appState = testAppState()
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = true) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    ChatBubbleColorsScreen(appState = appState, onBack = {})
                }
            }
        }
        composeRule.waitForIdle()

        val mineTop =
            composeRule
                .onNodeWithText(context.getString(R.string.bubble_preview_mine))
                .fetchSemanticsNode()
                .boundsInRoot
                .top
        val otherTop =
            composeRule
                .onNodeWithText(context.getString(R.string.bubble_preview_other))
                .fetchSemanticsNode()
                .boundsInRoot
                .top
        assertTrue("Expected sent preview above received preview", mineTop < otherTop)

        composeRule.onRoot().captureRoboImage("src/test/snapshots/chat_bubble_colors_screen_dark.png")
    }

    private fun testAppState(): WhiteNoiseAppState =
        WhiteNoiseAppState(
            context = context,
            draftStore = DraftStore(InMemoryDraftPersistence()),
            accountIdHexResolver = { null },
            accounts = listOf(activeAccount()),
            activeAccountRef = ACCOUNT_REF,
        ).also { it.updateThemeMode(AppThemeMode.Dark) }

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
    }
}

private class InMemoryDraftPersistence : DraftPersistence {
    override fun read(): Map<String, String> = emptyMap()

    override fun write(
        key: String,
        value: String?,
    ) = Unit
}
