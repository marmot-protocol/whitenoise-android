package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.ChatListAnchorOutcomeFfi
import dev.ipf.marmotkit.ChatListPageDirectionFfi
import dev.ipf.marmotkit.ChatListViewFfi
import dev.ipf.marmotkit.ChatListWindowSnapshotFfi
import dev.ipf.marmotkit.MarmotKitException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.atomic.AtomicInteger

/** Exercises the real window command/receive paths with handles that reject use after destruction. */
@RunWith(RobolectricTestRunner::class)
class ChatListWindowLifetimeTest {
    @Test
    fun everyCommandAfterRetirementIsANoopAndCloseIsIdempotent() =
        runBlocking {
            val fixture = lifetimeWindows()
            fixture.windows.close()
            withTimeout(5_000) { fixture.windows.awaitReleased() }
            LifetimeCommand.entries.forEach { command -> assertNull(command.run(fixture.windows)) }
            List(16) { async(kotlinx.coroutines.Dispatchers.Default) { fixture.windows.close() } }.awaitAll()
            assertEquals(0, fixture.chats.commandCalls.get())
            fixture.assertReleasedOnce()
        }

    @Test
    fun queuedCommandsCannotEnterRetiredHandles() =
        runBlocking {
            LifetimeCommand.entries.forEach { command ->
                val fixture = lifetimeWindows()
                val entered = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                fixture.chats.beforeCommand = {
                    entered.complete(Unit)
                    release.await()
                }
                val active = async { fixture.windows.setVisibleAnchor(ChatListViewFfi.CHATS, "old") }
                withTimeout(5_000) { entered.await() }
                // UNDISPATCHED reaches the held command mutex before returning to the test.
                val queued = async(start = CoroutineStart.UNDISPATCHED) { command.run(fixture.windows) }
                fixture.windows.close()
                assertTrue(fixture.windows.closed)
                assertFalse(fixture.chats.closed)
                release.complete(Unit)
                assertNull(withTimeout(5_000) { active.await() })
                assertNull(withTimeout(5_000) { queued.await() })
                withTimeout(5_000) { fixture.windows.awaitReleased() }
                assertEquals(1, fixture.chats.commandCalls.get())
                assertEquals(0L, fixture.windows.frame().revision)
                fixture.assertReleasedOnce()
            }
        }

    @Test
    fun everyAdmittedCommandRetainsItsHandleAndDropsItsLateResult() =
        runBlocking {
            LifetimeCommand.entries.forEach { command ->
                val fixture = lifetimeWindows()
                val entered = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                fixture.chats.beforeCommand = {
                    entered.complete(Unit)
                    release.await()
                }
                val initial = fixture.windows.frame()
                val pending = async { command.run(fixture.windows) }
                withTimeout(5_000) { entered.await() }
                fixture.windows.close()
                assertFalse(fixture.chats.closed)
                assertFalse(fixture.windows.publishIfCurrent(initial) { error("retired publication") })
                release.complete(Unit)
                assertNull(withTimeout(5_000) { pending.await() })
                withTimeout(5_000) { fixture.windows.awaitReleased() }
                assertEquals(initial, fixture.windows.frame())
                fixture.assertReleasedOnce()
            }
        }

    @Test
    fun cancellationReleasesTheLastBorrowWithoutCancellingCommandCallersOnClose() =
        runBlocking {
            val fixture = lifetimeWindows()
            val entered = CompletableDeferred<Unit>()
            fixture.chats.beforeCommand = {
                entered.complete(Unit)
                CompletableDeferred<Unit>().await()
            }
            val pending = async { fixture.windows.setVisibleAnchor(ChatListViewFfi.CHATS, "old") }
            withTimeout(5_000) { entered.await() }
            fixture.windows.close()
            assertTrue(pending.isActive)
            assertFalse(fixture.chats.closed)
            pending.cancel()
            withTimeout(5_000) { pending.join() }
            assertTrue(pending.isCancelled)
            withTimeout(5_000) { fixture.windows.awaitReleased() }
            fixture.assertReleasedOnce()
        }

    @Test
    fun unexpectedIllegalStateFailureIsPreservedAndStillReleasesTheBorrow() =
        runBlocking {
            supervisorScope {
                val fixture = lifetimeWindows()
                val entered = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                val failure = IllegalStateException("unrelated failure")
                fixture.chats.beforeCommand = {
                    entered.complete(Unit)
                    release.await()
                    throw failure
                }
                val pending = async { fixture.windows.returnToTop(ChatListViewFfi.CHATS) }
                withTimeout(5_000) { entered.await() }
                fixture.windows.close()
                release.complete(Unit)
                val observed = runCatching { withTimeout(5_000) { pending.await() } }.exceptionOrNull()
                assertTrue(observed is IllegalStateException)
                assertEquals(failure.message, observed?.message)
                withTimeout(5_000) { fixture.windows.awaitReleased() }
                fixture.assertReleasedOnce()
            }
        }

    @Test
    fun nativeWindowClosedIsIgnoredOnlyAfterThisSetRetires() =
        runBlocking {
            supervisorScope {
                val fixture = lifetimeWindows()
                val ended = MarmotKitException.ChatWindowClosed()
                fixture.chats.beforeCommand = { throw ended }
                assertTrue(
                    runCatching { fixture.windows.returnToTop(ChatListViewFfi.CHATS) }
                        .exceptionOrNull() is MarmotKitException.ChatWindowClosed,
                )
                val entered = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                fixture.chats.beforeCommand = {
                    entered.complete(Unit)
                    release.await()
                    throw ended
                }
                val pending = async { fixture.windows.returnToTop(ChatListViewFfi.CHATS) }
                withTimeout(5_000) { entered.await() }
                fixture.windows.close()
                release.complete(Unit)
                assertNull(withTimeout(5_000) { pending.await() })
                withTimeout(5_000) { fixture.windows.awaitReleased() }
                fixture.assertReleasedOnce()
            }
        }

    @Test
    fun closeCancelsPendingReceivesWithoutRelyingOnNativeDestructionToWakeThem() =
        runBlocking {
            val fixture = lifetimeWindows()
            val receiver = launch { fixture.windows.receive { _, _ -> error("unexpected replacement") } }
            fixture.handles.values.forEach { handle -> withTimeout(5_000) { handle.nextStarted.await() } }
            fixture.windows.close()
            withTimeout(5_000) { receiver.join() }
            withTimeout(5_000) { fixture.windows.awaitReleased() }
            fixture.handles.values.forEach { handle -> assertTrue(handle.nextCancelled.isCompleted) }
            fixture.assertReleasedOnce()
            fixture.windows.receive { _, _ -> error("retired replacement") }
            fixture.handles.values.forEach { handle -> assertEquals(1, handle.nextCalls.get()) }
        }

    @Test
    fun receiverRegistrationRacingCloseDoesNotLeaveAWorkerOrHandleAlive() =
        runBlocking {
            repeat(30) {
                val fixture = lifetimeWindows()
                val receiver =
                    launch(kotlinx.coroutines.Dispatchers.Default) {
                        fixture.windows.receive { _, _ -> error("unexpected replacement") }
                    }
                fixture.windows.close()
                withTimeout(5_000) { receiver.join() }
                withTimeout(5_000) { fixture.windows.awaitReleased() }
                fixture.assertReleasedOnce()
            }
        }

    @Test
    fun receiverCancelledBeforeStartCannotBeRegisteredOrDelayRelease() =
        runBlocking {
            val handles = CHAT_LIST_WINDOW_VIEWS.associateWith { view -> LifetimeTestWindow(view, "old") }
            val lifetime = ChatListWindowLifetime(handles)
            val neverStarted = launch(start = CoroutineStart.LAZY) { error("cancelled worker started") }
            neverStarted.cancel()
            assertFalse(lifetime.registerReceiver(neverStarted))
            lifetime.close()
            withTimeout(5_000) { lifetime.awaitReleased() }
            handles.values.forEach { handle -> assertEquals(1, handle.closeCalls.get()) }
        }

    @Test
    fun closeInsideReplacementCallbackDoesNotCancelItsParent() =
        runBlocking {
            val fixture = lifetimeWindows()
            val receiver =
                launch {
                    fixture.windows.receive { _, _ -> fixture.windows.close() }
                }
            withTimeout(5_000) { fixture.chats.nextStarted.await() }
            fixture.chats.emit("fresh")
            withTimeout(5_000) { receiver.join() }
            assertFalse(receiver.isCancelled)
            withTimeout(5_000) { fixture.windows.awaitReleased() }
            fixture.assertReleasedOnce()
        }

    @Test
    fun oneHandleCloseFailureDoesNotSkipOtherHandlesOrRepeatDestruction() =
        runBlocking {
            val fixture = lifetimeWindows()
            fixture.chats.failClose = true
            fixture.windows.close()
            withTimeout(5_000) { fixture.windows.awaitReleased() }
            fixture.windows.close()
            fixture.assertReleasedOnce()
        }
}

private enum class LifetimeCommand {
    ANCHOR,
    FORWARD,
    BACKWARD,
    TOP,
    ;

    suspend fun run(windows: ChatListWindowSet): ChatListWindowSnapshotFfi? =
        when (this) {
            ANCHOR -> windows.setVisibleAnchor(ChatListViewFfi.CHATS, "old")
            FORWARD -> windows.pageForward(ChatListViewFfi.CHATS)
            BACKWARD -> windows.pageBackward(ChatListViewFfi.CHATS)
            TOP -> windows.returnToTop(ChatListViewFfi.CHATS)
        }
}

internal data class LifetimeWindows(
    val windows: ChatListWindowSet,
    val handles: Map<ChatListViewFfi, LifetimeTestWindow>,
) {
    val chats: LifetimeTestWindow get() = handles.getValue(ChatListViewFfi.CHATS)

    fun assertReleasedOnce() {
        handles.values.forEach { handle -> assertEquals(1, handle.closeCalls.get()) }
    }
}

internal suspend fun lifetimeWindows(row: String = "old"): LifetimeWindows {
    val handles = CHAT_LIST_WINDOW_VIEWS.associateWith { view -> LifetimeTestWindow(view, row) }
    return LifetimeWindows(ChatListWindowSet.open("acct") { _, view -> handles.getValue(view) }, handles)
}

/** Native-like lifetime: destroy never wakes next(), and any later call fails instead of silently succeeding. */
internal class LifetimeTestWindow(
    private val view: ChatListViewFfi,
    private val row: String,
) : ChatListWindowHandle {
    private val updates = Channel<ChatListWindowSnapshotFfi>(Channel.UNLIMITED)
    val closeCalls = AtomicInteger()
    val commandCalls = AtomicInteger()
    val nextCalls = AtomicInteger()
    val nextStarted = CompletableDeferred<Unit>()
    val nextCancelled = CompletableDeferred<Unit>()
    var beforeCommand: suspend () -> Unit = {}
    var failClose = false
    val closed: Boolean get() = closeCalls.get() != 0

    override fun snapshot(): ChatListWindowSnapshotFfi = frame(0uL, row)

    override suspend fun next(): ChatListWindowSnapshotFfi? {
        check(!closed) { "native wrapper destroyed before next" }
        nextCalls.incrementAndGet()
        nextStarted.complete(Unit)
        return try {
            updates.receive()
        } catch (cancel: kotlinx.coroutines.CancellationException) {
            nextCancelled.complete(Unit)
            throw cancel
        }
    }

    override suspend fun page(
        sequence: ULong,
        direction: ChatListPageDirectionFfi,
        count: UInt,
    ): ChatListWindowSnapshotFfi = command(sequence)

    override suspend fun setVisibleAnchor(
        sequence: ULong,
        groupIdHex: String,
    ): ChatListWindowSnapshotFfi = command(sequence)

    override suspend fun returnToTop(sequence: ULong): ChatListWindowSnapshotFfi = command(sequence)

    private suspend fun command(sequence: ULong): ChatListWindowSnapshotFfi {
        check(!closed) { "native wrapper destroyed before command" }
        commandCalls.incrementAndGet()
        beforeCommand()
        check(!closed) { "native wrapper destroyed during command" }
        return frame(sequence + 1uL, "$row-command")
    }

    override fun close() {
        closeCalls.incrementAndGet()
        if (failClose) error("scripted close failure")
    }

    fun emit(row: String) {
        check(updates.trySend(frame(1uL, row)).isSuccess)
    }

    private fun frame(
        sequence: ULong,
        row: String,
    ) = ChatListWindowSnapshotFfi(
        subscriptionGeneration = "lifetime-test",
        sequence = sequence,
        view = view,
        rows = if (view == ChatListViewFfi.CHATS) listOf(presentedRow(row)) else emptyList(),
        hasMoreBefore = true,
        hasMoreAfter = true,
        anchor = ChatListAnchorOutcomeFfi.Top,
    )
}
