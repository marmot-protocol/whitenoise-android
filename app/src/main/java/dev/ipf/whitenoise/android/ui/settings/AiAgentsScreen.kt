package dev.ipf.whitenoise.android.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.WhiteNoiseUrls
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseButton
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseModalBottomSheet
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseSheetHeader
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseSpacing
import dev.ipf.whitenoise.android.ui.theme.amoledOutlineBorder

/**
 * AI Agents: what an agent is, the connectors whose rows open a setup sheet, and manual setup with the
 * public key and the connector documentation. Only the active account's public npub reaches the clipboard,
 * and nothing here installs a connector, creates an account or contacts an agent.
 */
@Suppress("FunctionNaming")
@Composable
internal fun AiAgentsScreen(
    appState: WhiteNoiseAppState,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val active = appState.activeAccount
    val npub = active?.let { appState.npub(it.accountIdHex) }?.takeIf { it.startsWith(NPUB_PREFIX) }
    AiAgentsContent(
        npub = npub,
        onBack = onBack,
        onCopy = { label, value -> copyAgentValue(context, label, value) },
        onOpenDocs = { openAgentConnectorDocs(context) },
    )
}

/**
 * The list itself, over a resolved public key. A null [npub] states why setup is unavailable and leaves every
 * identity-dependent row disabled; [onOpenDocs] reports whether a browser accepted the hand-off.
 */
@Suppress("FunctionNaming", "LongMethod")
@Composable
internal fun AiAgentsContent(
    npub: String?,
    onBack: () -> Unit,
    onCopy: (String, String) -> Unit,
    onOpenDocs: () -> Boolean,
) {
    var selectedConnectorId by rememberSaveable(npub) { mutableStateOf<String?>(null) }
    var feedback by rememberSaveable(npub) { mutableStateOf<Int?>(null) }
    val publicKeyLabel = stringResource(R.string.public_key)
    val manualSetup = stringResource(R.string.ai_agents_manual_setup_body, stringResource(R.string.new_message))
    SettingsScaffold(title = stringResource(R.string.ai_agents), onBack = onBack) {
        SettingsList {
            item {
                SettingsCallout(
                    text = stringResource(R.string.ai_agents_intro),
                    title = stringResource(R.string.ai_agents_about_title),
                )
            }
            if (npub == null) {
                item {
                    SettingsCallout(
                        text = stringResource(R.string.ai_agents_public_key_unavailable),
                        modifier = Modifier.testTag("ai_agents.public_key_unavailable"),
                        isError = true,
                    )
                }
            }
            item { SettingsSection(stringResource(R.string.ai_agents_connectors_title)) }
            item {
                SettingsGroup(modifier = Modifier.testTag("ai_agents.connectors")) {
                    agentConnectors.forEach { connector ->
                        row(connector.id) { rowContext ->
                            SettingsLink(
                                context = rowContext,
                                title = stringResource(connector.nameRes),
                                onClick = { selectedConnectorId = connector.id },
                                modifier = Modifier.testTag("ai_agents.connector.${connector.id}"),
                                subtitle = stringResource(connector.subtitleRes),
                                enabled = npub != null,
                            )
                        }
                    }
                }
            }
            item { SettingsExplainer(stringResource(R.string.ai_agents_clipboard_disclosure)) }
            item { SettingsSection(stringResource(R.string.ai_agents_manual_setup_title)) }
            item {
                SettingsGroup(modifier = Modifier.testTag("ai_agents.manual")) {
                    row("public_key") { rowContext ->
                        SettingsAction(
                            context = rowContext,
                            title = stringResource(R.string.copy_public_key),
                            onClick = {
                                npub?.let {
                                    onCopy(publicKeyLabel, it)
                                    feedback = R.string.public_key_copied
                                }
                            },
                            modifier = Modifier.testTag("ai_agents.copy_public_key"),
                            subtitle = npub?.let(::abbreviatedNpub),
                            enabled = npub != null,
                            leading = {
                                Icon(painterResource(R.drawable.ic_content_copy), contentDescription = null)
                            },
                        )
                    }
                    row("docs") { rowContext ->
                        SettingsLink(
                            context = rowContext,
                            title = stringResource(R.string.ai_agents_connector_docs_title),
                            onClick = { feedback = documentationOutcome(onOpenDocs()) },
                            modifier = Modifier.testTag("ai_agents.docs"),
                            subtitle = stringResource(R.string.ai_agents_connector_docs_subtitle),
                        )
                    }
                }
            }
            item { SettingsExplainer(manualSetup) }
            feedback?.let { message ->
                item {
                    SettingsCallout(
                        text = stringResource(message),
                        modifier =
                            Modifier
                                .testTag("ai_agents.feedback")
                                .semantics { liveRegion = LiveRegionMode.Polite },
                        isError = message == R.string.ai_agents_docs_failed,
                    )
                }
            }
        }
    }
    val connector = agentConnectors.firstOrNull { it.id == selectedConnectorId }
    if (connector != null && npub != null) {
        AgentSetupSheet(
            connector = connector,
            npub = npub,
            onDismiss = { selectedConnectorId = null },
            onCopy = onCopy,
        )
    }
}

/**
 * Prompt review before any copy: the instruction, the exact prompt in selectable monospace, the next step,
 * and one copy action pinned below the scrolling body. Copying changes no app or agent state.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Suppress("FunctionNaming", "LongMethod")
@Composable
private fun AgentSetupSheet(
    connector: AgentConnector,
    npub: String,
    onDismiss: () -> Unit,
    onCopy: (String, String) -> Unit,
) {
    val name = stringResource(connector.nameRes)
    val title = stringResource(R.string.ai_agents_setup_title, name)
    val prompt = stringResource(connector.promptRes, npub)
    var copied by rememberSaveable(connector.id, npub) { mutableStateOf(false) }
    val manualSetup = stringResource(R.string.ai_agents_manual_setup_body, stringResource(R.string.new_message))
    WhiteNoiseModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth()) {
            WhiteNoiseSheetHeader(title = title, onClose = onDismiss)
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState())
                        .testTag("ai_agents.setup_content"),
                verticalArrangement = Arrangement.spacedBy(WhiteNoiseSpacing.Related),
            ) {
                SettingsCallout(text = stringResource(R.string.ai_agents_setup_instruction, name))
                Surface(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = WhiteNoiseSpacing.CompactScreenMargin)
                            .testTag("ai_agents.prompt.${connector.id}"),
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.surfaceContainerLowest,
                    border = amoledOutlineBorder(),
                ) {
                    SelectionContainer {
                        Text(
                            text = prompt,
                            modifier = Modifier.padding(WhiteNoiseSpacing.FormField),
                            style = MaterialTheme.typography.bodyMedium,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
                SettingsExplainer(manualSetup)
            }
            SettingsBottomAction(color = MaterialTheme.colorScheme.surfaceContainerLow, tonalElevation = 0.dp) {
                if (copied) {
                    Text(
                        text = stringResource(R.string.ai_agents_prompt_copied),
                        modifier =
                            Modifier
                                .testTag("ai_agents.copy_feedback")
                                .semantics { liveRegion = LiveRegionMode.Polite },
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                WhiteNoiseButton(
                    onClick = {
                        onCopy(title, prompt)
                        copied = true
                    },
                    modifier = Modifier.fillMaxWidth().testTag("ai_agents.copy.${connector.id}"),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_content_copy),
                        contentDescription = null,
                        modifier = Modifier.size(SetupCopyIconSize),
                    )
                    Text(
                        text = stringResource(R.string.ai_agents_copy_prompt),
                        modifier = Modifier.padding(start = WhiteNoiseSpacing.Related),
                    )
                }
            }
        }
    }
}

/** The documentation hand-off states its own outcome rather than leaving the tap unanswered. */
private fun documentationOutcome(opened: Boolean): Int {
    if (opened) return R.string.ai_agents_docs_opened
    return R.string.ai_agents_docs_failed
}

/** The visible key stays short; the clipboard payload and the setup prompt carry the complete public key. */
private fun abbreviatedNpub(npub: String): String {
    if (npub.length <= NPUB_PREFIX_CHARS + NPUB_SUFFIX_CHARS) return npub
    return "${npub.take(NPUB_PREFIX_CHARS)}…${npub.takeLast(NPUB_SUFFIX_CHARS)}"
}

/** Public values only: the npub and the setup prompt that embeds it, never a private key. */
private fun copyAgentValue(
    context: Context,
    label: String,
    value: String,
) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText(label, value))
}

/** Reports whether a browser accepted the documentation hand-off, so the screen can state the outcome. */
private fun openAgentConnectorDocs(context: Context): Boolean {
    val intent = Intent(Intent.ACTION_VIEW, WhiteNoiseUrls.AGENT_CONNECTOR_DOCS.toUri())
    return runCatching { context.startActivity(intent) }.isSuccess
}

private const val NPUB_PREFIX = "npub1"
private const val NPUB_PREFIX_CHARS = 12
private const val NPUB_SUFFIX_CHARS = 5
private val SetupCopyIconSize = 24.dp
