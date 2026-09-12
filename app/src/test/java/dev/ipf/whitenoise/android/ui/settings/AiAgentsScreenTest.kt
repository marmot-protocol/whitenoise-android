package dev.ipf.whitenoise.android.ui.settings

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasTestTag
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

/**
 * Contract of AI Agents: the prompt is reviewable before it is copied, it carries only the active public key,
 * and without that key every setup action is disabled and explained.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-w360dp-h1600dp-mdpi")
class AiAgentsScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val app = ApplicationProvider.getApplicationContext<Context>()
    private val copies = mutableListOf<Pair<String, String>>()
    private var documentationOpens = true
    private var documentationHandOffs = 0
    private var backCount = 0

    /** Codex remains the fourth shipped connector. */
    @Test
    fun agentConnectorsIncludeCodexAsFourthConnector() {
        assertEquals(4, agentConnectors.size)
        assertEquals("codex", agentConnectors[3].id)
    }

    /** Every connector prompt carries the account's npub and leaves no placeholder behind. */
    @Test
    fun everyConnectorPromptInterpolatesNpubWithoutPlaceholder() {
        agentConnectors.forEach { connector ->
            val prompt = app.getString(connector.promptRes, TEST_NPUB)
            assertTrue(prompt.contains(TEST_NPUB))
            assertFalse(prompt.contains("<USER_NPUB>"))
        }
    }

    /** The Codex prompt interpolates the key once and still points at the harness guide and its checks. */
    @Test
    fun codexPromptInterpolatesNpubOnceAndReferencesHarnessGuide() {
        val prompt = app.getString(R.string.agent_connector_codex_prompt, TEST_NPUB)

        assertEquals(1, prompt.windowed(TEST_NPUB.length).count { it == TEST_NPUB })
        assertFalse(prompt.contains("<USER_NPUB>"))
        assertTrue(prompt.contains(CODEX_HARNESS_README_URL))
        assertTrue(prompt.contains("install-codex-marmot.sh"))
        assertTrue(prompt.contains("wn-codex --version"))
        assertTrue(prompt.contains("wn-agent"))
        assertTrue(prompt.contains("send a test message"))
        assertTrue(prompt.contains("Do not report setup complete until wn-codex returns a reply through White Noise"))
        assertTrue(prompt.contains("device verification required"))
    }

    /** A connector row opens its setup sheet, which names the agent and shows the formatted prompt. */
    @Test
    fun connectorRowOpensASetupSheetWithTheFormattedPrompt() {
        render()

        openSetupSheet("codex")

        composeRule.onNodeWithText(app.getString(R.string.ai_agents_setup_title, "Codex")).assertExists()
        composeRule.onNodeWithText(app.getString(R.string.ai_agents_setup_instruction, "Codex")).assertExists()
        composeRule.onNodeWithTag("ai_agents.prompt.codex").assertExists()
        composeRule.onNodeWithText(app.getString(R.string.agent_connector_codex_prompt, TEST_NPUB)).assertExists()
        composeRule.onNodeWithTag("ai_agents.copy_feedback").assertDoesNotExist()
    }

    /** Copy hands the exact prompt to the caller under the sheet's own title and announces the result. */
    @Test
    fun copyPromptWritesTheExactPromptAndAnnouncesIt() {
        render()

        openSetupSheet("hermes")
        composeRule.onNodeWithTag("ai_agents.copy.hermes").performClick()

        val prompt = app.getString(R.string.agent_connector_hermes_prompt, TEST_NPUB)
        composeRule.runOnIdle {
            assertEquals(listOf(app.getString(R.string.ai_agents_setup_title, "Hermes") to prompt), copies)
        }
        composeRule.onNodeWithTag("ai_agents.copy_feedback").assertExists()
        composeRule.onNodeWithText(app.getString(R.string.ai_agents_prompt_copied)).assertExists()
    }

    /** Manual setup copies the complete key while the row itself only shows its abbreviation. */
    @Test
    fun copyPublicKeyWritesTheCompleteKeyWhileTheRowStaysShort() {
        render()

        composeRule.onNodeWithTag("settings.list").performScrollToNode(hasTestTag("ai_agents.copy_public_key"))
        composeRule.onNodeWithText(ABBREVIATED_NPUB).assertExists()
        composeRule.onNodeWithTag("ai_agents.copy_public_key").performClick()

        composeRule.runOnIdle {
            assertEquals(listOf(app.getString(R.string.public_key) to TEST_NPUB), copies)
        }
        composeRule.onNodeWithText(app.getString(R.string.public_key_copied)).assertExists()
    }

    /** The documentation row is a button that states whether the hand-off started or failed. */
    @Test
    fun documentationHandOffStatesBothOutcomes() {
        render()

        composeRule.onNodeWithTag("settings.list").performScrollToNode(hasTestTag("ai_agents.docs"))
        val docs = composeRule.onNodeWithTag("ai_agents.docs").fetchSemanticsNode()
        assertEquals(Role.Button, docs.config[SemanticsProperties.Role])
        assertEquals(
            listOf(
                app.getString(R.string.ai_agents_connector_docs_title),
                app.getString(R.string.ai_agents_connector_docs_subtitle),
            ),
            docs.config[SemanticsProperties.Text].map { it.text },
        )

        composeRule.onNodeWithTag("ai_agents.docs").performClick()
        composeRule.onNodeWithText(app.getString(R.string.ai_agents_docs_opened)).assertExists()

        documentationOpens = false
        composeRule.onNodeWithTag("settings.list").performScrollToNode(hasTestTag("ai_agents.docs"))
        composeRule.onNodeWithTag("ai_agents.docs").performClick()
        composeRule.onNodeWithText(app.getString(R.string.ai_agents_docs_failed)).assertExists()
        composeRule.runOnIdle { assertEquals(2, documentationHandOffs) }
    }

    /** Without a usable public key the screen explains recovery and no setup action can run. */
    @Test
    fun withoutAPublicKeySetupIsExplainedAndDisabled() {
        render(npub = null)

        composeRule.onNodeWithTag("ai_agents.public_key_unavailable").assertExists()
        composeRule.onNodeWithText(app.getString(R.string.ai_agents_public_key_unavailable)).assertExists()
        composeRule.onNodeWithTag("ai_agents.connector.hermes").assertIsNotEnabled().performClick()
        composeRule.onNodeWithTag("ai_agents.setup_content").assertDoesNotExist()

        composeRule.onNodeWithTag("settings.list").performScrollToNode(hasTestTag("ai_agents.copy_public_key"))
        composeRule.onNodeWithTag("ai_agents.copy_public_key").assertIsNotEnabled().performClick()
        composeRule.runOnIdle { assertTrue(copies.isEmpty()) }
        composeRule.onNodeWithTag("ai_agents.feedback").assertDoesNotExist()
    }

    /** Back returns to Settings through the caller, once per tap. */
    @Test
    fun backInvokesTheCallerOncePerTap() {
        render()

        composeRule.onNodeWithContentDescription(app.getString(R.string.back)).performClick()

        composeRule.runOnIdle { assertEquals(1, backCount) }
    }

    /** Connector subtitles stay fully readable at the narrowest supported width. */
    @Test
    @Config(qualifiers = "en-w360dp-h780dp-mdpi")
    fun connectorSubtitlesAreFullyVisibleAtCompactWidth() {
        render()

        agentConnectors.forEach { connector ->
            val subtitle = app.getString(connector.subtitleRes)
            composeRule
                .onNodeWithTag("settings.list")
                .performScrollToNode(hasTestTag("ai_agents.connector.${connector.id}"))
            val layouts = mutableListOf<TextLayoutResult>()
            composeRule
                .onNodeWithText(subtitle, useUnmergedTree = true)
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { action -> action(layouts) }

            val layout = layouts.single()
            assertFalse(
                "Connector subtitle is ellipsized: $subtitle",
                (0 until layout.lineCount).any(layout::isLineEllipsized),
            )
        }
    }

    /** The screen itself puts the reviewed prompt on the system clipboard for the active account. */
    @Test
    fun screenCopiesTheReviewedPromptToTheSystemClipboard() {
        clearClipboard()
        composeRule.setContent {
            WhiteNoiseTheme {
                AiAgentsScreen(appState = appStateWithNpub(TEST_NPUB), onBack = {})
            }
        }

        openSetupSheet("codex")
        composeRule.onNodeWithTag("ai_agents.copy.codex").performClick()
        composeRule.waitForIdle()

        assertEquals(app.getString(R.string.agent_connector_codex_prompt, TEST_NPUB), clipboardText())
    }

    private fun render(npub: String? = TEST_NPUB) {
        composeRule.setContent {
            WhiteNoiseTheme {
                AiAgentsContent(
                    npub = npub,
                    onBack = { backCount++ },
                    onCopy = { label, value -> copies += label to value },
                    onOpenDocs = {
                        documentationHandOffs++
                        documentationOpens
                    },
                )
            }
        }
    }

    private fun openSetupSheet(connectorId: String) {
        composeRule
            .onNodeWithTag("settings.list")
            .performScrollToNode(hasTestTag("ai_agents.connector.$connectorId"))
        composeRule.onNodeWithTag("ai_agents.connector.$connectorId").performClick()
        composeRule.waitForIdle()
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
        private val ABBREVIATED_NPUB = TEST_NPUB.take(12) + "…" + TEST_NPUB.takeLast(5)
    }
}
