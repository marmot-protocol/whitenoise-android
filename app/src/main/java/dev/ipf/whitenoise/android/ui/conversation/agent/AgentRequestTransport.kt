package dev.ipf.whitenoise.android.ui.conversation.agent

import androidx.compose.runtime.staticCompositionLocalOf
import dev.ipf.marmotkit.SendAcceptDispositionFfi
import dev.ipf.whitenoise.android.core.agent.AgentRequest

/**
 * Where agent rows send the viewer's answers and controls.
 *
 * A conversation sends them to the group, which is the default when no transport is provided.
 * The developer agent demo provides one that answers on the device, so the rows can be tried
 * without a running agent.
 */
internal fun interface AgentRequestTransport {
    /** Sends [request]; [targetFirstEventId] is null for a control such as Stop. Null means it failed. */
    suspend fun send(
        agentPubkeyHex: String,
        targetFirstEventId: String?,
        request: AgentRequest,
    ): SendAcceptDispositionFfi?
}

internal val LocalAgentRequestTransport = staticCompositionLocalOf<AgentRequestTransport?> { null }
