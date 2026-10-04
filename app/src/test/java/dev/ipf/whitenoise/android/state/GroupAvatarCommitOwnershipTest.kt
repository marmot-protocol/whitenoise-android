package dev.ipf.whitenoise.android.state

import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.marmotkit.MarmotInterface
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.lang.reflect.Proxy
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
                    controller.updateGroupAvatarUrl(ScopedGroupImageMutation(NEW_AVATAR) { true }) { current }
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

    /** Holds the IO entry without mixing runTest's scheduler with another test scheduler. */
    private class QueuedIoDispatcher : CoroutineDispatcher() {
        private val queued = ArrayDeque<Runnable>()

        override fun dispatch(context: CoroutineContext, block: Runnable) {
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
            initialMarmotRuntime = AppMarmotRuntime("test", native(writes)),
            marmotIoDispatcher = ioDispatcher,
        )

    private companion object {
        const val ACCOUNT = "personal"
        const val OLD_AVATAR = "https://blossom.example/old.jpg"
        const val NEW_AVATAR = "https://blossom.example/new.jpg"
    }
}
