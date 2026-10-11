package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.MediaFileTransferControlFfi
import dev.ipf.marmotkit.NoPointer
import dev.ipf.whitenoise.android.ui.conversation.media.StagedDocumentRead
import dev.ipf.whitenoise.android.ui.conversation.media.readStagedDocument
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream

class FileBackedComposerUploadTest {
    @get:Rule
    val temporary = TemporaryFolder()

    /**
     * An account switch that cancels the caller while staging runs still releases the staged batch: the
     * temporary snapshot of an in-memory item is deleted and the file-backed item's pin is dropped.
     */
    @Test
    fun cancellationDuringStagingStillReleasesTheStagedBatch() =
        runTest {
            val directory = temporary.newFolder("upload_sources")
            val read = readStagedDocument(directory, 8) { ByteArrayInputStream(ByteArray(8)) }
            val pinned = (read as StagedDocumentRead.Success).source
            val attachments =
                listOf(
                    PendingAttachment(byteArrayOf(1, 2, 3), "image/jpeg", "cover.jpg"),
                    PendingAttachment(ByteArray(0), "video/mp4", "clip.mp4", sourceFile = pinned),
                )
            var uploaded = false

            val call =
                launch {
                    coroutineContext.job.cancel()
                    withFileUploadRequest(attachments, caption = null, directory, maxCiphertextBytes = 1_000_000) {
                        uploaded = true
                    }
                }
            call.join()

            assertFalse(uploaded)
            assertEquals("only the send's own snapshot is left", listOf(pinned.file.name), directory.list()!!.toList())
            pinned.close()
            assertFalse("its pin was released, so closing it deletes the file", pinned.file.exists())
        }

    /** The control is exposed to Cancel only while the native call runs, and withdrawn before it is released. */
    @Test
    fun controlIsRegisteredForTheCallAndWithdrawnBeforeRelease() =
        runTest {
            val control = RecordingControl()

            val result =
                withNativeTransferControl(
                    register = { cancel -> control.events += if (cancel == null) "withdraw" else "register" },
                    newControl = { control },
                ) { used ->
                    assertSame(control, used)
                    control.events += "upload"
                    7
                }

            assertEquals(7, result)
            assertEquals(listOf("register", "upload", "withdraw", "close"), control.events)
        }

    /** Cancelling the caller, as an account switch does, cancels the native control before releasing it. */
    @Test
    fun cancellingTheCallerCancelsTheNativeControl() =
        runTest {
            val control = RecordingControl()
            val started = CompletableDeferred<Unit>()
            val call =
                launch {
                    withNativeTransferControl(register = {}, newControl = { control }) {
                        started.complete(Unit)
                        awaitCancellation()
                    }
                }
            started.await()

            call.cancelAndJoin()

            assertEquals(listOf("cancel", "close"), control.events)
        }

    /** A Cancel the user pressed while the send waited for the commit lock stops the control as it registers. */
    @Test
    fun aCancelRecordedBeforeTheTransferStopsItAsItRegisters() =
        runTest {
            val retained = RetainedMediaUpload(attachments = emptyList(), caption = null)
            val control = RecordingControl()
            retained.requestTransferCancel()

            withNativeTransferControl(retained::attachTransferCancel, newControl = { control }) {
                assertEquals("cancelled before any work", listOf("cancel"), control.events)
            }

            assertEquals(listOf("cancel", "close"), control.events)
        }

    /** While the call runs the counter is read every 200 ms, and once more as it ends, before release. */
    @Test
    fun progressIsReadWhileTheCallRunsAndOnceMoreAtTheEnd() =
        runBlocking {
            val control = RecordingControl()
            val heard = mutableListOf<Long>()

            withNativeTransferControl(register = {}, onProgress = { heard += it }, newControl = { control }) {
                control.counter = 10L
                delay(500)
                control.counter = 30L
            }

            assertTrue("the running counter was reported", 10L in heard)
            assertEquals("the final value is reported last", 30L, heard.last())
            assertEquals(listOf("close"), control.events)
        }

    /** A failing call still reports its final counter before the control is released, and never after. */
    @Test
    fun aFailedCallReportsItsLastCounterBeforeRelease() =
        runBlocking {
            val control = RecordingControl()
            val heard = mutableListOf<Long>()

            val failure =
                runCatching {
                    withNativeTransferControl(register = {}, onProgress = { heard += it }, newControl = { control }) {
                        control.counter = 7L
                        error("upload refused")
                    }
                }.exceptionOrNull()

            assertEquals("upload refused", failure?.message)
            assertEquals(7L, heard.last())
            assertEquals(listOf("close"), control.events)
        }

    /** Progress is display only: a counter that cannot be read stops the reports and the upload still returns. */
    @Test
    fun anUnreadableCounterNeverFailsTheUpload() =
        runBlocking {
            val control = RecordingControl().apply { readable = false }
            val heard = mutableListOf<Long>()

            val result =
                withNativeTransferControl(register = {}, onProgress = { heard += it }, newControl = { control }) {
                    delay(300)
                    "sent"
                }

            assertEquals("sent", result)
            assertTrue(heard.isEmpty())
            assertEquals("one failed read, never retried", 1, control.failedReads)
            assertEquals(listOf("close"), control.events)
        }

    /** Without a listener the counter is never read, so sends that show no progress pay nothing for it. */
    @Test
    fun withoutAListenerTheCounterIsNeverRead() =
        runTest {
            val control = RecordingControl()

            withNativeTransferControl(register = {}, newControl = { control }) { control.counter = 5L }

            assertTrue(control.reads.isEmpty())
        }
}

/** A native control stand-in that records cancel, release and counter reads instead of reaching Rust. */
private class RecordingControl : MediaFileTransferControlFfi(NoPointer) {
    val events = mutableListOf<String>()
    val reads = mutableListOf<Long>()

    @Volatile var counter = 0L

    @Volatile private var closed = false

    @Volatile var readable = true

    @Volatile var failedReads = 0

    /**
     * Returns the scripted counter, fails a read made after release as the native handle would, and fails
     * every read while the counter is made unreadable.
     */
    override fun processedBytes(): ULong {
        check(!closed) { "counter read after the control was released" }
        if (!readable) {
            failedReads += 1
            error("counter unavailable")
        }
        reads += counter
        return counter.toULong()
    }

    /** Records the cancel the native control would receive. */
    override fun cancel() {
        events += "cancel"
    }

    /** Records the release without freeing a native handle. */
    override fun close() {
        closed = true
        events += "close"
    }
}
