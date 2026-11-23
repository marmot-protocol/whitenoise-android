package dev.ipf.whitenoise.android.ui.conversation

import android.net.Uri
import dev.ipf.marmotkit.MessageDraftAttachmentFfi
import dev.ipf.whitenoise.android.media.editor.EditorSessionStore
import dev.ipf.whitenoise.android.media.editor.FileBackedDraftNative
import dev.ipf.whitenoise.android.media.editor.InMemoryEditorStrings
import dev.ipf.whitenoise.android.media.editor.MarmotMessageDraftGateway
import dev.ipf.whitenoise.android.media.editor.MessageDraftRepository
import dev.ipf.whitenoise.android.media.editor.RevisionedMessageDraft
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** Pins that a restore attempt owns the plaintext files it writes until the composer adopts them. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class DraftRestoreAttemptTest {
    @get:Rule
    val folder = TemporaryFolder()

    /** A restore cancelled while copying its first stored file leaves neither that file nor its partial copy. */
    @Test
    fun cancelledDuringTheFirstFileLeavesNothing() =
        runTest {
            val native = FileBackedDraftNative("caption").apply { holdFile("a", 3uL, byteArrayOf(1, 2, 3)) }
            val gate = CompletableDeferred<Unit>()
            native.beforeChunk = { _, offset -> if (offset > 0L) gate.await() }
            val (attempt, read) = attemptFor(native)

            val restore = launch { attempt.materialize(attachment(read, "a")) }
            runCurrent()
            assertEquals(listOf("a.partial"), presentationFiles())
            restore.cancelAndJoin()
            attempt.releaseUnadopted()

            assertEquals(emptyList<String>(), presentationFiles())
        }

    /** A restore cancelled after its first of two files finished removes the finished file and the second's partial. */
    @Test
    fun cancelledAfterTheFirstOfTwoFilesLeavesNothing() =
        runTest {
            val native =
                FileBackedDraftNative("caption").apply {
                    holdFile("a", 2uL, byteArrayOf(1, 2))
                    holdFile("b", 3uL, byteArrayOf(3, 4, 5))
                }
            val gate = CompletableDeferred<Unit>()
            native.beforeChunk = { id, offset -> if (id == "b" && offset > 0L) gate.await() }
            val (attempt, read) = attemptFor(native)

            val restore = launch { listOf("a", "b").forEach { attempt.materialize(attachment(read, it)) } }
            runCurrent()
            assertEquals(listOf("a", "b.partial"), presentationFiles())
            restore.cancelAndJoin()
            attempt.releaseUnadopted()

            assertEquals(emptyList<String>(), presentationFiles())
        }

    /** An adopted restore keeps its files, inline and stored, with their full bodies. */
    @Test
    fun adoptedRestoreKeepsItsFiles() =
        runTest {
            val native =
                FileBackedDraftNative("caption").apply {
                    holdInline("photo", byteArrayOf(7))
                    holdFile("clip", 3uL, byteArrayOf(1, 2, 3))
                }
            val (attempt, read) = attemptFor(native)

            assertNotNull(attempt.materialize(attachment(read, "photo")))
            assertNotNull(attempt.materialize(attachment(read, "clip")))
            attempt.adopt()
            attempt.releaseUnadopted()

            assertEquals(listOf("clip", "photo"), presentationFiles())
            assertArrayEquals(byteArrayOf(1, 2, 3), File(folder.root, "clip").readBytes())
        }

    /** A stored file already on disk at full size is reused, never rewritten, and an unadopted attempt keeps it. */
    @Test
    fun existingFileIsReusedAndNeverDeleted() =
        runTest {
            val native = FileBackedDraftNative("caption").apply { holdFile("clip", 3uL, byteArrayOf(1, 2, 3)) }
            File(folder.root, "clip").writeBytes(byteArrayOf(9, 9, 9))
            val (attempt, read) = attemptFor(native)

            assertNotNull(attempt.materialize(attachment(read, "clip")))
            attempt.releaseUnadopted()

            assertArrayEquals(byteArrayOf(9, 9, 9), File(folder.root, "clip").readBytes())
            assertEquals(0, native.calls.count { it == "readMessageDraftAttachment" })
        }

    /** A restore attempt over [native]'s draft writing into the test folder, with the read it restores from. */
    private suspend fun TestScope.attemptFor(native: FileBackedDraftNative): RestoreUnderTest {
        val drafts =
            MessageDraftRepository(
                gateway = MarmotMessageDraftGateway { native.marmot },
                editorSessions = EditorSessionStore(InMemoryEditorStrings()),
                ioDispatcher = UnconfinedTestDispatcher(testScheduler),
            )
        val read = drafts.files.revisioned("acct", "group").getOrThrow()
        return RestoreUnderTest(DraftRestoreAttempt(FolderPresentation(folder.root), drafts, "acct", read), read)
    }

    /** The attachment [id] as the restore read it. */
    private fun attachment(
        read: RevisionedMessageDraft,
        id: String,
    ): MessageDraftAttachmentFfi = read.draft!!.mediaAttachments.single { it.id == id }

    /** The names of the files in the presentation folder, sorted. */
    private fun presentationFiles(): List<String> =
        folder.root
            .list()
            .orEmpty()
            .sorted()
}

/** One restore attempt and the draft read it restores from. */
private data class RestoreUnderTest(
    val attempt: DraftRestoreAttempt,
    val read: RevisionedMessageDraft,
)

/** Names each presentation file after its attachment id in [directory]. */
private class FolderPresentation(
    private val directory: File,
) : DraftPresentation {
    /** The file named after [attachment]'s id. */
    override fun fileFor(attachment: MessageDraftAttachmentFfi): File = File(directory, attachment.id)

    /** A plain file URI for [file]. */
    override fun uriFor(
        file: File,
        attachment: MessageDraftAttachmentFfi,
    ): Uri = Uri.fromFile(file)
}
