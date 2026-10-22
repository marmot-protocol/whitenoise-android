package dev.ipf.whitenoise.android.ui.conversation.nostr

import dev.ipf.marmotkit.MarmotKitException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/** Event-card author refreshes are throttled per author, and only a successful refresh uses up the interval. */
class PublicEventAuthorRefreshThrottleTest {
    private var now = 0L
    private val throttle = PublicEventAuthorRefreshThrottle(intervalNanos = INTERVAL, nanoTime = { now })

    /** A successful refresh holds the author's slot for the interval, then frees it. */
    @Test
    fun successfulRefreshThrottlesTheAuthorUntilTheIntervalPasses() =
        runTest {
            var refreshes = 0
            assertEquals(PublicEventAuthorRefreshThrottle.Refreshed("fresh"), throttle.refreshing(AUTHOR) { "fresh" })
            assertNull(throttle.refreshing(AUTHOR.uppercase()) { refreshes += 1 })
            now += INTERVAL
            assertTrue(throttle.refreshing(AUTHOR) { refreshes += 1 } != null)
            assertEquals(1, refreshes)
        }

    /** A failed refresh, such as one made offline, releases the slot so the next card retries immediately. */
    @Test
    fun failedRefreshDoesNotUseUpTheInterval() =
        runTest {
            val failure = runCatching { throttle.refreshing(AUTHOR) { throw IOException("offline") } }
            assertTrue(failure.exceptionOrNull() is IOException)

            assertEquals(PublicEventAuthorRefreshThrottle.Refreshed("online"), throttle.refreshing(AUTHOR) { "online" })
        }

    /**
     * Offline, MDK's `refreshProfile` fails with a MarmotKitException because no relay connects, so the slot is
     * released and the next card for the author retries once a relay is reachable.
     */
    @Test
    fun nativeRefreshFailureWithNoRelayReachableReleasesTheSlot() =
        runTest {
            val offline = MarmotKitException.Runtime("connect relays failed")
            val failure = runCatching { throttle.refreshing(AUTHOR) { throw offline } }
            assertSame(offline, failure.exceptionOrNull())

            assertEquals(PublicEventAuthorRefreshThrottle.Refreshed("online"), throttle.refreshing(AUTHOR) { "online" })
        }

    /** A refresh that finds no metadata still counts as made, unlike a throttled call. */
    @Test
    fun aRefreshWithNoMetadataIsDistinctFromAThrottledCall() =
        runTest {
            assertEquals(PublicEventAuthorRefreshThrottle.Refreshed(null), throttle.refreshing(AUTHOR) { null })
            assertNull(throttle.refreshing(AUTHOR) { "again" })
        }

    private companion object {
        const val AUTHOR = "abc123"
        const val INTERVAL = 1_000L
    }
}
