package dev.ipf.whitenoise.android.ui.settings

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/** Delayed native work must not publish a secret after the user cancels or loses ownership of its screen. */
@OptIn(ExperimentalCoroutinesApi::class)
class ProfileKeyOperationTest {
    /** Raw export completion after Cancel cannot open a picker, including non-cancellable native work. */
    @Test
    fun cancelledRawExportDoesNotOpenTheFilePicker() =
        runTest {
            val operation = ProfileKeyOperation()
            val deferred = DeferredSecret()
            val pickerContents = mutableListOf<String?>()
            operation.start(this, { true }, deferred::load, pickerContents::add)
            runCurrent()

            operation.cancel()
            deferred.complete("raw test key")
            runCurrent()

            assertTrue(pickerContents.isEmpty())
        }

    /** A cancelled encrypted export cannot replace a newer export or finish its busy state. */
    @Test
    fun lateEncryptedExportCannotPublishIntoANewerSession() =
        runTest {
            val operation = ProfileKeyOperation()
            val old = DeferredSecret()
            val current = DeferredSecret()
            val pickerContents = mutableListOf<String?>()
            var finished = 0
            operation.start(this, { true }, old::load, pickerContents::add) { finished++ }
            runCurrent()
            operation.cancel()
            operation.start(this, { true }, current::load, pickerContents::add) { finished++ }
            runCurrent()

            old.complete("cancelled encrypted backup")
            runCurrent()
            assertTrue(pickerContents.isEmpty())
            assertEquals(0, finished)

            current.complete("current encrypted backup")
            runCurrent()
            assertEquals(listOf("current encrypted backup"), pickerContents)
            assertEquals(1, finished)
        }

    /** A copy completion cannot write to the clipboard after the screen has stopped. */
    @Test
    fun backgroundedCopyDiscardsALateSecret() =
        runTest {
            val operation = ProfileKeyOperation()
            val deferred = DeferredSecret()
            var foreground = true
            var clipboard: String? = null
            operation.start(this, { foreground }, deferred::load, { clipboard = it })
            runCurrent()

            foreground = false
            deferred.complete("late private key")
            runCurrent()

            assertEquals(null, clipboard)
        }

    /** A reveal completion cannot expose the previous account's key before recomposition disposes its screen. */
    @Test
    fun switchedAccountRejectsTheOldRevealResult() =
        runTest {
            val operation = ProfileKeyOperation()
            val deferred = DeferredSecret()
            var activeAccount = "account-a"
            var revealed: String? = null
            operation.start(this, { activeAccount == "account-a" }, deferred::load, { revealed = it })
            runCurrent()

            activeAccount = "account-b"
            deferred.complete("account-a test key")
            runCurrent()

            assertEquals(null, revealed)
        }

    /** Returning to the foreground cannot reauthorize a result from the operation cancelled on Stop. */
    @Test
    fun stopAndResumeDoesNotReviveACancelledReveal() =
        runTest {
            val operation = ProfileKeyOperation()
            val deferred = DeferredSecret()
            var revealed: String? = null
            operation.start(this, { true }, deferred::load, { revealed = it })
            runCurrent()
            operation.cancel()

            deferred.complete("late reveal after resume")
            runCurrent()

            assertEquals(null, revealed)
        }

    /** Repeated taps do not issue another native request while one result is pending. */
    @Test
    fun pendingSecretOperationRejectsReentry() =
        runTest {
            val operation = ProfileKeyOperation()
            val deferred = DeferredSecret()
            var delivered = 0
            assertTrue(operation.start(this, { true }, deferred::load, { delivered++ }))
            runCurrent()
            assertFalse(operation.start(this, { true }, { error("Duplicate native call") }, { delivered++ }))
            deferred.complete("test key")
            runCurrent()

            assertEquals(1, delivered)
        }

    /** Models a native completion that resumes even after its calling coroutine was cancelled. */
    private class DeferredSecret {
        private lateinit var continuation: Continuation<String?>

        /** Deliberately uses an uncancellable suspension to exercise the publication guard. */
        suspend fun load(): String? = suspendCoroutine { continuation = it }

        /** Delivers one synthetic secret without involving platform keys or a native runtime. */
        fun complete(value: String) {
            continuation.resume(value)
        }
    }
}
