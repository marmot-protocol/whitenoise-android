package dev.ipf.whitenoise.android.ui.settings

import android.content.Context
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.common.Explanation
import dev.ipf.whitenoise.android.ui.common.ExplanationHost
import dev.ipf.whitenoise.android.ui.common.explainOnLongPress
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Hold-to-explain on organize controls (hubs, folders, emoji, help, sharing, agents, developer). */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
class OrganizeExplanationsTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private var clicks = 0

    @Test
    fun hubLinkHoldExplainsAndQuickClickRuns() =
        assertHoldThenClick(R.string.explanation_organize_hub_chat_folders) { explanation ->
            SettingsGroup {
                row("link") { rowContext ->
                    SettingsLink(rowContext, TITLE, { clicks++ }, Modifier.testTag(TAG), explanation = explanation)
                }
            }
        }

    @Test
    fun developerSwitchHoldExplainsAndQuickClickRuns() =
        assertHoldThenClick(R.string.explanation_organize_dev_mode) { explanation ->
            SettingsGroup {
                row("switch") { rowContext ->
                    SettingsSwitch(
                        rowContext,
                        TITLE,
                        false,
                        { clicks++ },
                        Modifier.testTag(TAG),
                        explanation = explanation,
                    )
                }
            }
        }

    @Test
    fun emojiActionHoldExplainsAndQuickClickRuns() =
        assertHoldThenClick(R.string.explanation_organize_emoji_check_update) { explanation ->
            SettingsGroup {
                row("action") { rowContext ->
                    SettingsAction(rowContext, TITLE, { clicks++ }, Modifier.testTag(TAG), explanation = explanation)
                }
            }
        }

    @Test
    fun customButtonHoldExplainsAndQuickClickRuns() =
        assertHoldThenClick(R.string.explanation_organize_share_scan) { explanation ->
            TextButton(
                onClick = { clicks++ },
                modifier = Modifier.testTag(TAG).explainOnLongPress(Explanation(TITLE, explanation)),
            ) { Text(TITLE) }
        }

    /** A quick click runs the control once; holding it opens the sheet and leaves the callback untouched. */
    private fun assertHoldThenClick(
        body: Int,
        content: @Composable (explanation: String) -> Unit,
    ) {
        composeRule.setContent {
            WhiteNoiseTheme { ExplanationHost { Surface { content(context.getString(body)) } } }
        }
        composeRule.onNode(hasText(TITLE)).performClick()
        assertEquals(1, clicks)
        clicks = 0
        composeRule.onNode(hasText(TITLE)).performTouchInput { longClick() }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("explanation.sheet").assertIsDisplayed()
        composeRule.onNodeWithTag("explanation.body").assertTextEquals(context.getString(body))
        composeRule.onNode(hasText(TITLE) and hasAnyAncestor(hasTestTag("explanation.sheet"))).assertIsDisplayed()
        assertEquals(0, clicks)
    }

    private companion object {
        const val TITLE = "Row title"
        const val TAG = "organize.control"
    }
}
