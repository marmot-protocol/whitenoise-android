package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.LocalSendAcceptanceFfi
import dev.ipf.marmotkit.LocalSendStatusFfi
import dev.ipf.marmotkit.MarmotKitException
import dev.ipf.marmotkit.SendAcceptDispositionFfi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DurableLocalSendsTest {
    /** Native local acceptance must remain pending and preserve the exact optimistic token. */
    @Test
    fun textAndReplyUseStableTokenWithoutClaimingDelivery() =
        runTest {
            for (reply in listOf(null, "parent")) {
                val calls = mutableListOf<String>()
                val engine =
                    nativeBoundary { method, args ->
                        calls += method
                        when (method) {
                            "localSendStatus" -> null
                            "sendTextWithClientToken", "replyToMessageWithClientToken" -> {
                                assertEquals("logical-token", args[args.size - 2])
                                LocalSendAcceptanceFfi("logical-token", "11".repeat(32))
                            }
                            else -> error(method)
                        }
                    }
                val result = engine.sendComposerTextWithToken("account", "group", reply, "hello", "logical-token")
                assertEquals(SendAcceptDispositionFfi.ACCEPTED_PENDING, result.acceptDisposition)
                assertEquals(0u, result.published)
                assertEquals(listOf("11".repeat(32)), result.messageIds)
                val expectedMethod = if (reply == null) "sendTextWithClientToken" else "replyToMessageWithClientToken"
                assertEquals(expectedMethod, calls.last())
            }
        }

    /** A retained token owns the send after interruption; retry must not call admission again. */
    @Test
    fun recoveredOwnershipDoesNotReadmit() =
        runTest {
            for (status in listOf(LocalSendStatusFfi.Queued, LocalSendStatusFfi.EngineOwned)) {
                val engine =
                    nativeBoundary { method, _ ->
                        check(method == "localSendStatus")
                        status
                    }
                val result = engine.sendComposerTextWithToken("account", "group", null, "hello", "same-token")
                assertEquals(SendAcceptDispositionFfi.ACCEPTED_PENDING, result.acceptDisposition)
            }
        }

    /** A rejected token is terminal instead of silently sending the same payload under another identity. */
    @Test
    fun rejectedTokenRequiresDeliberateNewSubmission() =
        runTest {
            val engine =
                nativeBoundary { method, _ ->
                    check(method == "localSendStatus")
                    LocalSendStatusFfi.Rejected
                }
            val failure =
                runCatching {
                    engine.sendComposerTextWithToken("account", "group", null, "hello", "rejected")
                }.exceptionOrNull()
            assertTrue(failure is IllegalStateException)
        }

    /** A failed status probe is diagnostic context, not a replacement for the admission failure. */
    @Test
    fun recoveryProbeCannotMaskAdmissionFailure() =
        runTest {
            val admissionFailure = MarmotKitException.Publish("primary admission failure")
            val recoveryFailure = MarmotKitException.Runtime("secondary status failure")
            var statusReads = 0
            val engine =
                nativeBoundary { method, _ ->
                    when (method) {
                        "localSendStatus" -> {
                            statusReads += 1
                            if (statusReads == 1) null else throw recoveryFailure
                        }
                        else -> error(method)
                    }
                }

            val thrown =
                runCatching {
                    engine.admitLocalSend("account", "group", "token") { throw admissionFailure }
                }.exceptionOrNull()

            assertTrue(thrown === admissionFailure)
            val diagnostic = admissionFailure.suppressed.single()
            assertTrue(diagnostic === recoveryFailure || diagnostic.cause === recoveryFailure)
        }
}
