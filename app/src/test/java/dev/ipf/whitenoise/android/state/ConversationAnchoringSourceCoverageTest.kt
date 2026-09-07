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

    @Test
    fun conversationAnchoringLifecycleFollowsController() {
        val source = conversationScreenSource().readText()
        val entrySnapshotIndex = source.indexOf("val entryUnreadSnapshot =")
        val scrollRestoreIndex = source.indexOf("val scrollRestore =")
        val unreadJumpOwner =
            source.substring(
                source.indexOf("var unreadJumpState by"),
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
                    "var lastFollowedLatestId by mutableStateOf(initialFollowedLatestId)",
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

    private fun String.containsAll(vararg fragments: String): Boolean =
        fragments.all(::contains)

    private fun conversationScreenSource(): File =
        listOf(
            File("src/main/java/dev/ipf/whitenoise/android/ui/conversation/ConversationScreen.kt"),
            File("app/src/main/java/dev/ipf/whitenoise/android/ui/conversation/ConversationScreen.kt"),
        ).firstOrNull { it.exists() }
            ?: error("Missing ConversationScreen.kt source file")
}
