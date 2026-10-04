package dev.ipf.whitenoise.android.state

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.marmotkit.EncryptedMediaVersionFfi
import dev.ipf.marmotkit.MarmotInterface
import dev.ipf.marmotkit.MediaAttachmentReferenceFfi
import dev.ipf.marmotkit.MediaDownloadResultFfi
import dev.ipf.marmotkit.MediaLocatorFfi
import dev.ipf.whitenoise.android.diagnostics.PerformanceLayer
import dev.ipf.whitenoise.android.diagnostics.PerformanceOperation
import dev.ipf.whitenoise.android.diagnostics.PerformancePhase
import dev.ipf.whitenoise.android.diagnostics.PerformanceResult
import dev.ipf.whitenoise.android.diagnostics.PerformanceTrace
import dev.ipf.whitenoise.android.media.AttachmentPlaintext
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.OutputStream
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.Continuation

/**
 * Forward materialization reads MarmotKit's retained copy of the source attachment, the canonical local store,
 * before it asks the network, and still downloads when no layer holds the bytes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en")
class ForwardRetainedSourceTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val downloads = AtomicInteger(0)

    @Suppress("UNCHECKED_CAST")
    private val marmot =
        Proxy.newProxyInstance(
            MarmotInterface::class.java.classLoader,
            arrayOf(MarmotInterface::class.java),
        ) { proxy, method, arguments ->
            when (method.name) {
                "downloadMedia" -> {
                    downloads.incrementAndGet()
                    MediaDownloadResultFfi(
                        plaintext = DOWNLOADED,
                        fileName = "notes.txt",
                        mediaType = "text/plain",
                        sizeBytes = DOWNLOADED.size.toULong(),
                    )
                }
                "accountUnreadSummary", "chatList" -> emptyList<Any>()
                "toString" -> "ForwardRetainedSourceMarmotFake"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === arguments?.firstOrNull()
                else ->
                    if (arguments?.lastOrNull() is Continuation<*>) {
                        error("Unexpected Marmot call: ${method.name}")
                    } else {
                        throw UnsupportedOperationException("Unexpected Marmot call: ${method.name}")
                    }
            }
        } as MarmotInterface

    private data class Recorded(
        val phase: PerformancePhase,
        val result: PerformanceResult,
        val layer: PerformanceLayer,
    )

    /** The whole-file read of a retained lease must not run on the main thread a forward session starts on. */
    @Test
    fun retainedLeaseIsCopiedOffTheMainThreadAndReleased() {
        val lease = RecordingLease(DOWNLOADED)

        val bytes = runBlocking { readRetainedForwardPlaintext { lease } }

        assertArrayEquals(DOWNLOADED, bytes)
        val copyThread = checkNotNull(lease.copiedOn) { "the lease was never copied" }
        assertTrue("the lease was copied on the main thread", copyThread !== Looper.getMainLooper().thread)
        assertTrue("the lease was not released", lease.closed)
    }

    /** An absent lease reads as null, and a copy that fails still releases the lease. */
    @Test
    fun absentOrFailingRetainedLeaseIsNullOrReleased() {
        assertNull(runBlocking { readRetainedForwardPlaintext { null } })

        val failing = RecordingLease(DOWNLOADED, failCopy = true)
        runCatching { runBlocking { readRetainedForwardPlaintext { failing } } }

        assertTrue("a failed copy left the lease open", failing.closed)
    }

    /** Records the thread that copied it and whether it was closed, like the lease the viewer reads. */
    private class RecordingLease(
        private val bytes: ByteArray,
        private val failCopy: Boolean = false,
    ) : AttachmentPlaintext {
        @Volatile var copiedOn: Thread? = null

        @Volatile var closed = false

        override val size: Long = bytes.size.toLong()

        /** Notes the copying thread, then streams the bytes or fails as scripted. */
        override fun copyTo(output: OutputStream) {
            copiedOn = Thread.currentThread()
            check(!failCopy) { "scripted copy failure" }
            output.write(bytes)
        }

        /** Notes that the lease was released. */
        override fun close() {
            closed = true
        }
    }

    /** A retained copy is served without any network download and the lookup is attributed to MarmotKit. */
    @Test
    fun retainedSourceIsReadFromNativeRetentionInsteadOfDownloading() {
        val appState = appState()
        val events = mutableListOf<Recorded>()
        val diagnostics = diagnostics(events)
        val retained = byteArrayOf(9, 8, 7)

        val plaintext =
            await(
                appState.mutationsScope.async {
                    appState.materializeAttachmentPlaintextIsolated(request(), reference(), diagnostics) { retained }
                },
            )

        assertArrayEquals(retained, plaintext)
        assertEquals(0, downloads.get())
        assertEquals(
            listOf(Recorded(PerformancePhase.FORWARD_SOURCE_LOOKUP, PerformanceResult.SUCCESS, PerformanceLayer.MDK)),
            events,
        )
    }

    /** With no cached or retained copy the forward still downloads, and the lookup closes as a miss. */
    @Test
    fun missingSourceFallsBackToTheNativeDownload() {
        val appState = appState()
        val events = mutableListOf<Recorded>()
        val diagnostics = diagnostics(events)

        val plaintext =
            await(
                appState.mutationsScope.async {
                    appState.materializeAttachmentPlaintextIsolated(request(), reference(), diagnostics) { null }
                },
            )

        assertArrayEquals(DOWNLOADED, plaintext)
        assertEquals(1, downloads.get())
        assertEquals(
            listOf(
                Recorded(PerformancePhase.FORWARD_SOURCE_LOOKUP, PerformanceResult.PENDING, PerformanceLayer.STORAGE),
                Recorded(
                    PerformancePhase.FORWARD_SOURCE_DOWNLOAD_START,
                    PerformanceResult.PENDING,
                    PerformanceLayer.MDK,
                ),
                Recorded(
                    PerformancePhase.FORWARD_SOURCE_DOWNLOAD_RETURN,
                    PerformanceResult.SUCCESS,
                    PerformanceLayer.MDK,
                ),
            ),
            events,
        )
    }

    /** A failing retained read never fails the forward; it falls back to the download. */
    @Test
    fun failingRetainedReadFallsBackToTheNativeDownload() {
        val appState = appState()

        val plaintext =
            await(
                appState.mutationsScope.async {
                    appState.materializeAttachmentPlaintextIsolated(request(), reference()) {
                        error("retained read failed")
                    }
                },
            )

        assertArrayEquals(DOWNLOADED, plaintext)
        assertEquals(1, downloads.get())
    }

    /** Builds a diagnostics owner whose recorder appends only phase, result and layer. */
    private fun diagnostics(events: MutableList<Recorded>): ForwardDiagnostics =
        requireNotNull(
            ForwardDiagnostics.begin(
                nowMs = { 0L },
                begin = {
                    PerformanceTrace(
                        PerformanceOperation.MESSAGE_FORWARD,
                        sessionGeneration = 1L,
                        operationId = 1L,
                        startedAtMs = 0L,
                    )
                },
                record = { _, phase, _, _, result, layer, _ -> events += Recorded(phase, result, layer) },
            ),
        )

    /** Pumps the main looper until the main-confined materialization finishes. */
    private fun <T> await(
        deferred: Deferred<T>,
        timeoutMillis: Long = 20_000,
    ): T {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (deferred.isCompleted) return deferred.getCompleted()
            Thread.sleep(5)
        }
        error("materialization did not finish")
    }

    /** Builds an app state wired to the scripted engine proxy. */
    private fun appState(): WhiteNoiseAppState =
        WhiteNoiseAppState(
            context = context,
            draftStore = DraftStore.forContext(context),
            accountIdHexResolver = { null },
            accounts = listOf(account()),
            activeAccountRef = ACCOUNT,
        ).also { state ->
            WhiteNoiseAppState::class.java
                .getDeclaredField("marmotRuntime")
                .apply { isAccessible = true }
                .set(state, AppMarmotRuntime(rootPath = "test", marmot = marmot))
        }

    /** Builds one signed-in signing-account summary. */
    private fun account() =
        AccountSummaryFfi(
            label = ACCOUNT,
            accountIdHex = "ab".repeat(32),
            localSigning = true,
            externalSigning = false,
            signedOut = false,
            running = true,
        )

    /** The forward's source request, without a source id, exactly as the production transport builds it. */
    private fun request() = AttachmentTransferRequest(ACCOUNT, SOURCE_GROUP, SOURCE_MESSAGE, 0)

    /** One complete authoritative media reference. */
    private fun reference() =
        MediaAttachmentReferenceFfi(
            locators = listOf(MediaLocatorFfi(kind = "blossom-v1", value = "https://media.example/notes.txt")),
            ciphertextSha256 = "a".repeat(64),
            plaintextSha256 = "b".repeat(64),
            nonceHex = "c".repeat(24),
            fileName = "notes.txt",
            mediaType = "text/plain",
            version = EncryptedMediaVersionFfi.V1,
            sourceEpoch = 4uL,
            dim = null,
            thumbhash = null,
        )

    private companion object {
        const val ACCOUNT = "forward-retained-account"
        const val SOURCE_GROUP = "f1e2d3c4b5a69788"
        const val SOURCE_MESSAGE = "0f1e2d3c4b5a6978"
        val DOWNLOADED = byteArrayOf(1, 2, 3, 4)
    }
}
