package dev.ipf.whitenoise.android.ui.settings

import androidx.annotation.StringRes
import dev.ipf.whitenoise.android.R

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
        AgentConnector(
            id = "codex",
            nameRes = R.string.agent_connector_codex_name,
            subtitleRes = R.string.agent_connector_codex_subtitle,
            promptRes = R.string.agent_connector_codex_prompt,
        ),
    )
