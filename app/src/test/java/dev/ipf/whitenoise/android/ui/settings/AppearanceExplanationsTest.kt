package dev.ipf.whitenoise.android.ui.settings

import android.content.Context
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.AppFontScale
import dev.ipf.whitenoise.android.state.BubbleTheme
import dev.ipf.whitenoise.android.ui.common.ExplanationHost
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Hold-to-explain on the appearance, font size, spoken audio and date/time controls. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
class AppearanceExplanationsTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private var clicks = 0

    @Test
    fun appFontLinkHoldExplainsAndQuickClickRuns() =
        assertHoldThenClick(R.string.explanation_appearance_app_font, TAG) {
            SettingsGroup {
                row("app-font") { rowContext ->
                    SettingsLink(
                        context = rowContext,
                        title = "App font",
                        onClick = { clicks++ },
                        modifier = Modifier.testTag(TAG),
                        explanation = context.getString(R.string.explanation_appearance_app_font),
                    )
                }
            }
        }

    @Test
    fun fontSizeLinkHoldExplainsAndQuickClickRuns() =
        assertHoldThenClick(R.string.explanation_appearance_font_size, TAG) {
            SettingsGroup {
                row("font-size") { rowContext ->
                    SettingsLink(
                        context = rowContext,
                        title = "Font size",
                        onClick = { clicks++ },
                        modifier = Modifier.testTag(TAG),
                        explanation = context.getString(R.string.explanation_appearance_font_size),
                    )
                }
            }
        }

    @Test
    fun spokenAudioSwitchHoldExplainsAndQuickClickRuns() =
        assertHoldThenClick(R.string.explanation_appearance_spoken_audio_alert, "notification-quiet-spoken-audio") {
            SpokenAudioAlertSwitch(checked = false, onCheckedChange = { clicks++ })
        }

    @Test
    fun fontSizeOptionHoldExplainsAndQuickClickRuns() =
        assertHoldThenClick(R.string.explanation_appearance_font_size_large, "font_size.${AppFontScale.Large.name}") {
            FontSizeDialog(
                selected = AppFontScale.Default,
                onSelect = { clicks++ },
                onDismiss = {},
                theme = BubbleTheme.Light,
                mineArgb = null,
                otherArgb = null,
            )
        }

    /** A quick click runs the control once; holding it opens the sheet and leaves the callback untouched. */
    private fun assertHoldThenClick(
        body: Int,
        tag: String,
        content: @Composable () -> Unit,
    ) {
        composeRule.setContent {
            WhiteNoiseTheme { ExplanationHost { Surface { content() } } }
        }
        composeRule.onNodeWithTag(tag).performClick()
        assertEquals(1, clicks)
        clicks = 0
        composeRule.onNodeWithTag(tag).performTouchInput { longClick() }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("explanation.sheet").assertIsDisplayed()
        composeRule.onNodeWithTag("explanation.body").assertTextEquals(context.getString(body))
        assertEquals(0, clicks)
    }

    private companion object {
        const val TAG = "settings.row"
    }
}
