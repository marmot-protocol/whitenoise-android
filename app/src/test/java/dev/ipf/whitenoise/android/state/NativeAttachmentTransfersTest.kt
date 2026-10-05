package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.AttachmentTransferStateFfi
import dev.ipf.marmotkit.AttachmentTransferStatusFfi
import dev.ipf.whitenoise.android.functionBody
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

class NativeAttachmentTransfersTest {
    /** Only an explicit request with complete native identity can demand transfer. */
    @Test
    fun `native demand is reserved for explicit requests with exact identity`() {
        val target = NativeAttachmentTarget("11".repeat(32), "22".repeat(32), 3)

        assertTrue(shouldUseNativeExplicitDemand(AttachmentDownloadPriority.Interactive, target))
        assertFalse(shouldUseNativeExplicitDemand(AttachmentDownloadPriority.Automatic, target))
        assertFalse(shouldUseNativeExplicitDemand(AttachmentDownloadPriority.Interactive, null))
    }

    /** Native terminal and active states map without losing cancellation semantics. */
    @Test
    fun `native transfer states preserve terminal and active meaning`() {
        assertEquals(
            AttachmentTransferState.Available,
            status(AttachmentTransferStateFfi.READY).toPresentationState(),
        )
        assertEquals(
            AttachmentTransferState.Failed,
            status(AttachmentTransferStateFfi.FAILED).toPresentationState(),
        )
        assertEquals(
            AttachmentTransferState.Cancelled,
            status(AttachmentTransferStateFfi.CANCELLED).toPresentationState(),
        )
        assertEquals(
            AttachmentTransferState.Downloading,
            status(AttachmentTransferStateFfi.VERIFYING_PLAINTEXT).toPresentationState(),
        )
        assertEquals(
            AttachmentTransferState.Remote,
            status(AttachmentTransferStateFfi.POLICY_BLOCKED).toPresentationState(),
        )
    }

    /** Observer disposal closes only its feed; explicit cancellation owns native cancellation. */
    @Test
    fun `native acquisition teardown does not cancel shared durable work`() {
        val source = source().readText()
        val acquisition = source.functionBody("WhiteNoiseAppState.acquireNativeAttachment")
        val explicitCancellation = appStateSource().readText().functionBody("cancelAttachmentDownload")

        val observationIndex = acquisition.indexOf("awaitNativeAttachment(")
        val demandIndex = acquisition.indexOf("requestNativeInteractiveAttachment(")
        assertTrue(observationIndex >= 0 && demandIndex > observationIndex)
        assertTrue("owned?.close()" in source.functionBody("awaitNativeAttachment"))
        assertFalse("cancelNativeAttachment(" in acquisition)
        assertTrue("cancelNativeAttachmentBounded(request)" in explicitCancellation)
        assertTrue("withContext(NonCancellable)" in source)
        val bounded = cancellationSource().readText().substringBefore("/** Cancels the current native job")
        assertTrue("withContext(NonCancellable)" in bounded)
        assertTrue("withTimeoutOrNull(NATIVE_ATTACHMENT_CANCEL_TIMEOUT_MILLIS)" in bounded)
    }

    /** Cancellation after native subscribe returns still closes the newly owned feed. */
    @Test
    fun `cancelled subscription handoff closes native feed`() =
        runBlocking {
            val closed = AtomicBoolean()
            val owner =
                launch(start = CoroutineStart.UNDISPATCHED) {
                    val callerJob = currentCoroutineContext().job
                    awaitNativeAttachment(
                        open = {
                            callerJob.cancel()
                            object : NativeTransferFeed {
                                override suspend fun next() = yield().let { null }

                                override fun close() {
                                    closed.set(true)
                                }
                            }
                        },
                    ) { null }
                }

            owner.join()

            assertTrue(owner.isCancelled)
            assertTrue("cancellation stranded the native transfer subscription", closed.get())
        }

    /** Integrity/policy terminal states must never enter WorkManager's transient retry bucket. */
    @Test
    fun `native terminal failures are non retryable`() {
        val failure = NativeAttachmentTerminalException(AttachmentTransferStateFfi.FAILED)

        assertFalse(isTransientAttachmentDownloadFailure(failure))
        assertFalse(shouldRetryAttachmentDownloadWork(transientRetrySpent = false, failure = failure))
    }

    /** A canonical pre-admission snapshot proves cancellation needs no native command. */
    @Test
    fun cancellationBeforeAdmissionIsConfirmedWithoutACommand() =
        runBlocking {
            assertEquals(
                NativeAttachmentCancellationOutcome.NoWork,
                nativeAttachmentCancellationOutcome(status(AttachmentTransferStateFfi.NOT_REQUESTED)) {
                    error("No acquisition exists")
                },
            )
        }

    /** Missing identity or snapshot cannot establish that an actual native job has stopped. */
    @Test
    fun missingReferenceIsUnconfirmedExceptForCanonicalNoWork() =
        runBlocking {
            assertEquals(
                NativeAttachmentCancellationOutcome.Unconfirmed,
                nativeAttachmentCancellationOutcome(null) {
                    error("No identity exists")
                },
            )
            val phases = AttachmentTransferStateFfi.entries.filter { it != AttachmentTransferStateFfi.NOT_REQUESTED }
            for (phase in phases) {
                assertEquals(
                    NativeAttachmentCancellationOutcome.Unconfirmed,
                    nativeAttachmentCancellationOutcome(status(phase)) {
                        error("No reference exists")
                    },
                )
            }
        }

    /** The actual native command's Boolean remains authoritative when acquisition has a reference. */
    @Test
    fun nativeCancellationRejectionCannotClaimSuccess() =
        runBlocking {
            val current = status(AttachmentTransferStateFfi.DOWNLOADING).copy(reference = "native-body")
            assertEquals(
                NativeAttachmentCancellationOutcome.Acknowledged,
                nativeAttachmentCancellationOutcome(current) {
                    assertEquals("native-body", it)
                    true
                },
            )
            assertEquals(
                NativeAttachmentCancellationOutcome.Unconfirmed,
                nativeAttachmentCancellationOutcome(current) { false },
            )
        }

    /** Builds one transfer status fixture for presentation mapping. */
    private fun status(state: AttachmentTransferStateFfi) =
        AttachmentTransferStatusFfi(
            reference = null,
            state = state,
            attempt = 0u,
            received = 0u,
            total = null,
            retryAt = null,
        )

    /** Locates the production transfer owner in root- and module-scoped test layouts. */
    private fun source(): File =
        listOf(
            File("src/main/java/dev/ipf/whitenoise/android/state/NativeAttachmentTransfers.kt"),
            File("app/src/main/java/dev/ipf/whitenoise/android/state/NativeAttachmentTransfers.kt"),
        ).firstOrNull(File::exists) ?: error("Missing NativeAttachmentTransfers.kt")

    /** Locates the production app-state owner in root- and module-scoped test layouts. */
    private fun appStateSource(): File =
        listOf(
            File("src/main/java/dev/ipf/whitenoise/android/state/AppState.kt"),
            File("app/src/main/java/dev/ipf/whitenoise/android/state/AppState.kt"),
        ).firstOrNull(File::exists) ?: error("Missing AppState.kt")

    /** Locates the cancellation owner after acquisition and control responsibilities were separated. */
    private fun cancellationSource(): File =
        listOf(
            File("src/main/java/dev/ipf/whitenoise/android/state/NativeAttachmentCancellation.kt"),
            File("app/src/main/java/dev/ipf/whitenoise/android/state/NativeAttachmentCancellation.kt"),
        ).firstOrNull(File::exists) ?: error("Missing NativeAttachmentCancellation.kt")
}
