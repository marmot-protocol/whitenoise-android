package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.EncryptedMediaVersionFfi
import dev.ipf.marmotkit.LocalSendAcceptanceFfi
import dev.ipf.marmotkit.LocalSendStatusFfi
import dev.ipf.marmotkit.MarmotKitException
import dev.ipf.marmotkit.MediaAttachmentReferenceFfi
import dev.ipf.marmotkit.MediaLocatorFfi
import dev.ipf.whitenoise.android.core.ForwardAttachmentSource
import dev.ipf.whitenoise.android.core.ForwardMessagePayload
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
@Suppress("LargeClass") // Cross-phase forwarding races share one transport fake and assertion vocabulary.
class MessageForwardingTest {
    @Test
    fun cancelledOperationNeverOffersRetryForEarlierFailures() {
        val snapshot =
            ForwardOperationSnapshot(
                phase = ForwardOperationPhase.Cancelled,
                preparedAttachments = 1,
                totalAttachments = 1,
                targets =
                    listOf(
                        ForwardTargetProgress(
                            groupIdHex = "target",
                            phase = ForwardTargetPhase.Failed,
                            totalAttachments = 1,
                            totalMessages = 1,
                            failureStage = ForwardFailureStage.Upload,
                        ),
                    ),
            )

        assertFalse(snapshot.canRetry)
    }

    @Test
    fun mediaIsMaterializedOnceAndUploadedFreshForEveryTarget() =
        runTest {
            val transport = RecordingForwardTransport()
            val session =
                ForwardSession(
                    scope = this,
                    messages =
                        listOf(
                            mediaPayload("media", "caption", "photo.jpg", "notes.pdf"),
                            textPayload("text", "after media"),
                        ),
                    targetGroupIds = listOf("target-a", "target-b", "target-a"),
                    transport = transport,
                )

            session.start()
            advanceUntilIdle()

            assertEquals(listOf(0, 1), transport.materializedIndices)
            assertEquals(listOf("target-a", "target-b"), transport.uploadTargets.sorted())
            assertEquals(
                listOf("target-a" to 0, "target-a" to 1, "target-b" to 0, "target-b" to 1),
                transport.published.sortedWith(compareBy<Pair<String, Int>> { it.first }.thenBy { it.second }),
            )
            assertTrue(transport.publishedMediaHashes.getValue("target-a").all { it.startsWith("target-a-") })
            assertTrue(transport.publishedMediaHashes.getValue("target-b").all { it.startsWith("target-b-") })
            assertEquals(ForwardOperationPhase.Completed, session.state.value.phase)

            session.start()
            advanceUntilIdle()
            assertEquals(2, transport.uploadTargets.size)
            assertEquals(4, transport.published.size)
        }

    @Test
    fun retryRunsOnlyFailedTargetsWithoutDuplicatingSuccessfulSends() =
        runTest {
            val transport = RecordingForwardTransport(failUploadOnceFor = mutableSetOf("target-b"))
            val session =
                ForwardSession(
                    scope = this,
                    messages = listOf(mediaPayload("media", null, "photo.jpg")),
                    targetGroupIds = listOf("target-a", "target-b"),
                    transport = transport,
                )

            session.start()
            advanceUntilIdle()

            assertEquals(ForwardOperationPhase.PartialFailure, session.state.value.phase)
            assertEquals(listOf("target-a" to 0), transport.published)

            session.retryFailed()
            advanceUntilIdle()

            assertEquals(ForwardOperationPhase.Completed, session.state.value.phase)
            assertEquals(1, transport.uploadTargets.count { it == "target-a" })
            assertEquals(2, transport.uploadTargets.count { it == "target-b" })
            assertEquals(listOf("target-a" to 0, "target-b" to 0), transport.published)
        }

    /**
     * A message still queued at the deadline fails the destination; Retry reuses that destination's uploads, resumes at
     * that message and waits for the engine to take the queued admission instead of admitting it again.
     */
    @Test
    fun publishOnlyRetryReusesTargetUploadsAndResumesAtTheQueuedMessage() =
        runTest {
            val transport =
                RecordingForwardTransport(scriptedOutcomeOnce = mutableMapOf("target" to ScriptedAdmission.HoldQueued))
            val session =
                ForwardSession(
                    scope = this,
                    messages =
                        listOf(
                            textPayload("first", "first"),
                            mediaPayload("second", "caption", "photo.jpg"),
                        ),
                    targetGroupIds = listOf("target"),
                    transport = transport,
                )

            session.start()
            advanceUntilIdle()

            assertEquals(ForwardOperationPhase.Failed, session.state.value.phase)
            assertEquals(
                ForwardFailureStage.PublishTimeout,
                session.state.value.targets
                    .single()
                    .failureStage,
            )
            assertEquals(1, transport.uploadTargets.size)

            transport.releaseQueued()
            session.retryFailed()
            advanceUntilIdle()

            assertEquals(ForwardOperationPhase.Completed, session.state.value.phase)
            assertEquals(listOf(0, 1), transport.publishStartIndices)
            assertEquals(listOf("target" to 0, "target" to 1), transport.published)
            assertEquals(1, transport.uploadTargets.size)
        }

    /**
     * Every destination and message gets its own client token, and a retry of the same message to the same
     * destination reuses it, which is what lets MDK refuse to publish it twice (#2556).
     */
    @Test
    fun clientTokensAreStableAcrossRetriesAndDistinctPerDestinationAndMessage() =
        runTest {
            val transport =
                RecordingForwardTransport(
                    scriptedOutcomeOnce = mutableMapOf("target-a" to ScriptedAdmission.FailBeforeAdmission),
                )
            val session =
                ForwardSession(
                    scope = this,
                    messages = listOf(textPayload("first", "first"), textPayload("second", "second")),
                    targetGroupIds = listOf("target-a", "target-b"),
                    transport = transport,
                )

            session.start()
            advanceUntilIdle()
            session.retryFailed()
            advanceUntilIdle()

            assertEquals(ForwardOperationPhase.Completed, session.state.value.phase)
            val tokensBySlot = transport.admissionTokens.groupBy({ it.first to it.second }, { it.third })
            val retriedSlot = tokensBySlot.getValue("target-a" to 1)
            assertEquals(2, retriedSlot.size)
            assertEquals(1, retriedSlot.toSet().size)
            val firstTokens = tokensBySlot.values.map { it.first() }
            assertEquals(4, firstTokens.toSet().size)
            assertTrue(transport.admissionTokens.none { (target, _, token) -> target in token })
        }

    /**
     * A queued message is not sent until MDK's engine owns it: at the deadline the destination fails as a publish
     * timeout, never Completed, and Retry waits on the same token without admitting it again.
     */
    @Test
    fun aQueuedMessageIsNotReportedSentAndTimesOutWithItsOwnStage() =
        runTest {
            val transport =
                RecordingForwardTransport(scriptedOutcomeOnce = mutableMapOf("target" to ScriptedAdmission.HoldQueued))
            val session =
                ForwardSession(
                    scope = this,
                    messages = listOf(textPayload("first", "first"), textPayload("second", "second")),
                    targetGroupIds = listOf("target"),
                    transport = transport,
                )

            session.start()
            advanceTimeBy(FORWARD_PUBLISH_ADMISSION_MILLIS - 1)
            runCurrent()
            val waiting =
                session.state.value.targets
                    .single()
            assertEquals(ForwardTargetPhase.Sending, waiting.phase)
            assertEquals(1, waiting.sentMessages)
            advanceUntilIdle()

            val failed = session.state.value
            assertEquals(ForwardFailureStage.PublishTimeout, failed.targets.single().failureStage)
            assertTrue(failed.canRetry)
            assertFalse(failed.canAutomaticallyRetry)

            transport.releaseQueued()
            session.retryFailed()
            advanceUntilIdle()

            assertEquals(ForwardOperationPhase.Completed, session.state.value.phase)
            assertEquals(listOf(0, 1), transport.publishStartIndices)
            assertEquals(1, transport.admissionsOf("target", 1))
            assertEquals(1, transport.tokensForMessage(1).toSet().size)
        }

    /**
     * A message MDK rejects after queuing it is a failure, never a sent message; it waits for a deliberate Retry, which
     * admits it under a fresh token.
     */
    @Test
    fun aMessageRejectedWhileQueuedFailsAndOnlyAManualRetryUsesAFreshToken() =
        runTest {
            val transport =
                RecordingForwardTransport(
                    scriptedOutcomeOnce = mutableMapOf("target" to ScriptedAdmission.RejectWhileQueued),
                )
            val session =
                ForwardSession(
                    scope = this,
                    messages = listOf(textPayload("first", "first"), textPayload("second", "second")),
                    targetGroupIds = listOf("target"),
                    transport = transport,
                )

            session.start()
            advanceUntilIdle()
            val failed = session.state.value
            assertEquals(ForwardOperationPhase.Failed, failed.phase)
            assertEquals(ForwardFailureStage.PublishRejected, failed.targets.single().failureStage)
            assertTrue(failed.canRetry)
            assertFalse(failed.canAutomaticallyRetry)

            session.retryFailed()
            advanceUntilIdle()

            assertEquals(ForwardOperationPhase.Completed, session.state.value.phase)
            assertEquals(2, transport.tokensForMessage(1).toSet().size)
            assertEquals(listOf("target" to 1), transport.rejected)
            assertEquals(listOf("target" to 0, "target" to 1), transport.published)
        }

    /**
     * A message MDK rejects after queuing it, as after a destination epoch change, gets fresh destination references:
     * Retry uploads that message again, keeps the other message's references, and then sends it under a fresh token.
     */
    @Test
    fun retryAfterARejectionUploadsOnlyThatMessageAgainAndThenSucceeds() =
        runTest {
            val transport =
                RecordingForwardTransport(
                    scriptedOutcomeOnce = mutableMapOf("target" to ScriptedAdmission.RejectWhileQueued),
                )
            val session =
                ForwardSession(
                    scope = this,
                    messages =
                        listOf(
                            mediaPayload("first", "first caption", "first.jpg"),
                            mediaPayload("second", "second caption", "second.jpg"),
                        ),
                    targetGroupIds = listOf("target"),
                    transport = transport,
                )

            session.start()
            advanceUntilIdle()
            assertEquals(
                ForwardFailureStage.PublishRejected,
                session.state.value.targets
                    .single()
                    .failureStage,
            )
            assertEquals(2, transport.uploadTargets.size)

            session.retryFailed()
            advanceUntilIdle()

            assertEquals(ForwardOperationPhase.Completed, session.state.value.phase)
            assertEquals(3, transport.uploadTargets.size)
            assertEquals(listOf("target" to 0, "target" to 1), transport.published)
            assertEquals(2, transport.tokensForMessage(1).toSet().size)
        }

    /**
     * An epoch change makes every reference uploaded before it stale, so rejecting the first unsent message also
     * drops the later messages' references: one Retry uploads both again and the batch completes.
     */
    @Test
    fun aRejectionDropsTheReferencesOfEveryLaterUnsentMessageSoOneRetrySuffices() =
        runTest {
            val transport =
                RecordingForwardTransport(
                    scriptedOutcomeOnce = mutableMapOf("target" to ScriptedAdmission.RejectWhileQueued),
                    scriptedMessageIndex = 0,
                )
            val session =
                ForwardSession(
                    scope = this,
                    messages =
                        listOf(
                            mediaPayload("first", "first caption", "first.jpg"),
                            mediaPayload("second", "second caption", "second.jpg"),
                        ),
                    targetGroupIds = listOf("target"),
                    transport = transport,
                )

            session.start()
            advanceUntilIdle()
            assertEquals(
                ForwardFailureStage.PublishRejected,
                session.state.value.targets
                    .single()
                    .failureStage,
            )
            assertEquals(2, transport.uploadTargets.size)

            session.retryFailed()
            advanceUntilIdle()

            assertEquals(ForwardOperationPhase.Completed, session.state.value.phase)
            assertEquals(4, transport.uploadTargets.size)
            assertEquals(listOf("target" to 0, "target" to 1), transport.published)
            assertEquals(2, transport.tokensForMessage(0).toSet().size)
        }

    /**
     * A synchronous stale-epoch refusal takes the same path: it is never retried automatically with the stale
     * references, and the user's Retry uploads that message again before admitting it.
     */
    @Test
    fun aStaleEpochRefusalIsNotRetriedAutomaticallyAndRetryUploadsAgain() =
        runTest {
            val terminalSnapshots = mutableListOf<ForwardOperationSnapshot>()
            val transport =
                RecordingForwardTransport(
                    scriptedOutcomeOnce = mutableMapOf("target" to ScriptedAdmission.RefuseStaleEpoch),
                )
            val owner =
                ForwardOperationOwner(
                    scope = this,
                    automaticRetryAttempts = 3,
                    retryDelayMillis = { 1_000L },
                    onTerminal = terminalSnapshots::add,
                )
            val session =
                ForwardSession(
                    scope = this,
                    messages =
                        listOf(
                            mediaPayload("first", "first caption", "first.jpg"),
                            mediaPayload("second", "second caption", "second.jpg"),
                        ),
                    targetGroupIds = listOf("target"),
                    transport = transport,
                )

            assertTrue(owner.start(session))
            advanceUntilIdle()

            assertEquals(ForwardOperationPhase.Failed, owner.state.value?.phase)
            assertEquals(
                ForwardFailureStage.PublishRejected,
                owner.state.value
                    ?.targets
                    ?.single()
                    ?.failureStage,
            )
            assertEquals(listOf(0), transport.publishStartIndices)
            assertEquals(2, transport.uploadTargets.size)

            session.retryFailed()
            advanceUntilIdle()

            assertEquals(ForwardOperationPhase.Completed, session.state.value.phase)
            assertEquals(3, transport.uploadTargets.size)
            assertEquals(listOf("target" to 0, "target" to 1), transport.published)
            owner.release()
        }

    /** A message that stays queued is never admitted a second time, however often the user retries. */
    @Test
    fun aMessageStillQueuedIsNeverAdmittedAgainOnRetry() =
        runTest {
            val transport =
                RecordingForwardTransport(scriptedOutcomeOnce = mutableMapOf("target" to ScriptedAdmission.HoldQueued))
            val session =
                ForwardSession(
                    scope = this,
                    messages = listOf(textPayload("first", "first"), textPayload("second", "second")),
                    targetGroupIds = listOf("target"),
                    transport = transport,
                )

            session.start()
            advanceUntilIdle()
            session.retryFailed()
            advanceUntilIdle()

            assertEquals(ForwardOperationPhase.Failed, session.state.value.phase)
            assertEquals(1, transport.admissionsOf("target", 1))
            assertEquals(listOf("target" to 0, "target" to 1), transport.published)
        }

    /** An admission that stored nothing is retried at the same message under the same token, and sent once. */
    @Test
    fun aFailedAdmissionRetriesTheSameMessageUnderTheSameToken() =
        runTest {
            val transport =
                RecordingForwardTransport(
                    scriptedOutcomeOnce = mutableMapOf("target" to ScriptedAdmission.FailBeforeAdmission),
                )
            val session =
                ForwardSession(
                    scope = this,
                    messages = listOf(textPayload("first", "first"), textPayload("second", "second")),
                    targetGroupIds = listOf("target"),
                    transport = transport,
                )

            session.start()
            advanceUntilIdle()
            assertEquals(
                ForwardFailureStage.Publish,
                session.state.value.targets
                    .single()
                    .failureStage,
            )
            session.retryFailed()
            advanceUntilIdle()

            assertEquals(ForwardOperationPhase.Completed, session.state.value.phase)
            assertEquals(listOf(0, 1), transport.publishStartIndices)
            assertEquals(listOf("target" to 0, "target" to 1), transport.published)
            assertEquals(1, transport.tokensForMessage(1).toSet().size)
        }

    /**
     * An admission that outlives its wait still lands in MDK later; Retry finds it by its token and counts it, so the
     * message exists once and is never admitted a second time.
     */
    @Test
    fun aLateAdmissionLandingAfterTheTimeoutIsCountedByRetry() =
        runTest {
            val transport =
                RecordingForwardTransport(
                    scriptedOutcomeOnce = mutableMapOf("target" to ScriptedAdmission.StallAdmission),
                )
            val session =
                ForwardSession(
                    scope = this,
                    messages = listOf(textPayload("first", "first"), textPayload("second", "second")),
                    targetGroupIds = listOf("target"),
                    transport = transport,
                )

            session.start()
            advanceUntilIdle()
            assertEquals(
                ForwardFailureStage.PublishTimeout,
                session.state.value.targets
                    .single()
                    .failureStage,
            )

            transport.landStalledAdmissions()
            session.retryFailed()
            advanceUntilIdle()

            assertEquals(ForwardOperationPhase.Completed, session.state.value.phase)
            assertEquals(1, transport.admissionsOf("target", 1))
            assertEquals(listOf("target" to 0, "target" to 1), transport.published)
        }

    /** A failed re-materialization on retry keeps the destination's tokens, so the queued message is never resent. */
    @Test
    fun materializationRetryFailureKeepsTheTokensOfAQueuedMessage() =
        runTest {
            val transport =
                RecordingForwardTransport(
                    scriptedOutcomeOnce = mutableMapOf("target" to ScriptedAdmission.HoldQueued),
                    failMaterializeOnCall = 2,
                )
            val session =
                ForwardSession(
                    scope = this,
                    messages =
                        listOf(
                            textPayload("first", "first"),
                            mediaPayload("second", "caption", "photo.jpg"),
                        ),
                    targetGroupIds = listOf("target"),
                    transport = transport,
                )

            session.start()
            advanceUntilIdle()
            session.retryFailed()
            advanceUntilIdle()

            assertEquals(ForwardOperationPhase.Failed, session.state.value.phase)
            assertEquals(
                ForwardFailureStage.Materialize,
                session.state.value.targets
                    .single()
                    .failureStage,
            )
            assertTrue(session.state.value.canRetry)

            transport.releaseQueued()
            session.retryFailed()
            advanceUntilIdle()

            assertEquals(ForwardOperationPhase.Completed, session.state.value.phase)
            assertEquals(listOf("target" to 0, "target" to 1), transport.published)
            assertEquals(1, transport.admissionsOf("target", 1))
        }

    /**
     * A destination upload that never starts fails that target as an upload timeout instead of looking stuck
     * (#2556), offers Retry without retrying by itself, and Retry uploads again and publishes the message once.
     */
    @Test
    fun aStalledUploadFailsAtTheUploadStageAndRetryPublishesOnce() =
        runTest {
            val transport = RecordingForwardTransport(stallUploadOnceFor = mutableSetOf("target"))
            val session =
                ForwardSession(
                    scope = this,
                    messages = listOf(mediaPayload("media", null, "notes.txt")),
                    targetGroupIds = listOf("target"),
                    transport = transport,
                )

            session.start()
            advanceUntilIdle()

            assertEquals(ForwardOperationPhase.Failed, session.state.value.phase)
            val failed =
                session.state.value.targets
                    .single()
            assertEquals(ForwardFailureStage.UploadTimeout, failed.failureStage)
            assertTrue(session.state.value.canRetry)
            assertFalse(session.state.value.canAutomaticallyRetry)
            assertEquals(emptyList<Pair<String, Int>>(), transport.published)

            session.retryFailed()
            advanceUntilIdle()

            assertEquals(ForwardOperationPhase.Completed, session.state.value.phase)
            assertEquals(listOf("target", "target"), transport.uploadTargets)
            assertEquals(listOf("target" to 0), transport.published)
        }

    /** Cancellation after upload begins must not cancel unrelated source-cache work. */
    @Test
    fun cancellationWhileUploadingPublishesNothing() =
        runTest {
            val uploadGate = CompletableDeferred<Unit>()
            val transport = RecordingForwardTransport(uploadGate = uploadGate)
            val session =
                ForwardSession(
                    scope = this,
                    messages = listOf(mediaPayload("media", null, "photo.jpg")),
                    targetGroupIds = listOf("target"),
                    transport = transport,
                )

            session.start()
            runCurrent()
            session.cancel()
            advanceUntilIdle()

            assertEquals(emptyList<Pair<String, Int>>(), transport.published)
            assertEquals(ForwardOperationPhase.Cancelled, session.state.value.phase)
            assertEquals(0, transport.cancelledMaterializations)
        }

    @Test
    fun releaseWhileUploadingCancelsAndClearsRetainedPlaintext() =
        runTest {
            val uploadGate = CompletableDeferred<Unit>()
            val retained = byteArrayOf(7, 8, 9)
            val transport = RecordingForwardTransport(uploadGate = uploadGate, materializedBytes = retained)
            val session =
                ForwardSession(
                    scope = this,
                    messages = listOf(mediaPayload("media", null, "photo.jpg")),
                    targetGroupIds = listOf("target"),
                    transport = transport,
                )

            session.start()
            runCurrent()
            session.release()
            advanceUntilIdle()

            assertEquals(ForwardOperationPhase.Cancelled, session.state.value.phase)
            assertTrue(retained.contentEquals(byteArrayOf(0, 0, 0)))
            assertTrue(transport.published.isEmpty())
        }

    @Test
    fun materializationUsesTheSameBoundedFanoutAsTargetWork() =
        runTest {
            val materializeGate = CompletableDeferred<Unit>()
            val transport = RecordingForwardTransport(materializeGate = materializeGate)
            val session =
                ForwardSession(
                    scope = this,
                    messages = listOf(mediaPayload("media", null, "one.bin", "two.bin", "three.bin")),
                    targetGroupIds = listOf("target"),
                    transport = transport,
                    targetFanout = 2,
                )

            session.start()
            runCurrent()

            assertEquals(2, transport.materializedIndices.size)
            materializeGate.complete(Unit)
            advanceUntilIdle()
            assertEquals(3, transport.materializedIndices.size)
            assertEquals(ForwardOperationPhase.Completed, session.state.value.phase)
        }

    /** A never-completing APK source becomes an actionable failure without any destination side effect. */
    @Test
    fun apkPreparationTimeoutIsRecoverableAndPublishesNothing() =
        runTest {
            val stalledSource = CompletableDeferred<Unit>()
            val transport = RecordingForwardTransport(materializeGates = ArrayDeque(listOf(stalledSource)))
            val session =
                ForwardSession(
                    scope = this,
                    messages = listOf(apkPayload()),
                    targetGroupIds = listOf("target"),
                    transport = transport,
                    preparationTimeoutMillis = 1_000L,
                )

            session.start()
            runCurrent()
            assertEquals(ForwardOperationPhase.Preparing, session.state.value.phase)

            advanceTimeBy(1_000L)
            advanceUntilIdle()

            assertEquals(ForwardOperationPhase.Failed, session.state.value.phase)
            assertEquals(
                ForwardFailureStage.PreparationTimeout,
                session.state.value.targets
                    .single()
                    .failureStage,
            )
            assertTrue(session.state.value.canRetry)
            assertFalse(session.state.value.canAutomaticallyRetry)
            assertEquals(1, transport.cancelledMaterializations)
            assertTrue(transport.uploadTargets.isEmpty())
            assertTrue(transport.published.isEmpty())

            session.cancel()
            assertEquals(ForwardOperationPhase.Failed, session.state.value.phase)
        }

    /** Manual retry reacquires a timed-out APK and preserves its typed filename without duplicate sends. */
    @Test
    fun manualRetryAfterTimeoutReacquiresApkAndPublishesExactlyOnce() =
        runTest {
            val stalledSource = CompletableDeferred<Unit>()
            val transport = RecordingForwardTransport(materializeGates = ArrayDeque(listOf(stalledSource)))
            val session =
                ForwardSession(
                    scope = this,
                    messages = listOf(apkPayload()),
                    targetGroupIds = listOf("target"),
                    transport = transport,
                    preparationTimeoutMillis = 1_000L,
                )

            session.start()
            advanceTimeBy(1_000L)
            advanceUntilIdle()
            session.retryFailed()
            advanceUntilIdle()

            assertEquals(ForwardOperationPhase.Completed, session.state.value.phase)
            assertEquals(listOf(0, 0), transport.materializedIndices)
            assertEquals(listOf("target"), transport.uploadTargets)
            assertEquals(listOf("target" to 0), transport.published)
            assertEquals(listOf("white-noise.apk"), transport.publishedMediaFileNames.getValue("target"))
            assertEquals(listOf(APK_MIME), transport.publishedMediaTypes.getValue("target"))
            assertEquals(1, transport.cancelledMaterializations)
        }

    /** Explicit cancellation wins the timeout race and never uploads or publishes the stalled APK. */
    @Test
    fun cancellingStalledApkBeforeDeadlineStaysCancelled() =
        runTest {
            val transport =
                RecordingForwardTransport(
                    materializeGates = ArrayDeque(listOf(CompletableDeferred<Unit>())),
                )
            val session =
                ForwardSession(
                    scope = this,
                    messages = listOf(apkPayload()),
                    targetGroupIds = listOf("target"),
                    transport = transport,
                    preparationTimeoutMillis = 1_000L,
                )

            session.start()
            runCurrent()
            advanceTimeBy(999L)
            runCurrent()
            session.cancel()
            advanceUntilIdle()
            advanceTimeBy(1L)
            runCurrent()

            assertEquals(ForwardOperationPhase.Cancelled, session.state.value.phase)
            assertEquals(1, transport.cancelledMaterializations)
            assertTrue(transport.uploadTargets.isEmpty())
            assertTrue(transport.published.isEmpty())
        }

    /** Timeout wipes plaintext prepared by siblings before one attachment became stuck. */
    @Test
    fun preparationTimeoutWipesPartiallyPreparedPlaintext() =
        runTest {
            val retained = byteArrayOf(7, 8, 9)
            val ready = CompletableDeferred(Unit)
            val stalled = CompletableDeferred<Unit>()
            val transport =
                RecordingForwardTransport(
                    materializeGates = ArrayDeque(listOf(ready, stalled)),
                    materializedBytes = retained,
                )
            val session =
                ForwardSession(
                    scope = this,
                    messages = listOf(mediaPayload("apk-message", null, "ready.apk", "stalled.apk")),
                    targetGroupIds = listOf("target"),
                    transport = transport,
                    preparationTimeoutMillis = 1_000L,
                )

            session.start()
            advanceUntilIdle()

            assertEquals(ForwardOperationPhase.Failed, session.state.value.phase)
            assertTrue(retained.contentEquals(byteArrayOf(0, 0, 0)))
            assertTrue(transport.uploadTargets.isEmpty())
            assertTrue(transport.published.isEmpty())
        }

    /** A transport cleanup exception cannot replace the recoverable deadline state. */
    @Test
    fun cleanupFailureDoesNotMaskPreparationTimeout() =
        runTest {
            val transport =
                RecordingForwardTransport(
                    materializeGate = CompletableDeferred<Unit>(),
                    failMaterializationCleanup = true,
                )
            val session =
                ForwardSession(
                    scope = this,
                    messages = listOf(apkPayload()),
                    targetGroupIds = listOf("target"),
                    transport = transport,
                    preparationTimeoutMillis = 1_000L,
                )

            session.start()
            advanceUntilIdle()

            assertEquals(ForwardOperationPhase.Failed, session.state.value.phase)
            assertEquals(
                ForwardFailureStage.PreparationTimeout,
                session.state.value.targets
                    .single()
                    .failureStage,
            )
            assertEquals(1, transport.cancelledMaterializations)
            assertTrue(transport.published.isEmpty())
        }

    /** Cancellation accepted from inside deadline cleanup wins the race with timeout projection. */
    @Test
    fun cancellationDuringTimeoutCleanupFinishesCancelled() =
        runTest {
            lateinit var session: ForwardSession
            val transport =
                RecordingForwardTransport(
                    materializeGate = CompletableDeferred<Unit>(),
                    onMaterializationCleanup = { session.cancel() },
                )
            session =
                ForwardSession(
                    scope = this,
                    messages = listOf(apkPayload()),
                    targetGroupIds = listOf("target"),
                    transport = transport,
                    preparationTimeoutMillis = 1_000L,
                )

            session.start()
            advanceUntilIdle()

            assertEquals(ForwardOperationPhase.Cancelled, session.state.value.phase)
            assertEquals(1, transport.cancelledMaterializations)
            assertTrue(transport.uploadTargets.isEmpty())
            assertTrue(transport.published.isEmpty())
        }

    /** The app owner surfaces preparation timeout once instead of silently repeating the same stall. */
    @Test
    fun ownerDoesNotAutomaticallyRetryPreparationTimeout() =
        runTest {
            val terminalSnapshots = mutableListOf<ForwardOperationSnapshot>()
            val transport = RecordingForwardTransport(materializeGate = CompletableDeferred<Unit>())
            val owner =
                ForwardOperationOwner(
                    scope = this,
                    automaticRetryAttempts = 3,
                    retryDelayMillis = { 1L },
                    onTerminal = terminalSnapshots::add,
                )
            val session =
                ForwardSession(
                    scope = this,
                    messages = listOf(apkPayload()),
                    targetGroupIds = listOf("target"),
                    transport = transport,
                    preparationTimeoutMillis = 1_000L,
                )

            assertTrue(owner.start(session))
            advanceUntilIdle()

            assertEquals(ForwardOperationPhase.Failed, owner.state.value?.phase)
            assertTrue(owner.state.value?.canRetry == true)
            assertEquals(listOf(0), transport.materializedIndices)
            assertEquals(listOf(ForwardOperationPhase.Failed), terminalSnapshots.map { it.phase })
            assertTrue(transport.published.isEmpty())
            owner.release()
        }

    /** The app owner surfaces an upload that never started once, instead of going back to in progress (#2556). */
    @Test
    fun ownerDoesNotAutomaticallyRetryAnUploadThatNeverStarted() =
        runTest {
            val terminalSnapshots = mutableListOf<ForwardOperationSnapshot>()
            val transport = RecordingForwardTransport(stallUploadOnceFor = mutableSetOf("target"))
            val owner =
                ForwardOperationOwner(
                    scope = this,
                    automaticRetryAttempts = 3,
                    retryDelayMillis = { 1L },
                    onTerminal = terminalSnapshots::add,
                )
            val session =
                ForwardSession(
                    scope = this,
                    messages = listOf(mediaPayload("media", null, "notes.txt")),
                    targetGroupIds = listOf("target"),
                    transport = transport,
                )

            assertTrue(owner.start(session))
            advanceUntilIdle()

            assertEquals(ForwardOperationPhase.Failed, owner.state.value?.phase)
            assertTrue(owner.state.value?.canRetry == true)
            assertEquals(listOf("target"), transport.uploadTargets)
            assertEquals(listOf(ForwardOperationPhase.Failed), terminalSnapshots.map { it.phase })
            assertTrue(transport.published.isEmpty())
            owner.release()
        }

    @Test
    fun oversizedMaterializedBatchFailsBeforeAnyUpload() =
        runTest {
            val transport = RecordingForwardTransport(materializedByteCount = 4)
            val session =
                ForwardSession(
                    scope = this,
                    messages = listOf(mediaPayload("media", null, "one.bin", "two.bin")),
                    targetGroupIds = listOf("target"),
                    transport = transport,
                    maxRetainedBytes = 7,
                )

            session.start()
            advanceUntilIdle()

            assertEquals(ForwardOperationPhase.Failed, session.state.value.phase)
            assertEquals(
                ForwardFailureStage.PayloadTooLarge,
                session.state.value.targets
                    .single()
                    .failureStage,
            )
            assertTrue(transport.uploadTargets.isEmpty())
            assertTrue(transport.published.isEmpty())
            assertFalse(session.state.value.canRetry)
        }

    @Test
    fun attachmentExpiringAfterUploadIsNeverPublishedOrRetried() =
        runTest {
            var now = 100uL
            val transport = RecordingForwardTransport(onUpload = { now = 101uL })
            val session =
                ForwardSession(
                    scope = this,
                    messages = listOf(mediaPayload("media", null, "photo.jpg").copy(expiresAtSeconds = 101uL)),
                    targetGroupIds = listOf("target"),
                    transport = transport,
                    clockSeconds = { now },
                )

            session.start()
            advanceUntilIdle()

            assertEquals(ForwardOperationPhase.Failed, session.state.value.phase)
            assertEquals(
                ForwardFailureStage.Expired,
                session.state.value.targets
                    .single()
                    .failureStage,
            )
            assertEquals(listOf("target"), transport.uploadTargets)
            assertTrue(transport.published.isEmpty())
            assertFalse(session.state.value.canRetry)
        }

    @Test
    fun optimisticSourceResolvesAuthoritativeMetadataBeforeDownload() =
        runTest {
            val optimistic = reference("draft.jpg", "optimistic", sourceEpoch = 0uL)
            val authoritative =
                reference("final.jpg", "authoritative").copy(
                    mediaType = "image/jpeg",
                    dim = "1280x720",
                    thumbhash = "thumb",
                )
            var downloadedReference: MediaAttachmentReferenceFfi? = null
            val sourceCacheBytes = byteArrayOf(1, 2, 3)

            val prepared =
                materializeForwardAttachment(
                    source = ForwardAttachmentSource(0, optimistic),
                    resolveAuthoritativeReference = { authoritative },
                    downloadPlaintext = { reference ->
                        downloadedReference = reference
                        sourceCacheBytes
                    },
                )

            assertEquals(authoritative, downloadedReference)
            assertEquals("final.jpg", prepared.fileName)
            assertEquals("image/jpeg", prepared.mediaType)
            assertEquals("1280x720", prepared.dim)
            assertEquals("thumb", prepared.thumbhash)
            assertTrue(prepared.plaintextBytes.contentEquals(byteArrayOf(1, 2, 3)))
            prepared.plaintextBytes.fill(0)
            assertTrue(sourceCacheBytes.contentEquals(byteArrayOf(1, 2, 3)))
        }

    @Test
    fun authoritativeSourceSkipsResolutionAndMissingProjectionFailsBeforeDownload() =
        runTest {
            var resolved = false
            val authoritative = reference("ready.pdf", "ready")
            materializeForwardAttachment(
                source = ForwardAttachmentSource(0, authoritative),
                resolveAuthoritativeReference = {
                    resolved = true
                    null
                },
                downloadPlaintext = { byteArrayOf(1) },
            )
            assertFalse(resolved)

            var downloaded = false
            try {
                materializeForwardAttachment(
                    source = ForwardAttachmentSource(0, reference("draft.pdf", "draft", sourceEpoch = 0uL)),
                    resolveAuthoritativeReference = { null },
                    downloadPlaintext = {
                        downloaded = true
                        byteArrayOf(1)
                    },
                )
                fail("Expected unresolved optimistic reference to fail")
            } catch (_: AttachmentReferenceNotReadyException) {
                assertFalse(downloaded)
            }
        }

    @Test
    fun completedSessionClearsRetainedPlaintextAndPreservesMediaMetadata() =
        runTest {
            val retained = byteArrayOf(4, 5, 6)
            val transport = RecordingForwardTransport(materializedBytes = retained)
            val session =
                ForwardSession(
                    scope = this,
                    messages = listOf(mediaPayload("media", "source caption", "photo.jpg")),
                    targetGroupIds = listOf("target"),
                    transport = transport,
                )

            session.start()
            advanceUntilIdle()

            assertEquals(ForwardOperationPhase.Completed, session.state.value.phase)
            assertTrue(retained.contentEquals(byteArrayOf(0, 0, 0)))
            assertEquals(listOf("source caption"), transport.publishedMediaCaptions.getValue("target"))
            assertEquals(listOf("photo.jpg"), transport.publishedMediaFileNames.getValue("target"))
            assertEquals(listOf("application/octet-stream"), transport.publishedMediaTypes.getValue("target"))
        }

    @Test
    fun retriableFailureClearsPlaintextBeforeRetry() =
        runTest {
            val retained = byteArrayOf(7, 8, 9)
            val transport =
                RecordingForwardTransport(
                    failUploadOnceFor = mutableSetOf("target"),
                    materializedBytes = retained,
                )
            val session =
                ForwardSession(
                    scope = this,
                    messages = listOf(mediaPayload("media", null, "photo.jpg")),
                    targetGroupIds = listOf("target"),
                    transport = transport,
                )

            session.start()
            advanceUntilIdle()

            assertEquals(ForwardOperationPhase.Failed, session.state.value.phase)
            assertTrue(session.state.value.canRetry)
            assertTrue(retained.contentEquals(byteArrayOf(0, 0, 0)))
        }

    @Test
    fun operationOwnerPublishesLiveStateAndCancelsBeforeSending() =
        runTest {
            val uploadGate = CompletableDeferred<Unit>()
            val transport = RecordingForwardTransport(uploadGate = uploadGate)
            val owner = ForwardOperationOwner(scope = this, automaticRetryAttempts = 0)
            val session =
                ForwardSession(
                    scope = this,
                    messages = listOf(mediaPayload("media", "caption", "photo.jpg")),
                    targetGroupIds = listOf("target"),
                    transport = transport,
                )

            assertTrue(owner.start(session))
            runCurrent()
            assertEquals(
                ForwardTargetPhase.Uploading,
                owner.state.value
                    ?.targets
                    ?.single()
                    ?.phase,
            )
            assertTrue(owner.cancel())
            advanceUntilIdle()

            assertEquals(ForwardOperationPhase.Cancelled, owner.state.value?.phase)
            assertTrue(transport.published.isEmpty())
            assertTrue(owner.dismiss())
            assertEquals(null, owner.state.value)
        }

    @Test
    fun operationOwnerRetriesOnlyFailedTargetsAndRetainsTerminalState() =
        runTest {
            val terminalSnapshots = mutableListOf<ForwardOperationSnapshot>()
            val transport = RecordingForwardTransport(failUploadOnceFor = mutableSetOf("target-b"))
            val owner =
                ForwardOperationOwner(
                    scope = this,
                    automaticRetryAttempts = 0,
                    onTerminal = terminalSnapshots::add,
                )
            val session =
                ForwardSession(
                    scope = this,
                    messages = listOf(mediaPayload("media", null, "photo.jpg")),
                    targetGroupIds = listOf("target-a", "target-b"),
                    transport = transport,
                )

            assertTrue(owner.start(session))
            advanceUntilIdle()

            assertEquals(ForwardOperationPhase.PartialFailure, owner.state.value?.phase)
            assertTrue(owner.retry())
            advanceUntilIdle()

            assertEquals(ForwardOperationPhase.Completed, owner.state.value?.phase)
            assertEquals(1, transport.uploadTargets.count { it == "target-a" })
            assertEquals(2, transport.uploadTargets.count { it == "target-b" })
            assertEquals(listOf("target-a" to 0, "target-b" to 0), transport.published)
            assertEquals(
                listOf(ForwardOperationPhase.PartialFailure, ForwardOperationPhase.Completed),
                terminalSnapshots.map(ForwardOperationSnapshot::phase),
            )
            owner.release()
        }

    @Test
    fun operationOwnerAutomaticallyRetriesOnceWithoutDuplicatePublish() =
        runTest {
            val terminalSnapshots = mutableListOf<ForwardOperationSnapshot>()
            val transport = RecordingForwardTransport(failUploadOnceFor = mutableSetOf("target"))
            val owner =
                ForwardOperationOwner(
                    scope = this,
                    automaticRetryAttempts = 1,
                    retryDelayMillis = { 1_000L },
                    onTerminal = terminalSnapshots::add,
                )
            val session =
                ForwardSession(
                    scope = this,
                    messages = listOf(mediaPayload("media", "caption", "photo.jpg")),
                    targetGroupIds = listOf("target"),
                    transport = transport,
                )

            assertTrue(owner.start(session))
            runCurrent()
            assertEquals(ForwardOperationPhase.Failed, owner.state.value?.phase)

            advanceTimeBy(999L)
            runCurrent()
            assertEquals(1, transport.uploadTargets.size)

            advanceTimeBy(1L)
            advanceUntilIdle()

            assertEquals(ForwardOperationPhase.Completed, owner.state.value?.phase)
            assertEquals(2, transport.uploadTargets.size)
            assertEquals(listOf("target" to 0), transport.published)
            assertEquals(listOf(ForwardOperationPhase.Completed), terminalSnapshots.map { it.phase })
            owner.release()
        }

    /** Once a destination is sending, the forward can no longer be cancelled. */
    @Test
    fun operationCannotBeCancelledAfterPublishingBegins() =
        runTest {
            val snapshot =
                ForwardOperationSnapshot(
                    phase = ForwardOperationPhase.Running,
                    preparedAttachments = 0,
                    totalAttachments = 0,
                    targets =
                        listOf(
                            ForwardTargetProgress(
                                groupIdHex = "target",
                                phase = ForwardTargetPhase.Sending,
                                totalAttachments = 0,
                                totalMessages = 2,
                            ),
                        ),
                )

            assertFalse(snapshot.canCancel)
        }

    /** A publish held past the slow threshold shows the notice, stays uncancellable and still publishes only once. */
    @Test
    fun slowPublishShowsTheNoticeButStaysUncancellableAndPublishesOnce() =
        runTest {
            val publishGate = CompletableDeferred<Unit>()
            val transport = RecordingForwardTransport(publishGate = publishGate)
            val session = textSession(transport)

            session.start()
            advanceTimeBy(FORWARD_PHASE_SLOW_MILLIS - 1)
            runCurrent()
            assertFalse(
                session.state.value.targets
                    .single()
                    .slow,
            )
            advanceTimeBy(1)
            runCurrent()

            val slow = session.state.value
            assertEquals(ForwardTargetPhase.Sending, slow.targets.single().phase)
            assertTrue(slow.targets.single().slow)
            assertFalse(slow.canCancel)
            assertFalse(slow.canRetry)
            session.cancel()
            publishGate.complete(Unit)
            advanceUntilIdle()

            assertEquals(ForwardOperationPhase.Completed, session.state.value.phase)
            assertFalse(
                session.state.value.targets
                    .single()
                    .slow,
            )
            assertEquals(listOf("target" to 0), transport.published)
        }

    /** A slow publish that ends at the deadline shows the notice first and is never sent a second time. */
    @Test
    fun slowPublishThatTimesOutIsNeverResent() =
        runTest {
            val transport =
                RecordingForwardTransport(
                    scriptedOutcomeOnce = mutableMapOf("target" to ScriptedAdmission.HoldQueued),
                    scriptedMessageIndex = 0,
                )
            val session =
                ForwardSession(
                    scope = this,
                    messages = listOf(textPayload("first", "first")),
                    targetGroupIds = listOf("target"),
                    transport = transport,
                )

            session.start()
            advanceTimeBy(FORWARD_PHASE_SLOW_MILLIS + 1)
            runCurrent()
            assertTrue(
                session.state.value.targets
                    .single()
                    .slow,
            )
            advanceUntilIdle()
            assertEquals(ForwardOperationPhase.Failed, session.state.value.phase)
            assertFalse(
                session.state.value.targets
                    .single()
                    .slow,
            )

            transport.releaseQueued()
            session.retryFailed()
            advanceUntilIdle()

            assertEquals(ForwardOperationPhase.Completed, session.state.value.phase)
            assertEquals(listOf("target" to 0), transport.published)
            assertEquals(1, transport.admissionsOf("target", 0))
        }

    /** The upload notice belongs to the upload phase and clears as soon as the destination starts sending. */
    @Test
    fun slowUploadNoticeClearsWhenSendingStarts() =
        runTest {
            val uploadGate = CompletableDeferred<Unit>()
            val publishGate = CompletableDeferred<Unit>()
            val transport = RecordingForwardTransport(uploadGate = uploadGate, publishGate = publishGate)
            val session = mediaSession(transport)

            session.start()
            advanceTimeBy(FORWARD_PHASE_SLOW_MILLIS)
            runCurrent()
            assertEquals(
                ForwardTargetPhase.Uploading,
                session.state.value.targets
                    .single()
                    .phase,
            )
            assertTrue(
                session.state.value.targets
                    .single()
                    .slow,
            )

            uploadGate.complete(Unit)
            runCurrent()

            assertEquals(
                ForwardTargetPhase.Sending,
                session.state.value.targets
                    .single()
                    .phase,
            )
            assertFalse(
                session.state.value.targets
                    .single()
                    .slow,
            )
            publishGate.complete(Unit)
            advanceUntilIdle()
            assertEquals(ForwardOperationPhase.Completed, session.state.value.phase)
        }

    /** A retry starts its own slow timer, so the failed attempt's earlier start can never mark it slow early. */
    @Test
    fun retryIsNotMarkedSlowByAnEarlierAttemptsTimer() =
        runTest {
            val firstUpload = CompletableDeferred<Unit>()
            val retryUpload = CompletableDeferred<Unit>()
            val transport =
                RecordingForwardTransport(
                    failUploadOnceFor = mutableSetOf("target"),
                    uploadGates = ArrayDeque(listOf(firstUpload, retryUpload)),
                )
            val session = mediaSession(transport)

            session.start()
            advanceTimeBy(FORWARD_PHASE_SLOW_MILLIS - 5_000L)
            firstUpload.complete(Unit)
            runCurrent()
            assertEquals(ForwardOperationPhase.Failed, session.state.value.phase)

            session.retryFailed()
            advanceTimeBy(FORWARD_PHASE_SLOW_MILLIS - 1_000L)
            runCurrent()
            assertEquals(
                ForwardTargetPhase.Uploading,
                session.state.value.targets
                    .single()
                    .phase,
            )
            assertFalse(
                session.state.value.targets
                    .single()
                    .slow,
            )

            advanceTimeBy(1_000L)
            runCurrent()
            assertTrue(
                session.state.value.targets
                    .single()
                    .slow,
            )
            retryUpload.complete(Unit)
            advanceUntilIdle()
            assertEquals(ForwardOperationPhase.Completed, session.state.value.phase)
        }

    /** Slow preparation is announced well before the preparation deadline and cleared once that deadline fails it. */
    @Test
    fun slowPreparationIsAnnouncedBeforeThePreparationTimeout() =
        runTest {
            val transport = RecordingForwardTransport(materializeGates = ArrayDeque(listOf(CompletableDeferred())))
            val session = mediaSession(transport)

            session.start()
            advanceTimeBy(FORWARD_PHASE_SLOW_MILLIS)
            runCurrent()
            assertEquals(ForwardOperationPhase.Preparing, session.state.value.phase)
            assertTrue(session.state.value.preparationSlow)

            advanceUntilIdle()

            assertEquals(ForwardOperationPhase.Failed, session.state.value.phase)
            assertEquals(
                ForwardFailureStage.PreparationTimeout,
                session.state.value.targets
                    .single()
                    .failureStage,
            )
            assertFalse(session.state.value.preparationSlow)
        }

    /**
     * Once a destination has started publishing, Cancel stays off for the operation: a publish that timed out may
     * still land in MDK, so a failed destination with nothing counted as sent must not make Cancel available again
     * while another destination is still uploading.
     */
    @Test
    fun aPublishTimeoutKeepsCancelOffWhileAnotherDestinationIsStillUploading() =
        runTest {
            val secondUpload = CompletableDeferred<Unit>()
            val transport =
                RecordingForwardTransport(
                    scriptedOutcomeOnce = mutableMapOf("first" to ScriptedAdmission.StallAdmission),
                    scriptedMessageIndex = 0,
                    uploadGateFor = mapOf("second" to secondUpload),
                )
            val session =
                ForwardSession(
                    scope = this,
                    messages = listOf(mediaPayload("media", null, "notes.txt")),
                    targetGroupIds = listOf("first", "second"),
                    transport = transport,
                )

            session.start()
            advanceTimeBy(FORWARD_PUBLISH_ADMISSION_MILLIS + 1)
            runCurrent()
            val snapshot = session.state.value
            val first = snapshot.targets.single { it.groupIdHex == "first" }
            val second = snapshot.targets.single { it.groupIdHex == "second" }
            assertEquals(ForwardOperationPhase.Running, snapshot.phase)
            assertEquals(ForwardFailureStage.PublishTimeout, first.failureStage)
            assertEquals(0, first.sentMessages)
            assertEquals(ForwardTargetPhase.Uploading, second.phase)
            assertFalse(snapshot.canCancel)

            session.cancel()
            secondUpload.complete(Unit)
            advanceUntilIdle()
            assertEquals(ForwardOperationPhase.PartialFailure, session.state.value.phase)
        }

    /** Cancelling while a slow upload is still running publishes nothing and leaves no slow notice behind. */
    @Test
    fun cancellingASlowUploadPublishesNothing() =
        runTest {
            val transport = RecordingForwardTransport(uploadGate = CompletableDeferred())
            val session = mediaSession(transport)

            session.start()
            advanceTimeBy(FORWARD_PHASE_SLOW_MILLIS)
            runCurrent()
            assertTrue(
                session.state.value.targets
                    .single()
                    .slow,
            )
            assertTrue(session.state.value.canCancel)

            session.cancel()
            advanceUntilIdle()

            assertEquals(ForwardOperationPhase.Cancelled, session.state.value.phase)
            assertFalse(
                session.state.value.targets
                    .single()
                    .slow,
            )
            assertEquals(emptyList<Pair<String, Int>>(), transport.published)
        }

    /** One-target text forward used by the slow-phase tests. */
    private fun CoroutineScope.textSession(transport: ForwardTransport) =
        ForwardSession(
            scope = this,
            messages = listOf(textPayload("first", "first")),
            targetGroupIds = listOf("target"),
            transport = transport,
        )

    /** One-target single-attachment forward used by the slow-phase tests. */
    private fun CoroutineScope.mediaSession(transport: ForwardTransport) =
        ForwardSession(
            scope = this,
            messages = listOf(mediaPayload("media", null, "notes.txt")),
            targetGroupIds = listOf("target"),
            transport = transport,
        )

    private class RecordingForwardTransport(
        private val failUploadOnceFor: MutableSet<String> = mutableSetOf(),
        private val stallUploadOnceFor: MutableSet<String> = mutableSetOf(),
        /** Destinations whose message at [scriptedMessageIndex] behaves once like a given MDK admission outcome. */
        private val scriptedOutcomeOnce: MutableMap<String, ScriptedAdmission> = mutableMapOf(),
        private val scriptedMessageIndex: Int = 1,
        private val uploadGate: CompletableDeferred<Unit>? = null,
        private val uploadGates: ArrayDeque<CompletableDeferred<Unit>> = ArrayDeque(),
        /** Destinations whose every upload waits on their own gate, ahead of the shared gates. */
        private val uploadGateFor: Map<String, CompletableDeferred<Unit>> = emptyMap(),
        private val publishGate: CompletableDeferred<Unit>? = null,
        private val materializeGate: CompletableDeferred<Unit>? = null,
        private val materializeGates: ArrayDeque<CompletableDeferred<Unit>> = ArrayDeque(),
        private val failMaterializationCleanup: Boolean = false,
        private val onMaterializationCleanup: () -> Unit = {},
        private val failMaterializeOnCall: Int? = null,
        private val materializedByteCount: Int = 1,
        private val materializedBytes: ByteArray? = null,
        private val onUpload: () -> Unit = {},
    ) : ForwardTransport {
        val materializedIndices = mutableListOf<Int>()
        val uploadTargets = mutableListOf<String>()
        val publishStartIndices = mutableListOf<Int>()

        /** MDK's token ledger: one status per admitted token, as `localSendStatus` would report it. */
        val tokenStatuses = mutableMapOf<String, LocalSendStatusFfi>()

        /** Tokens admitted while their message was held at Queued, which [releaseQueued] hands to the engine. */
        private val heldQueued = mutableSetOf<String>()

        /** Tokens whose admission stalled past the deadline and that [landStalledAdmissions] lets land later. */
        private val stalledAdmissions = mutableListOf<Triple<String, Int, String>>()

        /** Every rejected message, as (destination, message index), each of which left a failed bubble. */
        val rejected = mutableListOf<Pair<String, Int>>()

        /** Reference hashes encrypted at an epoch the destination has left, which MDK refuses to admit again. */
        private val staleReferenceHashes = mutableSetOf<String>()

        /** Every reference hash uploaded to each destination, all of which an epoch change there makes stale. */
        private val uploadedHashesByTarget = mutableMapOf<String, MutableSet<String>>()

        /** Hands every message held at Queued to the engine, as MDK's account worker eventually does. */
        fun releaseQueued() {
            heldQueued.forEach { token -> tokenStatuses[token] = LocalSendStatusFfi.EngineOwned }
            heldQueued.clear()
        }

        /** Lets admissions that outlived their wait land after all, the way MDK finishes them in the background. */
        fun landStalledAdmissions() {
            stalledAdmissions.forEach { (target, index, token) ->
                tokenStatuses[token] = LocalSendStatusFfi.EngineOwned
                published += target to index
            }
            stalledAdmissions.clear()
        }

        /** How many distinct messages MDK holds for [index] to [target], whatever their state. */
        fun admissionsOf(
            target: String,
            index: Int,
        ): Int = published.count { it == target to index } + rejected.count { it == target to index }

        val published = mutableListOf<Pair<String, Int>>()

        /** Every client token the session supplied, as (destination, message index, token), in admission order. */
        val admissionTokens = mutableListOf<Triple<String, Int, String>>()

        /** The tokens supplied for message [index] across every destination and attempt, in order. */
        fun tokensForMessage(index: Int): List<String> =
            admissionTokens
                .filter { it.second == index }
                .map { it.third }

        val publishedMediaHashes = mutableMapOf<String, List<String>>()
        val publishedMediaCaptions = mutableMapOf<String, List<String?>>()
        val publishedMediaFileNames = mutableMapOf<String, List<String>>()
        val publishedMediaTypes = mutableMapOf<String, List<String>>()
        var cancelledMaterializations = 0
        private var materializeCallCount = 0

        /** Suspends on the configured attempt gate before returning source metadata and plaintext. */
        override suspend fun materialize(
            sourceGroupIdHex: String,
            sourceMessageIdHex: String,
            source: ForwardAttachmentSource,
        ): PendingAttachment {
            materializedIndices += source.attachmentIndex
            (materializeGates.removeFirstOrNull() ?: materializeGate)?.await()
            materializeCallCount += 1
            if (materializeCallCount == failMaterializeOnCall) error("materialize failed")
            return PendingAttachment(
                plaintextBytes =
                    materializedBytes
                        ?: ByteArray(materializedByteCount) { source.attachmentIndex.toByte() },
                mediaType = source.reference.mediaType,
                fileName = source.reference.fileName,
                dim = source.reference.dim,
                thumbhash = source.reference.thumbhash,
            )
        }

        /** Records the session's request to discard transport work that exceeded its preparation lifetime. */
        override fun cancelStalledMaterialization() {
            cancelledMaterializations += 1
            onMaterializationCleanup()
            if (failMaterializationCleanup) error("cleanup failed")
        }

        /**
         * Records the upload and waits on its per-attempt gate, then fails or never starts once per chosen target, or
         * uploads.
         */
        override suspend fun upload(
            targetGroupIdHex: String,
            message: PreparedForwardMessage.Media,
        ): List<MediaAttachmentReferenceFfi> {
            uploadTargets += targetGroupIdHex
            onUpload()
            uploadGateFor[targetGroupIdHex]?.await()
            (uploadGates.removeFirstOrNull() ?: uploadGate)?.await()
            if (failUploadOnceFor.remove(targetGroupIdHex)) error("upload failed")
            if (stallUploadOnceFor.remove(targetGroupIdHex)) throw ForwardUploadStalledException(null)
            // Each upload encrypts afresh, so a re-upload yields references distinct from any earlier, stale ones.
            val uploadNumber = uploadTargets.size
            return message.attachments
                .mapIndexed { index, attachment ->
                    reference(
                        fileName = attachment.fileName,
                        hashPrefix = "$targetGroupIdHex-$index-u$uploadNumber",
                    )
                }.also { references ->
                    uploadedHashesByTarget.getOrPut(targetGroupIdHex) { mutableSetOf() } +=
                        references.map(MediaAttachmentReferenceFfi::ciphertextSha256)
                }
        }

        /**
         * Waits on the publish gate, then publishes each message through the production [admitForwardPublish]
         * against this transport's token ledger, so session tests exercise the real admission, handoff and
         * no-duplicate rules rather than exceptions thrown by a fake.
         */
        override suspend fun publishBatch(
            targetGroupIdHex: String,
            messages: List<PreparedForwardMessage>,
            uploadedReferences: Map<Int, List<MediaAttachmentReferenceFfi>>,
            startIndex: Int,
            clientTokenFor: (messageIndex: Int) -> String,
            onBeforeMessagePublished: (messageIndex: Int) -> Unit,
            onMessagePublished: (messageIndex: Int) -> Unit,
        ) {
            publishStartIndices += startIndex
            publishGate?.await()
            for (index in startIndex until messages.size) {
                currentCoroutineContext().ensureActive()
                onBeforeMessagePublished(index)
                val token = clientTokenFor(index)
                admissionTokens += Triple(targetGroupIdHex, index, token)
                val scripted = if (index == scriptedMessageIndex) scriptedOutcomeOnce[targetGroupIdHex] else null
                admitForwardPublish(
                    messageIndex = index,
                    token = token,
                    probe = { probeToken(token) },
                    admit = {
                        admitToken(targetGroupIdHex, index, token, scripted, uploadedReferences[index].orEmpty())
                    },
                )
                val message = messages[index]
                if (message is PreparedForwardMessage.Media) {
                    publishedMediaHashes[targetGroupIdHex] =
                        uploadedReferences.getValue(index).map(MediaAttachmentReferenceFfi::ciphertextSha256)
                    publishedMediaCaptions[targetGroupIdHex] = listOf(message.caption)
                    publishedMediaFileNames[targetGroupIdHex] = message.attachments.map(PendingAttachment::fileName)
                    publishedMediaTypes[targetGroupIdHex] = message.attachments.map(PendingAttachment::mediaType)
                }
                onMessagePublished(index)
            }
        }

        /** Reads a token the way `localSendStatus` does. */
        private fun probeToken(token: String): LocalSendStatusFfi? = tokenStatuses[token]

        /**
         * Admits a token as MDK would: an owned token returns its existing identity without a new message, a fresh
         * one creates exactly one, and the scripted outcome for this destination's scripted message applies once.
         */
        @Suppress("LongMethod")
        private suspend fun admitToken(
            target: String,
            index: Int,
            token: String,
            scripted: ScriptedAdmission?,
            references: List<MediaAttachmentReferenceFfi>,
        ): LocalSendAcceptanceFfi {
            val acceptance = LocalSendAcceptanceFfi(clientToken = token, messageIdHex = "message-$token")
            if (tokenStatuses[token] != null) return acceptance
            val hashes = references.map(MediaAttachmentReferenceFfi::ciphertextSha256)
            if (hashes.any(staleReferenceHashes::contains)) throw staleEpochRefusal()
            if (scripted != null) scriptedOutcomeOnce.remove(target)
            when (scripted) {
                ScriptedAdmission.FailBeforeAdmission -> error("admission refused before it was stored")
                ScriptedAdmission.StallAdmission -> {
                    stalledAdmissions += Triple(target, index, token)
                    awaitCancellation()
                }
                ScriptedAdmission.HoldQueued -> {
                    tokenStatuses[token] = LocalSendStatusFfi.Queued
                    heldQueued += token
                    published += target to index
                }
                ScriptedAdmission.RejectWhileQueued -> {
                    tokenStatuses[token] = LocalSendStatusFfi.Queued
                    rejected += target to index
                    // The account worker drains it and fails validation after an epoch change, so every reference
                    // uploaded to this destination before it is stale from now on.
                    tokenStatuses[token] = LocalSendStatusFfi.Rejected
                    staleReferenceHashes += uploadedHashesByTarget[target].orEmpty()
                }
                ScriptedAdmission.RefuseStaleEpoch -> {
                    staleReferenceHashes += uploadedHashesByTarget[target].orEmpty()
                    throw staleEpochRefusal()
                }
                null -> {
                    tokenStatuses[token] = LocalSendStatusFfi.EngineOwned
                    published += target to index
                }
            }
            return acceptance
        }
    }

    /** One scripted MDK admission outcome for a destination's scripted message. */
    private enum class ScriptedAdmission {
        /** The admission call fails and nothing is stored. */
        FailBeforeAdmission,

        /** The admission call never returns within the deadline; [RecordingForwardTransport.landStalledAdmissions]. */
        StallAdmission,

        /** Admitted, but held at Queued until [RecordingForwardTransport.releaseQueued]. */
        HoldQueued,

        /** Admitted and queued, then rejected when the account worker drains it; its references are stale after. */
        RejectWhileQueued,

        /** Refused synchronously at admission because its references were encrypted at an epoch the group left. */
        RefuseStaleEpoch,
    }

    private fun textPayload(
        id: String,
        text: String,
    ) = ForwardMessagePayload.Text("source", id, text)

    private fun mediaPayload(
        id: String,
        caption: String?,
        vararg fileNames: String,
    ) = ForwardMessagePayload.Media(
        sourceGroupIdHex = "source",
        sourceMessageIdHex = id,
        caption = caption,
        attachments =
            fileNames.mapIndexed { index, fileName ->
                ForwardAttachmentSource(index, reference(fileName, "source-$index"))
            },
    )

    /** Builds the APK payload used to verify both liveness and typed metadata preservation. */
    private fun apkPayload() =
        ForwardMessagePayload.Media(
            sourceGroupIdHex = "source",
            sourceMessageIdHex = "apk-message",
            caption = null,
            attachments =
                listOf(
                    ForwardAttachmentSource(
                        attachmentIndex = 0,
                        reference = reference("white-noise.apk", "apk-source", mediaType = APK_MIME),
                    ),
                ),
        )

    private companion object {
        /** Builds a complete encrypted-media reference while allowing tests to retain the source MIME type. */
        fun reference(
            fileName: String,
            hashPrefix: String,
            sourceEpoch: ULong = 7uL,
            mediaType: String = "application/octet-stream",
        ) = MediaAttachmentReferenceFfi(
            locators = listOf(MediaLocatorFfi("blossom-v1", "https://media.example/$hashPrefix")),
            ciphertextSha256 = "$hashPrefix-${"a".repeat(64)}",
            plaintextSha256 = "b".repeat(64),
            nonceHex = "c".repeat(24),
            fileName = fileName,
            mediaType = mediaType,
            version = EncryptedMediaVersionFfi.V1,
            sourceEpoch = sourceEpoch,
            dim = null,
            thumbhash = null,
        )

        const val APK_MIME = "application/vnd.android.package-archive"
    }
}

/** MDK's synchronous refusal of a media reference encrypted at an epoch the destination has left. */
private fun staleEpochRefusal() = MarmotKitException.MediaReferenceStaleEpoch(sourceEpoch = 3uL, currentEpoch = 4uL)
