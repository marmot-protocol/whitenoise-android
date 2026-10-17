package dev.ipf.whitenoise.android.state

import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.marmotkit.MarmotInterface
import dev.ipf.marmotkit.MarmotKitException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.lang.reflect.Proxy
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@OptIn(ExperimentalCoroutinesApi::class)
class GroupAvatarCommitOwnershipTest {
    @Test
    fun editorRetiredWhileWaitingForGroupCommitDoesNotWriteOrReportFailure() =
        runTest {
            val writes = AtomicInteger()
            val state = appState(writes)
            val group = conversationTimelineTestGroup().copy(avatarUrl = OLD_AVATAR)
            val controller = ConversationController(state, group)
            val releaseLock = CompletableDeferred<Unit>()
            val holder = launch { state.withGroupCommitLock(ACCOUNT, group.groupIdHex) { releaseLock.await() } }
            runCurrent()
            var current = true
            val result =
                async {
                    controller.updateGroupAvatarUrl(
                        ScopedGroupImageMutation(NEW_AVATAR) { true },
                        commitIfCurrent = { current },
                    )
                }
            runCurrent()
            assertFalse(result.isCompleted)
            current = false
            releaseLock.complete(Unit)
            holder.join()
            val committed = result.await()
            assertEquals(0, writes.get())
            assertFalse(committed)
            assertEquals(OLD_AVATAR, controller.group.avatarUrl)
            assertNull(controller.lastMutationError)
            assertFalse(controller.mutationInFlight)
        }

    @Test
    fun alreadyRetiredAttemptDoesNotReachNativeWrite() =
        runTest {
            val writes = AtomicInteger()
            val controller =
                ConversationController(
                    appState(writes),
                    conversationTimelineTestGroup().copy(avatarUrl = OLD_AVATAR),
                )
            val committed =
                controller.updateGroupAvatarUrl(
                    ScopedGroupImageMutation(NEW_AVATAR) { true },
                    commitIfCurrent = { false },
                )
            assertEquals(0, writes.get())
            assertFalse(committed)
            assertEquals(OLD_AVATAR, controller.group.avatarUrl)
            assertNull(controller.lastMutationError)
        }

    @Test
    fun currentAttemptWritesOnceAndUpdatesAvatar() =
        runTest {
            val writes = AtomicInteger()
            val controller = ConversationController(appState(writes), conversationTimelineTestGroup())
            assertTrue(controller.updateGroupAvatarUrl(ScopedGroupImageMutation(NEW_AVATAR) { true }))
            assertEquals(1, writes.get())
            assertEquals(NEW_AVATAR, controller.group.avatarUrl)
            assertNull(controller.lastMutationError)
        }

    @Test
    fun editorRetiredWhileIoIsQueuedDoesNotWriteOrChangeAvatar() =
        runTest {
            val writes = AtomicInteger()
            val ioDispatcher = QueuedIoDispatcher()
            val state = appState(writes, ioDispatcher)
            val controller = ConversationController(state, conversationTimelineTestGroup().copy(avatarUrl = OLD_AVATAR))
            var current = true
            val result =
                async {
                    controller.updateGroupAvatarUrl(ScopedGroupImageMutation(NEW_AVATAR) { current })
                }
            runCurrent()
            assertFalse(result.isCompleted)
            assertEquals(0, writes.get())
            current = false
            ioDispatcher.runQueued()
            runCurrent()
            assertFalse(result.await())
            assertEquals(0, writes.get())
            assertEquals(OLD_AVATAR, controller.group.avatarUrl)
            assertNull(controller.lastMutationError)
            assertFalse(controller.mutationInFlight)
        }

    @Test
    fun currentAttemptQueuedForIoWritesOnceAfterDispatch() =
        runTest {
            val writes = AtomicInteger()
            val ioDispatcher = QueuedIoDispatcher()
            val controller =
                ConversationController(
                    appState(writes, ioDispatcher),
                    conversationTimelineTestGroup(),
                )
            val result = async { controller.updateGroupAvatarUrl(ScopedGroupImageMutation(NEW_AVATAR) { true }) }
            runCurrent()
            assertFalse(result.isCompleted)
            assertEquals(0, writes.get())
            ioDispatcher.runQueued()
            runCurrent()
            assertTrue(result.await())
            assertEquals(1, writes.get())
            assertEquals(NEW_AVATAR, controller.group.avatarUrl)
        }

    /**
     * Once the user continued past MDK's warning, the retry reaches MDK even if the editor closed while the
     * acknowledgement was recorded; bailing out there would leave that consent for some later, different change.
     */
    @Test
    fun anAcknowledgedRetryReachesMdkAfterTheEditorCloses() =
        runBlocking {
            val calls = Collections.synchronizedList(mutableListOf<String>())
            val current = AtomicBoolean(true)
            val state =
                appState(
                    AtomicInteger(),
                    runtime =
                        scripted { name, arguments ->
                            calls += name
                            when (name) {
                                "groupCommitDelivery" -> null
                                "acknowledgeGroupCatchingUp" -> {
                                    current.set(false)
                                    true
                                }
                                "updateGroupAvatarUrl" ->
                                    if (calls.count { it == name } == 1) {
                                        failSuspendedNativeCall(arguments, MarmotKitException.GroupCatchingUp(GROUP))
                                    } else {
                                        null
                                    }
                                else -> error("Unexpected native method: $name")
                            }
                        },
                )
            val controller = ConversationController(state, conversationTimelineTestGroup().copy(avatarUrl = OLD_AVATAR))
            val result =
                CoroutineScope(Job() + Dispatchers.Default).async {
                    controller.updateGroupAvatarUrl(
                        ScopedGroupImageMutation(NEW_AVATAR) { true },
                        commitIfCurrent = { current.get() },
                    )
                }
            awaitWithMainLooper { state.groupChangeConfirmations.pending != null || result.isCompleted }
            check(!result.isCompleted) { "finished without asking: ${controller.lastMutationError}" }
            checkNotNull(state.groupChangeConfirmations.pending).answer(proceed = true)
            awaitWithMainLooper { result.isCompleted }

            val writes = calls.count { it == "updateGroupAvatarUrl" }
            assertEquals("the acknowledgement was left for another change", 2, writes)
            assertEquals(1, calls.count { it == "acknowledgeGroupCatchingUp" })
        }

    /** The test thread is Robolectric's main thread, so its looper runs between polls for main-thread steps. */
    private suspend fun awaitWithMainLooper(done: () -> Boolean) {
        withTimeout(TIMEOUT_MILLIS) {
            while (!done()) {
                shadowOf(Looper.getMainLooper()).idle()
                delay(POLL_MILLIS)
            }
        }
    }

    /** A runtime whose every call is answered by [answer] with the method's name and arguments. */
    private fun scripted(answer: (String, Array<out Any?>?) -> Any?): MarmotInterface =
        Proxy.newProxyInstance(MarmotInterface::class.java.classLoader, arrayOf(MarmotInterface::class.java)) {
            _,
            method,
            arguments,
            ->
            answer(method.name.substringBefore('-'), arguments)
        } as MarmotInterface

    /** Holds the IO entry without mixing runTest's scheduler with another test scheduler. */
    private class QueuedIoDispatcher : CoroutineDispatcher() {
        private val queued = ArrayDeque<Runnable>()

        override fun dispatch(
            context: CoroutineContext,
            block: Runnable,
        ) {
            queued.addLast(block)
        }

        fun runQueued() {
            while (queued.isNotEmpty()) queued.removeFirst().run()
        }
    }

    private fun native(writes: AtomicInteger): MarmotInterface =
        Proxy.newProxyInstance(
            MarmotInterface::class.java.classLoader,
            arrayOf(MarmotInterface::class.java),
        ) { proxy, method, args ->
            when (method.name.substringBefore('-')) {
                "toString" -> "avatar-test-native"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.firstOrNull()
                "updateGroupAvatarUrl" -> {
                    writes.incrementAndGet()
                    null
                }
                else -> error("Unexpected native method: ${method.name}")
            }
        } as MarmotInterface

    private fun appState(
        writes: AtomicInteger,
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
        runtime: MarmotInterface = native(writes),
    ): WhiteNoiseAppState =
        WhiteNoiseAppState(
            context = ApplicationProvider.getApplicationContext(),
            draftStore = DraftStore(ConversationTimelineTestDraftPersistence()),
            accountIdHexResolver = { ConversationTimelineTestIds.ACCOUNT_ID },
            accounts =
                listOf(
                    AccountSummaryFfi(
                        label = ACCOUNT,
                        accountIdHex = ConversationTimelineTestIds.ACCOUNT_ID,
                        localSigning = true,
                        externalSigning = false,
                        signedOut = false,
                        running = true,
                    ),
                ),
            activeAccountRef = ACCOUNT,
            initialMarmotRuntime = AppMarmotRuntime("test", runtime),
            marmotIoDispatcher = ioDispatcher,
        )

    private companion object {
        const val ACCOUNT = "personal"
        const val OLD_AVATAR = "https://blossom.example/old.jpg"
        const val NEW_AVATAR = "https://blossom.example/new.jpg"
        const val TIMEOUT_MILLIS = 5_000L
        const val POLL_MILLIS = 10L
        val GROUP = ConversationTimelineTestIds.GROUP_ID
    }
}
