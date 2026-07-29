package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.AppMessageRecordFfi
import dev.ipf.marmotkit.MarkdownDocumentFfi
import dev.ipf.marmotkit.TimelineMessageRecordFfi
import dev.ipf.marmotkit.TimelineReactionSummaryFfi
import dev.ipf.whitenoise.android.functionBody
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class TimelineOrderingInversionTest {
    @Test
    fun orphanReleaseTargetsPreservesWhoseBubbleIsGoneAndProjectionLanded() {
        val orphaned =
            orphanedOptimisticSendPreserveIds(
                optimisticSendPreserveIds = setOf("confirmed"),
                optimisticKeys = emptySet(),
                projectedMessageIds = setOf("confirmed"),
            )

        assertEquals(setOf("confirmed"), orphaned)
    }

    @Test
    fun orphanReleaseKeepsAPreserveWhileItsOptimisticBubbleStillCoexists() {
        val orphaned =
            orphanedOptimisticSendPreserveIds(
                optimisticSendPreserveIds = setOf("confirmed"),
                // The "Sent" bubble for this id is still on screen during handoff.
                optimisticKeys = setOf("msg:confirmed"),
                projectedMessageIds = setOf("confirmed"),
            )

        assertTrue(orphaned.isEmpty())
    }

    @Test
    fun orphanReleaseWaitsUntilTheProjectionActuallyExists() {
        val orphaned =
            orphanedOptimisticSendPreserveIds(
                optimisticSendPreserveIds = setOf("confirmed"),
                optimisticKeys = emptySet(),
                // Projection not yet applied — nothing to settle onto.
                projectedMessageIds = emptySet(),
            )

        assertTrue(orphaned.isEmpty())
    }

    @Test
    fun preserveOwnershipSurvivesControllerReplacementAndCanBeReleasedByTheReplacement() {
        val conversationPreserves = OptimisticSendPositionPreserves()
        conversationPreserves.add("confirmed")

        // AppState hands the same conversation-retained owner to a replacement
        // controller after the sender's controller leaves the screen.
        val replacementControllerPreserves = conversationPreserves
        assertEquals(setOf("confirmed"), replacementControllerPreserves.snapshot())

        val released =
            replacementControllerPreserves.releaseOrphaned(
                optimisticKeys = emptySet(),
                projectedMessageIds = setOf("confirmed"),
            )

        assertEquals(setOf("confirmed"), released)
        assertTrue(conversationPreserves.snapshot().isEmpty())
    }

    @Test
    fun confirmedMediaBridgeBecomesPrunableAsSoonAsItsProjectionExists() {
        assertEquals(
            setOf("msg:confirmed"),
            confirmedOptimisticMessageKeys(
                optimisticKeys = setOf("msg:pending", "msg:confirmed"),
                projectedMessageIds = setOf("confirmed"),
            ),
        )
    }

    @Test
    fun controllerReplacementRoutesTheHandoffToTheNewestMatchingController() {
        data class AttachedController(
            val name: String,
            val conversation: String,
        )

        val old = AttachedController("old", "group")
        val replacement = AttachedController("replacement", "group")
        val unrelated = AttachedController("unrelated", "other")

        assertEquals(
            replacement,
            newestMatchingController(listOf(old, unrelated, replacement)) {
                it.conversation == "group"
            },
        )
    }

    @Test
    fun settlementPublishesOnceOnTheNextTurnAndCoalescesDuplicateRequests() =
        runTest {
            var publications = 1
            var settlementJob: Job? = null
            settlementJob =
                deferTimelinePositionSettlement(backgroundScope, settlementJob) {
                    publications += 1
                }
            val firstJob = settlementJob

            settlementJob =
                deferTimelinePositionSettlement(backgroundScope, settlementJob) {
                    publications += 1
                }

            assertSame(firstJob, settlementJob)
            assertEquals("the handoff snapshot must remain observable first", 1, publications)
            runCurrent()
            assertEquals("true projected order must publish exactly once afterward", 2, publications)
        }

    @Test
    fun confirmedSnapshotIsBuiltBeforeOrphanedPreservesAreReleased() {
        val publishBody = controllersSource().readText().functionBody("publishTimelineFromIndexesInternal")
        val snapshotAssignment = publishBody.indexOf("timeline =")
        val orphanRelease = publishBody.indexOf("releaseOrphanedOptimisticSendPreserves()")
        val settlementSchedule = publishBody.indexOf("scheduleTimelinePositionSettlement()")

        assertTrue("timeline snapshot assignment must exist", snapshotAssignment >= 0)
        assertTrue(
            "the confirmed row must publish once with its optimistic position before cleanup",
            orphanRelease > snapshotAssignment,
        )
        assertTrue(
            "cleanup must schedule a second observable publication in true order",
            settlementSchedule > orphanRelease,
        )
    }

    @Test
    fun mediaBridgeUsesTheTrackedOptimisticPreserveLifecycle() {
        val uploadBody = controllersSource().readText().functionBody("performMediaUpload")
        val bridgeInsert = uploadBody.indexOf("optimisticMessages[\"msg:\$confirmedId\"]")
        val trackedPreserve =
            uploadBody.indexOf(
                "preserveOptimisticDisplayPosition(confirmedId, confirmedId)",
                startIndex = bridgeInsert,
            )

        assertTrue("confirmed media bridge insertion must exist", bridgeInsert >= 0)
        assertTrue(
            "media bridge overrides must be registered for orphan cleanup",
            trackedPreserve > bridgeInsert,
        )
        assertTrue(
            "media completion must route reconciliation to the attached controller",
            uploadBody.indexOf(
                "appState.deliverConfirmedMediaHandoff(",
                startIndex = trackedPreserve,
            ) >
                trackedPreserve,
        )

        val handoffBody = controllersSource().readText().functionBody("acceptConfirmedMediaHandoff")
        assertTrue(
            "the receiving controller must prune the exact confirmed bridge",
            "optimisticMessages.remove(\"msg:\$confirmedId\")" in handoffBody,
        )
        assertTrue(
            "the receiving controller must publish without another engine event",
            "publishTimelineFromIndexes()" in handoffBody,
        )
    }

    @Test
    fun adjacentInversionsCaptureAnOverrideThatReversesEngineOrder() {
        // "older" carries a display override (200) that renders it below the newer
        // row, but its engine timeline time (100) is older — the #1578 symptom.
        val older = projectedMsg("older", displayedAt = 200uL, timelineAt = 100uL, receivedAt = 100uL, order = 9uL)
        val newer = projectedMsg("newer", displayedAt = 150uL, timelineAt = 150uL, receivedAt = 150uL, order = 0uL)

        val inversion =
            adjacentTimelineInversions(
                listOf(older, newer).sortedWith(::compareTimelineMessages),
            ).single()

        assertEquals("newer", inversion.above.record.messageIdHex)
        assertEquals("older", inversion.below.record.messageIdHex)
        assertTrue(inversion.sourceTimelineInverted)
        assertTrue(inversion.arrivalInverted)
    }

    @Test
    fun adjacentInversionsCaptureSenderTimeSkewAgainstArrivalOrder() {
        // A delayed relay delivery: the older-by-send-time row arrived last, so it
        // is arrival-inverted but NOT source-inverted (faithful engine skew, #3).
        val delayedOlder =
            projectedMsg("older", displayedAt = 100uL, timelineAt = 100uL, receivedAt = 200uL, order = 0uL)
        val arrivedFirst =
            projectedMsg("newer", displayedAt = 150uL, timelineAt = 150uL, receivedAt = 100uL, order = 0uL)

        val inversion =
            adjacentTimelineInversions(
                listOf(delayedOlder, arrivedFirst).sortedWith(::compareTimelineMessages),
            ).single()

        assertFalse(inversion.sourceTimelineInverted)
        assertTrue(inversion.arrivalInverted)
    }

    @Test
    fun adjacentInversionsIgnoreChronologicalAndOptimisticPairs() {
        val older = projectedMsg("older", displayedAt = 100uL, timelineAt = 100uL, receivedAt = 100uL, order = 0uL)
        val newer = projectedMsg("newer", displayedAt = 150uL, timelineAt = 150uL, receivedAt = 150uL, order = 0uL)
        // An optimistic row has no projection yet, so it is never flagged.
        val optimistic = optimisticMsg("optimistic", recordedAt = 200uL, order = 1uL)

        assertTrue(adjacentTimelineInversions(listOf(older, newer, optimistic)).isEmpty())
    }

    private fun projectedMsg(
        id: String,
        displayedAt: ULong,
        timelineAt: ULong,
        receivedAt: ULong,
        order: ULong,
    ): TimelineMessage {
        val projected =
            TimelineMessageRecordFfi(
                messageIdHex = id,
                sourceMessageIdHex = id,
                direction = "received",
                groupIdHex = "g",
                sender = "s",
                plaintext = "",
                contentTokens =
                    MarkdownDocumentFfi(
                        truncated = false,
                        blocks = emptyList(),
                        blankLinesBefore = ByteArray(0),
                    ),
                kind = 9uL,
                tags = emptyList(),
                timelineAt = timelineAt,
                receivedAt = receivedAt,
                replyToMessageIdHex = null,
                replyPreview = null,
                mediaJson = null,
                media = emptyList(),
                agentTextStreamJson = null,
                groupSystem = null,
                reactions = TimelineReactionSummaryFfi(byEmoji = emptyList(), userReactions = emptyList()),
                deleted = false,
                deletedByMessageIdHex = null,
                invalidationStatus = null,
                sourceEpoch = null,
                retentionSeconds = null,
                retentionExpiresAt = null,
            )
        return TimelineMessage(
            id = "msg:$id",
            record = record(id, recordedAt = displayedAt),
            status = MessageStatus.Sent,
            projected = projected,
            timelineOrder = order,
        )
    }

    private fun optimisticMsg(
        id: String,
        recordedAt: ULong,
        order: ULong,
    ): TimelineMessage =
        TimelineMessage(
            id = "msg:$id",
            record = record(id, recordedAt = recordedAt),
            status = MessageStatus.Pending,
            timelineOrder = order,
        )

    private fun record(
        id: String,
        recordedAt: ULong,
    ): AppMessageRecordFfi =
        AppMessageRecordFfi(
            messageIdHex = id,
            direction = "received",
            groupIdHex = "g",
            sender = "s",
            plaintext = "",
            contentTokens =
                MarkdownDocumentFfi(
                    truncated = false,
                    blocks = emptyList(),
                    blankLinesBefore = ByteArray(0),
                ),
            kind = 9uL,
            tags = emptyList(),
            sourceEpoch = null,
            retentionSeconds = null,
            retentionExpiresAt = null,
            recordedAt = recordedAt,
            receivedAt = recordedAt,
        )

    private fun controllersSource(): File =
        listOf(
            File("src/main/java/dev/ipf/whitenoise/android/state/Controllers.kt"),
            File("app/src/main/java/dev/ipf/whitenoise/android/state/Controllers.kt"),
        ).firstOrNull(File::exists) ?: error("Missing Controllers.kt")
}
