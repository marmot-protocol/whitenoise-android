package dev.ipf.whitenoise.android.ui.conversation.media

import dev.ipf.marmotkit.AppMessageRecordFfi
import dev.ipf.marmotkit.AttachmentAcquisitionModeFfi
import dev.ipf.marmotkit.MarkdownDocumentFfi
import dev.ipf.marmotkit.Marmot
import dev.ipf.marmotkit.MarmotOptions
import dev.ipf.marmotkit.MediaAttachmentReferenceFfi
import dev.ipf.marmotkit.RelayPolicyFfi
import dev.ipf.whitenoise.android.media.FixtureSession
import dev.ipf.whitenoise.android.state.AttachmentTransferRequest
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.GroupMemberSnapshot
import dev.ipf.whitenoise.android.state.MediaAutoDownloadNetwork
import dev.ipf.whitenoise.android.state.MediaAutoDownloadType
import dev.ipf.whitenoise.android.state.MessageStatus
import dev.ipf.whitenoise.android.state.TimelineMessage
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** What the real-tile device tests share: the loopback runtime, a receiver controller and the row a tile renders. */
internal object TileFixtureSupport {
    /** Opens the generated loopback-only runtime used by every fixture probe. */
    fun openRuntime(
        root: File,
        relays: List<String>,
    ): Marmot =
        Marmot.newWithConfiguration(
            root.absolutePath,
            relays,
            MarmotOptions(
                relayPolicy = RelayPolicyFfi.ALLOW_LOOPBACK_RELAYS_AND_BLOBS,
                attachmentAcquisitionMode = AttachmentAcquisitionModeFfi.HOST_MANAGED,
            ),
        )

    /** One state and controller per account, with every automatic-download cell off for images and videos. */
    suspend fun receiverController(
        session: FixtureSession,
        request: AttachmentTransferRequest,
    ): Pair<WhiteNoiseAppState, ConversationController> {
        val state = session.state(request.accountRef)
        withContext(Dispatchers.Main.immediate) {
            for (type in listOf(MediaAutoDownloadType.Image, MediaAutoDownloadType.Video)) {
                for (network in MediaAutoDownloadNetwork.values()) state.setMediaAutoDownload(type, network, false)
            }
        }
        val details = session.marmot.groupDetails(request.accountRef, request.groupIdHex)
        val members = session.marmot.groupMembers(request.accountRef, request.groupIdHex)
        val controller =
            withContext(Dispatchers.Main.immediate) {
                ConversationController(state, details.group, GroupMemberSnapshot(members))
            }
        return state to controller
    }

    /** The kind-9 row the production tile renders, built from the real identifiers and reference. */
    fun timelineMessage(
        request: AttachmentTransferRequest,
        reference: MediaAttachmentReferenceFfi,
        sent: Boolean,
    ) = TimelineMessage(
        id = "msg:${request.messageIdHex}",
        record =
            AppMessageRecordFfi(
                messageIdHex = request.messageIdHex,
                direction = if (sent) "sent" else "received",
                groupIdHex = request.groupIdHex,
                sender = request.accountRef,
                plaintext = "",
                contentTokens =
                    MarkdownDocumentFfi(
                        truncated = false,
                        blankLinesBefore = byteArrayOf(),
                        blocks = emptyList(),
                    ),
                kind = 9uL,
                tags = emptyList(),
                sourceEpoch = reference.sourceEpoch,
                retentionSeconds = null,
                retentionExpiresAt = null,
                recordedAt = 1uL,
                receivedAt = 1uL,
            ),
        status = if (sent) MessageStatus.Sent else MessageStatus.Received,
    )
}
