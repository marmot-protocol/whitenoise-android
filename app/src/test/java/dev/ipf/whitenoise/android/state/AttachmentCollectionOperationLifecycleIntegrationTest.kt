package dev.ipf.whitenoise.android.state

import android.net.Uri
import androidx.compose.runtime.snapshots.Snapshot
import dev.ipf.whitenoise.android.ui.conversation.CollectionTokenStatus
import dev.ipf.whitenoise.android.ui.conversation.ConversationMediaSender
import dev.ipf.whitenoise.android.ui.conversation.cancelCollection
import dev.ipf.whitenoise.android.ui.conversation.collectionChunkKey
import dev.ipf.whitenoise.android.ui.conversation.media.PendingMediaSlot
import dev.ipf.whitenoise.android.ui.conversation.restoreCollectionOperations
import dev.ipf.whitenoise.android.ui.conversation.retryCollectionChunk
import dev.ipf.whitenoise.android.ui.conversation.skipCollectionChunk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.Collections

/**
 * Drives collection sends (#2576) through the shipping composer sender and conversation controller, with
 * scripted upload and publish seams: chunking, caption placement, the journal, blocking failures, retries,
 * cancellation, account changes and restoration after the process ends.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en")
@OptIn(ExperimentalCoroutinesApi::class)
class AttachmentCollectionOperationLifecycleIntegrationTest {
    /** Records what each native call saw, in order. */
    private class Calls {
        val uploads: MutableList<List<String>> = Collections.synchronizedList(mutableListOf())
        val publishes: MutableList<String?> = Collections.synchronizedList(mutableListOf())
    }

    /** A staged visual pick whose draft bytes are already prepared, so no decoder runs. */
    private fun image(index: Int) =
        PendingMediaSlot("slot-$index", Uri.parse("content://collection.test/image/$index")) to
            PendingAttachment(ByteArray(64) { index.toByte() }, "image/jpeg", "image-$index.jpg")

    /** A staged document whose draft bytes are already prepared. */
    private fun document(index: Int) =
        Uri.parse("content://collection.test/doc/$index") to
            PendingAttachment(ByteArray(64) { index.toByte() }, "application/pdf", "doc-$index.pdf")

    /** Runs [block] with Main on the test scheduler and the fixture cleaned up afterwards. */
    private fun lifecycle(
        uploadFailures: Set<Int> = emptySet(),
        publishFailures: Set<Int> = emptySet(),
        disappearingSecs: ULong = 0uL,
        onPublish: (WhiteNoiseAppState, Int) -> Unit = { _, _ -> },
        onUpload: (WhiteNoiseAppState) -> Unit = {},
        block: suspend TestScope.(Fixture) -> Unit,
    ) = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val state = mediaSendReconciliationAppState()
        val calls = Calls()
        var uploadAttempts = 0
        var publishAttempts = 0
        val controller =
            collectionLifecycleController(
                state,
                disappearingSecs,
                upload = { request ->
                    uploadAttempts += 1
                    onUpload(state)
                    if (uploadAttempts in uploadFailures) error("scripted upload failure")
                    calls.uploads += request.attachments.map { it.fileName }
                },
                publish = { caption ->
                    publishAttempts += 1
                    onPublish(state, publishAttempts)
                    if (publishAttempts in publishFailures) error("scripted publish failure")
                    calls.publishes += caption
                },
            )
        try {
            controller.retryMembers()
            val sender = ConversationMediaSender(state, controller, state.appContext, fileBackedLimits = { null }) {}
            block(Fixture(state, controller, sender, calls))
        } finally {
            finishMediaFixture(controller, state)
            Dispatchers.resetMain()
        }
    }

    /** Everything one test needs to drive and observe a send. */
    private class Fixture(
        val state: WhiteNoiseAppState,
        val controller: ConversationController,
        val sender: ConversationMediaSender,
        val calls: Calls,
    ) {
        val registry: AttachmentCollectionRegistry
            get() = state.attachmentCollections
        val operation: AttachmentCollectionOperation?
            get() =
                registry.operations.value.values
                    .singleOrNull()
        val journalFiles: List<File>
            get() =
                File(state.appContext.noBackupFilesDir, "attachment_collections")
                    .walkTopDown()
                    .filter { it.isFile }
                    .toList()
    }

    /** Advances Main and the snapshot system until [condition] holds, with real time for IO hops. */
    private fun TestScope.awaitUntil(
        description: String,
        condition: () -> Boolean,
    ) {
        repeat(AWAIT_ROUNDS) {
            Snapshot.sendApplyNotifications()
            testScheduler.advanceUntilIdle()
            if (condition()) return
            Thread.sleep(AWAIT_SLEEP_MS)
        }
        fail("timed out waiting for $description")
    }

    /** Sends [images] and [documents] with [caption] through the composer's staged-send entry point. */
    private fun Fixture.send(
        images: List<Pair<PendingMediaSlot, PendingAttachment>>,
        documents: List<Pair<Uri, PendingAttachment>> = emptyList(),
        caption: String = "",
    ) {
        sender.sendStagedAttachments(
            imageSlots = images.map { it.first },
            documentUris = documents.map { it.first },
            caption = caption,
            preparedImageAttachments = images.associate { it.first.id to it.second },
            preparedDocumentAttachments = documents.associate { it.first to it.second },
        )
    }

    /** One hundred mixed items go out as ordered bounded chunks, with the trimmed caption on chunk 1 only. */
    @Test
    fun hundredMixedItemsSendAsOrderedChunksWithCaptionOnlyOnTheFirst() =
        lifecycle { fixture ->
            val images = List(90) { image(it) }
            val documents = List(10) { document(it) }
            fixture.send(images, documents, caption = "  holiday  ")
            awaitUntil("all chunks published") { fixture.calls.publishes.size == 19 }
            awaitUntil("operation finished") { fixture.operation == null }

            val uploaded = fixture.calls.uploads
            assertEquals(images.map { it.second.fileName } + documents.map { it.second.fileName }, uploaded.flatten())
            assertTrue(uploaded.all { it.size <= ATTACHMENT_CHUNK_MAX_ITEMS })
            assertEquals(listOf("holiday") + List(18) { null }, fixture.calls.publishes)
            assertTrue("terminal success deletes the journal", fixture.journalFiles.isEmpty())
        }

    /** The journal on disk holds each chunk's MDK token by the time that chunk's upload starts. */
    @Test
    fun chunkTokenIsJournaledBeforeItsUpload() {
        val journaledAtUpload = Collections.synchronizedList(mutableListOf<Boolean>())
        lifecycle(
            onUpload = { state ->
                val live =
                    state.attachmentCollections.operations.value.values
                        .single()
                val uploading = live.chunks.first { it.state == CollectionChunkState.UPLOADING }
                val onDisk =
                    AttachmentCollectionJournal(state.appContext.noBackupFilesDir)
                        .read(live.accountRef, live.groupIdHex)
                        .single()
                journaledAtUpload += onDisk.chunks.single { it.id == uploading.id }.clientToken == uploading.clientToken
            },
        ) { fixture ->
            fixture.send(List(12) { image(it) })
            awaitUntil("both chunks published") { fixture.calls.publishes.size == 2 }
            awaitUntil("operation finished") { fixture.operation == null }
            assertEquals(listOf(true, true), journaledAtUpload)
            assertEquals(2, fixture.calls.publishes.size)
        }
    }

    /** A publish-only failure blocks later chunks; Retry reuses the uploaded blobs and publishes once. */
    @Test
    fun publishOnlyFailureBlocksLaterChunksAndRetryReusesTheUpload() =
        lifecycle(publishFailures = setOf(2)) { fixture ->
            fixture.send(images = emptyList(), documents = List(3) { document(it) })
            awaitUntil("second chunk blocked") { fixture.operation?.phase == CollectionOperationPhase.BLOCKED }
            assertEquals(2, fixture.calls.uploads.size)
            assertEquals(1, fixture.calls.publishes.size)
            assertTrue("the record survives a block", fixture.journalFiles.isNotEmpty())

            val operation = requireNotNull(fixture.operation)
            val blocked = requireNotNull(operation.nextChunk)
            fixture.controller.retryCollectionChunk(operation.id, blocked.id)
            fixture.controller.retryCollectionChunk(operation.id, blocked.id)
            awaitUntil("operation finished") { fixture.operation == null }

            assertEquals("the failed chunk's upload is reused", 3, fixture.calls.uploads.size)
            assertEquals(listOf("doc-0.pdf", "doc-1.pdf", "doc-2.pdf"), fixture.calls.uploads.flatten())
            assertEquals(3, fixture.calls.publishes.size)
        }

    /** An upload failure retried by repeated taps uploads once more and publishes once. */
    @Test
    fun repeatedRetryTapsAfterUploadFailureNeverDuplicate() =
        lifecycle(uploadFailures = setOf(1)) { fixture ->
            fixture.send(images = emptyList(), documents = List(2) { document(it) })
            awaitUntil("first chunk blocked") { fixture.operation?.phase == CollectionOperationPhase.BLOCKED }
            assertTrue("nothing after the failed chunk starts", fixture.calls.uploads.isEmpty())
            val operation = requireNotNull(fixture.operation)
            val blocked = requireNotNull(operation.nextChunk)
            val token = requireNotNull(blocked.clientToken)
            repeat(3) { fixture.controller.retryCollectionChunk(operation.id, blocked.id) }
            awaitUntil("operation finished") { fixture.operation == null }

            assertEquals(listOf(listOf("doc-0.pdf"), listOf("doc-1.pdf")), fixture.calls.uploads)
            assertEquals(2, fixture.calls.publishes.size)
            assertTrue(
                "the retried chunk kept its token",
                fixture.controller.timeline.any { it.id == collectionChunkKey(token) },
            )
        }

    /** Skipping a failed chunk names its items skipped and lets the later chunks go. */
    @Test
    fun skippingAFailedChunkSendsTheRest() =
        lifecycle(uploadFailures = setOf(2)) { fixture ->
            fixture.send(images = emptyList(), documents = List(3) { document(it) })
            awaitUntil("second chunk blocked") { fixture.operation?.phase == CollectionOperationPhase.BLOCKED }
            val operation = requireNotNull(fixture.operation)
            val blocked = requireNotNull(operation.nextChunk)
            fixture.controller.skipCollectionChunk(operation.id, blocked.id)
            awaitUntil("operation finished") { fixture.operation == null }

            assertEquals(listOf("doc-0.pdf", "doc-2.pdf"), fixture.calls.uploads.flatten())
            assertEquals(2, fixture.calls.publishes.size)
        }

    /** Cancelling a blocked operation keeps the sent chunk, cancels the rest and deletes the journal. */
    @Test
    fun cancelStopsUnacceptedChunksAndClearsTheJournal() =
        lifecycle(uploadFailures = setOf(2)) { fixture ->
            fixture.send(images = emptyList(), documents = List(4) { document(it) })
            awaitUntil("second chunk blocked") { fixture.operation?.phase == CollectionOperationPhase.BLOCKED }
            val operation = requireNotNull(fixture.operation)
            fixture.controller.cancelCollection(operation.id)
            awaitUntil("operation finished") { fixture.operation == null }

            assertEquals(listOf("doc-0.pdf"), fixture.calls.uploads.flatten())
            assertEquals(1, fixture.calls.publishes.size)
            assertTrue(fixture.journalFiles.isEmpty())
            assertFalse(fixture.controller.timeline.any { it.status == MessageStatus.Failed })
        }

    /** An account switch after chunk 1 pauses the rest instead of sending them under the wrong session. */
    @Test
    fun accountChangeAfterTheFirstChunkPausesTheRest() =
        lifecycle(onPublish = { state, attempt -> if (attempt == 1) endMediaUploadSession(state) }) { fixture ->
            fixture.send(images = emptyList(), documents = List(3) { document(it) })
            awaitUntil("operation paused") { fixture.operation?.phase == CollectionOperationPhase.PAUSED }

            assertEquals(1, fixture.calls.uploads.size)
            assertEquals(1, fixture.calls.publishes.size)
            assertEquals(1, fixture.operation?.published)
            assertTrue("a paused operation keeps its record", fixture.journalFiles.isNotEmpty())
        }

    /** Ends the account's media upload session, as an account switch or sign-out does. */
    private fun endMediaUploadSession(state: WhiteNoiseAppState) {
        (
            WhiteNoiseAppState::class.java
                .getDeclaredField("mediaUploadSessionLifetime")
                .apply { isAccessible = true }
                .get(state) as StalenessGuard
        ).advance()
    }

    /** The group's disappearing timer is captured for every chunk, not just the first. */
    @Test
    fun disappearingTimerAppliesToEveryChunk() {
        lifecycle(disappearingSecs = 3_600uL) { fixture ->
            fixture.send(images = emptyList(), documents = List(3) { document(it) })
            awaitUntil("every chunk published") { fixture.calls.publishes.size == 3 }
            awaitUntil("operation finished") { fixture.operation == null }
            val retention = fixture.controller.timeline.map { it.retentionAtSendSeconds }
            assertEquals(3, retention.size)
            assertTrue(retention.all { it == 3_600uL })
        }
    }

    /**
     * After the process ends, MDK is asked about each recorded token: an accepted token settles as sent, an
     * unreadable item is named, and an operation MDK accepted nothing of is left to the composer draft.
     */
    @Test
    fun restorationReconcilesTokensAndNamesUnreadableItems() =
        lifecycle { fixture ->
            val account = requireNotNull(fixture.controller.boundAccountRef)
            val group = fixture.controller.group.groupIdHex
            val journal = AttachmentCollectionJournal(fixture.state.appContext.noBackupFilesDir)
            val planned = List(3) { PlannedCollectionItem("d$it", CollectionItemKind.DOCUMENT, 1L) }
            val base =
                AttachmentCollectionOperation(
                    id = "previous-process",
                    accountRef = account,
                    groupIdHex = group,
                    items = planned.map { CollectionItem(it.id, "content://gone/${it.id}", it.kind) },
                    chunks = planAttachmentChunks("previous-process", planned),
                    createdAtMs = System.currentTimeMillis(),
                    updatedAtMs = System.currentTimeMillis(),
                )
            val first = base.chunks[0].id
            val second = base.chunks[1].id
            val recorded =
                base
                    .withChunkToken(first, "token-1", 1L)
                    .withChunkState(first, CollectionChunkState.UPLOADING, CollectionItemState.UPLOADING, 1L)
                    .withChunkToken(second, "token-2", 1L)
                    .withChunkState(second, CollectionChunkState.UPLOADING, CollectionItemState.UPLOADING, 1L)
                    .copy(updatedAtMs = System.currentTimeMillis())
            journal.write(recorded)
            journal.write(base.copy(id = "never-accepted"))

            fixture.state.restoreCollectionOperations(
                account,
                group,
                readable = { uri -> !uri.endsWith("d2") },
                tokenStatus = { token ->
                    if (token == "token-1") CollectionTokenStatus.ACCEPTED else CollectionTokenStatus.NOT_ACCEPTED
                },
            )

            val restored = requireNotNull(fixture.registry.operations.value["previous-process"])
            assertNull(fixture.registry.operations.value["never-accepted"])
            assertEquals(CollectionOperationPhase.PAUSED, restored.phase)
            assertEquals(CollectionChunkState.ACCEPTED, restored.chunks[0].state)
            assertEquals(CollectionChunkState.PLANNED, restored.chunks[1].state)
            assertNull("an unaccepted token is not reused", restored.chunks[1].clientToken)
            assertEquals(setOf("d2"), restored.unrecoverableItemIds)
            assertEquals(1, restored.published)
            assertEquals(listOf("d0", "d1", "d2"), restored.items.map { it.id })
            assertTrue(fixture.calls.uploads.isEmpty())
        }

    private companion object {
        const val AWAIT_ROUNDS = 500
        const val AWAIT_SLEEP_MS = 10L
    }
}
