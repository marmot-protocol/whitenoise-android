package dev.ipf.whitenoise.android.state

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.marmotkit.ChatListMessageDeliveryStateFfi
import dev.ipf.marmotkit.Marmot
import dev.ipf.marmotkit.MarmotAndroid
import dev.ipf.marmotkit.MarmotOptions
import dev.ipf.marmotkit.RelayPolicyFfi
import dev.ipf.marmotkit.TimelineMessageQueryFfi
import dev.ipf.marmotkit.TimelineMessageRecordFfi
import dev.ipf.marmotkit.marmotDeploymentWhiteNoise
import dev.ipf.whitenoise.android.MdkAdoptionDeviceAcceptance
import dev.ipf.whitenoise.android.core.TimelineProjector
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Real MLS, relay, durable native storage and Android-controller qualification on a disposable emulator. */
@MdkAdoptionDeviceAcceptance
@RunWith(AndroidJUnit4::class)
class RecipientConfirmationNativeDeviceTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val root = File(context.cacheDir, "recipient-confirmation-${UUID.randomUUID()}")
    private val runtimes = mutableListOf<Marmot>()
    private val controllers = mutableListOf<ConversationController>()

    /** Closes handles before deleting only the generated fixture's private roots. */
    @After
    fun tearDown() =
        runBlocking {
            controllers.forEach(ConversationController::onCleared)
            runtimes.forEach { runCatching { it.shutdownAndClose() } }
            check(!root.exists() || root.deleteRecursively())
        }

    /** A genuine peer reaction settles an unacknowledged send live and after the sender's native store reopens. */
    @Test(timeout = 240_000L)
    @Suppress("LongMethod") // Keep the dependent failure, real peer receipt and reopened-store assertions together.
    fun failedSendSettlesFromPeerReactionWithoutPublicationEchoAndSurvivesReopen() =
        runBlocking {
            MarmotAndroid.initialize(context)
            check(root.mkdirs())
            LoopbackNostrRelay().use { relay ->
                withTimeout(210_000L) {
                    val sender = participant("sender", relay)
                    var peer = participant("peer", relay)
                    val group =
                        sender.native.createGroup(
                            sender.account.label,
                            "Receipt fixture",
                            listOf(peer.account.accountIdHex),
                            null,
                        )
                    awaitInvite(peer, group)
                    sender.native.sendText(sender.account.label, group, "ready")
                    awaitRecord(peer, group, "ready") { true }

                    val failed = failPublication(sender, relay, group)
                    close(sender)
                    // Only the generated peer sees the rejected envelope. The sender will later get its reaction,
                    // never an own-message relay echo that could satisfy the source-event delivery condition.
                    val envelope = relay.publicationAttempts(GROUP_KIND).last()
                    relay.rejectedPublicationKinds = emptySet()
                    relay.seedEvents(listOf(envelope))
                    close(peer)
                    peer = reopen(peer, relay)
                    val received = awaitRecord(peer, group, BODY) { true }
                    assertEquals(failed.messageIdHex, received.messageIdHex)
                    relay.hiddenEventIds = setOf(envelope.getString("id"))

                    val onlineSender = reopen(sender, relay)
                    val liveController = controller(onlineSender, group)
                    awaitTimeline(liveController, failed.messageIdHex, MessageStatus.Pending)
                    assertEquals(
                        "Delivery not confirmed",
                        warning(liveController, failed.messageIdHex),
                    )
                    peer.native.reactToMessage(peer.account.label, group, received.messageIdHex, "+")
                    val settled = awaitRecord(onlineSender, group, BODY) { it.recipientConfirmed }
                    assertNativeReceipt(settled, peer.account.accountIdHex)
                    awaitTimeline(liveController, failed.messageIdHex, MessageStatus.Sent)
                    assertNull(warning(liveController, failed.messageIdHex))
                    assertEquals("+", liveController.reactions[failed.messageIdHex]?.single()?.emoji)
                    assertChatListDelivered(onlineSender, group)
                    liveController.onCleared()
                    controllers -= liveController
                    close(onlineSender)

                    val reopened = reopen(onlineSender, relay)
                    assertNativeReceipt(
                        awaitRecord(reopened, group, BODY) { it.recipientConfirmed },
                        peer.account.accountIdHex,
                    )
                    val restored = controller(reopened, group)
                    awaitTimeline(restored, failed.messageIdHex, MessageStatus.Sent)
                    assertNull(warning(restored, failed.messageIdHex))
                    assertChatListDelivered(reopened, group)
                }
            }
        }

    /** A relay rejection must produce the real native failure before any recipient evidence is introduced. */
    private suspend fun failPublication(
        sender: Participant,
        relay: LoopbackNostrRelay,
        group: String,
    ): TimelineMessageRecordFfi {
        val before = relay.publicationAttempts(GROUP_KIND).size
        relay.rejectedPublicationKinds = setOf(GROUP_KIND)
        runCatching { sender.native.sendText(sender.account.label, group, BODY) }
        val record = awaitRecord(sender, group, BODY) { it.invalidationStatus == "local_publish_failed" }
        val attempts = relay.publicationAttempts(GROUP_KIND).drop(before).distinctBy { it.getString("id") }
        assertEquals("one exact generated ciphertext must have been rejected", 1, attempts.size)
        assertNull(record.sourceMessageIdHex)
        assertFalse(record.recipientConfirmed)
        return record
    }

    /** Reads authoritative rows until the specific fixture message reaches the required native state. */
    private suspend fun awaitRecord(
        participant: Participant,
        group: String,
        body: String,
        matches: (TimelineMessageRecordFfi) -> Boolean,
    ): TimelineMessageRecordFfi =
        withTimeout(60_000L) {
            var found: TimelineMessageRecordFfi? = null
            while (found == null) {
                found =
                    participant.native
                        .timelineMessages(
                            participant.account.label,
                            TimelineMessageQueryFfi(group, null, null, null, null, null, 100u),
                        ).messages.singleOrNull { it.plaintext == body && matches(it) }
                if (found == null) delay(100L)
            }
            requireNotNull(found)
        }

    /** Native confirmation is peer-owned, remains durable and never fabricates relay publication. */
    private fun assertNativeReceipt(
        record: TimelineMessageRecordFfi,
        peer: String,
    ) {
        assertTrue(record.recipientConfirmed)
        assertNull(record.sourceMessageIdHex)
        assertNull(record.invalidationStatus)
        assertTrue(record.reactions.userReactions.any { it.sender == peer && it.emoji == "+" })
    }

    /** A real native subscription feeds the same controller used by the app's conversation screen. */
    private suspend fun controller(
        participant: Participant,
        group: String,
    ): ConversationController {
        val state =
            WhiteNoiseAppState(
                context = context,
                draftStore = DraftStore(ConversationTimelineTestDraftPersistence()),
                accounts = listOf(participant.account),
                activeAccountRef = participant.account.label,
                accountIdHexResolver = { participant.account.accountIdHex },
                initialMarmotRuntime = AppMarmotRuntime(participant.root.path, participant.native),
            )
        return ConversationController(
            appState = state,
            initialGroup = participant.native.groupDetails(participant.account.label, group).group,
            startOnConstruction = true,
        ).also { controllers += it }
    }

    /** Waits for the production subscription, checking the exact message rather than timeline size alone. */
    private suspend fun awaitTimeline(
        controller: ConversationController,
        message: String,
        status: MessageStatus,
    ) {
        withTimeout(30_000L) {
            while (controller.timeline.none { it.record.messageIdHex == message && it.status == status }) delay(100L)
        }
    }

    /** Uses the actual warning formatter on the exact authoritative row received by the controller. */
    private fun warning(
        controller: ConversationController,
        message: String,
    ) = TimelineProjector.invalidationWarning(controller.timelineRecords.getValue(message))

    /** The native latest-message projection must agree with the conversation's delivery status. */
    private fun assertChatListDelivered(
        participant: Participant,
        group: String,
    ) {
        val row = participant.native.chatList(participant.account.label, true).single { it.groupIdHex == group }
        assertEquals(ChatListMessageDeliveryStateFfi.DELIVERED, row.lastMessage?.deliveryState)
    }

    /** Accepts only this generated group's Welcome, with a finite bound. */
    private suspend fun awaitInvite(
        peer: Participant,
        group: String,
    ) {
        withTimeout(60_000L) {
            while (runCatching { peer.native.acceptGroupInvite(peer.account.label, group) }.isFailure) delay(100L)
        }
    }

    /** Creates a generated identity on a loopback-only runtime. */
    private suspend fun participant(
        name: String,
        relay: LoopbackNostrRelay,
    ): Participant {
        val directory = File(root, name)
        val native = open(directory, relay)
        val relays = listOf(relay.url)
        return Participant(directory, native, native.createIdentity(relays, relays))
    }

    /** Opens the reviewed native artifact against the fixture's own root and relay. */
    private suspend fun open(
        directory: File,
        relay: LoopbackNostrRelay,
    ): Marmot =
        Marmot.newWithConfiguration(
            directory.path,
            listOf(relay.url),
            MarmotOptions(
                relayPolicy = RelayPolicyFfi.ALLOW_LOOPBACK_RELAYS_AND_BLOBS,
                deployment = marmotDeploymentWhiteNoise(),
            ),
        ).also {
            runtimes += it
            it.start()
        }

    /** Stops only a generated runtime so no own-publication echo can settle the controlled failure. */
    private suspend fun close(participant: Participant) {
        participant.native.shutdownAndClose()
        runtimes -= participant.native
    }

    /** Keeps the same generated identity and store while replacing the native runtime instance. */
    private suspend fun reopen(
        participant: Participant,
        relay: LoopbackNostrRelay,
    ) = participant.copy(native = open(participant.root, relay))

    private data class Participant(val root: File, val native: Marmot, val account: AccountSummaryFfi)

    private companion object {
        const val GROUP_KIND = 445
        const val BODY = "A real peer receipt settles this failed send"
    }
}
