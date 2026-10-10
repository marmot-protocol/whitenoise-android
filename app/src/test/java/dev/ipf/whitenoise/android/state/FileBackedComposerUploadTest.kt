package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.MediaFileTransferControlFfi
import dev.ipf.marmotkit.NoPointer
import dev.ipf.whitenoise.android.ui.conversation.media.StagedDocumentRead
import dev.ipf.whitenoise.android.ui.conversation.media.readStagedDocument
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
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
}

/** A native control stand-in that records cancel and release instead of reaching Rust. */
private class RecordingControl : MediaFileTransferControlFfi(NoPointer) {
    val events = mutableListOf<String>()

    /** Records the cancel the native control would receive. */
    override fun cancel() {
        events += "cancel"
    }

    /** Records the release without freeing a native handle. */
    override fun close() {
        events += "close"
    }
}
