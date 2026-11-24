package dev.ipf.whitenoise.android.state

import dev.ipf.whitenoise.android.appModuleSource
import dev.ipf.whitenoise.android.conversationControllerFiles
import dev.ipf.whitenoise.android.functionBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Enforces that disappearing-message expiry deferral, foreground sweep rows, retained expiry checks,
 * and read anchoring derive row order only from [ConversationController.loadedTimelineMessagesInDisplayOrder].
 * Append-only `timelineOrder` supplies loaded membership, never positions.
 */
class DisappearingExpiryOrderCoverageTest {
    /** The shared helper deduplicates optimistic-first by raw id before display ordering, never by `timelineOrder`. */
    @Test
    fun chronologicalHelperUsesDisplayOrderingWithOptimisticFirstPrecedence() {
        val sweepSource = appModuleSource(STATE_DIR + "ConversationControllerExpirySweep.kt")
        val projectionSource = appModuleSource(STATE_DIR + "ConversationControllerProjection.kt")
        val body = sweepSource.functionBody("loadedTimelineMessagesInDisplayOrder")
        val optimisticIndex = body.indexOf("optimisticMessages.values")
        val projectedIndex = body.indexOf("loadedProjectedRows()")
        val rawIdDedupIndex = body.indexOf("distinctBy { it.record.messageIdHex }")
        val displayOrderIndex = body.indexOf("orderTimelineMessagesForDisplay(")

        assertTrue(
            "loadedTimelineMessagesInDisplayOrder must order rows with orderTimelineMessagesForDisplay",
            displayOrderIndex >= 0,
        )
        assertTrue(
            "loadedTimelineMessagesInDisplayOrder must include optimistic rows before projected rows",
            optimisticIndex >= 0 && projectedIndex > optimisticIndex,
        )
        assertTrue(
            "loadedTimelineMessagesInDisplayOrder must deduplicate optimistic-first by raw message id before ordering",
            rawIdDedupIndex > projectedIndex && displayOrderIndex > rawIdDedupIndex,
        )
        assertFalse(
            "loadedTimelineMessagesInDisplayOrder must never read append-only timelineOrder directly",
            "timelineOrder" in body,
        )
        assertTrue(
            "loadedProjectedRows must resolve timelineOrder membership through timelineItemsById",
            "timelineOrder.mapNotNull { timelineItemsById[it] }" in projectionSource,
        )
    }

    /** Expiry filtering, sweep rows, retained checks, and read anchoring must share the chronological helper. */
    @Test
    fun expiryDeferralAndReadAnchoringUseOnlyTheChronologicalHelper() {
        val projection = appModuleSource(STATE_DIR + "ConversationControllerProjection.kt")
        val sweep = appModuleSource(STATE_DIR + "ConversationControllerExpirySweep.kt")
        val readState = appModuleSource(STATE_DIR + "ConversationControllerReadState.kt")
        val controller = appModuleSource(STATE_DIR + "ConversationController.kt")

        val orderedSites =
            mapOf(
                "publishTimelineFromIndexesInternal" to projection.functionBody("publishTimelineFromIndexesInternal"),
                "foregroundSweepExpiryRows" to sweep.functionBody("foregroundSweepExpiryRows"),
                "expiryOrderFor" to sweep.functionBody("expiryOrderFor"),
                "anchorReadExpiryUpTo" to readState.functionBody("anchorReadExpiryUpTo"),
            )
        orderedSites.forEach { (name, body) ->
            assertTrue(
                "$name must derive message order from loadedTimelineMessagesInDisplayOrder()",
                "loadedTimelineMessagesInDisplayOrder()" in body,
            )
            assertFalse("$name must never read append-only timelineOrder for positions", "timelineOrder" in body)
        }
        assertTrue(
            "expiryOrderFor must preserve first-occurrence ordering via firstMessageOrder(",
            "firstMessageOrder(" in sweep.functionBody("expiryOrderFor"),
        )

        val perRowSites =
            mapOf(
                "retainedExpiryRow" to sweep.functionBody("retainedExpiryRow"),
                "currentActionTimelineItem" to controller.functionBody("currentActionTimelineItem"),
            )
        perRowSites.forEach { (name, body) ->
            assertTrue("$name must take its row order from expiryOrderFor(", "expiryOrderFor(" in body)
            assertFalse("$name must never read append-only timelineOrder for positions", "timelineOrder" in body)
        }
    }

    /** Controller files read `timelineOrder` only for membership and mutate it only through add/remove/clear. */
    @Test
    fun controllerReadsTimelineOrderOnlyForMembershipAndPreservesAppendOnlyInsert() {
        conversationControllerFiles().forEach { file ->
            assertEquals(
                "${file.name} may only resolve timelineOrder membership or mutate it via add/remove/clear",
                emptyList<String>(),
                timelineOrderViolations(file),
            )
        }

        val projection = appModuleSource(STATE_DIR + "ConversationControllerProjection.kt")
        val insertBody = projection.functionBody("insertTimelineItemId")
        assertTrue(
            "insertTimelineItemId must keep its O(1) append to timelineOrder",
            "timelineOrder.add(itemId)" in insertBody,
        )
    }

    /** Lists every line of [file] that touches the controller's `timelineOrder` list outside the allowed uses. */
    private fun timelineOrderViolations(file: File): List<String> =
        file
            .readLines()
            .withIndex()
            .filter { (_, line) -> hasDisallowedTimelineOrderUse(line) }
            .map { (index, line) -> "${file.name}:${index + 1}: ${line.trim()}" }

    /** Whether the code part of [line] references the `timelineOrder` list for anything but membership or mutation. */
    private fun hasDisallowedTimelineOrderUse(line: String): Boolean {
        val code =
            line
                .substringBefore("//")
                .trim()
                .replace(NAMED_ARG_FORWARD, "")
                .replace(QUALIFIED_LIST_REFERENCE, "timelineOrder")
        val isComment = code.startsWith("*") || code.startsWith("/*")
        return !isComment &&
            LIST_REFERENCE.findAll(code).any { match ->
                !ALLOWED_LIST_USE.containsMatchIn(code.substring(match.range.first))
            }
    }

    private companion object {
        const val STATE_DIR = "src/main/java/dev/ipf/whitenoise/android/state/"

        /** A named argument forwarding a same-named `timelineOrder` parameter, not the controller list. */
        val NAMED_ARG_FORWARD = Regex("""\btimelineOrder\s*=\s*timelineOrder\b""")

        /** Explicitly qualified controller list references, normalized so they are checked too. */
        val QUALIFIED_LIST_REFERENCE = Regex("""\b(?:this|controller)\.timelineOrder\b""")

        /** A bare list reference: not a row property, declaration, named argument or parameter, but `==` counts. */
        val LIST_REFERENCE = Regex("""(?<![.\w])(?<!val )timelineOrder\b(?!\s*(?:=(?!=)|:))""")

        /** Membership resolved through `timelineItemsById`, with no positional access. */
        const val MEMBERSHIP_USE = """mapNotNull(?:To\(\w+\))?\s*\{\s*timelineItemsById\[it]\s*}"""

        /** Mutation via add/remove/clear, or a [MEMBERSHIP_USE] read. */
        val ALLOWED_LIST_USE = Regex("""^timelineOrder\.(?:(?:add|remove|clear)\(|$MEMBERSHIP_USE)""")
    }
}
