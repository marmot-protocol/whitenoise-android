package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.MarmotKitException
import dev.ipf.whitenoise.android.core.DiagnosticFormatter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalGroupDeleteAttemptTest {
    private val pending = PendingLocalGroupDeleteCleanup("account", "group", listOf("old-cache"), setOf("tag"))

    @Test
    fun terminalClosedReadDoesNotSpendRecoveryWithoutAnotherRead() =
        runTest {
            var reads = 0
            var recoveries = 0
            val closed = MarmotKitException.TransportClosed()
            val attempt = LocalGroupDeleteAttempt({ true }, { recoveries++ })
            val failure =
                runCatching {
                    attempt.present {
                        if (++reads == IDEMPOTENT_RUNTIME_MUTATION_RETRY_ATTEMPTS) throw closed
                        throw MarmotKitException.StorageBusy("private storage details")
                    }
                }.exceptionOrNull() as LocalGroupDeleteFailure
            assertSame(closed, failure.cause)
            assertEquals(IDEMPOTENT_RUNTIME_MUTATION_RETRY_ATTEMPTS, reads)
            assertEquals(0, recoveries)
            assertTrue(failure.diagnosticTechnicalDetail.contains("exhausted=1;presence=unknown"))
            attempt.recoverOnce()
            assertEquals(1, recoveries)
        }

    @Test
    fun largeBatchSharesOneReadinessRecoveryEvenWhenEveryChatNeedsRecovery() =
        runTest {
            val budget = LocalGroupDeleteReadinessBudget()
            var recoveries = 0
            repeat(500) {
                val attempt = LocalGroupDeleteAttempt({ true }, { recoveries++ }, readinessBudget = budget)
                attempt.recoverOnce()
                attempt.recoverOnce()
            }
            assertEquals(1, recoveries)
            LocalGroupDeleteAttempt({ true }, { recoveries++ }).recoverOnce()
            assertEquals(2, recoveries)
        }

    @Test
    fun explicitRetryOfLostCommitReturnsOriginalCleanupWithoutPreparingOrDeleting() =
        runTest {
            val events = mutableListOf<String>()
            val attempt = LocalGroupDeleteAttempt({ true }, { events += "ready" })
            val result =
                stageAndDeleteLocalGroup(
                    attempt,
                    LocalGroupDeleteOperations(
                        previous = { pending },
                        present = {
                            events += "absent"
                            false
                        },
                        prepare = { error("must keep original cleanup keys") },
                        stage = { error("must not replace the journal") },
                        delete = { error("must not repeat committed deletion") },
                    ),
                )
            assertSame(pending, result)
            assertEquals(listOf("ready", "absent"), events)
        }

    @Test
    fun freshGestureChecksNativePresenceBeforePreflightJournalAndDelete() =
        runTest {
            val events = mutableListOf<String>()
            val result =
                stageAndDeleteLocalGroup(
                    LocalGroupDeleteAttempt({ true }, { error("healthy worker needs no recovery") }),
                    LocalGroupDeleteOperations(
                        previous = {
                            events += "journal-read"
                            null
                        },
                        present = {
                            events += "present"
                            true
                        },
                        prepare = {
                            events += "media"
                            pending
                        },
                        stage = { events += "stage" },
                        delete = { events += "delete" },
                    ),
                )
            assertSame(pending, result)
            assertEquals(listOf("journal-read", "present", "media", "stage", "delete"), events)
        }

    @Test
    fun presentExplicitRetryPreservesOldAndNewCleanupKeysBeforeDeleting() =
        runTest {
            val prepared = pending.copy(mediaCacheKeys = listOf("new-cache"), ciphertextTags = setOf("new-tag"))
            var staged: PendingLocalGroupDeleteCleanup? = null
            var deletes = 0
            val result =
                stageAndDeleteLocalGroup(
                    LocalGroupDeleteAttempt({ true }, {}),
                    LocalGroupDeleteOperations(
                        previous = { pending },
                        present = { true },
                        prepare = { prepared },
                        stage = { staged = it },
                        delete = { deletes++ },
                    ),
                )
            val merged =
                pending.copy(
                    mediaCacheKeys = listOf("old-cache", "new-cache"),
                    ciphertextTags = setOf("tag", "new-tag"),
                )
            assertEquals(merged, staged)
            assertEquals(merged, result)
            assertEquals(1, deletes)
        }

    @Test
    fun mismatchedPriorCleanupIdentityNeverStagesOrDeletes() =
        runTest {
            val failure =
                runCatching {
                    stageAndDeleteLocalGroup(
                        LocalGroupDeleteAttempt({ true }, {}),
                        LocalGroupDeleteOperations(
                            previous = { pending.copy(account = "foreign-account") },
                            present = { true },
                            prepare = { pending },
                            stage = { error("mismatched intent must not be replaced") },
                            delete = { error("mismatched intent must not delete") },
                        ),
                    )
                }.exceptionOrNull() as LocalGroupDeleteFailure
            assertTrue(failure.diagnosticTechnicalDetail.startsWith("phase=client_journal;"))
        }

    @Test
    fun unknownPreviousOutcomeNeverPreparesStagesOrDeletes() =
        runTest {
            var recoveries = 0
            val result =
                runCatching {
                    stageAndDeleteLocalGroup(
                        LocalGroupDeleteAttempt({ true }, { recoveries++ }),
                        LocalGroupDeleteOperations(
                            previous = { pending },
                            present = { throw MarmotKitException.TransportClosed() },
                            prepare = { error("unknown must retain media") },
                            stage = { error("unknown must retain journal") },
                            delete = { error("unknown must never delete") },
                        ),
                    )
                }
            assertEquals(1, recoveries)
            assertTrue(result.exceptionOrNull() is LocalGroupDeleteFailure)
            assertEquals(
                "phase=presence_reconciliation;attempt=3;exhausted=1;presence=unknown",
                (result.exceptionOrNull() as LocalGroupDeleteFailure).diagnosticTechnicalDetail,
            )
        }

    @Test
    fun closedPreflightRecoversOnceAndCanFinishWithinBudget() =
        runTest {
            var recoveries = 0
            var reads = 0
            val attempt = LocalGroupDeleteAttempt({ true }, { recoveries++ })
            val value =
                attempt.read(LocalDeletePhase.MediaPreflight) {
                    if (++reads < 3) throw MarmotKitException.TransportClosed()
                    pending
                }
            assertSame(pending, value)
            assertEquals(1, recoveries)
            assertEquals(3, reads)
        }

    @Test
    fun persistentPreflightFailureIdentifiesExhaustionWithoutNativeText() =
        runTest {
            val native = MarmotKitException.TransportClosed()
            val failure =
                runCatching {
                    LocalGroupDeleteAttempt({ true }, {}).read(LocalDeletePhase.MediaPreflight) { throw native }
                }.exceptionOrNull() as LocalGroupDeleteFailure
            assertSame(native, failure.cause)
            assertEquals(
                "phase=media_preflight;attempt=3;exhausted=1;presence=unknown",
                failure.diagnosticTechnicalDetail,
            )
            assertEquals("CONNECTIVITY", failure.diagnosticErrorCode)
        }

    @Test
    fun recoveryMustPrecedeReadThatAuthorizesAnotherMutation() =
        runTest {
            val events = mutableListOf<String>()
            var calls = 0
            LocalGroupDeleteAttempt({ true }, { events += "ready" }).delete(
                delete = {
                    events += "delete"
                    if (++calls == 1) throw MarmotKitException.TransportClosed()
                },
                readPresence = {
                    events += "present"
                    true
                },
            )
            assertEquals(listOf("delete", "ready", "present", "delete"), events)
        }

    @Test
    fun closedConfirmationReadCanRecoverAfterANonTransportMutationFailure() =
        runTest {
            var ready = false
            var recoveries = 0
            var deletes = 0
            var reads = 0
            val attempt =
                LocalGroupDeleteAttempt({ true }, {
                    recoveries++
                    ready = true
                })
            attempt.delete(
                delete = {
                    if (++deletes == 1) throw MarmotKitException.StorageBusy("private storage details")
                },
                readPresence = {
                    reads++
                    if (!ready) throw MarmotKitException.TransportClosed()
                    true
                },
            )
            assertEquals(1, recoveries)
            assertEquals(2, deletes)
            assertEquals(2, reads)
        }

    @Test
    fun exhaustedNativeMutationIsDistinctFromExhaustedPresenceRead() =
        runTest {
            val failure =
                runCatching {
                    LocalGroupDeleteAttempt({ true }, {}).delete(
                        delete = { throw MarmotKitException.TransportClosed() },
                        readPresence = { true },
                    )
                }.exceptionOrNull() as LocalGroupDeleteFailure
            assertEquals(
                "phase=native_delete;attempt=3;exhausted=1;presence=present",
                failure.diagnosticTechnicalDetail,
            )
        }

    @Test
    fun failedRecoveryNeverSubstitutesForAuthoritativePresence() =
        runTest {
            var reads = 0
            val attempt = LocalGroupDeleteAttempt({ true }, { throw MarmotKitException.TransportClosed() })
            attempt.delete(
                delete = { throw MarmotKitException.TransportClosed() },
                readPresence = {
                    reads++
                    false
                },
            )
            assertEquals(1, reads)
        }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun stalledReadinessIsBoundedAndStillRequiresAnAuthoritativeRead() =
        runTest {
            var deletes = 0
            var reads = 0
            LocalGroupDeleteAttempt({ true }, { awaitCancellation() }).delete(
                delete = {
                    deletes++
                    throw MarmotKitException.TransportClosed()
                },
                readPresence = {
                    reads++
                    false
                },
            )
            assertEquals(1, deletes)
            assertEquals(1, reads)
            assertEquals(LOCAL_DELETE_READINESS_TIMEOUT_MS, testScheduler.currentTime)
        }

    @Test
    fun runtimeReplacementDuringReadDiscardsPresenceResult() =
        runTest {
            var current = true
            val failure =
                runCatching {
                    LocalGroupDeleteAttempt({ current }, {}).present {
                        current = false
                        true
                    }
                }.exceptionOrNull()
            assertTrue(failure is CancellationException)
            assertFalse(failure is LocalGroupDeleteFailure)
        }

    @Test
    fun accountSwitchDuringRecoveryNeverAdmitsAnotherNativeCall() =
        runTest {
            var current = true
            var deletes = 0
            val failure =
                runCatching {
                    LocalGroupDeleteAttempt({ current }, { current = false }).delete(
                        delete = {
                            deletes++
                            throw MarmotKitException.TransportClosed()
                        },
                        readPresence = { error("stale recovery must stop before querying") },
                    )
                }.exceptionOrNull()
            assertTrue(failure is CancellationException)
            assertEquals(1, deletes)
        }

    @Test
    fun journalAndPostCommitFailuresHaveDifferentSafeDiagnostics() =
        runTest {
            for (phase in listOf(LocalDeletePhase.ClientJournal, LocalDeletePhase.PostCommitCleanup)) {
                val failure =
                    runCatching {
                        LocalGroupDeleteAttempt({ true }, {}).phase(phase) { error("private group secret") }
                    }.exceptionOrNull() as LocalGroupDeleteFailure
                assertEquals(
                    "phase=${phase.code};attempt=0;exhausted=0;presence=unknown",
                    failure.diagnosticTechnicalDetail,
                )
                assertFalse(failure.diagnosticTechnicalDetail.contains("private"))
            }
        }

    @Test
    fun copiedReportIncludesPhaseAndTypedCauseButNoNativeMessageOrIdentities() {
        val secret = "nsec1" + "q".repeat(60)
        val failure =
            LocalGroupDeleteFailure(
                LocalDeletePhase.PresenceReconciliation,
                3,
                true,
                null,
                MarmotKitException.StorageBusy("private account and group $secret"),
            )
        val report =
            DiagnosticFormatter.errorReport(
                "CHAT_LOCAL_DELETE",
                failure,
                DiagnosticFormatter.ErrorReportContext("test", "test", "test"),
            )
        assertTrue(report.contains("phase=presence_reconciliation;attempt=3;exhausted=1;presence=unknown"))
        assertTrue(report.contains("marmot=StorageBusy"))
        assertFalse(report.contains("private account"))
        assertFalse(report.contains(secret))
    }
}
