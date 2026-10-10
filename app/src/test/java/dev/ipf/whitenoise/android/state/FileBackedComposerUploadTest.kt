package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.MediaFileTransferControlFfi
import dev.ipf.marmotkit.NoPointer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class FileBackedComposerUploadTest {
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
