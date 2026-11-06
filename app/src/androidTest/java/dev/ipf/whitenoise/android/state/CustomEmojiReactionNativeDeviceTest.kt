package dev.ipf.whitenoise.android.state

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.marmotkit.AttachmentAcquisitionModeFfi
import dev.ipf.marmotkit.AttachmentEntryFfi
import dev.ipf.marmotkit.Marmot
import dev.ipf.marmotkit.MarmotAndroid
import dev.ipf.marmotkit.MarmotInterface
import dev.ipf.marmotkit.MarmotOptions
import dev.ipf.marmotkit.MediaUploadRequestFfi
import dev.ipf.marmotkit.MediaUploadResultFfi
import dev.ipf.marmotkit.RelayPolicyFfi
import dev.ipf.marmotkit.marmotDeploymentWhiteNoise
import dev.ipf.whitenoise.android.MdkAdoptionDeviceAcceptance
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.media.PlatformBackgroundAttachmentProbe
import dev.ipf.whitenoise.android.notifications.NotificationReactionSendOutcome
import dev.ipf.whitenoise.android.ui.CustomEmojiStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Actual Android send entry points, independent MLS peers and bounded encrypted artwork acquisition. */
@MdkAdoptionDeviceAcceptance
@RunWith(AndroidJUnit4::class)
class CustomEmojiReactionNativeDeviceTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext

    /** Both send entry points carry real images, surviving an epoch advance, receiver restart and exact removal. */
    @Test(timeout = 240_000L)
    @Suppress("LongMethod") // One generated peer pair owns the dependent epoch, restart and removal checks.
    fun customArtworkReachesAnIndependentPeerAndSurvivesRestart() =
        runBlocking {
            check(PlatformBackgroundAttachmentProbe.shell("getprop ro.kernel.qemu").trim() == "1")
            MarmotAndroid.initialize(context)
            val root = File(context.cacheDir, "emoji-peer-${UUID.randomUUID()}").apply { check(mkdirs()) }
            val code = ":fixture_${UUID.randomUUID().toString().replace("-", "")}:"
            val file = File(context.filesDir, "${CustomEmojiStore.DIRECTORY}/${code.trim(':')}.png")
            file.parentFile?.mkdirs()
            val bytes = context.resources.openRawResource(R.drawable.builtin_emoji_marmot).use { it.readBytes() }
            file.writeBytes(bytes)
            val natives = mutableListOf<Marmot>()
            val states = mutableListOf<WhiteNoiseAppState>()
            var controller: ConversationController? = null
            try {
                LoopbackNostrRelay().use { relay ->
                    EmojiBlossomFixture().use { blobs ->
                        withTimeout(210_000L) {
                            val sender = participant(File(root, "sender"), relay, natives)
                            var peer = participant(File(root, "peer"), relay, natives)
                            val group = sender.native.createGroup(sender.account.label, "Emoji peers", listOf(peer.account.accountIdHex), null)
                            await { runCatching { peer.native.acceptGroupInvite(peer.account.label, group) }.isSuccess }
                            val target =
                                sender.native
                                    .sendText(sender.account.label, group, "Generated emoji target")
                                    .messageIds
                                    .single()
                            val senderState = state(sender, blobs).also(states::add)
                            var peerState = state(peer, blobs).also(states::add)
                            val active =
                                ConversationController(senderState, sender.native.groupDetails(sender.account.label, group).group, startOnConstruction = true)
                            controller = active
                            await { active.membersVerified && active.isSelfMember && active.timeline.any { it.record.messageIdHex == target } }
                            withContext(Dispatchers.Main.immediate) {
                                active.toggleReaction(code, active.timeline.single { it.record.messageIdHex == target }.record)
                            }
                            val entry = awaitArtwork(peer, group, target, code)
                            assertArrayEquals(bytes, acquire(peerState, group, entry))
                            val third = participant(File(root, "third"), relay, natives)
                            val oldEpoch = sender.native.groupMlsState(sender.account.label, group).epoch
                            sender.native.inviteMembers(sender.account.label, group, listOf(third.account.accountIdHex))
                            await { peer.native.groupMlsState(peer.account.label, group).epoch > oldEpoch }
                            assertArrayEquals(bytes, peerState.reactionArtworkBytes(peer.account.label, group, entry, false))
                            peerState.mutationsScope.cancel()
                            peer.native.shutdownAndClose()
                            natives -= peer.native
                            peer = peer.copy(native = open(peer.root, relay, natives))
                            peerState = state(peer, blobs).also(states::add)
                            val restored = awaitArtwork(peer, group, target, code)
                            assertEquals(entry.sourceMessageIdHex, restored.sourceMessageIdHex)
                            assertArrayEquals(bytes, peerState.reactionArtworkBytes(peer.account.label, group, restored, false))
                            assertEquals(
                                NotificationReactionSendOutcome.Sent,
                                senderState.sendNotificationReaction(sender.account.label, group, target, ":wn:"),
                            )
                            val notificationArt = awaitArtwork(peer, group, target, ":wn:")
                            val wn = context.resources.openRawResource(R.drawable.builtin_emoji_wn).use { it.readBytes() }
                            assertArrayEquals(wn, acquire(peerState, group, notificationArt))
                            await { active.reactions[target].orEmpty().any { it.emoji == code && it.mine } }
                            withContext(Dispatchers.Main.immediate) {
                                active.toggleReaction(code, active.timeline.single { it.record.messageIdHex == target }.record)
                            }
                            await { peer.native.reactionAttachment(peer.account.label, group, restored.messageIdHex) == null }
                            assertNull(peerState.openNativeAttachment(requireNotNull(restored.reactionRequest(peer.account.label, group))))
                        }
                    }
                }
            } finally {
                controller?.onCleared()
                states.forEach { it.mutationsScope.cancel() }
                natives.forEach { runCatching { it.shutdownAndClose() } }
                check(file.delete())
                check(root.deleteRecursively())
            }
        }

    /** Private generated client and its reopenable native store. */
    private data class Participant(
        val root: File,
        val native: Marmot,
        val account: AccountSummaryFfi,
    )

    /** Creates only a disposable identity whose invitation route is the fixture relay. */
    private suspend fun participant(
        root: File,
        relay: LoopbackNostrRelay,
        natives: MutableList<Marmot>,
    ): Participant {
        val native = open(root, relay, natives)
        return Participant(root, native, native.createIdentity(listOf(relay.url), listOf(relay.url)))
    }

    /** Opens the actual published native artifact with loopback-only test traffic admitted. */
    private suspend fun open(
        root: File,
        relay: LoopbackNostrRelay,
        natives: MutableList<Marmot>,
    ): Marmot {
        val native =
            Marmot.newWithConfiguration(
                root.path,
                listOf(relay.url),
                MarmotOptions(
                    relayPolicy = RelayPolicyFfi.ALLOW_LOOPBACK_RELAYS_AND_BLOBS,
                    deployment = marmotDeploymentWhiteNoise(),
                    attachmentAcquisitionMode = AttachmentAcquisitionModeFfi.HOST_MANAGED,
                ),
            )
        natives += native
        native.start()
        return native
    }

    /** Redirects uploads to the generated blob server; every protocol and read operation remains real native code. */
    private fun state(
        participant: Participant,
        blobs: EmojiBlossomFixture,
    ): WhiteNoiseAppState {
        val routed =
            object : MarmotInterface by participant.native {
                override suspend fun uploadMedia(
                    accountRef: String,
                    groupIdHex: String,
                    request: MediaUploadRequestFfi,
                ): MediaUploadResultFfi = participant.native.uploadMedia(accountRef, groupIdHex, request.copy(blossomServer = blobs.url))
            }
        return WhiteNoiseAppState(
            context = context,
            draftStore = DraftStore(ConversationTimelineTestDraftPersistence()),
            accounts = listOf(participant.account),
            activeAccountRef = participant.account.label,
            accountIdHexResolver = { participant.account.accountIdHex },
            initialMarmotRuntime = AppMarmotRuntime(participant.root.path, routed),
        )
    }

    /** Resolves the peer's exact kind-7 ID, not a source borrowed from another message or shortcode. */
    private suspend fun awaitArtwork(
        peer: Participant,
        group: String,
        target: String,
        code: String,
    ): AttachmentEntryFfi {
        var entry: AttachmentEntryFfi? = null
        await {
            val reaction = peer.native.messageReactions(peer.account.label, group, target).firstOrNull { it.emoji == code }
            entry = reaction?.let { peer.native.reactionAttachment(peer.account.label, group, it.reactionMessageIdHex) }
            entry != null
        }
        return requireNotNull(entry)
    }

    /** Uses MDK's original source slot and the existing bounded host-managed acquisition and local-read adapters. */
    private suspend fun acquire(
        state: WhiteNoiseAppState,
        group: String,
        entry: AttachmentEntryFfi,
    ): ByteArray {
        val account = requireNotNull(state.activeAccountRef)
        state.acquireNativeAttachment(requireNotNull(entry.reactionRequest(account, group)), AttachmentDownloadPriority.Interactive)
        return requireNotNull(state.reactionArtworkBytes(account, group, entry, false))
    }

    /** Polls only generated local state under a finite fixture deadline. */
    private suspend fun await(condition: suspend () -> Boolean) =
        withTimeout(60_000L) {
            while (!condition()) delay(100L)
        }
}
