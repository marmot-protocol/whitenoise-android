package dev.ipf.whitenoise.android.state

import android.content.Context
import dev.ipf.whitenoise.android.notifications.BackgroundConnectionPreferences
import dev.ipf.whitenoise.android.notifications.PushTokenStore
import java.util.concurrent.atomic.AtomicInteger

/** Test boundary that records request generations while preserving production persistence semantics. */
internal class RecordingNativePushFallbackPlatform(
    private val context: Context,
    private val persistResults: ArrayDeque<Boolean> = ArrayDeque(),
    private val startResults: ArrayDeque<Boolean> = ArrayDeque(),
    private val clearResults: ArrayDeque<Boolean> = ArrayDeque(),
    private val beforePersist: () -> Unit = {},
    private val beforeClear: () -> Unit = {},
) : NativePushFallbackPlatform {
    val starts = mutableListOf<Long>()
    private val persistCallCount = AtomicInteger()
    private val clearCallCount = AtomicInteger()

    val persistCalls: Int
        get() = persistCallCount.get()

    val clearCalls: Int
        get() = clearCallCount.get()

    override fun persistBackgroundConnectionEnabled(isStillDesired: () -> Boolean): Boolean {
        persistCallCount.incrementAndGet()
        beforePersist()
        val result = persistResults.removeFirstOrNull() ?: true
        return if (result) {
            BackgroundConnectionPreferences.setEnabledDurablyIf(context, true, isStillDesired)
        } else {
            if (!isStillDesired()) return false
            // SharedPreferences may expose the new in-memory value even when
            // its disk commit fails. Reconciliation must still call us again.
            context
                .getSharedPreferences("whitenoise", Context.MODE_PRIVATE)
                .edit()
                .putBoolean("background_connection_enabled", true)
                .apply()
            false
        }
    }

    override fun startBackgroundConnection(requestGeneration: Long): Boolean {
        starts += requestGeneration
        return startResults.removeFirstOrNull() ?: true
    }

    override fun recordPendingRegistrationClear(accountRef: String): Boolean {
        clearCallCount.incrementAndGet()
        beforeClear()
        val result = clearResults.removeFirstOrNull() ?: true
        val recorded = PushTokenStore.create(context).recordPendingClear(accountRef)
        return result && recorded
    }
}
