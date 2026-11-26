package dev.ipf.whitenoise.android.state

import android.content.Context
import dev.ipf.whitenoise.android.notifications.BackgroundConnectionPreferences
import dev.ipf.whitenoise.android.notifications.PushTokenStore
import kotlinx.coroutines.CompletableDeferred
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/** Test boundary that records request generations while preserving production persistence semantics. */
internal class RecordingNativePushFallbackPlatform(
    private val context: Context,
    private val persistResults: ArrayDeque<Boolean> = ArrayDeque(),
    private val startResults: ArrayDeque<Boolean> = ArrayDeque(),
    private val clearResults: ArrayDeque<Boolean> = ArrayDeque(),
    private val stopResults: ArrayDeque<Boolean> = ArrayDeque(),
    private val beforePersist: () -> Unit = {},
    private val beforeClear: () -> Unit = {},
) : NativePushFallbackPlatform {
    val starts = CopyOnWriteArrayList<Long>()
    private val firstStart = CompletableDeferred<Unit>()
    val stops = AtomicInteger()

    /** Every Firebase auto-init write, in call order. */
    val autoInitWrites = CopyOnWriteArrayList<Boolean>()
    private val persistCallCount = AtomicInteger()
    private val clearCallCount = AtomicInteger()

    val persistCalls: Int
        get() = persistCallCount.get()

    val clearCalls: Int
        get() = clearCallCount.get()

    override fun persistBackgroundConnectionEnabled(
        enabled: Boolean,
        isStillDesired: () -> Boolean,
    ): Boolean {
        persistCallCount.incrementAndGet()
        beforePersist()
        val result = persistResults.removeFirstOrNull() ?: true
        return if (result) {
            BackgroundConnectionPreferences.setEnabledDurablyIf(context, enabled, isStillDesired)
        } else {
            if (!isStillDesired()) return false
            // SharedPreferences may expose the new in-memory value even when
            // its disk commit fails. Reconciliation must still call us again.
            context
                .getSharedPreferences("whitenoise", Context.MODE_PRIVATE)
                .edit()
                .putBoolean("background_connection_enabled", enabled)
                .apply()
            false
        }
    }

    /** Waits for a service start request from the production reconciliation coroutine. */
    suspend fun awaitFirstStart() = firstStart.await()

    override fun startBackgroundConnection(requestGeneration: Long): Boolean {
        val accepted = startResults.removeFirstOrNull() ?: true
        starts += requestGeneration
        firstStart.complete(Unit)
        return accepted
    }

    override fun stopBackgroundConnection(): Boolean {
        stops.incrementAndGet()
        return stopResults.removeFirstOrNull() ?: true
    }

    override fun recordPendingRegistrationClear(accountRef: String): Boolean {
        clearCallCount.incrementAndGet()
        beforeClear()
        val result = clearResults.removeFirstOrNull() ?: true
        val recorded = PushTokenStore.create(context).recordPendingClear(accountRef)
        return result && recorded
    }

    /** When set, every auto-init write fails as an SDK failure would, without being recorded. */
    @Volatile
    var failAutoInitWrites = false

    /** Records the write instead of touching the Firebase SDK, or fails while [failAutoInitWrites] is set. */
    override fun setFcmAutoInitEnabled(enabled: Boolean) {
        check(!failAutoInitWrites) { "auto-init write failed" }
        autoInitWrites += enabled
    }
}
