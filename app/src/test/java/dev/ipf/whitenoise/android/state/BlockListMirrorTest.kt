package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.BlockListSnapshotFfi
import dev.ipf.marmotkit.BlockListSubscriptionInterface
import dev.ipf.marmotkit.BlockedUserFfi
import dev.ipf.marmotkit.MarmotKitException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BlockListMirrorTest {
    private val mainDispatcher = StandardTestDispatcher()

    @Before
    fun installMain() {
        Dispatchers.setMain(mainDispatcher)
    }

    @After
    fun resetMainDispatcher() {
        Dispatchers.resetMain()
    }

    /**
     * Waits for a stopped receive job to finish while the test main dispatcher is still installed. The
     * loop's IO steps resume onto Main; a resume that lands after `resetMain()` is an uncaught exception
     * on a worker thread, which fails the next `runTest` anywhere in the JVM.
     */
    private fun Job?.drain() {
        val job = this ?: return
        runBlocking {
            withTimeout(5_000) {
                while (!job.isCompleted) {
                    mainDispatcher.scheduler.advanceUntilIdle()
                    delay(5)
                }
            }
        }
    }

    /** The initial snapshot answers membership checks case-insensitively and newer replacements replace the list. */
    @Test
    fun installsSnapshotThenReplacements() =
        runBlocking {
            val subscription = FakeBlockList(initial = snapshot(1uL, "AA", "bb"))
            val mirror = BlockListMirror()
            mirror.bind("acct") { subscription }
            awaitUntil { mirror.revision == 1uL }

            assertTrue(mirror.isBlocked("aa"))
            assertTrue(mirror.isBlocked("BB"))
            assertFalse(mirror.isBlocked("cc"))

            subscription.emit(snapshot(2uL, "cc"))
            awaitUntil { mirror.revision == 2uL }
            assertFalse(mirror.isBlocked("aa"))
            assertTrue(mirror.isBlocked("cc"))
            mirror.stop().drain()
        }

    /** An older revision never replaces a newer one. */
    @Test
    fun ignoresOlderRevisions() {
        val mirror = BlockListMirror()
        mirror.install(snapshot(5uL, "aa"))
        mirror.install(snapshot(4uL, "zz"))
        assertEquals(listOf("aa"), mirror.users.map { it.publicKey })
    }

    /** Stopping forgets the account and its list; rebinding the same account restarts the receive loop. */
    @Test
    fun stopClearsAndRebindRestarts() =
        runBlocking {
            val first = FakeBlockList(initial = snapshot(1uL, "aa"))
            val mirror = BlockListMirror()
            mirror.bind("acct") { first }
            awaitUntil { mirror.revision == 1uL }
            mirror.stop().drain()
            assertEquals(null, mirror.accountRef)
            assertTrue(mirror.users.isEmpty())

            val second = FakeBlockList(initial = snapshot(7uL, "dd"))
            mirror.bind("acct") { second }
            awaitUntil { mirror.revision == 7uL }
            assertTrue(mirror.isBlocked("dd"))
            mirror.stop().drain()
        }

    private suspend fun awaitUntil(condition: () -> Boolean) {
        withTimeout(5_000) {
            while (!condition()) {
                mainDispatcher.scheduler.advanceUntilIdle()
                delay(5)
            }
        }
    }

    /** A bound mirror with a revision answers from its own rows without an authoritative read. */
    @Test
    fun boundMirrorAnswersWithoutReading() =
        runBlocking {
            val mirror = BlockListMirror()
            // The stream ends right after its snapshot, so this test leaves no receive coroutine parked on
            // the test dispatcher for whichever class the runner schedules next.
            mirror.bind("acct") { FakeBlockList(initial = snapshot(1uL, "aa"), endAfterSnapshot = true) }
            awaitUntil { mirror.revision == 1uL }

            var reads = 0
            val read: suspend () -> Boolean = {
                reads += 1
                true
            }
            assertEquals(true, resolveBlockedState(mirror, "acct", "AA", read))
            assertEquals(false, resolveBlockedState(mirror, "acct", "bb", read))
            assertEquals(0, reads)
            mirror.stop().drain()
        }

    /** Without a bound revision the authoritative read decides. */
    @Test
    fun unboundMirrorUsesTheAuthoritativeRead() =
        runBlocking {
            assertEquals(true, resolveBlockedState(BlockListMirror(), "acct", "aa") { true })
            assertEquals(false, resolveBlockedState(BlockListMirror(), "acct", "aa") { false })
        }

    /**
     * A failed authoritative read stays unknown rather than "not blocked": the profile row keeps its
     * disabled state instead of offering Block for someone who may already be blocked.
     */
    @Test
    fun failedReadStaysUnknown() =
        runBlocking {
            val unavailable =
                resolveBlockedState(BlockListMirror(), "acct", "aa") {
                    throw MarmotKitException.BlockListUnavailable()
                }
            assertNull(unavailable)
        }
}

private fun snapshot(
    revision: ULong,
    vararg publicKeys: String,
) = BlockListSnapshotFfi(revision, publicKeys.map { BlockedUserFfi(it, isPrivate = false, createdAtMs = 1L) })

/** Scripted block-list stream. */
private class FakeBlockList(
    private val initial: BlockListSnapshotFfi,
    private val endAfterSnapshot: Boolean = false,
) : BlockListSubscriptionInterface {
    private val updates = Channel<BlockListSnapshotFfi>(Channel.UNLIMITED)

    override fun snapshot(): BlockListSnapshotFfi = initial

    override suspend fun next(): BlockListSnapshotFfi? {
        if (endAfterSnapshot) return null
        return updates.receiveCatching().getOrNull()
    }

    fun emit(snapshot: BlockListSnapshotFfi) {
        check(updates.trySend(snapshot).isSuccess)
    }
}
