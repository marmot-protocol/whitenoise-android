package dev.ipf.whitenoise.android.state

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import dev.ipf.whitenoise.android.media.AttachmentPlaintext
import dev.ipf.whitenoise.android.media.AttachmentTooLargeToPresentException
import dev.ipf.whitenoise.android.media.DiskByteCacheLease
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.OutputStream
import java.io.RandomAccessFile
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Pins the retained-plaintext read to a worker thread and to the account and session that started it. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RetainedAttachmentReadTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var appState: WhiteNoiseAppState

    /** Creates only local test state; the native runtime must never be reached. */
    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        appState =
            WhiteNoiseAppState(
                context = ApplicationProvider.getApplicationContext<Context>(),
                draftStore = DraftStore(EmptyDraftPersistence),
                accountIdHexResolver = { null },
                accounts = emptyList(),
                activeAccountRef = ACCOUNT,
            )
    }

    /** Restores the shared dispatcher after every bounded scenario. */
    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /** A file-sized read must run on a worker, never on the thread that renders the tile. */
    @Test
    fun materializationRunsOffTheMainThread() =
        runTest(dispatcher) {
            val plaintext = RecordingPlaintext(BYTES)

            val result = appState.readRetainedAttachmentBytes(ACCOUNT) { plaintext }

            assertArrayEquals(BYTES, result)
            assertNotNull("the plaintext was never read", plaintext.copiedOn)
            val mainThread = Looper.getMainLooper().thread
            assertTrue("the plaintext was read on the main thread", plaintext.copiedOn !== mainThread)
            assertTrue("the plaintext was not released", plaintext.closed)
        }

    /** A switch to another account while the bytes are being read must not hand them to the old account's tile. */
    @Test
    fun accountSwitchDuringTheReadRejectsTheBytes() =
        runTest(dispatcher) {
            val plaintext = ParkedPlaintext(BYTES)
            val read = async { appState.readRetainedAttachmentBytes(ACCOUNT) { plaintext } }
            plaintext.reading.await()

            appState.switchMediaSessionForTest(OTHER_ACCOUNT)
            plaintext.release()

            assertNull("old-account bytes were returned after the account changed", read.await())
            assertTrue("the rejected plaintext was not released", plaintext.closed)
        }

    /** A session reset that keeps the same account, such as a wipe and sign-back-in, still rejects the old read. */
    @Test
    fun sessionResetDuringTheReadRejectsTheBytes() =
        runTest(dispatcher) {
            val plaintext = ParkedPlaintext(BYTES)
            val read = async { appState.readRetainedAttachmentBytes(ACCOUNT) { plaintext } }
            plaintext.reading.await()

            appState.switchMediaSessionForTest(null)
            plaintext.release()

            assertEquals(ACCOUNT, appState.activeAccountRef)
            assertNull("bytes were returned across a media session reset", read.await())
            assertTrue(plaintext.closed)
        }

    /** A switch that lands before the native open returns is rejected as well. */
    @Test
    fun accountSwitchDuringTheOpenRejectsTheBytes() =
        runTest(dispatcher) {
            val plaintext = RecordingPlaintext(BYTES)

            val result =
                appState.readRetainedAttachmentBytes(ACCOUNT) {
                    appState.switchMediaSessionForTest(OTHER_ACCOUNT)
                    plaintext
                }

            assertNull(result)
            assertTrue(plaintext.closed)
        }

    /** A conversation for an account that is no longer active never starts the native open. */
    @Test
    fun staleAccountNeverOpensTheNativeSource() =
        runTest(dispatcher) {
            var opened = false

            val result =
                appState.readRetainedAttachmentBytes(OTHER_ACCOUNT) {
                    opened = true
                    RecordingPlaintext(BYTES)
                }

            assertNull(result)
            assertFalse("a stale account opened the retained source", opened)
        }

    /** A missing retained asset reads as null rather than failing the tile. */
    @Test
    fun missingRetainedAssetIsNull() =
        runTest(dispatcher) {
            assertNull(appState.readRetainedAttachmentBytes(ACCOUNT) { null })
            assertEquals(ACCOUNT, appState.activeAccountRef)
        }

    /** A retained asset above the budget is rejected by its declared size before it is read, and released. */
    @Test
    fun anOversizedRetainedAssetIsRejectedBeforeItIsRead() =
        runTest(dispatcher) {
            val plaintext = UnreadablePlaintext(size = SMALL_BUDGET_BYTES + 1)

            val failure =
                runCatching {
                    appState.readRetainedAttachmentBytes(ACCOUNT, maxBytes = SMALL_BUDGET_BYTES) { plaintext }
                }.exceptionOrNull()

            assertTrue(
                "expected a typed too-large failure, got $failure",
                failure is AttachmentTooLargeToPresentException,
            )
            assertEquals(SMALL_BUDGET_BYTES + 1, (failure as AttachmentTooLargeToPresentException).declaredBytes)
            assertFalse("the oversized plaintext was read", plaintext.read)
            assertTrue("the rejected lease was not released", plaintext.closed)
        }

    /** Without an explicit budget the presentation ceiling applies: one byte over is rejected, exactly at it reads. */
    @Test
    fun theDefaultBudgetIsThePresentationCeiling() =
        runTest(dispatcher) {
            val over = UnreadablePlaintext(size = ATTACHMENT_PRESENTATION_MAX_BYTES + 1)
            val rejected = runCatching { appState.readRetainedAttachmentBytes(ACCOUNT) { over } }.exceptionOrNull()
            assertTrue(rejected is AttachmentTooLargeToPresentException)
            assertTrue(over.closed)

            val exact =
                File.createTempFile("retained-exact", ".lease").also { file ->
                    RandomAccessFile(file, "rw").use { it.setLength(ATTACHMENT_PRESENTATION_MAX_BYTES) }
                }
            val bytes =
                appState.readRetainedAttachmentBytes(ACCOUNT) { AttachmentPlaintext.Lease(DiskByteCacheLease(exact)) }

            assertEquals(ATTACHMENT_PRESENTATION_MAX_BYTES, bytes?.size?.toLong())
            assertFalse("the lease was not released after the read", exact.exists())
        }

    /** Cancelling the read mid-copy abandons the copy, releases the lease and hands nothing back. */
    @Test
    fun cancellationDuringTheReadReleasesTheLeaseAndPublishesNothing() =
        runTest(dispatcher) {
            val plaintext = ParkedPlaintext(BYTES)
            val read = async { appState.readRetainedAttachmentBytes(ACCOUNT) { plaintext } }
            plaintext.reading.await()

            read.cancel()
            plaintext.release()

            val outcome = runCatching { read.await() }.exceptionOrNull()
            assertTrue("a cancelled read must complete cancelled, got $outcome", outcome is CancellationException)
            assertFalse("the cancelled copy still wrote its bytes", plaintext.completed)
            assertTrue("the cancelled lease was not released", plaintext.closed)
        }

    /** Records which thread streamed the bytes and whether it was released. */
    private open class RecordingPlaintext(
        private val bytes: ByteArray,
    ) : AttachmentPlaintext {
        @Volatile var copiedOn: Thread? = null

        @Volatile var closed = false

        /** True once the bytes were handed to the sink and accepted. */
        @Volatile var completed = false

        override val size: Long = bytes.size.toLong()

        /** Streams the bytes after noting the caller's thread and running the mid-read hook. */
        override fun copyTo(output: OutputStream) {
            copiedOn = Thread.currentThread()
            beforeWrite()
            output.write(bytes)
            completed = true
        }

        /** Runs after the thread is noted and before the bytes are written. */
        protected open fun beforeWrite() = Unit

        /** Notes that the lease was released. */
        override fun close() {
            closed = true
        }
    }

    /** Parks the worker mid-read so the test can change the session before the bytes are handed back. */
    private class ParkedPlaintext(
        bytes: ByteArray,
    ) : RecordingPlaintext(bytes) {
        val reading = CompletableDeferred<Unit>()
        private val released = CountDownLatch(1)

        /** Lets the parked worker finish its read. */
        fun release() = released.countDown()

        /** Announces the read has started, then waits for [release]. */
        override fun beforeWrite() {
            reading.complete(Unit)
            check(released.await(PARK_TIMEOUT_SECONDS, TimeUnit.SECONDS)) { "the parked read was never released" }
        }
    }

    /** Declares [size] and records whether anything tried to read it, which an oversized asset must not allow. */
    private class UnreadablePlaintext(
        override val size: Long,
    ) : AttachmentPlaintext {
        @Volatile var read = false

        @Volatile var closed = false

        /** Records the read attempt and fails, because an oversized declaration must be rejected first. */
        override fun copyTo(output: OutputStream) {
            read = true
            error("an oversized plaintext must be rejected before it is read")
        }

        /** Notes that the lease was released. */
        override fun close() {
            closed = true
        }
    }

    /** Persists nothing, so the state never touches the real draft store. */
    private object EmptyDraftPersistence : DraftPersistence {
        override fun read(): Map<String, String> = emptyMap()

        override fun write(
            key: String,
            value: String?,
        ) = Unit
    }

    private companion object {
        const val ACCOUNT = "sample-account"
        const val OTHER_ACCOUNT = "other-account"
        const val PARK_TIMEOUT_SECONDS = 10L
        const val SMALL_BUDGET_BYTES = 16L
        val BYTES = byteArrayOf(1, 2, 3, 4)
    }
}
