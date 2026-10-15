package dev.ipf.whitenoise.android.ui.navigation

import android.content.Context
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.test.core.app.ApplicationProvider
import dev.ipf.whitenoise.android.notifications.NotificationRouteFirstFrameGate
import dev.ipf.whitenoise.android.notifications.NotificationTarget
import dev.ipf.whitenoise.android.notifications.notificationMessagePreloadKey
import dev.ipf.whitenoise.android.state.ChatListItem
import dev.ipf.whitenoise.android.state.ChatsController
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
 * state owners replaced by a recording port. An inactive-account tap starts its process-scoped switch with the
 * exact preload held, a newer tap or a runtime replacement then takes over with its own first-frame window, and
 * only afterwards is the retired read released. The retired route must not touch the shell, acknowledge a tap,
 * lower the newer route's overlay or end the newer window.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en")
class NotificationOpenRouteSeamTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val gate = AccountRouteOrderGate(preloadFinishesFirst = false)
    private val appState = NotificationRouteHarness.appState(context, NotificationRouteHarness.fakeMarmot(gate))
    private val routed = NotificationRouteHarness.routedTarget(NotificationRouteHarness.TARGET_ACCOUNT)
    private val target = requireNotNull(routed.notificationTarget)
    private val requestId = routed.notificationRequestId
    private val request = mutableStateOf(NotificationOpenRequest(target, requestId))
    private val composedRuntime = mutableIntStateOf(appState.runtimeGeneration)
    private val firstFrame = NotificationFirstFramePriority { appState.runtimeGeneration }
    private val shell = RecordingShell()
    private val state =
        NotificationOpenRouteState(
            routingSlot = mutableStateOf(true),
            armedRequestIdSlot = mutableLongStateOf(0L),
            runtimeSlots = NotificationRuntimeRouteSlots(),
            inviteSlots = NotificationInviteRouteSlots(),
            preloadSlot = NotificationPreloadSlot(notificationMessagePreloadKey(target, requestId)),
            currency = NotificationOpenCurrency(request, composedRuntime) { appState.runtimeGeneration },
        )
    private val router = NotificationOpenRouter(appState, ChatsController(appState), firstFrame, state, shell)

    /** Releases every held read and stops the app's process-scoped work so nothing outlives the case. */
    @After
    fun tearDown() {
        gate.releasePreload.countDown()
        gate.releaseActivation.countDown()
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

    /** Runs one routing pass for the inactive-account tap and waits until its exact preload is held. */
    private fun startRetiredSwitch() {
        runBlocking { router.route(target, requestId) }
        awaitCondition("the retired preload never started") { gate.preloadStarted.count == 0L }
        assertEquals(listOf("arm", "close"), shell.calls.toList())
    }

    /** Releases the retired read, lets its completion run and checks that the newer owner kept everything. */
    private fun releaseRetiredPreloadAndAssertUnchanged(newerGate: NotificationRouteFirstFrameGate) {
        val before = snapshot()
        gate.releasePreload.countDown()
        val completed = gate.preloadCompleted.await(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
        check(completed) { "the retired preload never completed" }
        repeat(SETTLE_POLLS) {
            ShadowLooper.idleMainLooper()
            assertEquals("a retired callback changed the newer route", before, snapshot())
            Thread.sleep(POLL_MILLIS)
        }
        assertSame(newerGate, firstFrame.gate)
        assertFalse(newerGate.isReleased)
    }

    /** Everything a retired route must leave alone: shell writes, acknowledgements, the overlay and the window. */
    private fun snapshot(): List<Any?> {
        val calls = shell.calls.toList()
        return listOf(calls, state.routing, firstFrame.gate, shell.selectedOpenContext)
    }

    /** Replaces the runtime generation, both live and as the composition last saw it. */
    private fun replaceRuntime() {
        val next = appState.runtimeGeneration + 1
        WhiteNoiseAppState::class.java
            .getDeclaredMethod("setRuntimeGeneration", Int::class.javaPrimitiveType)
            .apply { isAccessible = true }
            .invoke(appState, next)
        composedRuntime.intValue = next
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

    /** Records every shell write the route asks for, in order. */
    private class RecordingShell : NotificationOpenShell {
        val calls = CopyOnWriteArrayList<String>()
        override var selectedOpenContext = ConversationOpenContext()

        /** Records an arming of a new request. */
        override fun armRequest() {
            calls += "arm"
        }

        /** Records the pre-switch close. */
        override fun closeConversationForAccountSwitch() {
            calls += "close"
        }

        /** Records a fallback to the chat list. */
        override fun fallBackToChatList() {
            calls += "fallBack"
        }

        /** Records an opened conversation and adopts its context. */
        override fun openRoutedConversation(
            item: ChatListItem,
            openContext: ConversationOpenContext,
        ) {
            calls += "open:${item.id}"
            selectedOpenContext = openContext
        }

        /** Records an acknowledgement. */
        override fun targetHandled(
            target: NotificationTarget,
            requestId: Long,
        ) {
            calls += "handled:$requestId"
        }
    }

    private companion object {
        const val TIMEOUT_MILLIS = NotificationRouteHarness.ROUTE_TIMEOUT_MILLIS
        const val POLL_MILLIS = 20L
        const val SETTLE_POLLS = 50
    }
}
