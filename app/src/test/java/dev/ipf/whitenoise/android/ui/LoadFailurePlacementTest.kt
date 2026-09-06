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
    /** Omits error UI when no failure exists, regardless of retained content. */
    @Test
    fun noFailureShowsNoFailureSurface() {
        assertEquals(LoadFailurePlacement.None, loadFailurePlacement(hasFailure = false, hasLoadedContent = false))
        assertEquals(LoadFailurePlacement.None, loadFailurePlacement(hasFailure = false, hasLoadedContent = true))
    }

    /** Uses full-screen recovery when startup failed before any content was available. */
    @Test
    fun initialFailureUsesFullScreenRecovery() {
        assertEquals(LoadFailurePlacement.FullScreen, loadFailurePlacement(hasFailure = true, hasLoadedContent = false))
    }

    /** Keeps existing content mounted when a refresh or page request fails. */
    @Test
    fun refreshOrPagingFailureKeepsLoadedContentVisible() {
        assertEquals(LoadFailurePlacement.Inline, loadFailurePlacement(hasFailure = true, hasLoadedContent = true))
    }

    /** Places page failures at the edge owned by the failed pagination direction. */
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

    /** Rekeys both failed subscriptions while preserving already loaded chat content. */
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
        val failureResolutionStart = "private fun discardInitialTimelineSeedForFailure"
        val failureResolutionEnd = "private fun publishAuthoritativeEmptyInitialTimeline"
        val seededReconciliationStart = "val seededTailAlignmentReady ="
        val seededReconciliationEnd = "ConversationTailInsetReanchorEffect("
        val transcriptVisibilityStart = "val transcriptVisibilityCommitted by"
        val transcriptVisibilityEnd = "// First-frame completion waits"
        assertUniqueOrderedWindow(controllers, failureResolutionStart, failureResolutionEnd)
        assertUniqueOrderedWindow(screen, seededReconciliationStart, seededReconciliationEnd)
        assertUniqueOrderedWindow(screen, transcriptVisibilityStart, transcriptVisibilityEnd)
        val failureResolution =
            controllers
                .substringAfter(failureResolutionStart)
                .substringBefore(failureResolutionEnd)
        val seededReconciliation =
            screen
                .substringAfter(seededReconciliationStart)
                .substringBefore(seededReconciliationEnd)
        val transcriptVisibilityOwner =
            screen
                .substringAfter(transcriptVisibilityStart)
                .substringBefore(transcriptVisibilityEnd)

        assertTrue("publishTimelineFromIndexes()" in failureResolution)
        assertTrue("hasPublishedAuthoritativeTimeline = true" in failureResolution)
        assertTrue("hasPreparedInitialPresentation = true" in failureResolution)
        assertTrue("initialTimelineAnchored = true" in seededReconciliation)
        assertTrue("initialTimelineAnchored = initialTimelineAnchored" in transcriptVisibilityOwner)
        assertTrue("seededTailAlignmentCommitted = seededTailAlignmentCommitted" in transcriptVisibilityOwner)
        assertTrue("if (!transcriptVisibilityCommitted) hideFromAccessibility()" in screen)
    }

    /** Rejects missing, duplicated, or inverted source windows before substring assertions run. */
    private fun assertUniqueOrderedWindow(
        source: String,
        start: String,
        end: String,
    ) {
        assertEquals(1, source.countOccurrences(start))
        assertEquals(1, source.countOccurrences(end))
        assertTrue(source.indexOf(start) < source.indexOf(end))
    }

    /** Ensures terminal open cancels its foreground sweep sibling before returning. */
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

    /** Counts literal source markers so structural tests fail on ambiguity. */
    private fun String.countOccurrences(needle: String): Int = windowed(needle.length).count { it == needle }
}
