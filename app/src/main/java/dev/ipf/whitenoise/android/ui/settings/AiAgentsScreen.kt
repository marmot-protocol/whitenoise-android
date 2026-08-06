package dev.ipf.whitenoise.android.ui.settings

import android.content.Context
import android.content.Intent
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.WhiteNoiseUrls
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.common.CopyableValueRow
import dev.ipf.whitenoise.android.ui.common.SectionCard
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseSnackbarHost
import kotlinx.coroutines.launch

internal data class AgentConnector(
    val id: String,
    @StringRes val nameRes: Int,
    @StringRes val subtitleRes: Int,
    @StringRes val promptRes: Int,
)

internal val agentConnectors =
    listOf(
        AgentConnector(
            id = "hermes",
            nameRes = R.string.agent_connector_hermes_name,
            subtitleRes = R.string.agent_connector_hermes_subtitle,
            promptRes = R.string.agent_connector_hermes_prompt,
        ),
        AgentConnector(
            id = "openclaw",
            nameRes = R.string.agent_connector_openclaw_name,
            subtitleRes = R.string.agent_connector_openclaw_subtitle,
            promptRes = R.string.agent_connector_openclaw_prompt,
        ),
        AgentConnector(
            id = "opencode",
            nameRes = R.string.agent_connector_opencode_name,
            subtitleRes = R.string.agent_connector_opencode_subtitle,
            promptRes = R.string.agent_connector_opencode_prompt,
        ),
    )

internal const val AI_AGENTS_CONTENT_TAG = "ai-agents-content"
internal const val AI_AGENTS_SCREEN_TAG = "ai-agents-screen"
internal const val AI_AGENTS_BACK_TAG = "ai-agents-back"
internal const val AI_AGENTS_COPY_NPUB_TAG = "ai-agents-copy-npub"
internal const val AI_AGENTS_CONNECTOR_DOCS_TAG = "ai-agents-connector-docs"

internal fun agentConnectorPreviewTag(id: String): String = "ai-agents-connector-$id-preview"

internal fun agentConnectorToggleTag(id: String): String = "ai-agents-connector-$id-toggle"

internal fun agentConnectorCopyTag(id: String): String = "ai-agents-connector-$id-copy"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AiAgentsScreen(
    appState: WhiteNoiseAppState,
    onBack: () -> Unit,
) {
    val active = appState.activeAccount
    val npub = active?.let { appState.npub(it.accountIdHex) }?.takeIf { it.startsWith("npub1") }
    val clipboard = LocalClipboardManager.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val copiedMessage = stringResource(R.string.ai_agents_prompt_copied)
    val context = LocalContext.current

    AiAgentsContent(
        npub = npub,
        snackbarHostState = snackbarHostState,
        onCopyPrompt = { prompt ->
            clipboard.setText(AnnotatedString(prompt))
            scope.launch { snackbarHostState.showSnackbar(copiedMessage) }
        },
        onOpenConnectorDocs = { openAgentConnectorDocs(context) },
        onBack = onBack,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AiAgentsContent(
    npub: String?,
    snackbarHostState: SnackbarHostState,
    onCopyPrompt: (String) -> Unit,
    onOpenConnectorDocs: () -> Unit,
    onBack: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    val actionsEnabled = npub != null
    var expandedConnectorIds by remember { mutableStateOf(emptySet<String>()) }
    val newMessageLabel = stringResource(R.string.new_message)
    val backLabel = stringResource(R.string.back)
    val connectorDocsTitle = stringResource(R.string.ai_agents_connector_docs_title)

    Scaffold(
        modifier = Modifier.testTag(AI_AGENTS_SCREEN_TAG),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.ai_agents)) },
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier =
                            Modifier
                                .testTag(AI_AGENTS_BACK_TAG)
                                .semantics { contentDescription = backLabel },
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = backLabel,
                        )
                    }
                },
            )
        },
        snackbarHost = { WhiteNoiseSnackbarHost(snackbarHostState) },
    ) { padding ->
        LazyColumn(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(16.dp)
                    .testTag(AI_AGENTS_CONTENT_TAG),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                SectionCard(title = stringResource(R.string.ai_agents_about_title)) {
                    Text(
                        text = stringResource(R.string.ai_agents_intro),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            item {
                SectionCard(title = stringResource(R.string.ai_agents_connectors_title)) {
                    Text(
                        text = stringResource(R.string.ai_agents_clipboard_disclosure),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    agentConnectors.forEach { connector ->
                        AgentConnectorRow(
                            connector = connector,
                            npub = npub,
                            actionsEnabled = actionsEnabled,
                            expanded = connector.id in expandedConnectorIds,
                            onExpandedChange = { expand ->
                                expandedConnectorIds =
                                    if (expand) {
                                        expandedConnectorIds + connector.id
                                    } else {
                                        expandedConnectorIds - connector.id
                                    }
                            },
                            onCopyPrompt = onCopyPrompt,
                        )
                    }
                }
            }
            item {
                SectionCard(title = stringResource(R.string.ai_agents_manual_setup_title)) {
                    Text(
                        text = stringResource(R.string.ai_agents_manual_setup_body, newMessageLabel),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (npub != null) {
                        CopyableValueRow(
                            label = "npub",
                            value = npub,
                            clipboard = clipboard,
                            modifier = Modifier.testTag(AI_AGENTS_COPY_NPUB_TAG),
                        )
                    } else {
                        ManualCopyNpubDisabledRow()
                    }
                    ListItem(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .testTag(AI_AGENTS_CONNECTOR_DOCS_TAG)
                                .clickable(onClick = onOpenConnectorDocs)
                                .semantics { contentDescription = connectorDocsTitle },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        headlineContent = { Text(stringResource(R.string.ai_agents_connector_docs_title)) },
                        supportingContent = {
                            Text(
                                stringResource(R.string.ai_agents_connector_docs_subtitle),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                        trailingContent = {
                            Icon(
                                Icons.AutoMirrored.Filled.OpenInNew,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun ManualCopyNpubDisabledRow() {
    val noActiveAccountMessage = stringResource(R.string.no_active_account_period)
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .testTag(AI_AGENTS_COPY_NPUB_TAG)
                .semantics(mergeDescendants = true) {
                    disabled()
                    contentDescription = noActiveAccountMessage
                },
    ) {
        Text("npub", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            noActiveAccountMessage,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontFamily = FontFamily.Monospace,
        )
    }
}

@Composable
private fun AgentConnectorRow(
    connector: AgentConnector,
    npub: String?,
    actionsEnabled: Boolean,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onCopyPrompt: (String) -> Unit,
) {
    val connectorName = stringResource(connector.nameRes)
    val prompt = npub?.let { stringResource(connector.promptRes, it) }
    val showCd = stringResource(R.string.agent_connector_show_prompt_cd, connectorName)
    val hideCd = stringResource(R.string.agent_connector_hide_prompt_cd, connectorName)
    val copyCd = stringResource(R.string.agent_connector_copy_prompt_cd, connectorName)

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ListItem(
            modifier = Modifier.fillMaxWidth(),
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            headlineContent = { Text(connectorName) },
            supportingContent = {
                Text(
                    stringResource(connector.subtitleRes),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            trailingContent = {
                Row {
                    IconButton(
                        onClick = { onExpandedChange(!expanded) },
                        enabled = actionsEnabled,
                        modifier =
                            Modifier
                                .testTag(agentConnectorToggleTag(connector.id))
                                .semantics { contentDescription = if (expanded) hideCd else showCd },
                    ) {
                        Icon(
                            imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = if (expanded) hideCd else showCd,
                        )
                    }
                    IconButton(
                        onClick = { prompt?.let(onCopyPrompt) },
                        enabled = actionsEnabled,
                        modifier =
                            Modifier
                                .testTag(agentConnectorCopyTag(connector.id))
                                .semantics { contentDescription = copyCd },
                    ) {
                        Icon(Icons.Default.ContentCopy, contentDescription = copyCd)
                    }
                }
            },
        )
        if (expanded && prompt != null) {
            SelectionContainer(
                modifier = Modifier.testTag(agentConnectorPreviewTag(connector.id)),
            ) {
                Text(
                    text = prompt,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

private fun openAgentConnectorDocs(context: Context) {
    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, WhiteNoiseUrls.AGENT_CONNECTOR_DOCS.toUri()))
    }
}
