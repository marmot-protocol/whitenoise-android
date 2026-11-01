package dev.ipf.whitenoise.android.state

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ConversationAnchoringSourceCoverageTest {
    @Test
    fun bottomInsetHolderFollowsController() {
        val source = conversationScreenSource().readText()
        val owner =
            source.substring(
                source.indexOf("val bottomInsetState ="),
                source.indexOf("val freezeRoutePresentation ="),
            )

        assertTrue(
            "bottom-inset mutable state must share one controller-keyed owner",
            owner.containsAll(
                "remember(controller) {",
                "ConversationBottomInsetState(",
                "var measuredBottomChromeHeightPx by bottomInsetState.measuredBottomChromeHeightPx",
                "var bottomInputRevision by bottomInsetState.bottomInputRevision",
                "var routePresentationFrozen by bottomInsetState.routePresentationFrozen",
            ),
        )
    }

    @Test
    fun seededTailHolderFollowsControllerAndNotificationRequest() {
        val source = conversationScreenSource().readText()
        val owner =
            source.substring(
                source.indexOf("val seededTailState ="),
                source.indexOf("ConversationTtsAutoReadEffects("),
            )

        assertTrue(
            "seeded tail state must share one controller/request-keyed owner",
            owner.containsAll(
                "remember(controller, notificationOpenRequestId)",
                "ConversationSeededTailState(",
                "firstFrameSeed.anchorTailImmediately && !firstFrameSeed.awaitingAuthoritativeTimeline",
                "var initialTimelineAnchored by seededTailState.initialTimelineAnchored",
            ),
        )
    }

    /** Unread jump and follow state reset with the controller, and scroll restore uses the reconciled entry unread. */
    @Test
    fun conversationAnchoringLifecycleFollowsController() {
        val source = conversationScreenSource().readText()
        val entrySnapshotIndex = source.indexOf("val entryUnreadSnapshot =")
        val scrollRestoreIndex = source.indexOf("val scrollRestore =")
        val unreadJumpOwner =
            source.substring(
                source.indexOf("val unreadJump ="),
                source.indexOf("val scrollCoordinator ="),
            )

        assertTrue(
            "same-group account switches must reset state and cancel old-controller effects",
            unreadJumpOwner.containsAll(
                "remember(controller, chat.id, conversationAccountRef, appState.runtimeGeneration)",
                "mutableStateOf(ConversationUnreadJumpState())",
            ) &&
                source.containsAll(
                    "ConversationNavigationState(",
                    "onDispose(state::cancelJobs)",
                    "var lastFollowedLatestId: String? = initialFollowedLatestId",
                    "LaunchedEffect(controller, latestTimelineItemId, initialTimelineAnchored)",
                    "LaunchedEffect(listState, controller)",
                ),
        )
        assertTrue(
            "scroll restore must use the reconciled entry unread count rather than the raw projection",
            entrySnapshotIndex >= 0 &&
                entrySnapshotIndex < scrollRestoreIndex &&
                "entryUnreadCount = entryUnreadCount" in
                source.substring(
                    scrollRestoreIndex,
                    source.indexOf("val positionalScrollRestore", scrollRestoreIndex),
                ),
        )
    }

    @Test
    fun pendingDictatedSendUsesTheAcceptedSendRevealPath() {
        val source = conversationScreenSource().readText()

        assertTrue(
            "a real dictated pending row must reveal through the same measured tail transaction as typed sends",
            source.containsAll(
                "appState.conversationDictation.pendingSendRevision(",
                "remember(controller, draftAccountRef) { mutableIntStateOf(dictatedPendingSendRevision) }",
                "LaunchedEffect(controller, dictatedPendingSendRevision)",
                "dictatedPendingSendRevision <= observedDictatedPendingSendRevision",
                "acceptedSendRevealedTranscript = true",
                "revealSentMessage()",
            ),
        )
    }

    /** The read anchor advances over rows that clear the composer in the reading viewport, not the raw layout. */
    @Test
    fun readAnchorFollowsTheUnobstructedViewport() {
        val source = conversationScreenSource().parentFile.resolve("ConversationUnreadTracking.kt").readText()
        val helper = source.substring(source.indexOf("private fun ConversationReadAnchorEffect("))

        assertTrue(
            "the read watermark must ignore rows the composer covers rather than the raw layout",
            helper.containsAll(
                "timelineViewport.readingLayoutInfo().newestReadRow(",
                // Structural rows map past the timeline, where the clamp would read them as newest.
                "timelineListIndices = trailingRowCount until trailingRowCount + renderedSize",
            ) &&
                "listState.layoutInfo" !in helper,
        )
    }

    /** Physical top means older history only in the reversed transcript, and endpoint reads follow layout. */
    @Test
    fun reversedDragSelectionMapsEdgesBeforeReadingThePostScrollEndpoint() {
        val source = conversationScreenSource().readText()
        val start = source.indexOf("fun messageDragSelectionEffects()")
        val loop = source.substring(start, source.indexOf("    messageDragSelectionEffects()", start))
        val scroll = loop.indexOf("listState.scrollBy(-scrollDelta)")
        val endpoint = loop.indexOf("currentMessageDragSelectionUpdater(pointerWindowY)")
        assertTrue("reversed list must invert the shared physical edge delta", scroll >= 0)
        assertTrue("endpoint must use the post-scroll native viewport", endpoint > scroll)
        assertTrue(loop.contains("while (dragAnchorTimelineId != null)"))
        assertTrue(loop.contains("withFrameNanos"))
        assertTrue(loop.contains("dragSelectionAutoScrollDelta("))
        assertTrue(source.contains("currentMessageDragSelectionUpdater by rememberUpdatedState<(Float) -> Boolean>"))
    }

    private fun String.containsAll(vararg fragments: String): Boolean = fragments.all(::contains)

    private fun conversationScreenSource(): File =
        listOf(
            File("src/main/java/dev/ipf/whitenoise/android/ui/conversation/ConversationScreen.kt"),
            File("app/src/main/java/dev/ipf/whitenoise/android/ui/conversation/ConversationScreen.kt"),
        ).firstOrNull { it.exists() }
            ?: error("Missing ConversationScreen.kt source file")
}
