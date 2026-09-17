package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.ConversationOpenModeFfi
import dev.ipf.marmotkit.ConversationWindowSubscription
import dev.ipf.marmotkit.Marmot
import dev.ipf.marmotkit.MarmotKitException
import dev.ipf.marmotkit.NoPointer
import dev.ipf.marmotkit.TimelineMessagesSubscription
import dev.ipf.marmotkit.TimelinePageFfi
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * A conversation whose MarmotKit window cannot open still gets its messages through the pre-0.10.0
 * timeline subscription. Runs under Robolectric because the refusal is logged through `android.util.Log`.
 */
@RunWith(RobolectricTestRunner::class)
class ConversationTimelineFallbackTest {
    /** A not-ready window falls back to the plain timeline, whose page becomes the seam's snapshot. */
    @Test
    fun notReadyWindowFallsBackToTheTimeline() =
        runBlocking {
            val engine = RefusingEngine.create(refuseWith = MarmotKitException.ConversationWindowNotReady())

            val handle = engine.openTimelineWithFallback("acct", "group", 50u)

            assertTrue(handle is FfiConversationTimelineSubscriptionHandle)
            assertEquals(listOf(50u), engine.timelineLimits)
            assertEquals(1, engine.windowAttempts)
            assertEquals(false, handle.snapshot()?.hasMoreBefore)
            assertNull(handle.latestInstalledWindow())
        }

    /** Every other window error takes the same fallback; the seam never throws for a refused open. */
    @Test
    fun timedOutWindowFallsBackToTheTimeline() =
        runBlocking {
            val engine = RefusingEngine.create(refuseWith = MarmotKitException.ConversationWindowTimedOut())

            val handle = engine.openTimelineWithFallback("acct", "group", 200u)

            assertTrue(handle is FfiConversationTimelineSubscriptionHandle)
            assertEquals(listOf(200u), engine.timelineLimits)
        }
}

/** Engine stub allocated without its native constructor; the window open is scripted to refuse. */
private class RefusingEngine private constructor() : Marmot(NoPointer) {
    lateinit var refusal: MarmotKitException
    lateinit var timelineLimits: MutableList<UInt>
    var windowAttempts = 0

    override suspend fun openConversationWindow(
        accountRef: String,
        groupIdHex: String,
        mode: ConversationOpenModeFfi,
        messageIdHex: String?,
        initialRows: UInt?,
        timeoutMs: UInt,
    ): ConversationWindowSubscription {
        windowAttempts += 1
        throw refusal
    }

    override suspend fun subscribeTimelineMessages(
        accountRef: String,
        groupIdHex: String?,
        limit: UInt?,
    ): TimelineMessagesSubscription {
        timelineLimits += limit ?: 0u
        return EmptyTimeline.create()
    }

    companion object {
        fun create(refuseWith: MarmotKitException): RefusingEngine =
            nativeStub(RefusingEngine::class.java).apply {
                refusal = refuseWith
                timelineLimits = mutableListOf()
            }
    }
}

/** Timeline subscription stub whose snapshot is one empty page. */
private class EmptyTimeline private constructor() : TimelineMessagesSubscription(NoPointer) {
    override fun snapshot(): TimelinePageFfi? = TimelinePageFfi(emptyList(), false, false)

    override suspend fun next(): TimelinePageFfi? = null

    override fun close() = Unit

    companion object {
        fun create(): EmptyTimeline = nativeStub(EmptyTimeline::class.java)
    }
}

/** Allocates a native handle stub without its constructor. */
private fun <T> nativeStub(type: Class<T>): T {
    val unsafeClass = Class.forName("sun.misc.Unsafe")
    val field = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }
    val unsafe = field.get(null)
    @Suppress("UNCHECKED_CAST")
    return unsafeClass.getMethod("allocateInstance", Class::class.java).invoke(unsafe, type) as T
}
