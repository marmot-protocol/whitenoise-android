package dev.ipf.whitenoise.android.state

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Real blocking reads must outlive the caller's bounded await without multiplying native work. */
class NotificationCandidateReadCoordinatorTest {
    /** A healthy in-flight enrichment delays a different message briefly instead of rejecting its first post. */
    @Test
    fun aDifferentCandidateWaitsForTheSameConversation() =
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val coordinator = NotificationCandidateReadCoordinator(scope, Dispatchers.IO)
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val calls = AtomicInteger()
            val key = NotificationCandidateReadKey("account", "chat", "first", 1, 1)
            try {
                val first =
                    async(start = CoroutineStart.UNDISPATCHED) {
                        coordinator.read(key, 2_000) {
                            calls.incrementAndGet()
                            entered.countDown()
                            check(release.await(10, TimeUnit.SECONDS))
                            NotificationCandidateState.Unread
                        }
                    }
                assertTrue(entered.await(1, TimeUnit.SECONDS))
                val second =
                    async(start = CoroutineStart.UNDISPATCHED) {
                        coordinator.read(key.copy(messageIdHex = "second"), 2_000) {
                            calls.incrementAndGet()
                            NotificationCandidateState.Read
                        }
                    }
                assertFalse("busy admission waits rather than returning Unknown", second.isCompleted)
                delay(200)
                release.countDown()
                assertEquals(NotificationCandidateState.Unread, first.await())
                assertEquals(NotificationCandidateState.Read, second.await())
                assertEquals(2, calls.get())
            } finally {
                release.countDown()
                scope.cancel()
            }
        }

    /** Global saturation wakes when any slot retires, even if the oldest binding remains blocked. */
    @Test
    fun aFifthConversationWaitsForAnyAvailableSlot() =
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val coordinator = NotificationCandidateReadCoordinator(scope, Dispatchers.IO)
            val entered = CountDownLatch(4)
            val releases = List(4) { CountDownLatch(1) }
            try {
                val running =
                    releases.mapIndexed { index, release ->
                        async(start = CoroutineStart.UNDISPATCHED) {
                            val key = NotificationCandidateReadKey("account", "chat$index", "message", 1, 1)
                            coordinator.read(key, 2_000) {
                                entered.countDown()
                                check(release.await(10, TimeUnit.SECONDS))
                                NotificationCandidateState.Unread
                            }
                        }
                    }
                assertTrue(entered.await(1, TimeUnit.SECONDS))
                val fifth =
                    async(start = CoroutineStart.UNDISPATCHED) {
                        val key = NotificationCandidateReadKey("account", "fifth", "message", 1, 1)
                        coordinator.read(key, 2_000) { NotificationCandidateState.Read }
                    }
                assertFalse("a healthy full gate must wait for admission", fifth.isCompleted)
                releases[2].countDown()
                assertEquals(NotificationCandidateState.Read, fifth.await())
                assertEquals("the oldest binding is still held", 1L, releases.first().count)
                releases.forEach { it.countDown() }
                running.forEach { assertEquals(NotificationCandidateState.Unread, it.await()) }
            } finally {
                releases.forEach { it.countDown() }
                scope.cancel()
            }
        }

    /** A later phase must observe current state rather than reuse a completed first-post decision. */
    @Test
    fun aCompletedCandidateIsReadAgain() =
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val coordinator = NotificationCandidateReadCoordinator(scope, Dispatchers.IO)
            val calls = AtomicInteger()
            val key = NotificationCandidateReadKey("account", "chat", "message", 1, 1)
            try {
                assertEquals(
                    NotificationCandidateState.Unread,
                    coordinator.read(key, 1_000) {
                        calls.incrementAndGet()
                        NotificationCandidateState.Unread
                    },
                )
                assertEquals(
                    NotificationCandidateState.Read,
                    coordinator.read(key, 1_000) {
                        calls.incrementAndGet()
                        NotificationCandidateState.Read
                    },
                )
                assertEquals(2, calls.get())
            } finally {
                scope.cancel()
            }
        }

    /** A timed-out retry joins the same binding, while another conversation has independent admission. */
    @Test
    fun aBlockedReadIsBoundedAndDoesNotOccupyAnotherConversation() =
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val coordinator = NotificationCandidateReadCoordinator(scope, Dispatchers.IO)
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val calls = AtomicInteger()
            val key = NotificationCandidateReadKey("account", "chat", "message", 1, 1)
            val read: suspend () -> NotificationCandidateState = {
                calls.incrementAndGet()
                entered.countDown()
                check(release.await(10, TimeUnit.SECONDS))
                NotificationCandidateState.Unread
            }
            try {
                val started = System.nanoTime()
                assertNull(coordinator.read(key, 100, read))
                assertTrue(entered.await(1, TimeUnit.SECONDS))
                assertNull(coordinator.read(key, 100, read))
                assertTrue("two awaits must not wait for the blocked binding", elapsedMillis(started) < 1_500)
                assertEquals(1, calls.get())
                assertEquals(
                    NotificationCandidateState.Unread,
                    coordinator.read(key.copy(groupIdHex = "other"), 1_000) { NotificationCandidateState.Unread },
                )
                assertEquals("the first binding remains blocked", 1L, release.count)
                assertNull(coordinator.read(key.copy(postEpoch = 2), 100) { error("must not join a previous epoch") })
            } finally {
                release.countDown()
                scope.cancel()
            }
        }

    /** Four genuinely blocked conversations saturate the bound instead of creating an unbounded native queue. */
    @Test
    fun abandonedCallsKeepAdmissionUntilActualCompletion() =
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val coordinator = NotificationCandidateReadCoordinator(scope, Dispatchers.IO)
            val release = CountDownLatch(1)
            val entered = CountDownLatch(4)
            try {
                repeat(4) { index ->
                    val key = NotificationCandidateReadKey("account", "chat$index", "message", 1, 1)
                    assertNull(
                        coordinator.read(key, 100) {
                            entered.countDown()
                            check(release.await(10, TimeUnit.SECONDS))
                            NotificationCandidateState.Unread
                        },
                    )
                }
                assertTrue(entered.await(1, TimeUnit.SECONDS))
                val fifth = NotificationCandidateReadKey("account", "fifth", "message", 1, 1)
                assertNull(coordinator.read(fifth, 100) { error("no fifth native read is admitted") })
            } finally {
                release.countDown()
                scope.cancel()
            }
        }

    /** Measures wall time independently of the coroutine scheduler under test. */
    private fun elapsedMillis(startedNanos: Long): Long = (System.nanoTime() - startedNanos) / 1_000_000
}
