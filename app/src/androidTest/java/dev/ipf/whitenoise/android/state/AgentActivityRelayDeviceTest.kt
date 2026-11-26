package dev.ipf.whitenoise.android.state

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.ipf.marmotkit.AppMessageRecordFfi
import dev.ipf.marmotkit.ConversationOpenModeFfi
import dev.ipf.marmotkit.Marmot
import dev.ipf.marmotkit.MarmotAndroid
import dev.ipf.marmotkit.MarmotKitException
import dev.ipf.marmotkit.MarmotOptions
import dev.ipf.marmotkit.RelayPolicyFfi
import dev.ipf.marmotkit.marmotDeploymentWhiteNoise
import dev.ipf.whitenoise.android.core.agent.AgentItemBody
import dev.ipf.whitenoise.android.core.agent.AgentItemReader
import dev.ipf.whitenoise.android.core.agent.AgentRequest
import dev.ipf.whitenoise.android.core.agent.AgentRequestEncoder
import dev.ipf.whitenoise.android.ui.conversation.agent.activityHistoryRows
import dev.ipf.whitenoise.android.ui.conversation.conversationTimelineRowVisible
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.io.path.createTempDirectory

/** Real Android MarmotKit, two isolated group members and a loopback relay. */
@RunWith(AndroidJUnit4::class)
class AgentActivityRelayDeviceTest {
    /** Exercises the published binding with an isolated MLS group and the app's Stop encoder. */
    @Test(timeout = 180_000L)
    fun runningTurnStaysInActivityAndItsStopReachesThePeer() =
        runBlocking {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            MarmotAndroid.initialize(context)
            val root = context.cacheDir.resolve("agent-activity").apply { mkdirs() }
            val fixture = createTempDirectory(root.toPath()).toFile()
            try {
                LoopbackNostrRelay().use { relay ->
                    val viewerRuntime = runtime(fixture, "viewer", relay)
                    try {
                        val agentRuntime = runtime(fixture, "agent", relay)
                        try {
                            withTimeout(120_000L) { runRoundTrip(viewerRuntime, agentRuntime, relay.url) }
                        } finally {
                            agentRuntime.shutdownAndClose()
                        }
                    } finally {
                        viewerRuntime.shutdownAndClose()
                    }
                }
            } finally {
                check(fixture.deleteRecursively())
            }
        }

    private suspend fun runRoundTrip(
        viewerRuntime: Marmot,
        agentRuntime: Marmot,
        relay: String,
    ) {
        val relays = listOf(relay)
        viewerRuntime.start()
        agentRuntime.start()
        val viewer = viewerRuntime.createIdentity(relays, relays)
        val agent = agentRuntime.createIdentity(relays, relays)
        val group =
            viewerRuntime.createGroup(viewer.label, "", listOf(agent.accountIdHex), null).requireCreatedGroupId()
        awaitInvite(agentRuntime, agent.label, group)
        val window =
            viewerRuntime.openTimelineWithFallback(viewer.label, group, 20u, mode = ConversationOpenModeFfi.LATEST)
        try {
            // Use the native window the activity sheet opens, even when the conversation is on a different page.
            window.snapshot()
            val sent = agentRuntime.sendCustomEvent(agent.label, group, AgentItemReader.ITEM_KIND, turnTags, "")
            val turn = awaitTurn(window, sent.messageIds.single())
            assertFalse(conversationTimelineRowVisible(turn))
            val item = requireNotNull(AgentItemReader.read(turn.record, turn.projected))
            assertTrue(item.body is AgentItemBody.Turn)
            assertTrue(item.state.isOpen)

            val request = AgentRequestEncoder.encode(item.agentPubkeyHex, item.rowId, AgentRequest.Interrupt("Stop"))
            viewerRuntime.sendCustomEvent(viewer.label, group, request.kind, request.tags, request.content)
            val received = awaitStop(agentRuntime, agent.label, group)
            assertEquals(viewer.accountIdHex, received.sender)
            assertEquals(request.tags, received.tags.map { it.values })
        } finally {
            try {
                window.cancel()
            } finally {
                window.close()
            }
        }
    }

    private suspend fun awaitInvite(
        native: Marmot,
        account: String,
        group: String,
    ) {
        while (true) {
            try {
                native.acceptGroupInvite(account, group)
                return
            } catch (_: MarmotKitException) {
                delay(150)
            }
        }
    }

    private suspend fun awaitTurn(
        window: ConversationTimelineSubscriptionHandle,
        id: String,
    ): TimelineMessage {
        while (true) {
            val page = checkNotNull(window.nextWindow())
            activityHistoryRows(page, emptySet()).firstOrNull { it.record.messageIdHex == id }?.let { return it }
        }
    }

    private suspend fun awaitStop(
        native: Marmot,
        account: String,
        group: String,
    ): AppMessageRecordFfi {
        while (true) {
            native.messages(account, group, 10u, listOf(1202uL)).firstOrNull()?.let { return it }
            delay(150)
        }
    }

    private suspend fun runtime(
        root: File,
        name: String,
        relay: LoopbackNostrRelay,
    ): Marmot =
        Marmot.newWithConfiguration(
            File(root, name).path,
            listOf(relay.url),
            MarmotOptions(
                relayPolicy = RelayPolicyFfi.ALLOW_LOOPBACK_RELAYS_AND_BLOBS,
                deployment = marmotDeploymentWhiteNoise(),
            ),
        )

    private val turnTags =
        listOf(
            listOf("v", "1"),
            listOf("state", "running"),
            listOf("title", "Working"),
            listOf("harness", "codex"),
            listOf("item_id", "turn"),
            listOf("seq", "1"),
            listOf("fields", """{"type":"turn","body":{}}"""),
        )
}
