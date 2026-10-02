package dev.ipf.whitenoise.android.media

import android.content.Context
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.marmotkit.AppBlobEndpointFfi
import dev.ipf.marmotkit.AttachmentPageReadFfi
import dev.ipf.marmotkit.Marmot
import dev.ipf.marmotkit.MediaAttachmentOutcomeFfi
import dev.ipf.marmotkit.MediaAttachmentReferenceFfi
import dev.ipf.whitenoise.android.state.AppMarmotRuntime
import dev.ipf.whitenoise.android.state.AttachmentTransferRequest
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.DraftPersistence
import dev.ipf.whitenoise.android.state.DraftStore
import dev.ipf.whitenoise.android.state.GroupMemberSnapshot
import dev.ipf.whitenoise.android.state.PendingAttachment
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File

/** Uses the shipping Android send controller and native admission, upload, publication and retention paths. */
internal suspend fun sendAndroidFixtureAttachment(
    context: Context,
    root: File,
    marmot: Marmot,
    sender: AccountSummaryFfi,
    group: String,
    blobPort: Int,
    bytes: ByteArray,
    qualifyOwnLocalCache: Boolean = false,
): MediaAttachmentReferenceFfi {
    check(context.packageName == "dev.ipf.whitenoise.android.medialatency")
    val endpoint = AppBlobEndpointFfi("blossom-v1", "http://127.0.0.1:$blobPort")
    marmot.replaceEncryptedMediaBlobEndpoints(sender.label, group, listOf(endpoint))
    marmot.catchUpAccounts()
    val details = marmot.groupDetails(sender.label, group)
    val members = marmot.groupMembers(sender.label, group)
    val state =
        withContext(Dispatchers.Main.immediate) {
            WhiteNoiseAppState(
                context = context,
                draftStore = DraftStore(GeneratedSenderDrafts),
                accountIdHexResolver = { if (it == sender.label) sender.accountIdHex else null },
                accounts = listOf(sender),
                activeAccountRef = sender.label,
                initialMarmotRuntime = AppMarmotRuntime(root.absolutePath, marmot),
            )
        }
    val heldHostCopy =
        if (qualifyOwnLocalCache) LargeAttachmentLocalReadComparison.holdOutgoingHostCopy(state, root) else null
    val controller =
        withContext(Dispatchers.Main.immediate) {
            ConversationController(state, details.group, GroupMemberSnapshot(members))
        }
    try {
        withContext(Dispatchers.Main.immediate) {
            controller.retryMembers()
            check(controller.canSendMessages) { "generated sender membership not ready" }
            if (qualifyOwnLocalCache) controller.start()
            controller.sendAttachments(listOf(PendingAttachment(bytes, "text/plain", "fixture.txt")), caption = null)
        }
        val (messageId, reference) = awaitAndroidFixtureReference(marmot, sender.label, group)
        if (qualifyOwnLocalCache) {
            // Accepted-pending sends seed the confirmed cache when the shipping live projection reconciles.
            awaitAndroidFixtureProjection(controller, messageId)
            val request = AttachmentTransferRequest(sender.label, group, messageId, 0)
            LargeAttachmentLocalReadComparison.assertNativeBeforeHostCopy(
                state,
                request,
                reference,
                bytes,
                checkNotNull(heldHostCopy),
            )
            LargeAttachmentLocalReadComparison.assertOwnHostCache(
                state,
                request,
                reference,
                bytes,
            )
        }
        return reference
    } finally {
        heldHostCopy?.release()
        withContext(Dispatchers.Main.immediate) {
            controller.onCleared()
            state.mutationsScope.cancel()
        }
    }
}

/** Waits for the real foreground projection that exposes the confirmed own-file card. */
private suspend fun awaitAndroidFixtureProjection(
    controller: ConversationController,
    messageId: String,
) {
    withTimeout(30_000L) {
        while (!withContext(Dispatchers.Main.immediate) { controller.retainsTimelineRecord(messageId) }) delay(10L)
    }
}

/** Reads the actual published source; no outgoing row, asset reference or accepted send is manufactured. */
private suspend fun awaitAndroidFixtureReference(
    marmot: Marmot,
    sender: String,
    group: String,
): Pair<String, MediaAttachmentReferenceFfi> =
    withTimeout(30_000L) {
        var reference: Pair<String, MediaAttachmentReferenceFfi>? = null
        while (reference == null) {
            val read = marmot.attachmentHistoryPage(sender, group, 100u, null)
            if (read is AttachmentPageReadFfi.Page) {
                val page = read.page
                try {
                    reference =
                        page.entries
                            .mapNotNull { entry ->
                                val accepted = entry.attachment as? MediaAttachmentOutcomeFfi.Accepted
                                accepted?.reference?.let { entry.messageIdHex to it }
                            }.singleOrNull { it.second.fileName == "fixture.txt" }
                } finally {
                    page.nextCursor?.close()
                    page.version.close()
                }
            }
            if (reference == null) delay(100L)
        }
        reference
    }

/** Generated drafts never read or write the diagnostic app's existing drafts. */
private object GeneratedSenderDrafts : DraftPersistence {
    /** The generated send has no saved draft. */
    override fun read(): Map<String, String> = emptyMap()

    /** Discards only fixture draft mutations. */
    override fun write(
        key: String,
        value: String?,
    ) = Unit
}
