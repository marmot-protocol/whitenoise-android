package dev.ipf.whitenoise.android.state

import org.junit.Assert.assertEquals
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

    private fun OptimisticSendDiscard.row(): Row =
        Row(
            releasesEditsAndFailureNotice = releasesEditsAndFailureNotice,
            removesPhase = removesPhase,
            rollsBackPreview = rollsBackPreview,
            diagnostic = diagnostic,
        )

    @Test
    fun eachDiscardPathKeepsItsIntendedDifferences() {
        val forget = DiscardedSendDiagnostic.FORGET
        val expected =
            mapOf(
                OptimisticSendDiscard.CANCELLED to Row(true, false, true, forget),
                OptimisticSendDiscard.ABANDONED to Row(false, true, true, forget),
                OptimisticSendDiscard.DISCARDED_IN_FLIGHT to Row(false, false, true, forget),
                OptimisticSendDiscard.DISCARDED_AFTER_PUBLISH to
                    Row(false, false, false, DiscardedSendDiagnostic.COMPLETE_DROPPED),
                OptimisticSendDiscard.DISCARDED_AFTER_MEDIA_FAILURE to
                    Row(false, false, true, DiscardedSendDiagnostic.LEAVE_TRACKED),
            )
        assertEquals(expected, OptimisticSendDiscard.entries.associateWith { it.row() })
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
        val dropped = Regex("""PerformanceResult\.DROPPED""").findAll(source).count()
        assertEquals("only the shared cleanup completes a discarded send as dropped", 1, dropped)
    }

    private fun controllersSource(): String =
        listOf(
            File("src/main/java/dev/ipf/whitenoise/android/state/ConversationController.kt"),
            File("app/src/main/java/dev/ipf/whitenoise/android/state/ConversationController.kt"),
        ).first(File::exists).readText()
}
