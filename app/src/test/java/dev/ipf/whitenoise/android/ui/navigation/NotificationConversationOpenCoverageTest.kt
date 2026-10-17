package dev.ipf.whitenoise.android.ui.navigation

import dev.ipf.whitenoise.android.ui.conversation.NotificationLandingTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class NotificationConversationOpenCoverageTest {
    /** Queued work keeps the account that was used to decide whether notification hydration is deferred. */
    @Test
    fun queuedChatListBindUsesTheAccountThatOwnedItsDeferDecision() {
        val source = mainShellSource()
        val capture = source.indexOf("val chatListBindAccountRef = appState.activeAccountRef")
        assertTrue("capture account ownership before queuing the effect", capture >= 0)
        val guard = source.indexOf("if (deferNotificationChatListBind) return@LaunchedEffect", capture)
        assertTrue("the captured account must own the defer decision", guard > capture)
        val bindEffect = source.substring(guard).substringBefore("LaunchedEffect(")
        assertTrue(bindEffect.contains("accountRef = chatListBindAccountRef"))
        assertFalse("queued bind must retain its account", bindEffect.contains("appState.activeAccountRef"))
    }

    /** A pin tap waits for the App Lock decision instead of being armed, consumed or routed while it is pending. */
    @Test
    fun pinnedShortcutRouteWaitsForThePendingLockDecision() {
        assertTrue("the pass must re-run on the lock decision", "appUnlockEvaluationPending" in routeEffectKeys())
        val route = routerSource().between("suspend fun route(", "private fun retireClearedTarget")
        val wait = route.indexOf("target.shortcutCapability != null && appState.appUnlockEvaluationPending -> Unit")
        val arm = route.indexOf("else -> routeArmed(")
        assertTrue("pins must wait before the request is armed or consumed", wait >= 0 && wait < arm)
        val armed = routerSource().between("private suspend fun routeArmed(", "private fun awaitAccounts")
        assertTrue("arming happens only inside routeArmed", "state.armedRequestId = attempt.requestId" in armed)
    }

    /** A lock that shows while accounts are still loading consumes the pin before a later unlock could route it. */
    @Test
    fun pinnedShortcutRouteFailsClosedWhenTheLockShowsBeforeAccountsLoad() {
        assertTrue("the pass must re-run when the lock shows", "appState.appLockScreenVisible" in routeEffectKeys())
        val router = routerSource()
        val armed = router.between("private suspend fun routeArmed(", "private fun awaitAccounts")
        assertTrue(
            "an empty account list waits instead of resolving",
            armed.indexOf("awaitAccounts(attempt)") in 0 until armed.indexOf("resolveAndAct(attempt)"),
        )
        val block = router.between("private fun awaitAccounts(", "private suspend fun resolveAndAct")
        assertTrue(block.contains("pinnedShortcutLockDecision() == PinnedShortcutLockDecision.LOCKED"))
        assertTrue("a locked pin is consumed", "shell.targetHandled(attempt.target, attempt.requestId)" in block)
    }

    /** Route state is published only after the awaited card cancellation has returned. */
    @Test
    fun routeCommitAwaitsDismissalBeforePublishingNavigationState() {
        val commit = commitSource()
        val dismiss = commit.indexOf("appState.dismissNotificationRouteCards(")
        val recheck = commit.indexOf("if (rejectUnavailablePin(attempt)) return", dismiss)
        val navigation = commit.indexOf("shell.openRoutedConversation(")
        assertTrue("cancellation must precede every route-state publication", dismiss >= 0 && navigation > dismiss)
        assertTrue("pin authority is rechecked after the suspension", recheck in dismiss until navigation)
        val shellOpen = mainShellSource().between("override fun openRoutedConversation(", "override fun targetHandled")
        val section = shellOpen.indexOf("sectionName = MainSection.Chats.name")
        val selected = shellOpen.indexOf("selectedChat = item")
        assertTrue("selected chat must commit last, after the section", section >= 0 && selected > section)
    }

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

    /** The tapped card's message travels beside focus, never in it, and a later open cannot inherit it. */
    @Test
    fun notificationOpenCarriesTheCapturedTargetSeparatelyFromSearchFocus() {
        val target = NotificationLandingTarget("account-a", "group-1", "notified")
        val opened =
            nextNotificationConversationOpenContext(
                current = ConversationOpenContext(focusMessageId = "search-hit"),
                notificationTarget = target,
            )

        assertEquals(null, opened.focusMessageId)
        assertEquals(target, opened.notificationTarget)
        assertEquals(null, nextNotificationConversationOpenContext(opened).notificationTarget)
        assertEquals(null, ConversationOpenContext().notificationTarget)
    }

    /** The route captures the card's message when it commits, and the screen is handed exactly that pairing. */
    @Test
    fun routeCommitCapturesTheTappedMessageAndHandsItToTheConversationScreen() {
        val source = mainShellSource().replace(Regex("\\s+"), " ")
        val commit = commitSource().replace(Regex("\\s+"), " ")

        assertTrue(
            "the commit must capture the card's message",
            "notificationTarget = target.toLandingTarget()" in commit,
        )
        assertTrue(
            "the screen must receive the captured pairing, not the read-through cursor",
            "notificationLandingTarget = content.openContext.notificationTarget" in source,
        )
        assertTrue(
            "the read-through cursor stays a separate argument",
            "notificationReadThroughMessageId = target.messageIdHex" in commit,
        )
    }

    @Test
    fun repeatedNotificationOpenRequestsFreshAnchorForMountedConversation() {
        val first = nextNotificationConversationOpenContext(ConversationOpenContext())

        val repeated = nextNotificationConversationOpenContext(first)

        assertNotEquals(first.notificationOpenRequestId, repeated.notificationOpenRequestId)
    }

    /** A prior visible report cannot own a replacement request on the same retained controller. */
    @Test
    fun timelineVisibilityRequiresTheExactControllerRequestAndVisibleState() {
        assertTrue(conversationTimelineReportIsCurrent(true, true, 7L, 7L))
        assertFalse(conversationTimelineReportIsCurrent(true, true, 7L, 8L))
        assertFalse(conversationTimelineReportIsCurrent(false, true, 7L, 7L))
        assertFalse(conversationTimelineReportIsCurrent(true, false, 7L, 7L))
    }

    /** A same-controller request replacement invalidates its predecessor before the new report commits. */
    @Test
    fun timelineVisibilityOwnerInvalidatesAReplacedRequestSynchronously() {
        val owner = ConversationTimelineVisibilityOwner<Any>()
        val controller = Any()
        val outgoingController = Any()
        var selectedOwner: Any? = controller
        var selectedRequestId = 7L
        val publish: (Any, Long, Boolean) -> Unit = { candidateOwner, requestId, visible ->
            owner.reportIfCurrent(candidateOwner, requestId, visible, selectedOwner, selectedRequestId)
        }
        val retainedRequestSevenCallback: (Boolean) -> Unit = { visible -> publish(controller, 7L, visible) }
        val retainedOutgoingCallback: (Boolean) -> Unit = { visible -> publish(outgoingController, 8L, visible) }

        retainedRequestSevenCallback(true)
        assertTrue(owner.isCurrent(controller, notificationOpenRequestId = 7L))
        selectedRequestId = 8L
        assertFalse(owner.isCurrent(controller, notificationOpenRequestId = 8L))

        publish(controller, 8L, true)
        assertTrue(owner.isCurrent(controller, notificationOpenRequestId = 8L))
        retainedRequestSevenCallback(false)
        retainedOutgoingCallback(false)
        assertTrue(owner.isCurrent(controller, notificationOpenRequestId = 8L))

        publish(controller, 8L, false)
        assertFalse(owner.isCurrent(controller, notificationOpenRequestId = 8L))
        selectedOwner = outgoingController
        assertFalse(owner.isCurrent(outgoingController, notificationOpenRequestId = 8L))
    }

    /** A newly hidden request cannot inherit a visible report from the same retained controller. */
    @Test
    fun notificationVisibilityIsOwnedByTheExactOpenRequest() {
        val source = mainShellSource().replace(Regex("\\s+"), " ")

        assertTrue(
            "the visibility owner must publish one request-scoped report",
            "ConversationTimelineVisibilityOwner<ConversationController>()" in source,
        )
        assertTrue(
            "the current route must reject another request generation from the same controller",
            "conversationTimelineVisibility.isCurrent(" in source &&
                "owner = selectedOrPendingConversationController" in source &&
                "notificationOpenRequestId = selectedChatOpenContext.notificationOpenRequestId" in source,
        )
        assertTrue(
            "each conversation surface must publish the request generation it actually renders",
            "conversationTimelineVisibility.reportIfCurrent(" in source &&
                "selectedOwner = currentConversationTimelineOwner" in source &&
                "selectedNotificationOpenRequestId = currentConversationTimelineRequestId" in source &&
                "notificationOpenRequestId = content.openContext.notificationOpenRequestId" in source,
        )
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

    /** The inactive-account route holds its priority window from activation until the first conversation frame. */
    @Test
    fun inactiveAccountProductionRoutePrioritizesTargetUntilFirstFrame() {
        val switch = sourceFile("NotificationAccountSwitchRoute.kt").readText()
        val gateCreated = switch.indexOf("NotificationRouteFirstFrameGate(")
        val prioritySwitch =
            switch.indexOf(
                "AccountSwitchPreloadPolicy.TARGET_CONVERSATION_FIRST",
                startIndex = gateCreated,
            )
        val targetFirstEligibility = switch.indexOf("val canUseTargetFirst =")
        val signedInPreloadEligibility = switch.indexOf("val canPreload =", startIndex = targetFirstEligibility)
        val signedOutGate = switch.indexOf("if (canUseTargetFirst) NotificationRouteFirstFrameGate(")
        val shell = mainShellSource()
        val broadBindGuard = shell.indexOf("if (deferNotificationChatListBind) return@LaunchedEffect")
        val firstFrameCallback = shell.indexOf("onFirstFrameCommitted = {")
        val release = shell.indexOf("releaseNotificationFirstFrameGate(requestId)", startIndex = firstFrameCallback)

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

        val reads = readsSource()
        val load = reads.between("suspend fun loadMessage(", "private fun retryAfterActivation")
        val readyStep = "is NotificationMessagePreloadState.Ready -> completion.commit(attempt, preloadState.item)"
        val readyOpen = load.indexOf(readyStep)
        val failedPreload = load.indexOf("NotificationMessagePreloadState.Failed ->")
        val activeRetry = load.indexOf("shouldRetryNotificationMessageLoadAfterActivation(", startIndex = failedPreload)
        val retryCall = load.indexOf("retryAfterActivation(attempt)", startIndex = activeRetry)
        // Holding the overlay is only right while a retry is in flight. Once the exact read has answered
        // inconclusively and its one retry is used up, the overlay comes down and the tap stays pending
        // rather than the route claiming the conversation is gone (#2580).
        val spentRetry = load.indexOf("completion.releaseInconclusive(attempt)", startIndex = retryCall)
        assertTrue("the ready preload must open the conversation", readyOpen in 0 until failedPreload)
        assertTrue("a failed inactive preload must retry after activation", activeRetry > failedPreload)
        assertTrue("a spent retry must release the routing overlay", spentRetry > retryCall)

        val retry = reads.between("private fun retryAfterActivation(", "private suspend fun readTarget")
        val retryDirectLoad = retry.indexOf("loadNotificationMessageDirectly {")
        val retryReady = retry.indexOf("NotificationMessagePreloadState.Ready(outcome.item)", retryDirectLoad)
        assertTrue("the active-account retry must perform an exact local load", retryDirectLoad >= 0)
        assertTrue("a successful retry must publish the exact target", retryReady > retryDirectLoad)
        assertFalse("the chat list must stay hidden during active retry", "routing = false" in retry)
        val inconclusive = commitSource(whole = true).between("fun releaseInconclusive(", "suspend fun openListed")
        assertTrue("an inconclusive read lowers the overlay", "state.routing = false" in inconclusive)
    }

    /** The shell's priority window is process-owned: a shell unmount cannot be what releases it (#586). */
    @Test
    fun theFirstFrameGateIsProcessOwnedAndNotReleasedByShellUnmount() {
        val source = mainShellSource().replace(Regex("\\s+"), " ")

        assertTrue(
            "the gate must be read from the hoisted owner",
            "val notificationFirstFrameGate = shellStateHolder.notificationFirstFrame.gate" in source,
        )
        assertTrue(
            "release must go through the hoisted owner, scoped by request",
            "shellStateHolder.notificationFirstFrame.release(requestId)" in source,
        )
        assertFalse("unmounting the shell must not release the gate", "onDispose { ownedGate?.release() }" in source)
        assertFalse(
            "the gate must not be composition-local state",
            "var notificationFirstFrameGate by remember" in source,
        )
    }

    /** The shell-ready bind stays ungated: gating it behind a conversation frame deadlocked the app on device. */
    @Test
    fun prepareMainShellFirstFrameStaysUngatedByTheNotificationWindow() {
        val holder = sourceFile("MainShellStateHolder.kt").readText()
        val prepare = holder.substringAfter("internal fun PrepareMainShellFirstFrame(")

        assertTrue("it must still bind the chat list", "controller.bind(" in prepare)
        assertFalse("it must never wait on the first-frame gate", "notificationFirstFrame" in prepare)
        assertFalse("it must never wait on the first-frame gate", "NotificationRouteFirstFrameGate" in prepare)
    }

    /** The root keeps the shell composed for the retained route, and only outside the lock branch. */
    @Test
    fun theRootKeepsTheShellComposedForTheRetainedRouteButNeverUnderLock() {
        val root = rootSource().replace(Regex("\\s+"), " ")
        val locked = root.substringAfter("if (appState.appLockScreenVisible) {").substringBefore("} else {")

        assertTrue(
            "the retained route must count like a pending inbound route when choosing the surface",
            "inboundRoutePending = inboundRoutePending || notificationRouteOwnsShell" in root,
        )
        val ownerCall = root.substringAfter("val notificationRouteOwnsShell =")
        assertTrue(
            "the owner must be told whether the lock is up",
            "appLockScreenVisible = appState.appLockScreenVisible" in ownerCall,
        )
        assertFalse("the locked branch composes no shell", "MainShell(" in locked)
        assertFalse("the locked branch must not consult the route", "notificationRouteOwnsShell" in locked)
    }

    /** A failed exact preload keeps broad work deferred until its one retry settles. */
    @Test
    fun failedPreloadDefersBroadWorkUntilExactRetrySettles() {
        val switch = sourceFile("NotificationAccountSwitchRoute.kt").readText()
        val preloadCallback = switch.substringAfter("onPreload = {").substringBefore("}")
        assertFalse("an initial preload failure must retain the gate", "release" in preloadCallback)

        val retry = readsSource().between("private fun retryAfterActivation(", "private suspend fun readTarget")
        val retryFallback = retry.substringAfter("NotificationMessageDirectLoadOutcome.AwaitChatList -> {")
        val retryRelease = "retryGate?.let(owners.firstFrame::releaseOwned)"
        assertTrue("a failed active retry must release deferred work", retryRelease in retryFallback)
        assertTrue(
            "the retry captures its own gate before it suspends, so it never releases a successor's",
            retry.indexOf("val retryGate = owners.firstFrame.gateFor(requestId)") in
                0 until retry.indexOf("launchMutation"),
        )
        assertFalse("the retry must not release by request id", "owners.firstFrame.release(requestId)" in retry)

        val load = readsSource().between("suspend fun loadMessage(", "private fun retryAfterActivation")
        val directFallback = load.substringAfter("null ->")
        val directRelease = "AwaitChatList -> completion.releaseInconclusive(attempt)"
        assertTrue("a direct-load fallback must release deferred work", directRelease in directFallback)
        val inconclusive = commitSource(whole = true).between("fun releaseInconclusive(", "suspend fun openListed")
        assertTrue(
            "the inconclusive fallback releases the window before it lowers the overlay",
            inconclusive.indexOf("owners.firstFrame.release(attempt.requestId)") in
                0 until inconclusive.indexOf("state.routing = false"),
        )
    }

    /**
     * Process-scoped switch work outlives the pass that started it, so it releases only the gate object it began:
     * a successor that reuses the request id, after a remount or a new task, keeps its own window (#3309).
     */
    @Test
    fun processScopedSwitchCompletionsReleaseOnlyTheirOwnGate() {
        val switch = sourceFile("NotificationAccountSwitchRoute.kt").readText()
        assertFalse("no request-scoped release from the retired route", "firstFrame.release(" in switch)
        val ownedRelease = "ownedGate?.let(owners.firstFrame::releaseOwned)"
        assertTrue("the owned gate is released by identity", ownedRelease in switch)
        assertTrue(
            "a lost pin abandons the route through the owned release",
            "completion.rejectUnavailablePin(attempt, releaseGate = ::releaseOwnedGate)" in switch,
        )
        val failure = switch.between("private fun reconcileAfterActivation()", "private fun releaseStuckPreload")
        assertTrue("a switch that never landed releases its own gate", "releaseOwnedGate()" in failure)
        assertFalse("a switch that never landed never names a request id", "release(requestId)" in failure)
        val claimed = failure.indexOf("owners.firstFrame.routeClaimIsCurrent(claim)")
        assertTrue(
            "the failure path asks the process-owned route claim before any write",
            claimed in 0 until failure.indexOf("rejectUnavailablePin("),
        )
        val current = switch.between("private fun switchStillCurrent()", "private suspend fun runSwitch")
        assertTrue(
            "activation currency starts from the route claim, not the frozen request reader",
            "owners.firstFrame.routeClaimIsCurrent(claim) &&" in current,
        )
        assertTrue("every switch takes its own claim", "private val claim = owners.firstFrame.claimRoute()" in switch)
        val armed = routerSource().between("private suspend fun routeArmed(", "private fun awaitAccounts")
        assertTrue("arming a request claims it for the routing shell", "owners.firstFrame.claimRoute()" in armed)
    }

    /** Every process-scoped completion asks the live request and runtime fence before it writes shell state. */
    @Test
    fun processScopedCompletionsAskTheLiveFenceBeforeWriting() {
        val switch = sourceFile("NotificationAccountSwitchRoute.kt").readText().replace(Regex("\\s+"), " ")
        val preloadFence = "isCurrent = { currency.offers(requestId, target, routeRuntimeGeneration) }"
        assertTrue("the preload publishes only for the offered request", preloadFence in switch)
        assertTrue("activation runs only while the request owns it", "shouldActivate = ::switchStillCurrent" in switch)
        assertTrue(
            "a lost pin is failed only while the request is still offered",
            "if (offered && completion.rejectUnavailablePin(" in switch,
        )
        assertTrue("a never-landed switch fails only while still current", "if (!switchStillCurrent() ||" in switch)
        val retry = readsSource().replace(Regex("\\s+"), " ")
        val retryFence = "if (currency.offers(requestId, attempt.target, retryRuntimeGeneration))"
        assertTrue("the exact retry publishes only for the offered request", retryFence in retry)
        val state = sourceFile("NotificationOpenRouteState.kt").readText().replace(Regex("\\s+"), " ")
        val liveRead = "liveRuntimeGeneration() == runtimeGeneration"
        assertTrue("the runtime fence reads the live generation", liveRead in state)
    }

    /** The route's request and runtime fence sees a replaced runtime even after its composition stopped updating. */
    @Test
    fun aFrozenComposedReaderStillRejectsALiveRuntimeReplacement() {
        val target =
            dev.ipf.whitenoise.android.notifications.NotificationScenario
                .target(accountRef = "account-b", groupIdHex = "group")
        var liveRuntime = 4
        val currency =
            NotificationOpenCurrency(
                request = androidx.compose.runtime.mutableStateOf(NotificationOpenRequest(target, 9L)),
                composedRuntimeGeneration = androidx.compose.runtime.mutableIntStateOf(4),
                liveRuntimeGeneration = { liveRuntime },
            )
        assertTrue(currency.offers(9L, target, 4))
        liveRuntime = 5
        assertFalse("a retired callback must see the live replacement", currency.offers(9L, target, 4))
        assertFalse("a stale request never matches", currency.offers(8L, target, 5))
    }

    /** MainShell keeps no inline notification orchestration, and the seam writes shell state only through its port. */
    @Test
    fun notificationOrchestrationLivesInTheSeamAndWritesThroughTheInjectedShell() {
        val shell = mainShellSource()
        assertFalse("MainShell must not resolve notification steps inline", "resolveNotificationNav(" in shell)
        assertTrue("MainShell runs the seam", "NotificationOpenRouteEffect(" in shell)
        val seamFiles =
            listOf(
                "NotificationOpenRouter.kt",
                "NotificationExactOpenReads.kt",
                "NotificationAccountSwitchRoute.kt",
            )
        seamFiles.forEach { name ->
            val source = sourceFile(name).readText()
            listOf("selectedChat =", "sectionName =", "shellNavState =", "selectedChatOpenContext =").forEach { write ->
                assertFalse("$name must write $write only through NotificationOpenShell", write in source)
            }
        }
    }

    /** The seam's effect keeps every key the inline effect had, so the route re-fires exactly as before. */
    @Test
    fun theRouteEffectKeepsEveryInlineKey() {
        val keys = routeEffectKeys().replace(Regex("\\s+"), " ")
        listOf(
            "inboundTarget,",
            "inboundRequestId,",
            "inboundTarget?.replyDraft != null && appState.appInForeground",
            "inboundTarget?.shortcutCapability != null && appState.appUnlockEvaluationPending",
            "inboundTarget?.shortcutCapability != null && appState.appLockScreenVisible",
            "appState.activeAccountRef",
            "appState.runtimeGeneration",
            "appState.accounts",
            "chats.boundAccountRef",
            "chats.isLoading",
            "chats.items",
            "chats.materializedGroupsRevision",
            "route.inviteSlots.authoritativelyUnavailable",
            "route.preloadSlot.preload",
        ).forEach { key -> assertTrue("missing route key $key", key in keys) }
    }

    /** The Activity holds a pin tap while the App Lock decision loads and re-parses it once the decision exists. */
    @Test
    fun pinnedShortcutTapIsHeldInTheActivityUntilTheLockDecisionExists() {
        val source = mainActivitySource()
        val hold = source.indexOf("if (pinTapGate.hold(intent)) {")
        val parse = source.indexOf("val pinTarget = pinTapGate.target(this, intent)")
        assertTrue("the tap must be held before any target is parsed", hold >= 0 && hold < parse)
        assertTrue(source.substring(hold).substringBefore("}").contains("return"))
        assertTrue(source.contains("pinTapGate.replayWhenDecided(lifecycleScope, ::consumeIntent)"))
    }

    private fun mainActivitySource(): String =
        listOf(
            File("src/main/java/dev/ipf/whitenoise/android/MainActivity.kt"),
            File("app/src/main/java/dev/ipf/whitenoise/android/MainActivity.kt"),
        ).first(File::exists)
            .readText()

    /** Reads one navigation source file, from either the module or the repository working directory. */
    private fun sourceFile(name: String): File =
        listOf(
            File("src/main/java/dev/ipf/whitenoise/android/ui/navigation/$name"),
            File("app/src/main/java/dev/ipf/whitenoise/android/ui/navigation/$name"),
        ).first(File::exists)

    /** Reads the app root's source, from either the module or the repository working directory. */
    private fun rootSource(): String =
        listOf(
            File("src/main/java/dev/ipf/whitenoise/android/ui/WhiteNoiseApp.kt"),
            File("app/src/main/java/dev/ipf/whitenoise/android/ui/WhiteNoiseApp.kt"),
        ).first(File::exists)
            .readText()

    /** The keys of the seam's routing effect, up to the block that runs the pass. */
    private fun routeEffectKeys(): String =
        sourceFile("NotificationOpenRouteState.kt")
            .readText()
            .substringAfter("internal fun NotificationOpenRouteEffect(")
            .substringAfter("LaunchedEffect(")
            .substringBefore(") {")

    /** The router's source. */
    private fun routerSource(): String = sourceFile("NotificationOpenRouter.kt").readText()

    /** The exact-read source. */
    private fun readsSource(): String = sourceFile("NotificationExactOpenReads.kt").readText()

    /** The completion's commit, or the whole completion class with [whole]. */
    private fun commitSource(whole: Boolean = false): String {
        val completion = routerSource().substringAfter("internal class NotificationOpenCompletion(")
        val commit = completion.between("suspend fun commit(", "private fun beginConversationPhases")
        return if (whole) completion else commit
    }

    /** The text after [start] and before the following [end]. */
    private fun String.between(
        start: String,
        end: String,
    ): String = substringAfter(start).substringBefore(end)

    private fun mainShellSource(): String =
        listOf(
            File("src/main/java/dev/ipf/whitenoise/android/ui/navigation/MainShell.kt"),
            File("app/src/main/java/dev/ipf/whitenoise/android/ui/navigation/MainShell.kt"),
        ).first(File::exists)
            .readText()
}
