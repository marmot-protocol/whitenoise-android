package dev.ipf.whitenoise.android.state

import android.os.Looper
import dev.ipf.marmotkit.GroupEventKindFfi
import dev.ipf.marmotkit.MarmotEventFfi
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** A group-change notice lives only as long as the event subscription that reported it. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GroupChangeNoticeStreamTest {
    /**
     * A subscription that ends while its notice is on screen withdraws the notice unseen before the stream is
     * destroyed, so the notice can neither stay up nor be acknowledged while the listener reconnects.
     */
    @Test
    fun aStreamThatEndsWithdrawsItsNotice() =
        runBlocking {
            val fixture =
                NotificationBootstrapTestFixture(
                    context = RuntimeEnvironment.getApplication(),
                    emitStartupNotification = false,
                    initialActiveAccountRef = ACCOUNT,
                )
            val state = fixture.appState
            val listener = launch(Dispatchers.IO) { fixture.runReceiptLoop() }
            try {
                fixture.emitRuntimeEvent(notDelivered())
                awaitOnMain { state.toast != null }
                fixture.endRuntimeEventStream()
                awaitOnMain { fixture.destroyedEventSubscriptions.get() == 1 }
                assertNull(state.toast)
                assertTrue(state.groupChangeNotices.showing.isEmpty())
                assertFalse(state.groupChangeNotices.shown.contains(ACCOUNT, COMMIT))
            } finally {
                listener.cancel()
                awaitOnMain { listener.isCompleted }
                fixture.close()
            }
        }

    /**
     * A snackbar that ran to the end just before its stream closed still counts as seen, although the stream's end
     * cancels the presenter before it wakes: the change's next report, as the reopened stream's retained read raises
     * it, is acknowledged without showing it again.
     */
    @Test
    fun aSnackbarThatEndsAsItsStreamClosesStaysSeen() =
        runBlocking {
            val fixture =
                NotificationBootstrapTestFixture(
                    context = RuntimeEnvironment.getApplication(),
                    emitStartupNotification = false,
                    initialActiveAccountRef = ACCOUNT,
                )
            val state = fixture.appState
            val listener = launch(Dispatchers.IO) { fixture.runReceiptLoop() }
            var again: Deferred<Boolean>? = null
            try {
                fixture.emitRuntimeEvent(notDelivered())
                awaitOnMain { state.toast != null }
                // The root's snackbar ends, and the stream closes before the presenter sees the slot change.
                state.toast?.let(state::noteToastSeen)
                state.clearToast()
                fixture.endRuntimeEventStream()
                awaitOnMain { fixture.destroyedEventSubscriptions.get() == 1 }
                again =
                    async(start = CoroutineStart.UNDISPATCHED) {
                        state.showGroupChangeNotice(requireNotNull(groupChangeNotice(notDelivered())))
                    }
                assertTrue(again.isCompleted && again.await())
                assertNull(state.toast)
            } finally {
                again?.cancel()
                listener.cancel()
                awaitOnMain { listener.isCompleted && again?.isCompleted != false }
                fixture.close()
            }
        }

    /** Runs the paused main looper until [done] holds, as the presenter and the loop's cleanup both need it. */
    private suspend fun awaitOnMain(done: () -> Boolean) {
        withTimeout(AWAIT_TIMEOUT_MS) {
            while (!done()) {
                shadowOf(Looper.getMainLooper()).idle()
                delay(1L)
            }
        }
    }

    /** MDK's report that [ACCOUNT]'s invitation reached none of the group's relays. */
    private fun notDelivered() =
        MarmotEventFfi.GroupEvent(
            accountIdHex = "a".repeat(64),
            accountLabel = ACCOUNT,
            groupIdHex = "b".repeat(64),
            event =
                GroupEventKindFfi.GroupChangeNotDelivered(
                    commitIdHex = COMMIT,
                    intentKind = "invite",
                    reason = "not_delivered",
                ),
        )

    private companion object {
        const val ACCOUNT = "alice"
        val COMMIT = "c".repeat(64)
        const val AWAIT_TIMEOUT_MS = 10_000L
    }
}
