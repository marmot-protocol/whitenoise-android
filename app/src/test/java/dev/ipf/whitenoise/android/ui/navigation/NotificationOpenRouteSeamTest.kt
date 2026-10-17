package dev.ipf.whitenoise.android.ui.navigation

import android.content.Context
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.test.core.app.ApplicationProvider
import dev.ipf.whitenoise.android.notifications.NotificationMessagePreloadState
import dev.ipf.whitenoise.android.notifications.NotificationRouteFirstFrameGate
import dev.ipf.whitenoise.android.notifications.NotificationRouteTrace
import dev.ipf.whitenoise.android.notifications.NotificationScenario
import dev.ipf.whitenoise.android.notifications.NotificationTarget
import dev.ipf.whitenoise.android.notifications.NotificationTargetKind
import dev.ipf.whitenoise.android.notifications.inboundNotificationHandledMatchesCurrent
import dev.ipf.whitenoise.android.notifications.notificationMessagePreloadKey
import dev.ipf.whitenoise.android.state.ChatListItem
import dev.ipf.whitenoise.android.state.ChatsController
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/**
 * The notification-open seam itself (#3309): a production router over the real app state, with the shell's
 * state owners replaced by a recording port that answers acknowledgements as the Activity does. An
 * inactive-account tap starts its process-scoped switch with a read held: the exact preload, or the
 * activation at its first engine read. A newer tap, a runtime replacement, explicit navigation, Back,
 * account removal or a shell remount (what App Lock and Activity recreation do to a route in flight) then
 * changes what the route may still own, and only afterwards is the retired read released. The retired route
 * must not reopen a conversation, consume a tap it no longer owns, activate an account under a newer route,
 * lower a newer route's overlay or end a window it did not begin.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en")
class NotificationOpenRouteSeamTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val gate = AccountRouteOrderGate(preloadFinishesFirst = false)
    private val activationHold = AccountActivationHold()
    private val appState =
        NotificationRouteHarness.appState(
            context,
            NotificationRouteHarness.holdingActivation(NotificationRouteHarness.fakeMarmot(gate), activationHold),
        )
    private val routed = NotificationRouteHarness.routedTarget(NotificationRouteHarness.TARGET_ACCOUNT)
    private val target = requireNotNull(routed.notificationTarget)
    private val requestId = routed.notificationRequestId
    private val request = mutableStateOf(NotificationOpenRequest(target, requestId))
    private val composedRuntime = mutableIntStateOf(appState.runtimeGeneration)
    private val firstFrame = NotificationFirstFramePriority { appState.runtimeGeneration }
    private val shell = RecordingShell(request)
    private val state = routeState(target, requestId, request, composedRuntime)
    private val router = NotificationOpenRouter(appState, ChatsController(appState), firstFrame, state, shell)

    /** Releases every held read and stops the app's process-scoped work so nothing outlives the case. */
    @After
    fun tearDown() {
        gate.releasePreload.countDown()
        gate.releaseActivation.countDown()
        activationHold.releaseAll()
        appState.mutationsScope.cancel()
    }

    /** A newer tap owns the shell and its own window before the retired preload is released. */
    @Test
    fun aHeldPreloadReleasedAfterANewerTapChangesNothingTheNewerRouteOwns() {
        startRetiredSwitch()
        val newer = NotificationRouteHarness.routedTarget(NotificationRouteHarness.SOURCE_ACCOUNT, current = routed)
        val newerAccount = NotificationRouteHarness.SOURCE_ACCOUNT
        val newerGate = NotificationRouteFirstFrameGate(newer.notificationRequestId, newerAccount)
        request.value = NotificationOpenRequest(newer.notificationTarget, newer.notificationRequestId)
        firstFrame.begin(newerGate)

        releaseRetiredPreloadAndAssertUnchanged(newerGate)
    }

    /**
     * A runtime replacement re-runs the same request, so its successor window carries the same request id. Only
     * an identity-fenced release keeps that window, and only the live runtime fence keeps the overlay up.
     */
    @Test
    fun aHeldPreloadReleasedAfterARuntimeReplacementChangesNothingTheReplacementOwns() {
        startRetiredSwitch()
        replaceRuntime()
        val successorGate = NotificationRouteFirstFrameGate(requestId, NotificationRouteHarness.TARGET_ACCOUNT)
        firstFrame.begin(successorGate)

        releaseRetiredPreloadAndAssertUnchanged(successorGate)
    }

    /**
     * Explicit navigation: the route opened its conversation early, the reader then opened another chat
     * themselves, and the held switch finally reports that it never landed. The retired failure may release
     * only what it owns, so it must not fall back over the explicit selection, reopen the routed conversation
     * or acknowledge the consumed tap as the Activity's current one.
     */
    @Test
    fun aRetiredSwitchFailingAfterExplicitNavigationLeavesTheExplicitSelectionAlone() {
        val retiredWork = openEarlyWhileActivationIsHeld()
        firstFrame.release(requestId)
        shell.openExplicitly(EXPLICIT_CHAT)

        releaseRetiredActivationAndAssertUnchanged(retiredWork, fails = true)

        assertEquals(EXPLICIT_CHAT, shell.selectedChatId)
        assertEquals(listOf(requestId), shell.droppedAcknowledgements.toList())
        assertNull(firstFrame.gate)
    }

    /**
     * Back: the route opened its conversation early and the reader backed out of it, which releases the window
     * and finishes the trace, before the held switch reports that it never landed. The retired failure must not
     * reopen the conversation, fall back over the chat list the reader is already on, or acknowledge the
     * consumed tap as the Activity's current one.
     */
    @Test
    fun aRetiredSwitchFailingAfterBackDoesNotReopenOrFallBack() {
        val retiredWork = openEarlyWhileActivationIsHeld()
        shell.backOut()
        firstFrame.release(requestId)
        NotificationRouteTrace.finishRequest(requestId)

        releaseRetiredActivationAndAssertUnchanged(retiredWork, fails = true)

        assertNull(shell.selectedChatId)
        assertEquals(listOf(requestId), shell.droppedAcknowledgements.toList())
        assertNull(firstFrame.gate)
    }

    /**
     * Account removal: the reader is still inside the early-opened conversation when its account leaves the
     * account list, so the held activation can never publish it. Released afterwards, the retired switch may do
     * only what a never-landed switch always did, leave that conversation for the chat list, and must not reopen
     * it, acknowledge the consumed tap as a current one or touch a window it no longer holds.
     */
    @Test
    fun aRetiredSwitchReleasedAfterItsAccountWasRemovedOnlyLeavesTheConversation() {
        val retiredWork = openEarlyWhileActivationIsHeld()
        firstFrame.release(requestId)
        removeAccount(NotificationRouteHarness.TARGET_ACCOUNT)
        val before = shell.calls.toList()

        activationHold.read(0).release.countDown()
        awaitCompletion(retiredWork)

        assertEquals(before + "fallBack", shell.calls.toList())
        assertNull(shell.selectedChatId)
        assertEquals(listOf(requestId), shell.droppedAcknowledgements.toList())
        assertEquals(NotificationOpenRequest(null, requestId), request.value)
        assertEquals(NotificationRouteHarness.SOURCE_ACCOUNT, appState.activeAccountRef)
        assertNull(firstFrame.gate)
    }

    /**
     * Shell remount, which is what App Lock and Activity recreation do to a route in flight: the composition is
     * dropped, freezing the readers the route captured, and the remounted shell re-runs the same request with
     * fresh slots, so a successor switch for the same request id begins its own window. The retired switch then
     * fails. Request identity cannot tell the two apart, so the process-owned route claim must: the retired
     * switch may not fall back, consume the still-offered target the successor owns or end the successor's
     * window, and the successor must still reach the conversation.
     */
    @Test
    fun aRetiredSwitchFailingAfterAShellRemountLeavesTheSuccessorRouteIntact() {
        gate.releasePreload.countDown()
        val retired = routerWithFrozenReaders()
        val retiredWork = mutationsLaunchedBy { runBlocking { retired.route(target, requestId) } }
        val retiredGate = requireNotNull(firstFrame.gate)
        awaitHeldActivationRead(0)
        runBlocking { router.route(target, requestId) }
        val successorGate = requireNotNull(firstFrame.gate)
        assertNotSame(retiredGate, successorGate)
        assertTrue(retiredGate.isReleased)
        awaitHeldActivationRead(1)
        awaitCondition("the successor preload never published") {
            state.preloadSlot.preload?.state is NotificationMessagePreloadState.Ready
        }

        releaseRetiredActivationAndAssertUnchanged(retiredWork, fails = true)

        assertSame(successorGate, firstFrame.gate)
        assertFalse(successorGate.isReleased)
        assertEquals(NotificationOpenRequest(target, requestId), request.value)
        activationHold.read(1).release.countDown()
        awaitCondition("the successor never activated its account") {
            appState.activeAccountRef == NotificationRouteHarness.TARGET_ACCOUNT
        }
        runBlocking { router.route(target, requestId) }
        val open = "open:${target.groupIdHex}"
        assertEquals(listOf("arm", "close", "arm", "close", open, "handled:$requestId"), shell.calls.toList())
    }

    /**
     * The same remount for an invite, which holds no first-frame window at all, so only the route claim can tell
     * the retired switch from its same-request successor. The retired failure must not fall back or consume the
     * invite tap the successor still owns, and once the successor lands the tap stays pending for the chat list.
     */
    @Test
    fun anInviteSwitchRetiredByAShellRemountLeavesTheSameRequestToItsSuccessor() {
        val invite = NotificationScenario.target(messageIdHex = null, kind = NotificationTargetKind.INVITE)
        request.value = NotificationOpenRequest(invite, requestId)
        val retired = routerWithFrozenReaders(invite, requestId)
        val retiredWork = mutationsLaunchedBy { runBlocking { retired.route(invite, requestId) } }
        assertNull("an invite route holds no window", firstFrame.gate)
        awaitHeldActivationRead(0)
        val live = liveRouter(invite, requestId)
        runBlocking { live.route(invite, requestId) }
        awaitHeldActivationRead(1)

        releaseRetiredActivationAndAssertUnchanged(retiredWork, fails = true, live = live.state)

        assertEquals(NotificationOpenRequest(invite, requestId), request.value)
        activationHold.read(1).release.countDown()
        awaitCondition("the successor never activated its account") {
            appState.activeAccountRef == NotificationRouteHarness.TARGET_ACCOUNT
        }
        runBlocking { live.route(invite, requestId) }
        assertEquals(listOf("arm", "close", "arm", "close"), shell.calls.toList())
        assertEquals("the invite waits for its chat list", NotificationOpenRequest(invite, requestId), request.value)
    }

    /**
     * A retired switch whose shell was disposed, followed by a newer tap for the already active account, which
     * opens straight away and begins no window. The disposed route's request reader still shows its own tap, so
     * only the route claim can retire it: released, it must neither activate its account under the newer
     * conversation nor fall back over it.
     */
    @Test
    fun aRetiredSwitchOutlivingItsShellCannotActivateUnderANewerActiveAccountTap() {
        gate.releasePreload.countDown()
        val retired = routerWithFrozenReaders()
        val retiredWork = mutationsLaunchedBy { runBlocking { retired.route(target, requestId) } }
        awaitHeldActivationRead(0)
        val newer = NotificationRouteHarness.routedTarget(NotificationRouteHarness.SOURCE_ACCOUNT, current = routed)
        val newerTarget = requireNotNull(newer.notificationTarget)
        val newerId = newer.notificationRequestId
        request.value = NotificationOpenRequest(newerTarget, newerId)
        val live = liveRouter(newerTarget, newerId)
        runBlocking { live.route(newerTarget, newerId) }
        val open = "open:${newerTarget.groupIdHex}"
        assertEquals(listOf("arm", "close", "arm", open, "handled:$newerId"), shell.calls.toList())

        releaseRetiredActivationAndAssertUnchanged(retiredWork, fails = false, live = live.state)

        assertEquals(NotificationRouteHarness.SOURCE_ACCOUNT, appState.activeAccountRef)
        assertEquals(newerTarget.groupIdHex, shell.selectedChatId)
        assertEquals(newerId, shell.selectedOpenContext.notificationRouteTraceRequestId)
        assertTrue(shell.droppedAcknowledgements.isEmpty())
    }

    /** Runs one routing pass for the inactive-account tap and waits until its exact preload is held. */
    private fun startRetiredSwitch() {
        runBlocking { router.route(target, requestId) }
        awaitCondition("the retired preload never started") { gate.preloadStarted.count == 0L }
        assertEquals(listOf("arm", "close"), shell.calls.toList())
    }

    /**
     * Runs the inactive-account tap until its conversation is open early: the exact preload answers at once,
     * the pass re-runs on the Ready slot as the keyed effect would, and the commit opens the conversation and
     * consumes the tap while the switch's activation is still held at its first engine read. Returns the
     * process-scoped work the switch launched, so a test can wait for its completion.
     */
    private fun openEarlyWhileActivationIsHeld(): List<Job> {
        gate.releasePreload.countDown()
        val retiredWork = mutationsLaunchedBy { runBlocking { router.route(target, requestId) } }
        awaitCondition("the exact preload never published") {
            state.preloadSlot.preload?.state is NotificationMessagePreloadState.Ready
        }
        awaitHeldActivationRead(0)
        runBlocking { router.route(target, requestId) }
        assertEquals(listOf("arm", "close", "open:${target.groupIdHex}", "handled:$requestId"), shell.calls.toList())
        assertEquals(requestId, shell.selectedOpenContext.notificationRouteTraceRequestId)
        assertEquals(NotificationOpenRequest(null, requestId), request.value)
        assertEquals(NotificationRouteHarness.SOURCE_ACCOUNT, appState.activeAccountRef)
        return retiredWork
    }

    /** Runs [block] and returns the process-scoped mutations it launched, so their completion can be awaited. */
    private fun mutationsLaunchedBy(block: () -> Unit): List<Job> {
        val scope = requireNotNull(appState.mutationsScope.coroutineContext[Job])
        val before = scope.children.toSet()
        block()
        val launched = scope.children.filterNot { it in before }.toList()
        check(launched.isNotEmpty()) { "the pass launched no process-scoped work" }
        return launched
    }

    /** Waits until the [index]-th activation of the target account is held at its first engine read. */
    private fun awaitHeldActivationRead(index: Int) {
        awaitCondition("activation read $index never arrived") { activationHold.read(index).started.count == 0L }
    }

    /** Waits until every job in [work] has run to completion, and checks none of them failed. */
    private fun awaitCompletion(work: List<Job>) {
        awaitCondition("the retired switch never completed") { work.all { it.isCompleted } }
        assertTrue("the retired switch failed instead of standing down", work.none { it.isCancelled })
    }

    /** Releases the retired read, lets its completion run and checks that the newer owner kept everything. */
    private fun releaseRetiredPreloadAndAssertUnchanged(newerGate: NotificationRouteFirstFrameGate) {
        assertUnchangedAfter(completed = { gate.preloadCompleted.count == 0L }) { gate.releasePreload.countDown() }
        assertSame(newerGate, firstFrame.gate)
        assertFalse(newerGate.isReleased)
    }

    /**
     * Releases the held activation read, failing it when asked, waits until the retired switch's [retiredWork]
     * has run to completion, so its reconciliation is known to have happened, and checks it changed nothing.
     */
    private fun releaseRetiredActivationAndAssertUnchanged(
        retiredWork: List<Job>,
        fails: Boolean,
        live: NotificationOpenRouteState = state,
    ) {
        val read = activationHold.read(0)
        read.fails = fails
        assertUnchangedAfter(completed = { retiredWork.all { it.isCompleted } }, live = live) {
            read.release.countDown()
        }
        assertTrue("the retired switch failed instead of standing down", retiredWork.none { it.isCancelled })
    }

    /** Runs [release], waits until [completed], then keeps polling and fails on the first change to [snapshot]. */
    private fun assertUnchangedAfter(
        completed: () -> Boolean,
        live: NotificationOpenRouteState = state,
        release: () -> Unit,
    ) {
        val before = snapshot(live)
        release()
        awaitCondition("the retired work never completed", completed)
        repeat(SETTLE_POLLS) {
            ShadowLooper.idleMainLooper()
            assertEquals("a retired callback changed the newer route", before, snapshot(live))
            Thread.sleep(POLL_MILLIS)
        }
    }

    /** Everything a retired route must leave alone: shell writes, selection, pending tap, account, overlay, window. */
    private fun snapshot(live: NotificationOpenRouteState): List<Any?> =
        listOf(
            shell.calls.toList(),
            shell.selectedChatId,
            shell.selectedOpenContext,
            request.value,
            appState.activeAccountRef,
            live.routing,
            firstFrame.gate,
        )

    /** The route slots a freshly composed shell holds for [target] under [requestId], reading currency as given. */
    private fun routeState(
        target: NotificationTarget,
        requestId: Long,
        request: State<NotificationOpenRequest>,
        composedRuntime: State<Int>,
    ): NotificationOpenRouteState =
        NotificationOpenRouteState(
            routingSlot = mutableStateOf(true),
            armedRequestIdSlot = mutableLongStateOf(0L),
            runtimeSlots = NotificationRuntimeRouteSlots(),
            inviteSlots = NotificationInviteRouteSlots(),
            preloadSlot = NotificationPreloadSlot(notificationMessagePreloadKey(target, requestId)),
            currency = NotificationOpenCurrency(request, composedRuntime) { appState.runtimeGeneration },
        )

    /** A router a freshly composed shell would run for [target] under [requestId], over the Activity's live request. */
    private fun liveRouter(
        target: NotificationTarget,
        requestId: Long,
    ): NotificationOpenRouter {
        val live = routeState(target, requestId, request, composedRuntime)
        return NotificationOpenRouter(appState, router.chats, firstFrame, live, shell)
    }

    /**
     * A router whose request and runtime readers stop at their current values, as the rememberUpdatedState
     * readers of a shell that left composition do, over the same app state, priority owner and retained shell.
     */
    private fun routerWithFrozenReaders(
        target: NotificationTarget = this.target,
        requestId: Long = this.requestId,
    ): NotificationOpenRouter {
        val frozen =
            routeState(target, requestId, mutableStateOf(request.value), mutableIntStateOf(appState.runtimeGeneration))
        return NotificationOpenRouter(appState, router.chats, firstFrame, frozen, shell)
    }

    /** Replaces the runtime generation, both live and as the composition last saw it. */
    private fun replaceRuntime() {
        val next = appState.runtimeGeneration + 1
        setAppStateField("setRuntimeGeneration", Int::class.javaPrimitiveType!!, next)
        composedRuntime.intValue = next
    }

    /** Drops [accountRef] from the account list, as the engine's refreshed list after a wipe no longer carries it. */
    private fun removeAccount(accountRef: String) {
        setAppStateField("setAccounts", List::class.java, appState.accounts.filterNot { it.label == accountRef })
    }

    /** Writes one of the app state's privately set properties through its setter, as a production owner would. */
    private fun setAppStateField(
        setter: String,
        type: Class<*>,
        value: Any?,
    ) {
        WhiteNoiseAppState::class.java
            .getDeclaredMethod(setter, type)
            .apply { isAccessible = true }
            .invoke(appState, value)
    }

    /** Idles the main looper and gives the route's worker threads wall-clock time until [condition] holds. */
    private fun awaitCondition(
        failureMessage: String,
        condition: () -> Boolean,
    ) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(TIMEOUT_MILLIS)
        while (System.nanoTime() <= deadline) {
            ShadowLooper.idleMainLooper()
            if (condition()) return
            Thread.sleep(POLL_MILLIS)
        }
        throw AssertionError(failureMessage)
    }

    /**
     * Records every shell write the route asks for, in order, over the retained selection the port would write.
     * Acknowledgements are answered as the Activity answers them: only a tap that is still offered under the
     * same request id is consumed, any other one is dropped and recorded apart.
     */
    private class RecordingShell(
        private val request: MutableState<NotificationOpenRequest>,
    ) : NotificationOpenShell {
        val calls = CopyOnWriteArrayList<String>()
        val droppedAcknowledgements = CopyOnWriteArrayList<Long>()
        override var selectedOpenContext = ConversationOpenContext()
        var selectedChatId: String? = null

        /** Records an arming of a new request. */
        override fun armRequest() {
            calls += "arm"
        }

        /** Records the pre-switch close and drops the selection with its open-time state, as the port does. */
        override fun closeConversationForAccountSwitch() {
            calls += "close"
            deselect()
        }

        /** Records a fallback to the chat list and drops the selection. */
        override fun fallBackToChatList() {
            calls += "fallBack"
            deselect()
        }

        /** Records an opened conversation and adopts it with its context. */
        override fun openRoutedConversation(
            item: ChatListItem,
            openContext: ConversationOpenContext,
        ) {
            calls += "open:${item.id}"
            selectedChatId = item.id
            selectedOpenContext = openContext
        }

        /** Consumes the acknowledged tap only while the Activity still offers exactly it, otherwise drops the ack. */
        override fun targetHandled(
            target: NotificationTarget,
            requestId: Long,
        ) {
            val offered = request.value
            if (inboundNotificationHandledMatchesCurrent(offered.target, offered.requestId, target, requestId)) {
                request.value = NotificationOpenRequest(null, requestId)
                calls += "handled:$requestId"
            } else {
                droppedAcknowledgements += requestId
            }
        }

        /** The reader opens [chatId] themselves, as a chat-list tap, a search hit or a profile sheet would. */
        fun openExplicitly(chatId: String) {
            selectedChatId = chatId
            selectedOpenContext = ConversationOpenContext()
        }

        /** The reader backs out of the selected conversation to the chat list. */
        fun backOut() = deselect()

        /** Clears the selection and its open-time state. */
        private fun deselect() {
            selectedChatId = null
            selectedOpenContext = ConversationOpenContext()
        }
    }

    private companion object {
        const val TIMEOUT_MILLIS = NotificationRouteHarness.ROUTE_TIMEOUT_MILLIS
        const val POLL_MILLIS = 20L
        const val SETTLE_POLLS = 50
        const val EXPLICIT_CHAT = "group-explicit"
    }
}
