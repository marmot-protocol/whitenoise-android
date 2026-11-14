package dev.ipf.whitenoise.android.ui.settings

import android.content.Context
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
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
import dev.ipf.whitenoise.android.ui.common.ExplanationHost
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Hold-to-explain on the shared settings rows and the settings-specific switch rows. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
class SettingsExplanationsTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private var clicks = 0

    @Test
    fun actionRowHoldExplainsAndQuickClickRuns() =
        assertHoldThenClick(R.string.explanation_quarantine_retry) { explanation ->
            SettingsGroup {
                row("retry") { rowContext ->
                    SettingsAction(
                        rowContext,
                        TITLE,
                        { clicks++ },
                        Modifier.testTag(TAG),
                        explanation = explanation,
                        leading = null,
                    )
                }
            }
        }

    @Test
    fun switchRowHoldExplainsAndQuickClickRuns() =
        assertHoldThenClick(R.string.explanation_gestures_pull_unread) { explanation ->
            SettingsGroup {
                row("switch") { rowContext ->
                    SettingsSwitch(
                        rowContext,
                        TITLE,
                        false,
                        { clicks++ },
                        Modifier.testTag(TAG),
                        explanation = explanation,
                        leading = null,
                    )
                }
            }
        }

    @Test
    fun choiceRowHoldExplainsAndQuickClickRuns() =
        assertHoldThenClick(R.string.explanation_notifications_push) { explanation ->
            SettingsGroup {
                row("choice") { rowContext ->
                    SettingsChoice(
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
    fun consentSwitchRowHoldExplainsAndQuickClickRuns() =
        assertHoldThenClick(R.string.explanation_diagnostics_usage) { explanation ->
            ConsentSwitchRow(
                title = TITLE,
                subtitle = "Subtitle",
                checked = false,
                enabled = true,
                saving = false,
                onCheckedChange = { clicks++ },
                explanation = explanation,
            )
        }

    @Test
    fun downloadSwitchHoldExplainsAndQuickClickRuns() =
        assertHoldThenClick(R.string.explanation_data_usage_download_switch) { explanation ->
            DownloadSwitch(title = TITLE, checked = false, tag = TAG, explanation = explanation) { clicks++ }
        }

    @Test
    fun explainAccessibilityActionShowsTheSheetWithoutRunningTheRow() {
        val body = R.string.explanation_quarantine_retry
        render(body) { explanation ->
            SettingsGroup {
                row("retry") { rowContext ->
                    SettingsAction(
                        rowContext,
                        TITLE,
                        { clicks++ },
                        Modifier.testTag(TAG),
                        explanation = explanation,
                        leading = null,
                    )
                }
            }
        }
        val explain =
            composeRule
                .onNodeWithTag(TAG)
                .fetchSemanticsNode()
                .config[SemanticsActions.CustomActions]
                .single { it.label == context.getString(R.string.explanation_action_explain) }
        composeRule.runOnIdle { assertTrue(explain.action()) }
        assertSheet(body)
        assertEquals(0, clicks)
    }

    /** A quick click runs the row once; holding the same row opens the sheet and leaves the callback untouched. */
    private fun assertHoldThenClick(
        body: Int,
        content: @Composable (explanation: String) -> Unit,
    ) {
        render(body, content)
        composeRule.onNode(hasText(TITLE)).performClick()
        assertEquals(1, clicks)
        clicks = 0
        composeRule.onNode(hasText(TITLE)).performTouchInput { longClick() }
        composeRule.waitForIdle()
        assertSheet(body)
        assertEquals(0, clicks)
    }

    private fun render(
        body: Int,
        content: @Composable (explanation: String) -> Unit,
    ) {
        composeRule.setContent {
            WhiteNoiseTheme { ExplanationHost { Surface { content(context.getString(body)) } } }
        }
    }

    private fun assertSheet(body: Int) {
        composeRule.onNodeWithTag("explanation.sheet").assertIsDisplayed()
        composeRule.onNodeWithTag("explanation.body").assertTextEquals(context.getString(body))
        composeRule.onNode(hasText(TITLE) and hasAnyAncestor(hasTestTag("explanation.sheet"))).assertIsDisplayed()
    }

    private companion object {
        const val TITLE = "Row title"
        const val TAG = "settings.row"
    }
}
