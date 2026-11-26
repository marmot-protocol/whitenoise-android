package dev.ipf.whitenoise.android.state

import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.LocalSendAcceptanceFfi
import dev.ipf.marmotkit.LocalSendStatusFfi
import dev.ipf.marmotkit.MarmotInterface
import dev.ipf.marmotkit.MarmotKitException
import dev.ipf.marmotkit.MediaAttachmentReferenceFfi
import dev.ipf.marmotkit.MessageDraftRevisionFfi
import dev.ipf.marmotkit.SelectedMessageDraftAttachmentFfi
import dev.ipf.marmotkit.SelectedMessageDraftContentFfi
import dev.ipf.marmotkit.SelectedMessageDraftFfi
import dev.ipf.whitenoise.android.media.editor.nativeHandleStub
import dev.ipf.whitenoise.android.ui.conversation.media.StagedUploadSource
import dev.ipf.whitenoise.android.ui.conversation.media.closeQuietly
import dev.ipf.whitenoise.android.ui.profile.personTestGroup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** Pins that a failed send of stored draft files owns private copies of them, whatever the draft does next. */
class DraftFileCopiesTest {
    @get:Rule
    val folder = TemporaryFolder()

    /** After an unrelated send clears the draft, a retry still uploads the copies the failed send took. */
    @Test
    fun retryAfterAnUnrelatedSendClearsTheDraftUsesTheCopies() =
        runBlocking {
            val engine = CopyEngine(mapOf("clip" to byteArrayOf(1, 2, 3)))
            val retained = RetainedMediaUpload(listOf(storedPick("clip", 3L)), "caption")

            retained.adoptDraftCopies(copiesFor(engine, retained))
            engine.clear()

            assertTrue(retained.ownsDraftCopies)
            val owned = retained.ownedUploadAttachments().single()
            assertNull(owned.draftFile)
            assertArrayEquals(byteArrayOf(1, 2, 3), checkNotNull(owned.sourceFile).file.readBytes())
        }

    /** A pick added to the draft after the failure changes nothing the retry reads. */
    @Test
    fun retryAfterANewAttachmentUsesTheSameCopies() =
        runBlocking {
            val engine = CopyEngine(mapOf("clip" to byteArrayOf(4, 5)))
            val retained = RetainedMediaUpload(listOf(storedPick("clip", 2L)), "caption")
            retained.adoptDraftCopies(copiesFor(engine, retained))
            val before = retained.ownedUploadAttachments().single().sourceFile

            engine.add("added", byteArrayOf(9))

            assertSame(before, retained.ownedUploadAttachments().single().sourceFile)
            assertArrayEquals(byteArrayOf(4, 5), checkNotNull(before).file.readBytes())
        }

    /** A save that moves the draft mid-copy, such as typing, is followed while the draft still holds the file. */
    @Test
    fun movedDraftIsFollowedMidCopy() =
        runBlocking {
            val engine = CopyEngine(mapOf("clip" to ByteArray(3) { it.toByte() }), chunk = 1)
            var moved = false
            engine.beforeChunk = { offset ->
                if (offset == 1uL && !moved) {
                    moved = true
                    engine.move()
                }
            }

            val retained = RetainedMediaUpload(listOf(storedPick("clip", 3L)), "caption")
            val copies = DraftFileCopies()

            assertTrue(engine.copy(retained, copies))
            val copy = copies.take().getValue("clip")
            assertArrayEquals(byteArrayOf(0, 1, 2), copy.file.readBytes())
        }

    /** A file the draft no longer holds, or loses mid-copy, gives no copies and leaves no file behind. */
    @Test
    fun missingFileCopiesNothing() =
        runBlocking {
            val engine = CopyEngine(mapOf("a" to byteArrayOf(1, 2), "b" to byteArrayOf(3, 4)), chunk = 1)
            engine.beforeChunk = { offset -> if (offset == 1uL && engine.reading == "b") engine.clear() }
            val retained = RetainedMediaUpload(listOf(storedPick("a", 2L), storedPick("b", 2L)), "caption")

            retained.keepDraftCopies(StagedUploadSource::closeQuietly) { copies -> engine.copy(retained, copies) }

            assertFalse(retained.ownsDraftCopies)
            assertEquals(emptyList<String>(), folderFiles())
        }

    /**
     * A send cancelled once its copy is renamed into place, as the IO pool hands the finished file back, deletes it
     * instead of leaving plaintext the process sweep never reaches.
     */
    @Test
    fun cancellationAfterTheCopyIsRenamedLeavesNoFile() =
        runBlocking {
            val engine = CopyEngine(mapOf("clip" to byteArrayOf(1, 2, 3)))
            val retained = RetainedMediaUpload(listOf(storedPick("clip", 3L)), "caption")
            val closed = mutableListOf<Boolean>()
            lateinit var keeping: Job
            // The end-of-file read cancels the send, so the copy still completes and renames its file.
            engine.beforeChunk = { offset -> if (offset == 3uL) keeping.cancel() }

            keeping =
                launch {
                    retained.keepDraftCopies(recordingClose(closed)) { copies ->
                        engine.copy(retained, copies)
                    }
                }
            keeping.join()

            assertEquals(listOf(true), closed)
            assertFalse(retained.ownsDraftCopies)
            assertEquals(emptyList<String>(), folderFiles())
        }

    /**
     * A send cancelled after every copy is finished, as the copy returns from the IO pool to its caller, deletes them
     * rather than losing them with the result it never receives.
     */
    @Test
    fun cancellationAsTheFinishedCopiesReturnLeavesNoFile() =
        runBlocking {
            val engine = CopyEngine(mapOf("a" to byteArrayOf(1), "b" to byteArrayOf(2, 3)))
            val retained = RetainedMediaUpload(listOf(storedPick("a", 1L), storedPick("b", 2L)), "caption")
            val closed = mutableListOf<Boolean>()
            lateinit var keeping: Job

            keeping =
                launch {
                    retained.keepDraftCopies(recordingClose(closed)) { copies ->
                        withContext(Dispatchers.IO) { engine.copy(retained, copies).also { keeping.cancel() } }
                    }
                }
            keeping.join()

            assertEquals(listOf(true, true), closed)
            assertFalse(retained.ownsDraftCopies)
            assertEquals(emptyList<String>(), folderFiles())
        }

    /** Copies that arrive after the send released its sources, such as a discard during the copy, are deleted. */
    @Test
    fun copiesAdoptedAfterReleaseAreDeleted() {
        val retained = RetainedMediaUpload(listOf(storedPick("clip", 1L)), "caption")
        retained.releaseSource()
        val late = File(folder.root, "late").apply { writeBytes(byteArrayOf(1)) }

        retained.adoptDraftCopies(mapOf("clip" to StagedUploadSource(late, 1L)))

        assertFalse(late.exists())
        assertFalse(retained.ownsDraftCopies)
    }

    /** Releasing the send's sources deletes the copies it owns. */
    @Test
    fun releaseDeletesOwnedCopies() {
        val retained = RetainedMediaUpload(listOf(storedPick("clip", 1L)), "caption")
        val copy = File(folder.root, "copy").apply { writeBytes(byteArrayOf(1)) }
        retained.adoptDraftCopies(mapOf("clip" to StagedUploadSource(copy, 1L)))
        assertNotNull(retained.ownedUploadAttachments().single().sourceFile)

        retained.releaseSource()

        assertFalse(copy.exists())
    }

    /** The copies [engine]'s draft gives [retained]'s stored files, which must all succeed. */
    private suspend fun copiesFor(
        engine: CopyEngine,
        retained: RetainedMediaUpload,
    ): Map<String, StagedUploadSource> {
        val copies = DraftFileCopies()
        assertTrue(engine.copy(retained, copies))
        return copies.take()
    }

    /** Copies [retained]'s stored files out of this draft into the test folder, registering each in [copies]. */
    private suspend fun CopyEngine.copy(
        retained: RetainedMediaUpload,
        copies: DraftFileCopies,
    ) = marmot.copyDraftFiles("acct", "group", retained.attachments, folder.root, copies)

    /** Every file left in the test folder, partial copies included. */
    private fun folderFiles() =
        folder.root
            .list()
            .orEmpty()
            .toList()

    /** Deletes each source it is given, recording in [closed] whether its file existed when cleanup reached it. */
    private fun recordingClose(closed: MutableList<Boolean>): (StagedUploadSource) -> Unit =
        { source ->
            closed += source.file.exists()
            source.closeQuietly()
        }
}

/** Pins that a send of stored draft files MDK refuses for a stale epoch keeps a source its Retry can upload. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class DraftStoredAdmissionRefusalTest {
    /**
     * The refusal leaves the send private copies, taken while its caller still holds the commit lock, so a Retry after
     * an unrelated send clears the draft uploads the file again without the draft.
     */
    @Test
    fun staleEpochRefusalKeepsCopiesForARetryAfterTheDraftClears() =
        runBlocking {
            val engine = CopyEngine(mapOf("clip" to byteArrayOf(1, 2, 3)), refuseAdmission = true)
            val runtime = AppMarmotRuntime("test", engine.marmot)
            val appState =
                WhiteNoiseAppState(
                    context = ApplicationProvider.getApplicationContext(),
                    draftStore = DraftStore(NoDrafts),
                    accountIdHexResolver = { null },
                    accounts = emptyList(),
                    activeAccountRef = "acct",
                    initialMarmotRuntime = runtime,
                    marmotRuntimeFactory = { runtime },
                )
            val group = personTestGroup("group", "Team", members = null).group
            val controller = ConversationController(appState = appState, initialGroup = group)
            val retained = RetainedMediaUpload(listOf(storedPick("clip", 3L)), "caption")

            val refusal = runCatching { controller.admitRetainedMedia("acct", emptyList(), retained, "token") }

            assertTrue(refusal.exceptionOrNull() is MarmotKitException.MediaReferenceStaleEpoch)
            engine.clear()
            assertTrue(retained.ownsDraftCopies)
            val retry = checkNotNull(retained.ownedUploadAttachments().single().sourceFile)
            assertArrayEquals(byteArrayOf(1, 2, 3), retry.file.readBytes())
            retained.releaseSource()
        }

    /** Keeps the text drafts this test never writes out of storage. */
    private object NoDrafts : DraftPersistence {
        /** Starts with no saved text drafts. */
        override fun read(): Map<String, String> = emptyMap()

        /** Discards a text draft write. */
        override fun write(
            key: String,
            value: String?,
        ) = Unit
    }
}

/** A pick the draft stores as file [id] of [size] bytes. */
private fun storedPick(
    id: String,
    size: Long,
) = PendingAttachment(ByteArray(0), "video/mp4", "$id.mp4", draftFile = DraftStoredFile(id, size))

/** A draft holding stored files in memory, read in [chunk]-byte pieces under its current revision. */
private class CopyEngine(
    files: Map<String, ByteArray>,
    private val chunk: Int = Int.MAX_VALUE,
    private val refuseAdmission: Boolean = false,
) {
    private var bodies = files.toMutableMap()
    private var revision: MessageDraftRevisionFfi = newRevision()
    var beforeChunk: (ULong) -> Unit = {}
    var reading: String? = null
        private set

    val marmot: MarmotInterface = Engine()

    /** Moves the draft to a new revision holding the same files, as a text save does. */
    fun move() {
        revision = newRevision()
    }

    /** Clears the draft, as a successful unrelated send does. */
    fun clear() {
        bodies.clear()
        move()
    }

    /** Adds stored file [id], as staging another pick does. */
    fun add(
        id: String,
        body: ByteArray,
    ) {
        bodies[id] = body
        move()
    }

    /** The draft as MarmotKit selects it, or no draft once it is empty. */
    private fun selected(): SelectedMessageDraftFfi {
        val attachments =
            bodies.map { (id, body) ->
                SelectedMessageDraftAttachmentFfi(
                    id = id,
                    fileName = "$id.mp4",
                    mediaType = "video/mp4",
                    plaintextSize = body.size.toULong(),
                    dim = null,
                    thumbhash = null,
                    durationSeconds = null,
                    waveformSamples = emptyList(),
                    fileBacked = true,
                )
            }
        val draft =
            SelectedMessageDraftContentFfi("group", "caption", null, attachments, 1L, 1L)
                .takeIf { bodies.isNotEmpty() }
        return SelectedMessageDraftFfi(revision, draft)
    }

    /** Overrides answer directly, so MarmotKit's exceptions reach the code under test unwrapped. */
    private inner class Engine : MarmotInterface by unexpectedCopyCalls() {
        /** Returns the draft under its current revision. */
        override fun selectedMessageDraft(
            accountRef: String,
            groupIdHex: String,
        ): SelectedMessageDraftFfi = selected()

        /** Reads one chunk of [attachmentId] under the current revision only. */
        override suspend fun readMessageDraftAttachment(
            accountRef: String,
            revision: MessageDraftRevisionFfi,
            attachmentId: String,
            offset: ULong,
            limit: UInt,
        ): ByteArray? {
            reading = attachmentId
            beforeChunk(offset)
            if (revision !== this@CopyEngine.revision) throw MarmotKitException.MessageDraftRevisionConflict()
            val body = bodies[attachmentId] ?: return null
            val start = offset.toInt().coerceAtMost(body.size)
            val end = minOf(body.size, start + minOf(chunk, limit.toInt()))
            return body.copyOfRange(start, end)
        }

        /** No send was ever admitted under any token. */
        override fun localSendStatus(
            accountRef: String,
            groupIdHex: String,
            clientToken: String,
        ): LocalSendStatusFfi? = null

        /** Refuses the references for a stale epoch, as MDK does once the group moved past the upload's epoch. */
        override suspend fun sendPreparedMediaWithClientToken(
            accountRef: String,
            groupIdHex: String,
            attachments: List<MediaAttachmentReferenceFfi>,
            caption: String?,
            clientToken: String,
            consumingDraft: MessageDraftRevisionFfi?,
        ): LocalSendAcceptanceFfi {
            check(refuseAdmission) { "unexpected admission" }
            throw MarmotKitException.MediaReferenceStaleEpoch(sourceEpoch = 3uL, currentEpoch = 4uL)
        }
    }
}

/** A fresh draft revision handle that needs no native library. */
private fun newRevision(): MessageDraftRevisionFfi = nativeHandleStub(MessageDraftRevisionFfi::class.java)

/** Fails any binding call a copy test did not expect. */
private fun unexpectedCopyCalls(): MarmotInterface =
    nativeBoundary { method, _ ->
        error("unexpected native call $method")
    }
