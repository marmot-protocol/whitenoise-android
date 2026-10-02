package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.AttachmentLocalTargetFfi
import dev.ipf.marmotkit.AttachmentTransferSnapshotFfi
import dev.ipf.marmotkit.AttachmentTransferStateFfi
import dev.ipf.marmotkit.AttachmentTransferStatusFfi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/** Checks the Android adapter's calls against the published generated interface; no native runtime is simulated. */
class NativeAttachmentDemandBoundaryTest {
    private val target = AttachmentLocalTargetFfi("display-message", "source-message", 2u)

    /** Repeated interactive joins preserve identity and never invoke the budget-resetting binding. */
    @Test
    fun repeatedBackingOffDemandOnlyCallsExplicitRequest() =
        runTest {
            val calls = mutableListOf<String>()
            val engine =
                nativeBoundary { method, args ->
                    calls += method
                    assertEquals(listOf("account", "group"), args.take(2))
                    when (method) {
                        "attachmentTransferSnapshot" -> {
                            assertEquals(listOf(target), args[2])
                            snapshot(AttachmentTransferStateFfi.RETRY_SCHEDULED)
                        }
                        "requestExplicitAttachment" -> {
                            assertEquals(target, args[2])
                            "existing-reference"
                        }
                        else -> error("Unexpected native call: $method")
                    }
                }
            repeat(10) {
                assertEquals(
                    AttachmentTransferStateFfi.RETRY_SCHEDULED,
                    engine.requestNativeInteractiveAttachment(
                        "account",
                        "group",
                        target,
                        demandIntent = AttachmentDemandIntent.Join,
                    ),
                )
            }
            assertEquals(10, calls.count { it == "requestExplicitAttachment" })
            assertEquals(20, calls.count { it == "attachmentTransferSnapshot" })
        }

    /** A returned reference alone is not readiness: the adapter consumes the post-admission snapshot. */
    @Test
    fun readyAdmissionUsesFreshSnapshot() =
        runTest {
            var state = AttachmentTransferStateFfi.NOT_REQUESTED
            val engine =
                nativeBoundary { method, _ ->
                    when (method) {
                        "attachmentTransferSnapshot" -> snapshot(state)
                        "requestExplicitAttachment" -> {
                            state = AttachmentTransferStateFfi.READY
                            "retained-reference"
                        }
                        else -> error("Unexpected native call: $method")
                    }
                }
            assertEquals(
                AttachmentTransferStateFfi.READY,
                engine.requestNativeInteractiveAttachment(
                    "account",
                    "group",
                    target,
                    demandIntent = AttachmentDemandIntent.Join,
                ),
            )
        }

    /** Replaying durable work cannot turn a cancelled snapshot into a new acquisition. */
    @Test
    fun observedCancellationCannotIssueAnyDemand() =
        runTest {
            val calls = mutableListOf<String>()
            val engine =
                nativeBoundary { method, _ ->
                    calls += method
                    check(method == "attachmentTransferSnapshot")
                    snapshot(AttachmentTransferStateFfi.CANCELLED)
                }
            assertEquals(
                AttachmentTransferStateFfi.CANCELLED,
                engine.requestNativeInteractiveAttachment(
                    "account",
                    "group",
                    target,
                    demandIntent = AttachmentDemandIntent.Observe,
                ),
            )
            assertEquals(listOf("attachmentTransferSnapshot"), calls)
        }

    /** A rejected deliberate retry is returned as failure instead of waiting forever for progress. */
    @Test
    fun rejectedDeliberateRetryDoesNotWaitForUpdates() =
        runTest {
            val calls = mutableListOf<String>()
            val engine =
                nativeBoundary { method, _ ->
                    calls += method
                    when (method) {
                        "attachmentTransferSnapshot" -> snapshot(AttachmentTransferStateFfi.RETRY_EXHAUSTED)
                        "downloadAttachmentAgain" -> null
                        else -> error("Unexpected native call: $method")
                    }
                }
            val failure =
                runCatching {
                    engine.requestNativeInteractiveAttachment(
                        "account",
                        "group",
                        target,
                        demandIntent = AttachmentDemandIntent.Retry,
                    )
                }.exceptionOrNull()
            assertTrue(failure is IOException)
            assertEquals(listOf("attachmentTransferSnapshot", "downloadAttachmentAgain"), calls)
        }

    /** A non-null reference cannot hide a source becoming unavailable during native admission. */
    @Test
    fun unavailableAdmissionFailsAndClosesObservation() =
        runTest {
            var state = AttachmentTransferStateFfi.NOT_REQUESTED
            var closes = 0
            val engine =
                nativeBoundary { method, _ ->
                    when (method) {
                        "attachmentTransferSnapshot" -> snapshot(state)
                        "requestExplicitAttachment" -> {
                            state = AttachmentTransferStateFfi.UNAVAILABLE
                            "ineligible-reference"
                        }
                        else -> error("Unexpected native call: $method")
                    }
                }
            val feed =
                object : NativeTransferFeed {
                    /** Delivers the pre-admission state; any extra read indicates a stuck observer. */
                    override suspend fun next(): AttachmentTransferSnapshotFfi {
                        check(state == AttachmentTransferStateFfi.NOT_REQUESTED)
                        return snapshot(state)
                    }

                    /** Counts disposal even when admission rejects the observed source. */
                    override fun close() {
                        closes += 1
                    }
                }
            val failure =
                runCatching {
                    awaitNativeAttachment(open = { feed }) {
                        engine.requestNativeInteractiveAttachment(
                            "account",
                            "group",
                            target,
                            demandIntent = AttachmentDemandIntent.Join,
                        )
                    }
                }.exceptionOrNull()
            assertEquals(AttachmentTransferStateFfi.UNAVAILABLE, (failure as NativeAttachmentTerminalException).state)
            assertEquals(1, closes)
        }

    /** A refused initial demand cannot launch a waiter that would never receive an admission update. */
    @Test
    fun freshNotRequestedAdmissionFailsWithoutRetryReset() =
        runTest {
            val calls = mutableListOf<String>()
            val engine =
                nativeBoundary { method, _ ->
                    calls += method
                    when (method) {
                        "attachmentTransferSnapshot" -> snapshot(AttachmentTransferStateFfi.NOT_REQUESTED)
                        "requestExplicitAttachment" -> null
                        else -> error("Unexpected native call: $method")
                    }
                }
            val failure =
                runCatching {
                    engine.requestNativeInteractiveAttachment("account", "group", target, AttachmentDemandIntent.Join)
                }.exceptionOrNull()
            assertTrue(failure is IOException)
            val expected =
                listOf(
                    "attachmentTransferSnapshot",
                    "requestExplicitAttachment",
                    "attachmentTransferSnapshot",
                )
            assertEquals(expected, calls)
        }

    /** Produces the minimal generated snapshot needed to exercise adapter admission. */
    private fun snapshot(state: AttachmentTransferStateFfi) =
        AttachmentTransferSnapshotFfi(listOf(AttachmentTransferStatusFfi(null, state, 0u, 0u, null, null)))
}
