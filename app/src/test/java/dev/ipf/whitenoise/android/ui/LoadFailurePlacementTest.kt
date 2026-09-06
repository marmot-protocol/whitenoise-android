package dev.ipf.whitenoise.android.ui

import dev.ipf.whitenoise.android.state.ConversationLoadFailureEdge
import dev.ipf.whitenoise.android.state.ConversationSearchPageDirection
import dev.ipf.whitenoise.android.state.conversationLoadFailureEdge
import dev.ipf.whitenoise.android.ui.common.LoadFailurePlacement
import dev.ipf.whitenoise.android.ui.common.loadFailurePlacement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class LoadFailurePlacementTest {
    @Test
    fun noFailureShowsNoFailureSurface() {
        assertEquals(LoadFailurePlacement.None, loadFailurePlacement(hasFailure = false, hasLoadedContent = false))
        assertEquals(LoadFailurePlacement.None, loadFailurePlacement(hasFailure = false, hasLoadedContent = true))
    }

    @Test
    fun initialFailureUsesFullScreenRecovery() {
        assertEquals(LoadFailurePlacement.FullScreen, loadFailurePlacement(hasFailure = true, hasLoadedContent = false))
    }

    @Test
    fun refreshOrPagingFailureKeepsLoadedContentVisible() {
        assertEquals(LoadFailurePlacement.Inline, loadFailurePlacement(hasFailure = true, hasLoadedContent = true))
    }

    @Test
    fun pagingFailureStaysAtTheAffectedTimelineEdge() {
        assertEquals(
            ConversationLoadFailureEdge.TOP,
            conversationLoadFailureEdge(true, ConversationSearchPageDirection.OLDER),
        )
        assertEquals(
            ConversationLoadFailureEdge.BOTTOM,
            conversationLoadFailureEdge(true, ConversationSearchPageDirection.NEWER),
        )
        assertEquals(
            ConversationLoadFailureEdge.TOP,
            conversationLoadFailureEdge(false, ConversationSearchPageDirection.NEWER),
        )
    }

    @Test
    fun terminalRetryRekeysBothSubscriptionLifecyclesAndPreservesChats() {
        val sourceCandidates =
            listOf(
                File("src/main/java/dev/ipf/whitenoise/android"),
                File("app/src/main/java/dev/ipf/whitenoise/android"),
            )
        val sourceRoot = sourceCandidates.first(File::isDirectory)
        val controllers = File(sourceRoot, "state/Controllers.kt").readText()
        val shell = File(sourceRoot, "ui/navigation/MainShell.kt").readText()

        assertTrue(controllers.countOccurrences("terminalLoadFailure = true") >= 2)
        assertTrue(controllers.countOccurrences("retryGeneration += 1L") >= 2)
        assertTrue("chatsController.retryGeneration" in shell)
        assertTrue("ownedController.retryGeneration" in shell)
        assertTrue("preserveLoadedContent = chatsController.retryGeneration > 0L" in shell)
    }

    /** Keeps startup-failure completion behind the shared paint, accessibility, and telemetry reveal owner. */
    @Test
    fun optimisticRowStartupFailureCompletesTheAccessibilityRevealBarrier() {
        val sourceCandidates =
            listOf(
                File("src/main/java/dev/ipf/whitenoise/android"),
                File("app/src/main/java/dev/ipf/whitenoise/android"),
            )
        val sourceRoot = sourceCandidates.first(File::isDirectory)
        val controllers = File(sourceRoot, "state/Controllers.kt").readText()
        val screen = File(sourceRoot, "ui/conversation/ConversationScreen.kt").readText()
        val failureResolution =
            controllers
                .substringAfter("private fun discardInitialTimelineSeedForFailure")
                .substringBefore("private fun publishAuthoritativeEmptyInitialTimeline")
        val seededReconciliation =
            screen
                .substringAfter("SeededConversationAuthoritativeReconciliationEffect(")
                .substringBefore("// Edit events are derived state")
        val transcriptVisibilityOwner =
            screen
                .substringAfter("val transcriptVisibilityCommitted by")
                .substringBefore("// First-frame completion waits")

        assertTrue("publishTimelineFromIndexes()" in failureResolution)
        assertTrue("hasPublishedAuthoritativeTimeline = true" in failureResolution)
        assertTrue("hasPreparedInitialPresentation = true" in failureResolution)
        assertTrue("initialTimelineAnchored = true" in seededReconciliation)
        assertTrue("initialTimelineAnchored = initialTimelineAnchored" in transcriptVisibilityOwner)
        assertTrue("seededTailAlignmentCommitted = seededTailAlignmentCommitted" in transcriptVisibilityOwner)
        assertTrue("if (!transcriptVisibilityCommitted) hideFromAccessibility()" in screen)
    }

    @Test
    fun terminalConversationOpenStopsItsForegroundSweepSibling() {
        val sourceCandidates =
            listOf(
                File("src/main/java/dev/ipf/whitenoise/android"),
                File("app/src/main/java/dev/ipf/whitenoise/android"),
            )
        val sourceRoot = sourceCandidates.first(File::isDirectory)
        val runStart =
            File(sourceRoot, "state/Controllers.kt")
                .readText()
                .substringAfter("private suspend fun runStart(account: String)")
                .substringBefore("private fun discardInitialTimelineSeedForFailure")

        assertTrue("val foregroundSweepJob = launch" in runStart)
        assertTrue("foregroundSweepJob.cancel()" in runStart)
    }

    private fun String.countOccurrences(needle: String): Int = windowed(needle.length).count { it == needle }
}
