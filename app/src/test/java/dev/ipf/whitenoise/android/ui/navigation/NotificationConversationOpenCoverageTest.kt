package dev.ipf.whitenoise.android.ui.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class NotificationConversationOpenCoverageTest {
    @Test
    fun notificationOpenClearsSearchFocusAndRequestsUnreadAnchor() {
        val previous =
            ConversationOpenContext(
                focusMessageId = "search-hit",
                notificationOpenRequestId = 0L,
            )

        val opened = nextNotificationConversationOpenContext(previous)

        assertEquals(null, opened.focusMessageId)
        assertTrue(opened.notificationOpenRequestId > 0L)
    }

    @Test
    fun repeatedNotificationOpenRequestsFreshAnchorForMountedConversation() {
        val first = nextNotificationConversationOpenContext(ConversationOpenContext())

        val repeated = nextNotificationConversationOpenContext(first)

        assertNotEquals(first.notificationOpenRequestId, repeated.notificationOpenRequestId)
    }

    @Test
    fun notificationOpenCarriesProductionTraceOwnershipToFirstFrame() {
        val opened =
            nextNotificationConversationOpenContext(
                current = ConversationOpenContext(),
                notificationRouteTraceRequestId = 73L,
            )

        assertEquals(73L, opened.notificationRouteTraceRequestId)
    }

    @Test
    fun notificationOpenCarriesDurableReadThroughUntilUnreadBoundaryCapture() {
        val opened =
            nextNotificationConversationOpenContext(
                current = ConversationOpenContext(),
                notificationReadThroughMessageId = "message-1",
            )

        assertEquals("message-1", opened.notificationReadThroughMessageId)
    }

    @Test
    fun inactiveAccountProductionRoutePrioritizesTargetUntilFirstFrame() {
        val source = mainShellSource()
        val gateCreated = source.indexOf("NotificationRouteFirstFrameGate(")
        val prioritySwitch =
            source.indexOf(
                "AccountSwitchPreloadPolicy.TARGET_CONVERSATION_FIRST",
                startIndex = gateCreated,
            )
        val broadBindGuard = source.indexOf("if (deferNotificationChatListBind) return@LaunchedEffect")
        val firstFrameCallback = source.indexOf("onFirstFrameCommitted = {")
        val release =
            source.indexOf(
                "releaseNotificationFirstFrameGate(requestId)",
                startIndex = firstFrameCallback,
            )
        val failedPreload = source.indexOf("NotificationMessagePreloadState.Failed ->")
        val readyPreload = source.indexOf("is NotificationMessagePreloadState.Ready ->")
        val readyOpen = source.indexOf("commitNotificationConversationOpen(preloadState.item)", readyPreload)
        val activeRetry =
            source.indexOf(
                "shouldRetryNotificationMessageLoadAfterActivation(",
                startIndex = failedPreload,
            )
        val retryDirectLoad = source.indexOf("loadNotificationMessageDirectly {", startIndex = activeRetry)
        val retryReady = source.indexOf("NotificationMessagePreloadState.Ready(outcome.item)", retryDirectLoad)
        val retryBranchEnd = source.indexOf("null ->", startIndex = failedPreload)
        assertTrue("the retry branch must publish its successful result", retryBranchEnd > retryReady)
        val exposesChatListDuringRetry =
            source
                .indexOf("routingNotification = false", startIndex = failedPreload)
                .let { it in failedPreload until retryBranchEnd }
        val targetFirstEligibility = source.indexOf("val canUseTargetFirst =")
        val signedInPreloadEligibility = source.indexOf("val canPreload =", startIndex = targetFirstEligibility)
        val signedOutGate = source.indexOf("if (canUseTargetFirst) {", startIndex = signedInPreloadEligibility)

        assertTrue("the production switch route must own a first-frame priority gate", gateCreated >= 0)
        assertTrue(
            "target-first gating must be decided independently of signed-in preload eligibility",
            targetFirstEligibility >= 0 &&
                signedInPreloadEligibility > targetFirstEligibility &&
                signedOutGate > signedInPreloadEligibility,
        )
        assertTrue("notification activation must select target-first preload", prioritySwitch > gateCreated)
        assertTrue("the broad chat-list bind must honor the priority window", broadBindGuard >= 0)
        assertTrue("the readable conversation frame must release deferred work", release > firstFrameCallback)
        assertTrue("a failed inactive preload must retry after activation", activeRetry > failedPreload)
        assertTrue("the active-account retry must perform an exact local load", retryDirectLoad > activeRetry)
        assertTrue("a successful retry must publish the exact target", retryReady > retryDirectLoad)
        assertTrue("the ready preload must open the conversation", readyOpen in (readyPreload + 1) until failedPreload)
        assertTrue("the chat list must stay hidden during active retry", !exposesChatListDuringRetry)
    }

    @Test
    fun failedPreloadDefersBroadWorkUntilExactRetrySettles() {
        val source = mainShellSource()
        val preloadCallback = source.indexOf("onPreload = {")
        val preloadCallbackEnd = source.indexOf("} else {\n                        activateAccount()", preloadCallback)
        val firstReleaseAfterPreload =
            source.indexOf("releaseNotificationFirstFrameGate(routingRequestId)", preloadCallback)
        val failedPreload = source.indexOf("NotificationMessagePreloadState.Failed ->")
        val retryFallback =
            source.indexOf("NotificationMessageDirectLoadOutcome.AwaitChatList -> {", failedPreload)
        val retryBranchEnd = source.indexOf("null ->", failedPreload)
        val retryRelease = source.indexOf("releaseNotificationFirstFrameGate(routingRequestId)", retryFallback)
        val directFallback =
            source.indexOf("NotificationMessageDirectLoadOutcome.AwaitChatList -> {", retryBranchEnd)
        val directRelease = source.indexOf("releaseNotificationFirstFrameGate(routingRequestId)", directFallback)
        val directFallbackEnd = source.indexOf("routingNotification = false", directFallback)

        assertTrue("the preload callback boundary must be found", preloadCallbackEnd > preloadCallback)
        assertTrue("an initial preload failure must retain the gate", firstReleaseAfterPreload > preloadCallbackEnd)
        assertTrue("the active retry fallback must be found", retryFallback > failedPreload)
        assertTrue("the active retry boundary must be found", retryBranchEnd > retryFallback)
        assertTrue("the direct fallback must be found", directFallback > retryBranchEnd)
        assertTrue("the direct fallback boundary must be found", directFallbackEnd > directFallback)
        assertTrue(
            "a failed active retry must release deferred work",
            retryRelease in retryFallback until retryBranchEnd,
        )
        assertTrue(
            "a direct-load fallback must release deferred work",
            directRelease in directFallback until directFallbackEnd,
        )
    }

    private fun mainShellSource(): String =
        listOf(
            File("src/main/java/dev/ipf/whitenoise/android/ui/navigation/MainShell.kt"),
            File("app/src/main/java/dev/ipf/whitenoise/android/ui/navigation/MainShell.kt"),
        ).first(File::exists)
            .readText()
}
