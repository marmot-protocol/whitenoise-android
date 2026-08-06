package dev.ipf.whitenoise.android.ui.settings

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.BoundedNpubCache
import dev.ipf.whitenoise.android.state.DraftStore
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en")
class AiAgentsScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val app = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun expandShowsFormattedPromptWithoutPlaceholder() {
        val npub = TEST_NPUB
        val expectedHermesPrompt = app.getString(R.string.agent_connector_hermes_prompt, npub)

        composeRule.setContent {
            WhiteNoiseTheme {
                Surface {
                    AiAgentsContent(
                        npub = npub,
                        snackbarHostState = SnackbarHostState(),
                        onCopyPrompt = {},
                        onOpenConnectorDocs = {},
                        onBack = {},
                    )
                }
            }
        }

        composeRule
            .onNodeWithTag(agentConnectorToggleTag("hermes"))
            .performClick()

        composeRule
            .onNodeWithTag(AI_AGENTS_CONTENT_TAG)
            .performScrollToNode(hasTestTag(agentConnectorPreviewTag("hermes")))
        composeRule.onNodeWithTag(agentConnectorPreviewTag("hermes")).assertIsDisplayed()
        composeRule.onNodeWithText(expectedHermesPrompt).assertIsDisplayed()
        assertFalse(expectedHermesPrompt.contains("<USER_NPUB>"))
    }

    @Test
    fun everyConnectorPromptInterpolatesNpubWithoutPlaceholder() {
        agentConnectors.forEach { connector ->
            val prompt = app.getString(connector.promptRes, TEST_NPUB)
            assertTrue(prompt.contains(TEST_NPUB))
            assertFalse(prompt.contains("<USER_NPUB>"))
        }
    }

    @Test
    fun copyWritesPromptToClipboardAndShowsSnackbar() {
        clearClipboard()
        val npub = TEST_NPUB
        val expectedHermesPrompt = app.getString(R.string.agent_connector_hermes_prompt, npub)
        val copiedMessage = app.getString(R.string.ai_agents_prompt_copied)

        composeRule.setContent {
            WhiteNoiseTheme {
                Surface {
                    AiAgentsScreen(appState = appStateWithNpub(npub), onBack = {})
                }
            }
        }

        composeRule
            .onNodeWithContentDescription(app.getString(R.string.agent_connector_copy_prompt_cd, "Hermes"))
            .performClick()

        composeRule.waitForIdle()
        val clip = clipboardText()
        assertEquals(expectedHermesPrompt, clip)
        composeRule.onNodeWithText(copiedMessage).assertIsDisplayed()
    }

    @Test
    fun withoutActiveAccountCopyAndExpandAreDisabled() {
        clearClipboard()
        val appState =
            WhiteNoiseAppState(
                context = app,
                draftStore = DraftStore.forContext(app),
                accountIdHexResolver = { null },
                accounts = emptyList(),
                activeAccountRef = "no-such-account",
            )

        composeRule.setContent {
            WhiteNoiseTheme {
                Surface {
                    AiAgentsScreen(appState = appState, onBack = {})
                }
            }
        }

        composeRule
            .onNodeWithContentDescription(app.getString(R.string.agent_connector_show_prompt_cd, "Hermes"))
            .assertIsNotEnabled()
        composeRule
            .onNodeWithContentDescription(app.getString(R.string.agent_connector_copy_prompt_cd, "Hermes"))
            .assertIsNotEnabled()

        composeRule
            .onNodeWithTag(AI_AGENTS_CONTENT_TAG)
            .performScrollToNode(hasTestTag(AI_AGENTS_COPY_NPUB_TAG))
        composeRule
            .onNodeWithTag(AI_AGENTS_COPY_NPUB_TAG)
            .assertIsNotEnabled()

        composeRule
            .onNodeWithContentDescription(app.getString(R.string.agent_connector_copy_prompt_cd, "Hermes"))
            .performClick()
        composeRule.waitForIdle()
        assertTrue(clipboardText().isNullOrEmpty())
    }

    @Test
    fun backButtonInvokesCallback() {
        var backCount = 0
        composeRule.setContent {
            WhiteNoiseTheme {
                Surface {
                    AiAgentsScreen(appState = appStateWithNpub(TEST_NPUB), onBack = { backCount++ })
                }
            }
        }

        composeRule.onNodeWithTag(AI_AGENTS_BACK_TAG).performClick()
        composeRule.runOnIdle { assertEquals(1, backCount) }
    }

    private fun appStateWithNpub(npub: String): WhiteNoiseAppState {
        val appState =
            WhiteNoiseAppState(
                context = app,
                draftStore = DraftStore.forContext(app),
                accountIdHexResolver = { null },
                accounts = listOf(activeAccount()),
                activeAccountRef = ACCOUNT_REF,
            )
        seedNpub(appState, ACCOUNT_HEX, npub)
        return appState
    }

    private fun activeAccount() =
        AccountSummaryFfi(
            label = ACCOUNT_REF,
            accountIdHex = ACCOUNT_HEX,
            localSigning = true,
            externalSigning = false,
            signedOut = false,
            running = true,
        )

    private fun seedNpub(
        appState: WhiteNoiseAppState,
        accountIdHex: String,
        npub: String,
    ) {
        val field = WhiteNoiseAppState::class.java.getDeclaredField("npubs")
        field.isAccessible = true
        val cache = field.get(appState) as BoundedNpubCache
        cache.put(accountIdHex, npub)
    }

    private fun clipboardText(): String? {
        val clipboard = app.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = clipboard.primaryClip ?: return null
        if (clip.itemCount == 0) return null
        return clip.getItemAt(0).coerceToText(app).toString()
    }

    private fun clearClipboard() {
        val clipboard = app.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.clearPrimaryClip()
    }

    companion object {
        private const val ACCOUNT_REF = "test-account"
        private val ACCOUNT_HEX = "ab".repeat(32)
        private val TEST_NPUB = "npub1" + "a".repeat(58)
    }
}
