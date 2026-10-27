package dev.ipf.whitenoise.android.state

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Exercises the production presentation lifetime rather than matching controller source text. */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatsControllerListVisibilityTest {
    @Test
    fun returningFlushesFoldedStateSynchronouslyAndCancelsTheQueuedPublication() =
        runTest {
            var backing = 1
            var visible = 0
            var unread = 0
            lateinit var lifetime: ChatListPresentationLifetime
            lifetime =
                ChatListPresentationLifetime(this) {
                    unread = backing
                    if (lifetime.beginVisiblePublication()) visible = backing
                }
            lifetime.setVisible(false)
            backing = 2
            lifetime.schedule()
            advanceUntilIdle()
            assertEquals(2, unread)
            assertEquals(0, visible)
            backing = 3
            lifetime.schedule()
            lifetime.setVisible(true)
            assertEquals(3, visible)
            assertEquals(3, unread)
            backing = 4
            advanceUntilIdle()
            assertEquals(3, visible)
        }

    @Test
    fun returningBeforeTheFirstDebounceStillPublishesImmediately() =
        runTest {
            var publications = 0
            lateinit var lifetime: ChatListPresentationLifetime
            lifetime =
                ChatListPresentationLifetime(this) {
                    if (lifetime.beginVisiblePublication()) publications += 1
                }
            lifetime.setVisible(false)
            lifetime.schedule()
            lifetime.setVisible(true)
            assertEquals(1, publications)
            advanceUntilIdle()
            assertEquals(1, publications)
        }

    @Test
    fun burstPublishesOnceUsingTheLatestBackingState() =
        runTest {
            var backing = 0
            val published = mutableListOf<Int>()
            lateinit var lifetime: ChatListPresentationLifetime
            lifetime =
                ChatListPresentationLifetime(this) {
                    if (lifetime.beginVisiblePublication()) published += backing
                }
            repeat(8) {
                backing += 1
                lifetime.schedule()
            }
            runCurrent()
            advanceTimeBy(15)
            assertTrue(published.isEmpty())
            advanceUntilIdle()
            assertEquals(listOf(8), published)
        }

    @Test
    fun resetRejectsOldQueuedWorkAndKeepsTheHiddenPresentation() =
        runTest {
            var calls = 0
            val lifetime = ChatListPresentationLifetime(this) { calls += 1 }
            lifetime.setVisible(false)
            lifetime.schedule()
            runCurrent()
            lifetime.reset()
            advanceUntilIdle()
            assertEquals(0, calls)
            assertFalse(lifetime.beginVisiblePublication())
            lifetime.setVisible(true)
            assertEquals(1, calls)
        }

    @Test
    fun clearCancelsOnlyPresentationWorkAndCannotBeReactivated() =
        runTest {
            var calls = 0
            var otherWorkFinished = false
            val lifetime = ChatListPresentationLifetime(this) { calls += 1 }
            launch { otherWorkFinished = true }
            lifetime.schedule()
            lifetime.clear()
            lifetime.schedule()
            lifetime.setVisible(false)
            lifetime.setVisible(true)
            advanceUntilIdle()
            assertEquals(0, calls)
            assertFalse(lifetime.beginVisiblePublication())
            assertTrue(otherWorkFinished)
        }
}
