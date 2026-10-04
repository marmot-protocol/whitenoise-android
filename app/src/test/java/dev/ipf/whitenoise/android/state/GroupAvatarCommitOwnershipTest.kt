package dev.ipf.whitenoise.android.state

import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.marmotkit.MarmotInterface
import kotlinx.coroutines.CompletableDeferred
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

    private fun appState(writes: AtomicInteger): WhiteNoiseAppState =
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
        )

    private companion object {
        const val ACCOUNT = "personal"
        const val OLD_AVATAR = "https://blossom.example/old.jpg"
        const val NEW_AVATAR = "https://blossom.example/new.jpg"
    }
}
