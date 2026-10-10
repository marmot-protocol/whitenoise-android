package dev.ipf.whitenoise.android.state

import android.os.Looper
import org.robolectric.Shadows.shadowOf
import java.util.concurrent.TimeUnit

internal fun awaitConversationCondition(
    timeoutMs: Long = 5_000,
    condition: () -> Boolean,
) {
    val deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
    while (System.nanoTime() <= deadlineNanos) {
        shadowOf(Looper.getMainLooper()).idle()
        if (condition()) return
        Thread.sleep(10)
    }
    throw AssertionError("Condition not met within ${timeoutMs}ms")
}

internal fun awaitOpenedTimelineSubscriptionsClosed(subscriptions: ScriptedConversationLiveSubscriptions) {
    awaitConversationCondition {
        subscriptions.timelineScripts
            .take(subscriptions.timelineSubscriptionOpenCount)
            .all { it.closeCallCount >= 1 }
    }
}
