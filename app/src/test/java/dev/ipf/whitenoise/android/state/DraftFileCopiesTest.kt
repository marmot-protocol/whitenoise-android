package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.MarmotInterface
import dev.ipf.marmotkit.MarmotKitException
import dev.ipf.marmotkit.MessageDraftRevisionFfi
import dev.ipf.marmotkit.SelectedMessageDraftAttachmentFfi
import dev.ipf.marmotkit.SelectedMessageDraftContentFfi
import dev.ipf.marmotkit.SelectedMessageDraftFfi
import dev.ipf.whitenoise.android.media.editor.nativeHandleStub
import dev.ipf.whitenoise.android.ui.conversation.media.StagedUploadSource
import kotlinx.coroutines.runBlocking
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

            val copies = engine.marmot.copyDraftFiles("acct", "group", listOf(storedPick("clip", 3L)), folder.root)

            assertArrayEquals(byteArrayOf(0, 1, 2), checkNotNull(copies).getValue("clip").file.readBytes())
        }

    /** A file the draft no longer holds, or loses mid-copy, gives no copies and leaves no file behind. */
    @Test
    fun missingFileCopiesNothing() =
        runBlocking {
            val engine = CopyEngine(mapOf("a" to byteArrayOf(1, 2), "b" to byteArrayOf(3, 4)), chunk = 1)
            engine.beforeChunk = { offset -> if (offset == 1uL && engine.reading == "b") engine.clear() }

            val picks = listOf(storedPick("a", 2L), storedPick("b", 2L))
            val copies = engine.marmot.copyDraftFiles("acct", "group", picks, folder.root)

            assertNull(copies)
            assertEquals(
                emptyList<String>(),
                folder.root
                    .list()
                    .orEmpty()
                    .toList(),
            )
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
        val copies = engine.marmot.copyDraftFiles("acct", "group", retained.attachments, folder.root)
        return checkNotNull(copies)
    }

    /** A pick the draft stores as file [id] of [size] bytes. */
    private fun storedPick(
        id: String,
        size: Long,
    ) = PendingAttachment(ByteArray(0), "video/mp4", "$id.mp4", draftFile = DraftStoredFile(id, size))
}

/** A draft holding stored files in memory, read in [chunk]-byte pieces under its current revision. */
private class CopyEngine(
    files: Map<String, ByteArray>,
    private val chunk: Int = Int.MAX_VALUE,
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
    }
}

/** A fresh draft revision handle that needs no native library. */
private fun newRevision(): MessageDraftRevisionFfi = nativeHandleStub(MessageDraftRevisionFfi::class.java)

/** Fails any binding call a copy test did not expect. */
private fun unexpectedCopyCalls(): MarmotInterface =
    nativeBoundary { method, _ ->
        error("unexpected native call $method")
    }
