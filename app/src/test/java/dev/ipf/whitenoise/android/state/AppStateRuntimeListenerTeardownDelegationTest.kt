package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.MarmotInterface
import dev.ipf.marmotkit.NotificationUpdateFfi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Characterizes the production listener and teardown boundary without booting native runtime. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppStateRuntimeListenerTeardownDelegationTest {
    private val marmot =
        Proxy.newProxyInstance(
            MarmotInterface::class.java.classLoader,
            arrayOf(MarmotInterface::class.java),
        ) { _, method, _ -> error("No native calls expected: ${method.name}") } as MarmotInterface

    @Test
    fun acquiredReceiverClosesOnceOnStreamEndReceiveFailureAndCloseFailure() =
        runBlocking {
            for ((receiveFails, closeFails) in listOf(false to false, true to false, false to true)) {
                val closed = CompletableDeferred<Unit>()
                val closes = AtomicInteger()
                val activeAtNext = AtomicBoolean()
                val activeAtClose = AtomicBoolean(true)
                lateinit var state: WhiteNoiseAppState
                val subscription =
                    object : AppNotificationSubscription {
                        override suspend fun next(): NotificationUpdateFfi? {
                            activeAtNext.set(receiverActive(state).value)
                            if (receiveFails) error("receive failed")
                            return null
                        }

                        override fun close() {
                            activeAtClose.set(receiverActive(state).value)
                            closes.incrementAndGet()
                            closed.complete(Unit)
                            if (closeFails) error("close failed")
                        }
                    }
                state = state { subscription }
                val listener = launch(start = CoroutineStart.UNDISPATCHED) { state.runNotificationListenerLoop(marmot) }
                try {
                    withTimeout(5_000) { closed.await() }
                    listener.cancelAndJoin()
                    assertEquals(1, closes.get())
                    assertTrue(activeAtNext.get())
                    assertFalse(activeAtClose.get())
                    assertFalse(receiverActive(state).value)
                } finally {
                    listener.cancelAndJoin()
                }
            }
        }

    @Test
    fun concurrentAccountTeardownWaitsForCancelledReceiverCleanupAndRefusesReplacement() =
        runBlocking {
            val receiving = CompletableDeferred<Unit>()
            val closing = CompletableDeferred<Unit>()
            val releaseClose = CountDownLatch(1)
            val closes = AtomicInteger()
            val activeAtClose = AtomicBoolean(true)
            val closeReleased = AtomicBoolean()
            lateinit var state: WhiteNoiseAppState
            val subscription =
                object : AppNotificationSubscription {
                    override suspend fun next(): NotificationUpdateFfi? {
                        receiving.complete(Unit)
                        awaitCancellation()
                    }

                    override fun close() {
                        activeAtClose.set(receiverActive(state).value)
                        closes.incrementAndGet()
                        closing.complete(Unit)
                        closeReleased.set(releaseClose.await(5, TimeUnit.SECONDS))
                    }
                }
            state = state { subscription }
            val ownerStops = AtomicInteger()
            val receiverPublications = AtomicInteger()
            val observingOwnerInstalled = observeProductionOwnerStops(state, ownerStops, receiverPublications)
            val slot = field<NotificationJobSlot>(state, "notificationJob")
            val listener = launch(start = CoroutineStart.UNDISPATCHED) { state.runNotificationListenerLoop(marmot) }
            slot.startIfInactive { listener }
            try {
                withTimeout(5_000) { receiving.await() }
                val first =
                    launch(start = CoroutineStart.UNDISPATCHED) {
                        state.stopNotificationListenerForAccountTeardown()
                    }
                withTimeout(5_000) { closing.await() }
                val second =
                    launch(start = CoroutineStart.UNDISPATCHED) {
                        state.stopNotificationListenerForAccountTeardown()
                    }
                if (observingOwnerInstalled) assertEquals(2, ownerStops.get())
                assertFalse(first.isCompleted)
                assertFalse(second.isCompleted)
                assertNull(slot.currentOrStart { error("replacement must not start during cleanup") })
                releaseClose.countDown()
                withTimeout(5_000) {
                    first.join()
                    second.join()
                }
                state.stopNotificationListenerForAccountTeardown()
                if (observingOwnerInstalled) {
                    assertEquals(3, ownerStops.get())
                    assertEquals(2, receiverPublications.get())
                }
                assertEquals(1, closes.get())
                assertFalse(activeAtClose.get())
                assertTrue("cleanup was never released", closeReleased.get())
                assertFalse(receiverActive(state).value)
            } finally {
                releaseClose.countDown()
                listener.cancelAndJoin()
            }
        }

    @Test
    fun acquisitionFailureDoesNotPublishAnActiveReceiver() =
        runBlocking {
            val calls = AtomicInteger()
            val state =
                state {
                    calls.incrementAndGet()
                    error("subscription unavailable")
                }
            val listener = launch(start = CoroutineStart.UNDISPATCHED) { state.runNotificationListenerLoop(marmot) }
            try {
                assertEquals(1, calls.get())
                assertFalse(receiverActive(state).value)
            } finally {
                listener.cancelAndJoin()
            }
        }

    private fun state(subscribe: suspend () -> AppNotificationSubscription) =
        WhiteNoiseAppState(
            context = RuntimeEnvironment.getApplication(),
            draftStore = DraftStore.forContext(RuntimeEnvironment.getApplication()),
            accountIdHexResolver = { null },
            accounts = emptyList(),
            activeAccountRef = "",
            notificationSubscriber = { subscribe() },
            marmotRuntimeFactory = { error("Native runtime must not boot in this fixture") },
        )

    private fun receiverActive(app: WhiteNoiseAppState) = field<StateFlow<Boolean>>(app, "notificationReceiverActive")

    /** Supports the pre-extraction baseline; a wired owner must receive every real AppState stop. */
    private fun observeProductionOwnerStops(
        state: WhiteNoiseAppState,
        calls: AtomicInteger,
        receiverPublications: AtomicInteger,
    ): Boolean {
        val ownerField =
            WhiteNoiseAppState::class.java.declaredFields.singleOrNull {
                it.type == AppRuntimeListenerTeardownOwner::class.java
            } ?: return false
        ownerField.isAccessible = true
        val original = ownerField.get(state) as AppRuntimeListenerTeardownOwner
        val realReceiverActive = field<MutableStateFlow<Boolean>>(state, "notificationReceiverActive")
        val observingReceiverActive =
            object : MutableStateFlow<Boolean> by realReceiverActive {
                override var value: Boolean
                    get() = realReceiverActive.value
                    set(value) {
                        receiverPublications.incrementAndGet()
                        realReceiverActive.value = value
                    }
            }
        // Keep the original teardown collaborators and shared receiver state.
        // The observation wraps the whole existing stop rather than faking its cancellation/lifetime behavior.
        ownerField.set(
            state,
            AppRuntimeListenerTeardownOwner(
                cancelNetworkRecovery = {
                    calls.incrementAndGet()
                    original.stopForAccountTeardown()
                },
                cancelPushWakeDrain = {},
                cancelListener = {},
                clearUnreadRefresh = {},
                receiverActive = observingReceiverActive,
            ),
        )
        return true
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> field(
        state: WhiteNoiseAppState,
        name: String,
    ): T =
        WhiteNoiseAppState::class.java
            .getDeclaredField(name)
            .apply { isAccessible = true }
            .get(state) as T
}
