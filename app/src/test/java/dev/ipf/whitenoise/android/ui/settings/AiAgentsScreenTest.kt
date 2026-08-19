package dev.ipf.whitenoise.android.ui.settings

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
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
    fun agentConnectorsIncludeCodexAsFourthConnector() {
        assertEquals(4, agentConnectors.size)
        assertEquals("codex", agentConnectors[3].id)
    }

    @Test
    fun codexExpandShowsFormattedPromptWithoutPlaceholder() {
        val npub = TEST_NPUB
        val expectedCodexPrompt = app.getString(R.string.agent_connector_codex_prompt, npub)

        renderContent(npub)

        composeRule
            .onNodeWithTag(AI_AGENTS_CONTENT_TAG)
            .performScrollToNode(hasTestTag(agentConnectorToggleTag("codex")))
        composeRule
            .onNodeWithTag(agentConnectorToggleTag("codex"))
            .performClick()

        composeRule
            .onNodeWithTag(AI_AGENTS_CONTENT_TAG)
            .performScrollToNode(hasTestTag(agentConnectorPreviewTag("codex")))
        composeRule.onNodeWithTag(agentConnectorPreviewTag("codex")).assertIsDisplayed()
        composeRule.onNodeWithText(expectedCodexPrompt).assertIsDisplayed()
        assertFalse(expectedCodexPrompt.contains("<USER_NPUB>"))
    }

    @Test
    fun codexCopyPassesFormattedPromptToCallback() {
        val npub = TEST_NPUB
        val expectedCodexPrompt = app.getString(R.string.agent_connector_codex_prompt, npub)
        var copiedPrompt: String? = null

        renderContent(npub, onCopyPrompt = { copiedPrompt = it })

        composeRule
            .onNodeWithTag(AI_AGENTS_CONTENT_TAG)
            .performScrollToNode(hasTestTag(agentConnectorCopyTag("codex")))
        composeRule
            .onNodeWithTag(agentConnectorCopyTag("codex"))
            .performClick()

        composeRule.runOnIdle {
            assertEquals(expectedCodexPrompt, copiedPrompt)
        }
    }

    @Test
    fun codexPromptInterpolatesNpubOnceAndReferencesHarnessGuide() {
        val prompt = app.getString(R.string.agent_connector_codex_prompt, TEST_NPUB)

        assertEquals(1, prompt.windowed(TEST_NPUB.length).count { it == TEST_NPUB })
        assertFalse(prompt.contains("<USER_NPUB>"))
        assertTrue(prompt.contains(CODEX_HARNESS_README_URL))
        assertTrue(prompt.contains("install-codex-marmot.sh"))
        assertTrue(prompt.contains("wn-codex --version"))
        assertTrue(prompt.contains("wn-agent"))
    }

    @Test
    fun expandShowsFormattedPromptWithoutPlaceholder() {
        val npub = TEST_NPUB
        val expectedHermesPrompt = app.getString(R.string.agent_connector_hermes_prompt, npub)

        renderContent(npub)

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
    @Config(qualifiers = "en-w360dp-h780dp-mdpi")
    fun connectorSubtitlesAreFullyVisibleAtCompactWidth() {
        renderContent()

        agentConnectors.forEach { connector ->
            val subtitle = app.getString(connector.subtitleRes)
            composeRule
                .onNodeWithTag(AI_AGENTS_CONTENT_TAG)
                .performScrollToNode(hasText(subtitle))
            val layoutResults = mutableListOf<TextLayoutResult>()
            composeRule
                .onNodeWithText(subtitle, useUnmergedTree = true)
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { action ->
                    action(layoutResults)
                }

            val layout = layoutResults.single()
            assertFalse(
                "Connector subtitle is ellipsized: $subtitle",
                (0 until layout.lineCount).any(layout::isLineEllipsized),
            )
        }
    }

    @Test
    fun connectorDocsExposeTextAndButtonActionWithoutOverridingDescription() {
        val docsTitle = app.getString(R.string.ai_agents_connector_docs_title)
        val docsSubtitle = app.getString(R.string.ai_agents_connector_docs_subtitle)
        renderContent()

        composeRule
            .onNodeWithTag(AI_AGENTS_CONTENT_TAG)
            .performScrollToNode(hasTestTag(AI_AGENTS_CONNECTOR_DOCS_TAG))
        val docsNode = composeRule.onNodeWithTag(AI_AGENTS_CONNECTOR_DOCS_TAG).fetchSemanticsNode()

        assertFalse(docsNode.config.contains(SemanticsProperties.ContentDescription))
        assertEquals(Role.Button, docsNode.config[SemanticsProperties.Role])
        assertEquals(docsTitle, docsNode.config[SemanticsActions.OnClick].label)
        assertEquals(
            listOf(docsTitle, docsSubtitle),
            docsNode.config[SemanticsProperties.Text].map { it.text },
        )
    }

    @Test
    fun iconButtonsExposeEachContentDescriptionOnce() {
        renderContent()

        val expectedDescriptions =
            mapOf(
                AI_AGENTS_BACK_TAG to app.getString(R.string.back),
                agentConnectorToggleTag("hermes") to
                    app.getString(R.string.agent_connector_show_prompt_cd, "Hermes"),
                agentConnectorCopyTag("hermes") to
                    app.getString(R.string.agent_connector_copy_prompt_cd, "Hermes"),
            )

        expectedDescriptions.forEach { (tag, description) ->
            val node = composeRule.onNodeWithTag(tag).fetchSemanticsNode()
            assertEquals(listOf(description), node.config[SemanticsProperties.ContentDescription])
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

    private fun renderContent(
        npub: String? = TEST_NPUB,
        onCopyPrompt: (String) -> Unit = {},
    ) {
        composeRule.setContent {
            WhiteNoiseTheme {
                Surface {
                    AiAgentsContent(
                        npub = npub,
                        snackbarHostState = SnackbarHostState(),
                        onCopyPrompt = onCopyPrompt,
                        onOpenConnectorDocs = {},
                        onBack = {},
                    )
                }
            }
        }
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

    private fun clipboardText(): String? =
        (app.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
            .primaryClip
            ?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)
            ?.coerceToText(app)
            ?.toString()

    private fun clearClipboard() {
        val clipboard = app.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.clearPrimaryClip()
    }

    companion object {
        private const val ACCOUNT_REF = "test-account"
        private const val CODEX_HARNESS_README_URL =
            "https://github.com/marmot-protocol/mdk/blob/master/integrations/codex/marmot/README.md"
        private val ACCOUNT_HEX = "ab".repeat(32)
        private val TEST_NPUB = "npub1" + "a".repeat(58)
    }
}
