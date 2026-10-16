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
import dev.ipf.whitenoise.android.notifications.NotificationTarget
import dev.ipf.whitenoise.android.notifications.inboundNotificationHandledMatchesCurrent
import dev.ipf.whitenoise.android.notifications.notificationMessagePreloadKey
import dev.ipf.whitenoise.android.state.ChatListItem
import dev.ipf.whitenoise.android.state.ChatsController
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
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
 * account removal or an App Lock remount then changes what the route may still own, and only afterwards is
 * the retired read released. The retired route must not reopen a conversation, consume a tap it no longer
 * owns, lower a newer route's overlay or end a window it did not begin.
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
    private val state = routeState(request, composedRuntime)
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
        openEarlyWhileActivationIsHeld()
        firstFrame.release(requestId)
        shell.openExplicitly(EXPLICIT_CHAT)

        releaseRetiredActivationAndAssertUnchanged(fails = true)

        assertEquals(EXPLICIT_CHAT, shell.selectedChatId)
        assertEquals(listOf(requestId), shell.droppedAcknowledgements.toList())
        assertNull(firstFrame.gate)
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
     * consumes the tap while the switch's activation is still held at its first engine read.
     */
    private fun openEarlyWhileActivationIsHeld() {
        gate.releasePreload.countDown()
        runBlocking { router.route(target, requestId) }
        awaitCondition("the exact preload never published") {
            state.preloadSlot.preload?.state is NotificationMessagePreloadState.Ready
        }
        awaitHeldActivationRead(0)
        runBlocking { router.route(target, requestId) }
        assertEquals(listOf("arm", "close", "open:${target.groupIdHex}", "handled:$requestId"), shell.calls.toList())
        assertEquals(requestId, shell.selectedOpenContext.notificationRouteTraceRequestId)
        assertEquals(NotificationOpenRequest(null, requestId), request.value)
        assertEquals(NotificationRouteHarness.SOURCE_ACCOUNT, appState.activeAccountRef)
    }

    /** Waits until the [index]-th activation of the target account is held at its first engine read. */
    private fun awaitHeldActivationRead(index: Int) {
        awaitCondition("activation read $index never arrived") { activationHold.read(index).started.count == 0L }
    }

    /** Releases the retired read, lets its completion run and checks that the newer owner kept everything. */
    private fun releaseRetiredPreloadAndAssertUnchanged(newerGate: NotificationRouteFirstFrameGate) {
        assertUnchangedAfter(completed = { gate.preloadCompleted.count == 0L }) { gate.releasePreload.countDown() }
        assertSame(newerGate, firstFrame.gate)
        assertFalse(newerGate.isReleased)
    }

    /** Releases the held activation read, failing it when asked, and checks the retired switch changed nothing. */
    private fun releaseRetiredActivationAndAssertUnchanged(fails: Boolean) {
        val read = activationHold.read(0)
        read.fails = fails
        assertUnchangedAfter(completed = { read.passed.count == 0L }) { read.release.countDown() }
    }

    /** Runs [release], waits until [completed], then keeps polling and fails on the first change to [snapshot]. */
    private fun assertUnchangedAfter(
        completed: () -> Boolean,
        release: () -> Unit,
    ) {
        val before = snapshot()
        release()
        awaitCondition("the retired work never completed", completed)
        repeat(SETTLE_POLLS) {
            ShadowLooper.idleMainLooper()
            assertEquals("a retired callback changed the newer route", before, snapshot())
            Thread.sleep(POLL_MILLIS)
        }
    }

    /** Everything a retired route must leave alone: shell writes, selection, pending tap, overlay and window. */
    private fun snapshot(): List<Any?> =
        listOf(
            shell.calls.toList(),
            shell.selectedChatId,
            shell.selectedOpenContext,
            request.value,
            state.routing,
            firstFrame.gate,
        )

    /** The route slots a freshly composed shell holds for this tap, with currency read from the given readers. */
    private fun routeState(
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

    /** Replaces the runtime generation, both live and as the composition last saw it. */
    private fun replaceRuntime() {
        val next = appState.runtimeGeneration + 1
        setAppStateField("setRuntimeGeneration", Int::class.javaPrimitiveType!!, next)
        composedRuntime.intValue = next
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
