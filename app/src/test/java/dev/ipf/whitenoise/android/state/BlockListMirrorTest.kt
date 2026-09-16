package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.BlockListSnapshotFfi
import dev.ipf.marmotkit.BlockListSubscriptionInterface
import dev.ipf.marmotkit.BlockedUserFfi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
            mirror.stop()
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
            mirror.stop()
            assertEquals(null, mirror.accountRef)
            assertTrue(mirror.users.isEmpty())

            val second = FakeBlockList(initial = snapshot(7uL, "dd"))
            mirror.bind("acct") { second }
            awaitUntil { mirror.revision == 7uL }
            assertTrue(mirror.isBlocked("dd"))
            mirror.stop()
        }

    private suspend fun awaitUntil(condition: () -> Boolean) {
        withTimeout(5_000) {
            while (!condition()) {
                mainDispatcher.scheduler.advanceUntilIdle()
                delay(5)
            }
        }
    }
}

private fun snapshot(
    revision: ULong,
    vararg publicKeys: String,
) = BlockListSnapshotFfi(revision, publicKeys.map { BlockedUserFfi(it, isPrivate = false, createdAtMs = 1L) })

/** Scripted block-list stream. */
private class FakeBlockList(
    private val initial: BlockListSnapshotFfi,
) : BlockListSubscriptionInterface {
    private val updates = Channel<BlockListSnapshotFfi>(Channel.UNLIMITED)

    override fun snapshot(): BlockListSnapshotFfi = initial

    override suspend fun next(): BlockListSnapshotFfi? = updates.receiveCatching().getOrNull()

    fun emit(snapshot: BlockListSnapshotFfi) {
        check(updates.trySend(snapshot).isSuccess)
    }
}
