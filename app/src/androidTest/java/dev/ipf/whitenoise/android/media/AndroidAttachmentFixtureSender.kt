package dev.ipf.whitenoise.android.media

import android.content.Context
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.marmotkit.AppBlobEndpointFfi
import dev.ipf.marmotkit.AttachmentEntryFfi
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

/** The default generated attachment name; scenarios that send several files pass distinct names. */
internal const val FIXTURE_FILE_NAME = "fixture.txt"

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
    fileName: String = FIXTURE_FILE_NAME,
): MediaAttachmentReferenceFfi =
    sendAndroidFixtureMedia(
        context,
        root,
        marmot,
        sender,
        group,
        blobPort,
        listOf(PendingAttachment(bytes, "text/plain", fileName)),
        qualifyOwnLocalCache,
    ).references.single()

/** One genuinely published message: its identity and every accepted attachment reference in original order. */
internal class SentFixtureMessage(
    val messageIdHex: String,
    val sourceMessageIdHex: String,
    val references: List<MediaAttachmentReferenceFfi>,
    val ownHostCached: List<Boolean> = emptyList(),
)

/**
 * Sends one message carrying every supplied attachment through the shipping controller, so an album is a genuine
 * multi-attachment send. File names must be unique within the message, and own-cache qualification needs one file.
 */
internal suspend fun sendAndroidFixtureMedia(
    context: Context,
    root: File,
    marmot: Marmot,
    sender: AccountSummaryFfi,
    group: String,
    blobPort: Int,
    attachments: List<PendingAttachment>,
    qualifyOwnLocalCache: Boolean = false,
    awaitOwnHostPublication: Boolean = false,
): SentFixtureMessage {
    check(context.packageName == "dev.ipf.whitenoise.android.medialatency")
    require(attachments.isNotEmpty() && attachments.map { it.fileName }.toSet().size == attachments.size)
    require(!qualifyOwnLocalCache || attachments.size == 1)
    require(!qualifyOwnLocalCache || !awaitOwnHostPublication)
    val harness = openSenderHarness(context, root, marmot, sender, group, blobPort, qualifyOwnLocalCache)
    try {
        withContext(Dispatchers.Main.immediate) {
            harness.controller.retryMembers()
            check(harness.controller.canSendMessages) { "generated sender membership not ready" }
            if (qualifyOwnLocalCache || awaitOwnHostPublication) harness.controller.start()
        }
        if (qualifyOwnLocalCache || awaitOwnHostPublication) {
            awaitAndroidFixtureSenderReady(harness.controller)
        }
        withContext(Dispatchers.Main.immediate) {
            val queued = checkNotNull(harness.controller.queueAttachments(attachments, caption = null)) {
                "generated sender did not admit its media"
            }
            harness.controller.uploadQueued(queued)
        }
        val published = awaitAndroidFixtureReferences(marmot, sender.label, group, attachments.map { it.fileName })
        val sent =
            if (awaitOwnHostPublication) {
                withOwnHostPublication(harness, sender.label, group, published)
            } else {
                published
            }
        if (qualifyOwnLocalCache) {
            qualifyOwnCache(harness, sender.label, group, sent, attachments.single().plaintextBytes)
        }
        return sent
    } finally {
        harness.close()
    }
}

/** The generated sender's state and controller, plus the optional hold on its own host copy. */
internal class SenderHarness(
    val state: WhiteNoiseAppState,
    val controller: ConversationController,
    val heldHostCopy: OutgoingHostCopyHold?,
) {
    /** Releases any hold first, then cancels only this fixture's controller and state scope. */
    suspend fun close() {
        heldHostCopy?.release()
        withContext(Dispatchers.Main.immediate) {
            controller.onCleared()
            state.mutationsScope.cancel()
        }
    }
}

/** Points the generated group at the loopback blob server and builds a fixture-only sender state and controller. */
internal suspend fun openSenderHarness(
    context: Context,
    root: File,
    marmot: Marmot,
    sender: AccountSummaryFfi,
    group: String,
    blobPort: Int,
    holdHostCopy: Boolean,
): SenderHarness {
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
    val hold = if (holdHostCopy) LargeAttachmentLocalReadComparison.holdOutgoingHostCopy(state, root) else null
    val controller =
        withContext(Dispatchers.Main.immediate) {
            ConversationController(state, details.group, GroupMemberSnapshot(members))
        }
    return SenderHarness(state, controller, hold)
}

/** Initial live startup may replace send eligibility while attachment preparation is suspended. */
private suspend fun awaitAndroidFixtureSenderReady(controller: ConversationController) {
    withTimeout(30_000L) {
        while (true) {
            val ready =
                withContext(Dispatchers.Main.immediate) {
                    controller.hasPublishedAuthoritativeTimeline && !controller.isLoading && controller.canSendMessages
                }
            if (ready) return@withTimeout
            delay(10L)
        }
    }
}

/** Waits for the shipping publication, which runs in the state's own scope and would die with an early teardown. */
private suspend fun withOwnHostPublication(
    harness: SenderHarness,
    sender: String,
    group: String,
    published: SentFixtureMessage,
): SentFixtureMessage {
    awaitAndroidFixtureProjection(harness.controller, published.messageIdHex)
    val cached =
        published.references.indices.map { index ->
            harness.state.hasHostCachedAttachmentAfterHydration(
                AttachmentTransferRequest(sender, group, published.messageIdHex, index),
            )
        }
    return SentFixtureMessage(published.messageIdHex, published.sourceMessageIdHex, published.references, cached)
}

/** Proves native retention is readable before the held host copy finishes, then that the host copy follows. */
private suspend fun qualifyOwnCache(
    harness: SenderHarness,
    sender: String,
    group: String,
    sent: SentFixtureMessage,
    bytes: ByteArray,
) {
    // Accepted-pending sends seed the confirmed cache when the shipping live projection reconciles.
    awaitAndroidFixtureProjection(harness.controller, sent.messageIdHex)
    val request = AttachmentTransferRequest(sender, group, sent.messageIdHex, 0)
    val reference = sent.references.single()
    LargeAttachmentLocalReadComparison.assertNativeBeforeHostCopy(
        harness.state,
        request,
        reference,
        bytes,
        checkNotNull(harness.heldHostCopy),
    )
    LargeAttachmentLocalReadComparison.assertOwnHostCache(harness.state, request, reference, bytes)
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
internal suspend fun awaitAndroidFixtureReferences(
    marmot: Marmot,
    sender: String,
    group: String,
    fileNames: List<String>,
): SentFixtureMessage =
    withTimeout(30_000L) {
        var sent: SentFixtureMessage? = null
        while (sent == null) {
            val read = marmot.attachmentHistoryPage(sender, group, 100u, null)
            if (read is AttachmentPageReadFfi.Page) {
                val page = read.page
                try {
                    sent = publishedMessage(page.entries, fileNames)
                } finally {
                    page.nextCursor?.close()
                    page.version.close()
                }
            }
            if (sent == null) delay(100L)
        }
        sent
    }

/** Returns the one published message that carries every wanted file name, or null while it is still incomplete. */
private fun publishedMessage(
    entries: List<AttachmentEntryFfi>,
    fileNames: List<String>,
): SentFixtureMessage? {
    val accepted =
        entries.mapNotNull { entry ->
            (entry.attachment as? MediaAttachmentOutcomeFfi.Accepted)
                ?.takeIf { it.reference.fileName in fileNames }
                ?.let { Triple(entry, it.attachmentIndex.toInt(), it.reference) }
        }
    val message = accepted.map { it.first.messageIdHex }.distinct().singleOrNull()
    val ordered = accepted.sortedBy { it.second }
    return if (message == null || ordered.map { it.second } != fileNames.indices.toList()) {
        null
    } else {
        SentFixtureMessage(message, ordered.first().first.sourceMessageIdHex, ordered.map { it.third })
    }
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
