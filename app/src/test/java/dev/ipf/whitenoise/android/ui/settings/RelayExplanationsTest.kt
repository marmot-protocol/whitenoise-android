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

/** Hold-to-explain on representative relay, key package and group relay controls. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
class RelayExplanationsTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private var clicks = 0

    @Test
    fun postingValueRowHoldShowsExplanation() {
        val body = R.string.explanation_relays_posting
        render(body) { explanation ->
            SettingsGroup {
                row("posting") { rowContext ->
                    SettingsValue(rowContext, TITLE, "Published", Modifier.testTag(TAG), explanation)
                }
            }
        }
        holdAndAssertSheet(body)
        assertEquals(0, clicks)
    }

    @Test
    fun refreshActionHoldExplainsAndQuickClickRuns() =
        assertHoldThenClick(R.string.explanation_relays_refresh) { explanation ->
            SettingsGroup {
                row("refresh") { rowContext ->
                    SettingsAction(rowContext, TITLE, { clicks++ }, Modifier.testTag(TAG), explanation = explanation)
                }
            }
        }

    @Test
    fun relayRowHoldExplainsAndQuickClickRuns() =
        assertHoldThenClick(R.string.explanation_relays_relay_row) { explanation ->
            SettingsGroup {
                row("relay") { rowContext ->
                    SettingsLink(rowContext, TITLE, { clicks++ }, Modifier.testTag(TAG), explanation = explanation)
                }
            }
        }

    @Test
    fun removeActionHoldExplainsAndQuickClickRuns() =
        assertHoldThenClick(R.string.explanation_relays_remove) { explanation ->
            SettingsGroup {
                row("remove") { rowContext ->
                    SettingsAction(rowContext, TITLE, { clicks++ }, Modifier.testTag(TAG), explanation = explanation)
                }
            }
        }

    @Test
    fun textButtonHoldExplainsAndQuickClickRuns() =
        assertHoldThenClick(R.string.explanation_relays_setup_undo) { explanation ->
            TextButton(
                onClick = { clicks++ },
                modifier = Modifier.testTag(TAG).explainOnLongPress(Explanation(TITLE, explanation)),
            ) {
                Text(TITLE)
            }
        }

    private fun assertHoldThenClick(
        body: Int,
        content: @Composable (explanation: String) -> Unit,
    ) {
        render(body, content)
        composeRule.onNode(hasText(TITLE)).performClick()
        assertEquals(1, clicks)
        clicks = 0
        holdAndAssertSheet(body)
        assertEquals(0, clicks)
    }

    private fun holdAndAssertSheet(body: Int) {
        composeRule.onNode(hasText(TITLE)).performTouchInput { longClick() }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("explanation.sheet").assertIsDisplayed()
        composeRule.onNodeWithTag("explanation.body").assertTextEquals(context.getString(body))
        composeRule.onNode(hasText(TITLE) and hasAnyAncestor(hasTestTag("explanation.sheet"))).assertIsDisplayed()
    }

    private fun render(
        body: Int,
        content: @Composable (explanation: String) -> Unit,
    ) {
        composeRule.setContent {
            WhiteNoiseTheme { ExplanationHost { Surface { content(context.getString(body)) } } }
        }
    }

    private companion object {
        const val TITLE = "Row title"
        const val TAG = "relay.row"
    }
}
