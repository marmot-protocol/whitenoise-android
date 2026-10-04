package dev.ipf.whitenoise.android.state

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import dev.ipf.whitenoise.android.media.AttachmentPlaintext
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
import java.io.OutputStream
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

    /** Records which thread streamed the bytes and whether it was released. */
    private open class RecordingPlaintext(
        private val bytes: ByteArray,
    ) : AttachmentPlaintext {
        @Volatile var copiedOn: Thread? = null

        @Volatile var closed = false

        override val size: Long = bytes.size.toLong()

        /** Streams the bytes after noting the caller's thread and running the mid-read hook. */
        override fun copyTo(output: OutputStream) {
            copiedOn = Thread.currentThread()
            beforeWrite()
            output.write(bytes)
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
        val BYTES = byteArrayOf(1, 2, 3, 4)
    }
}
