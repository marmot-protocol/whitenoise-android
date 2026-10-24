package dev.ipf.whitenoise.android.ui.chats

import dev.ipf.whitenoise.android.state.ChatListActionOwner
import dev.ipf.whitenoise.android.state.ChatListItem
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en")
@OptIn(ExperimentalCoroutinesApi::class)
class ChatListActionCompletionTest {
    @Test fun committedReadAndPinRefreshAfterPresentationRetires() = checkNativeCompletions(Retirement.PRESENTATION)

    @Test fun committedReadAndPinCannotPublishAcrossAccountChange() = checkNativeCompletions(Retirement.ACCOUNT)

    @Test fun committedReadAndPinCannotPublishAcrossRuntimeChange() = checkNativeCompletions(Retirement.RUNTIME)

    @Test fun committedReadAndPinCannotPublishAcrossBindingChange() = checkNativeCompletions(Retirement.BINDING)

    @Test fun queuedReadAndPinCannotPassRetiredPresentationAdmission() =
        runBlocking {
            Command.entries.forEach { command ->
                val scope = TestScope()
                val fixture = OffWindowActionFixture(scope)
                try {
                    val present = AtomicBoolean(true)
                    val owner = fixture.controller.captureChatListActionOwner(present::get)!!
                    val item = fixture.controller.resolveChatListActionItem(ACTION_TARGET, owner)!!
                    val result = async(start = CoroutineStart.UNDISPATCHED) { command.commit(fixture, item, owner) }
                    assertTrue("the IO dispatcher must still hold the unstarted command", fixture.calls.isEmpty())
                    present.set(false)
                    scope.testScheduler.runCurrent()
                    assertFalse(result.await())
                    assertTrue(fixture.calls.isEmpty())
                } finally {
                    closeFixture(fixture, scope)
                }
            }
        }

    @Test fun admittedArchiveSettlesOnlyItsSessionAfterPresentationChanges() =
        runBlocking {
            Retirement.entries.forEach { retirement ->
                val scope = TestScope()
                val fixture = OffWindowActionFixture(scope, Dispatchers.IO)
                val release = CompletableDeferred<Unit>()
                try {
                    val present = AtomicBoolean(true)
                    val owner = fixture.controller.captureChatListActionOwner(present::get)!!
                    fixture.archiveEntered = CompletableDeferred()
                    fixture.releaseArchive = release
                    val result =
                        async(start = CoroutineStart.UNDISPATCHED) {
                            fixture.controller.setArchivedForOwner(listOf(ACTION_TARGET), true, false, owner)
                        }
                    fixture.archiveEntered!!.await()
                    when (retirement) {
                        Retirement.PRESENTATION -> present.set(false)
                        Retirement.ACCOUNT -> fixture.replaceAccount()
                        Retirement.RUNTIME -> fixture.replaceRuntimeGeneration()
                        Retirement.BINDING -> fixture.controller.bind(null)
                    }
                    release.complete(Unit)
                    assertEquals(if (retirement == Retirement.PRESENTATION) 1 else 0, result.await())
                    assertTrue("the admitted native command committed", fixture.row.get().archived)
                    assertEquals(listOf("archive:true"), fixture.calls.toList())
                    assertFalse(fixture.controller.containsGroup(ACTION_TARGET))
                } finally {
                    release.complete(Unit)
                    closeFixture(fixture, scope)
                }
            }
        }

    @Test fun archiveBatchCompletesAdmittedRowButDoesNotStartRetiredScopeSuccessor() =
        runBlocking {
            val scope = TestScope()
            val fixture = OffWindowActionFixture(scope, Dispatchers.IO)
            val release = CompletableDeferred<Unit>()
            try {
                val present = AtomicBoolean(true)
                val owner = fixture.controller.captureChatListActionOwner(present::get)!!
                fixture.archiveEntered = CompletableDeferred()
                fixture.releaseArchive = release
                val result =
                    async(start = CoroutineStart.UNDISPATCHED) {
                        fixture.controller.setArchivedForOwner(
                            listOf(ACTION_TARGET, ACTION_SURVIVOR),
                            true,
                            false,
                            owner,
                        )
                    }
                fixture.archiveEntered!!.await()
                present.set(false)
                release.complete(Unit)
                assertEquals(1, result.await())
                assertTrue(fixture.row.get().archived)
                assertFalse("unstarted second row must not commit", fixture.retainedRow.get().archived)
                assertEquals(listOf("archive:true"), fixture.calls.toList())
                assertEquals(
                    ACTION_SURVIVOR,
                    fixture.controller.items
                        .single()
                        .id,
                )
            } finally {
                release.complete(Unit)
                closeFixture(fixture, scope)
            }
        }

    @Test fun admittedMuteAndUnmuteCompleteOnlyForOriginalSession() =
        runBlocking {
            Retirement.entries.forEach { retirement ->
                listOf(true, false).forEach { muted ->
                    val scope = TestScope()
                    val fixture = OffWindowActionFixture(scope, Dispatchers.IO)
                    val release = CountDownLatch(1)
                    try {
                        fixture.row.updateAndGet { it.copy(muted = !muted) }
                        val present = AtomicBoolean(true)
                        val owner = fixture.controller.captureChatListActionOwner(present::get)!!
                        val committed = AtomicInteger()
                        fixture.nativeEntered = CountDownLatch(1)
                        fixture.releaseNative = release
                        fixture.state.setConversationMuted(
                            ACTION_TARGET,
                            muted,
                            onCommitted = { committed.incrementAndGet() },
                            isCurrent = { fixture.controller.chatListActionIsCurrent(owner) },
                            completionIsCurrent = { fixture.controller.chatListActionSessionIsCurrent(owner) },
                        )
                        ShadowLooper.idleMainLooper()
                        assertTrue(
                            "the actual native mute command must start",
                            fixture.nativeEntered!!.await(5, TimeUnit.SECONDS),
                        )
                        when (retirement) {
                            Retirement.PRESENTATION -> present.set(false)
                            Retirement.ACCOUNT -> fixture.replaceAccount()
                            Retirement.RUNTIME -> fixture.replaceRuntimeGeneration()
                            Retirement.BINDING -> fixture.controller.bind(null)
                        }
                        release.countDown()
                        awaitMuteCleanup(fixture)
                        val sameSession = retirement == Retirement.PRESENTATION
                        assertEquals(if (sameSession) 1 else 0, committed.get())
                        assertEquals(sameSession, muteOverrides(fixture).isNotEmpty())
                        if (sameSession) assertEquals(muted, fixture.state.isConversationMuted(ACTION_TARGET))
                        assertEquals(muted, fixture.row.get().muted)
                        assertEquals(listOf("mute:$muted"), fixture.calls.toList())
                    } finally {
                        release.countDown()
                        closeFixture(fixture, scope)
                    }
                }
            }
        }

    @Test fun queuedMuteCannotPassRetiredPresentationAdmissionAndBothPendingCountersClear() {
        val scope = TestScope()
        val fixture = OffWindowActionFixture(scope, Dispatchers.IO)
        val release = CountDownLatch(1)
        try {
            fixture.nativeEntered = CountDownLatch(1)
            fixture.releaseNative = release
            fixture.state.setConversationMuted(ACTION_TARGET, true)
            ShadowLooper.idleMainLooper()
            assertTrue(fixture.nativeEntered!!.await(5, TimeUnit.SECONDS))
            val present = AtomicBoolean(true)
            val owner = fixture.controller.captureChatListActionOwner(present::get)!!
            fixture.state.setConversationMuted(
                ACTION_TARGET,
                false,
                isCurrent = { fixture.controller.chatListActionIsCurrent(owner) },
                completionIsCurrent = { fixture.controller.chatListActionSessionIsCurrent(owner) },
            )
            // Run the AppState launch until the repository waits behind the held first command.
            ShadowLooper.idleMainLooper()
            present.set(false)
            release.countDown()
            awaitMuteCleanup(fixture)
            assertEquals(listOf("mute:true"), fixture.calls.toList())
            assertTrue(fixture.state.isConversationMuted(ACTION_TARGET))
        } finally {
            release.countDown()
            closeFixture(fixture, scope)
        }
    }

    private fun closeFixture(
        fixture: OffWindowActionFixture,
        scope: TestScope,
    ) {
        fixture.state.mutationsScope.cancel()
        val job = fixture.state.mutationsScope.coroutineContext[Job]!!
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        try {
            while (!job.isCompleted && System.nanoTime() < deadline) {
                ShadowLooper.idleMainLooper(20, TimeUnit.MILLISECONDS)
                Thread.yield()
            }
            assertTrue("AppState mutation jobs must drain", job.isCompleted)
        } finally {
            fixture.controller.onCleared()
            scope.cancel()
        }
    }

    private fun awaitMuteCleanup(fixture: OffWindowActionFixture) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (pendingMuteCommands(fixture).isNotEmpty() && System.nanoTime() < deadline) {
            ShadowLooper.idleMainLooper(20, TimeUnit.MILLISECONDS)
            Thread.yield()
        }
        assertTrue("all original-account pending commands must clean up", pendingMuteCommands(fixture).isEmpty())
    }

    private fun pendingMuteCommands(fixture: OffWindowActionFixture): Map<*, *> =
        fixture.state.javaClass
            .getDeclaredField("pendingMuteCommands")
            .apply { isAccessible = true }
            .get(fixture.state) as Map<*, *>

    private fun muteOverrides(fixture: OffWindowActionFixture): Map<*, *> =
        fixture.state.javaClass
            .getDeclaredField("authoritativeMuteOverrides")
            .apply { isAccessible = true }
            .get(fixture.state) as Map<*, *>

    /** A real synchronous native command is held after its admission and before its authoritative return. */
    private fun checkNativeCompletions(retirement: Retirement) =
        runBlocking {
            Command.entries.forEach { command ->
                val scope = TestScope()
                val fixture = OffWindowActionFixture(scope, Dispatchers.IO)
                val release = CountDownLatch(1)
                try {
                    val present = AtomicBoolean(true)
                    val owner = fixture.controller.captureChatListActionOwner(present::get)!!
                    val item = fixture.controller.resolveChatListActionItem(ACTION_TARGET, owner)!!
                    fixture.nativeEntered = CountDownLatch(1)
                    fixture.releaseNative = release
                    val result = async(start = CoroutineStart.UNDISPATCHED) { command.commit(fixture, item, owner) }
                    assertTrue(
                        "the actual native command must start",
                        fixture.nativeEntered!!.await(5, TimeUnit.SECONDS),
                    )
                    when (retirement) {
                        Retirement.PRESENTATION -> present.set(false)
                        Retirement.ACCOUNT -> fixture.replaceAccount()
                        Retirement.RUNTIME -> fixture.replaceRuntimeGeneration()
                        Retirement.BINDING -> fixture.controller.bind(null)
                    }
                    ShadowLooper.idleMainLooper(20, TimeUnit.MILLISECONDS)
                    val before = fixture.controller.forwardTargetsRevision
                    release.countDown()
                    val sameSession = retirement == Retirement.PRESENTATION
                    assertEquals(sameSession, result.await())
                    ShadowLooper.idleMainLooper(20, TimeUnit.MILLISECONDS)
                    assertEquals(
                        "only the original session may refresh",
                        sameSession,
                        fixture.controller.forwardTargetsRevision > before,
                    )
                    assertEquals(listOf(command.expectedCall), fixture.calls.toList())
                    assertFalse(
                        "completion must not grow the retained window",
                        fixture.controller.containsGroup(ACTION_TARGET),
                    )
                } finally {
                    release.countDown()
                    closeFixture(fixture, scope)
                }
            }
        }

    private enum class Retirement { PRESENTATION, ACCOUNT, RUNTIME, BINDING }

    private enum class Command(
        val expectedCall: String,
    ) {
        READ("read:false"),
        UNREAD("read:true"),
        PIN("pin:true"),
        UNPIN("pin:false"),
        ;

        suspend fun commit(
            fixture: OffWindowActionFixture,
            item: ChatListItem,
            owner: ChatListActionOwner,
        ): Boolean =
            when (this) {
                READ -> fixture.controller.markAllRead(item, owner)
                UNREAD -> fixture.controller.markUnread(item, owner)
                PIN -> fixture.controller.setPinned(item, true, owner)
                UNPIN -> fixture.controller.setPinned(item, false, owner)
            }
    }
}
