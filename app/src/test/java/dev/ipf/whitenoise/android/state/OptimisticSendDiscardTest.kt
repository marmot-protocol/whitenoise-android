package dev.ipf.whitenoise.android.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Pins which Android presentation state each discard path releases. Every
 * path clears the optimistic message, acceptance callback, message index and
 * upload maps; the rows below are the intended differences.
 */
class OptimisticSendDiscardTest {
    private data class Row(
        val releasesEditsAndFailureNotice: Boolean,
        val removesPhase: Boolean,
        val rollsBackPreview: Boolean,
        val diagnostic: DiscardedSendDiagnostic,
    )

    private fun OptimisticSendDiscard.row() =
        Row(releasesEditsAndFailureNotice, removesPhase, rollsBackPreview, diagnostic)

    @Test
    fun eachDiscardPathKeepsItsIntendedDifferences() {
        val forget = DiscardedSendDiagnostic.FORGET
        val expected =
            mapOf(
                "cancelled" to Row(true, false, true, forget),
                "abandoned" to Row(false, true, true, forget),
                "discarded in flight" to Row(false, false, true, forget),
                "discarded after publish" to Row(false, false, false, DiscardedSendDiagnostic.COMPLETE_DROPPED),
                "discarded after media failure" to Row(false, false, true, DiscardedSendDiagnostic.LEAVE_TRACKED),
            )
        val actual =
            mapOf(
                "cancelled" to OptimisticSendDiscard.CANCELLED.row(),
                "abandoned" to OptimisticSendDiscard.ABANDONED.row(),
                "discarded in flight" to OptimisticSendDiscard.DISCARDED_IN_FLIGHT.row(),
                "discarded after publish" to OptimisticSendDiscard.DISCARDED_AFTER_PUBLISH.row(),
                "discarded after media failure" to OptimisticSendDiscard.DISCARDED_AFTER_MEDIA_FAILURE.row(),
            )
        assertEquals(expected, actual)
    }

    @Test
    fun sharedCleanupAlwaysReleasesTheBubbleAndUploadState() {
        val body = discardFunctionBody()
        val unconditional =
            listOf(
                "optimisticMessages.remove(optimisticKey)",
                "durableAcceptanceCallbacks.remove(optimisticKey)",
                "messageById.remove(tempId)",
                "retainedMediaUploads.remove(optimisticKey)",
                "activeUploadKeys.remove(optimisticKey)",
                "publishTimelineFromIndexes()",
            )
        unconditional.forEach { call ->
            val line = body.lines().single { call in it }
            assertEquals("$call must not be behind an option", 8, line.indexOfFirst { !it.isWhitespace() })
        }
    }

    @Test
    fun everyDiscardSiteUsesTheSharedCleanup() {
        val source = controllersSource()
        val expectedCalls =
            mapOf(
                "CANCELLED" to 1,
                "ABANDONED" to 3,
                "DISCARDED_IN_FLIGHT" to 4,
                "DISCARDED_AFTER_PUBLISH" to 1,
                "DISCARDED_AFTER_MEDIA_FAILURE" to 1,
            )
        expectedCalls.forEach { (preset, count) ->
            val calls = Regex("""discardOptimisticSend\([^)]*OptimisticSendDiscard\.$preset\)""").findAll(source)
            assertEquals("call sites for $preset", count, calls.count())
        }
        val rollbacks = Regex("""rollbackOptimisticChatListPreview\(tempId\)""").findAll(source).count()
        assertEquals("only the shared cleanup rolls back a discarded preview", 1, rollbacks)
        assertTrue(
            "only the shared cleanup completes a discarded send as dropped",
            source.split("PerformanceResult.DROPPED").size == 2 &&
                "PerformanceResult.DROPPED" in discardFunctionBody(),
        )
    }

    private fun discardFunctionBody(): String {
        val source = controllersSource()
        val start = source.indexOf("private fun discardOptimisticSend(")
        assertTrue("shared discard cleanup must exist", start >= 0)
        val end = source.indexOf("\n    }\n", start)
        return source.substring(start, end)
    }

    private fun controllersSource(): String =
        listOf(
            File("src/main/java/dev/ipf/whitenoise/android/state/Controllers.kt"),
            File("app/src/main/java/dev/ipf/whitenoise/android/state/Controllers.kt"),
        ).first(File::exists).readText()
}
