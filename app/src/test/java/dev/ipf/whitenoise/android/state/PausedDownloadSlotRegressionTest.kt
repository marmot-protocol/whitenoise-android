package dev.ipf.whitenoise.android.state

import dev.ipf.whitenoise.android.state.MediaDownloadIntegrationFixture.Companion.qualifiedRequest
import dev.ipf.whitenoise.android.state.MediaDownloadIntegrationFixture.Companion.reference
import dev.ipf.whitenoise.android.state.MediaDownloadIntegrationFixture.Companion.request
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A visible download that MDK pauses gives its gate slot to other downloads, and taps on it keep reaching MDK while
 * it is paused or queuing for a slot again (#3819). Runs at the production Android/native boundary.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PausedDownloadSlotRegressionTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var fixture: MediaDownloadIntegrationFixture

    /** Installs a deterministic main dispatcher and a private encrypted test cache. */
    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        fixture = MediaDownloadIntegrationFixture()
    }

    /** Releases only this test's work, files, and dispatcher. */
    @After
    fun tearDown() {
        try {
            if (::fixture.isInitialized) fixture.closeAndDrain(dispatcher)
        } finally {
            Dispatchers.resetMain()
        }
    }

    /**
     * A visible download MDK pauses gives its slot to the next download, keeps waiting for its own pause to end, and
     * finishes once it gets a slot again (#3819).
     */
    @Test
    fun aPausedVisibleDownloadFreesItsSlotUntilThePauseEnds() =
        runTest(dispatcher) {
            val visible = (0 until 4).map { index -> async { download(index) } }
            val held = List(4) { fixture.entered.receive() }
            val waiting = async { download(4) }
            runCurrent()
            assertTrue("all four slots are held", fixture.entered.tryReceive().isFailure)

            val paused = held.first()
            paused.pause()
            val next = withContext(Dispatchers.Default) { withTimeout(5_000L) { fixture.entered.receive() } }
            assertEquals("the paused download's slot went to the next one", reference(4), next.reference)

            (held.drop(1) + next).forEach { it.succeed(bytes(it.reference.fileName)) }
            paused.succeed(bytes(paused.reference.fileName))
            (visible + waiting).awaitAll().forEachIndexed { index, result ->
                assertArrayEquals(bytes(reference(index).fileName), result)
            }
            assertEquals(5, fixture.calls.size)
        }

    /**
     * A tap on a visible download that MDK paused, and that gave its slot away, still asks MDK for it explicitly,
     * which is what resumes a transfer paused by the automatic policy.
     */
    @Test
    fun tappingAPausedDownloadStillAsksMdkForIt() =
        runTest(dispatcher) {
            val visible = (0 until 4).map { index -> async { download(index) } }
            val held = List(4) { fixture.entered.receive() }
            val waiting = async { download(4) }
            runCurrent()
            val paused = held.single { it.reference == reference(0) }
            paused.pause()
            val next = withContext(Dispatchers.Default) { withTimeout(5_000L) { fixture.entered.receive() } }

            val tapped = async { download(0, AttachmentDownloadPriority.Interactive) }
            runCurrent()
            fixture.awaitExplicitDemands(1)

            (held - paused + next).forEach { it.succeed(bytes(it.reference.fileName)) }
            paused.succeed(bytes(paused.reference.fileName))
            assertArrayEquals(bytes(reference(0).fileName), tapped.await())
            (visible + waiting).awaitAll()
            assertEquals(5, fixture.calls.size)
        }

    /**
     * A tap on a download that resumed after its pause but is still queuing for a slot asks MDK for it explicitly, so
     * the transfer gains explicit priority instead of staying automatic and pausing again.
     */
    @Test
    fun tappingAResumedDownloadQueuingForASlotStillAsksMdkForIt() =
        runTest(dispatcher) {
            val visible = (0 until 4).map { index -> async { download(index) } }
            val held = List(4) { fixture.entered.receive() }
            val waiting = async { download(4) }
            runCurrent()
            val paused = held.single { it.reference == reference(0) }
            paused.pause()
            val next = withContext(Dispatchers.Default) { withTimeout(5_000L) { fixture.entered.receive() } }
            paused.resume()
            val key = qualifiedRequest(0).cacheKey()
            withContext(Dispatchers.Default) { withTimeout(5_000L) { while (!gate().isQueuedForTest(key)) delay(1) } }

            val tapped = async { download(0, AttachmentDownloadPriority.Interactive) }
            runCurrent()
            fixture.awaitExplicitDemands(1)

            (held - paused + next).forEach { it.succeed(bytes(it.reference.fileName)) }
            paused.succeed(bytes(paused.reference.fileName))
            assertArrayEquals(bytes(reference(0).fileName), tapped.await())
            (visible + waiting).awaitAll()
            assertEquals(5, fixture.calls.size)
        }

    /** The app's download gate, read the way the fixture injects its other private state. */
    private fun gate(): AttachmentDownloadGate =
        WhiteNoiseAppState::class.java
            .getDeclaredField("attachmentDownloadGate")
            .apply { isAccessible = true }
            .get(fixture.state) as AttachmentDownloadGate

    /** Downloads [index] as a visible bubble does, or as a tap with [priority] Interactive. */
    private suspend fun download(
        index: Int,
        priority: AttachmentDownloadPriority = AttachmentDownloadPriority.Automatic,
    ) = fixture.state.downloadAttachmentPlaintext(
        request(index),
        reference(index),
        priority,
        persistInteractiveIntent = false,
    )

    /** Distinct bounded payloads catch accidental cross-attachment result reuse. */
    private fun bytes(name: String): ByteArray = ByteArray(64 * 1024) { (name.hashCode() + it).toByte() }
}
