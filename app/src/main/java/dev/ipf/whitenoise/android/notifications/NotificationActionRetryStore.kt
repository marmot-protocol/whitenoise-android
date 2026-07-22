package dev.ipf.whitenoise.android.notifications

import android.content.Context
import android.content.SharedPreferences

/** Durable retry accounting that keeps app-lock deferrals separate from operation failures. */
internal class NotificationActionRetryStore(
    private val preferences: SharedPreferences,
    private val nowMillis: () -> Long = { System.currentTimeMillis() },
) {
    /** Records one real operation failure and returns its zero-based attempt index. */
    fun recordOperationFailureAttempt(workKey: String): Int? =
        synchronized(STATE_LOCK) {
            val count = readInt(failureCountKey(workKey), 0)
            if (count < 0 || count == Int.MAX_VALUE) return@synchronized null
            val persisted =
                preferences
                    .edit()
                    .putInt(failureCountKey(workKey), count + 1)
                    .putLong(touchedAtKey(workKey), nowMillis())
                    .commit()
            pruneStaleEntries(activeWorkKey = workKey)
            count.takeIf { persisted }
        }

    fun operationFailureCount(workKey: String): Int =
        synchronized(STATE_LOCK) {
            readInt(failureCountKey(workKey), 0).coerceAtLeast(0)
        }

    /**
     * Starts or continues bounded lock waiting. A persistence failure fails
     * closed so WorkManager cannot fall back to an untracked infinite retry.
     */
    fun shouldDeferForLock(
        workKey: String,
        maximumWaitMillis: Long,
    ): Boolean {
        require(maximumWaitMillis >= 0L)
        return synchronized(STATE_LOCK) {
            val now = nowMillis()
            val storedStartedAt = readLong(lockStartedAtKey(workKey), MISSING_TIMESTAMP)
            val startedAt =
                when {
                    storedStartedAt == MISSING_TIMESTAMP -> now
                    now < storedStartedAt -> now
                    else -> storedStartedAt
                }
            val persisted =
                preferences
                    .edit()
                    .putLong(lockStartedAtKey(workKey), startedAt)
                    .putLong(touchedAtKey(workKey), now)
                    .commit()
            pruneStaleEntries(activeWorkKey = workKey)
            persisted && now - startedAt < maximumWaitMillis
        }
    }

    fun clear(workKey: String) {
        synchronized(STATE_LOCK) {
            preferences
                .edit()
                .remove(failureCountKey(workKey))
                .remove(lockStartedAtKey(workKey))
                .remove(touchedAtKey(workKey))
                .apply()
        }
    }

    /**
     * Drops entries whose work item died out-of-band (WorkManager pruning,
     * force-stop mid-retry): unlike the worker success/terminal paths, those
     * never call [clear], so their keys used to accumulate forever. Live
     * entries re-stamp their touched-at on every run, and runs are never
     * further apart than WorkManager's backoff ceiling — except a request
     * network-gated past the horizon, whose entry is dropped and whose worker
     * then resumes with a fresh budget: extra patience, never a lost action.
     * Entries persisted before staleness tracking have no timestamp; they get
     * stamped (grace) rather than dropped, so a live worker keeps one full
     * window to act.
     */
    private fun pruneStaleEntries(activeWorkKey: String) {
        val now = nowMillis()
        val workKeys =
            preferences.all.keys
                .asSequence()
                .mapNotNull { key ->
                    when {
                        key.startsWith(FAILURE_COUNT_PREFIX) -> key.removePrefix(FAILURE_COUNT_PREFIX)
                        key.startsWith(LOCK_STARTED_AT_PREFIX) -> key.removePrefix(LOCK_STARTED_AT_PREFIX)
                        key.startsWith(TOUCHED_AT_PREFIX) -> key.removePrefix(TOUCHED_AT_PREFIX)
                        else -> null
                    }
                }.toSet()
        val editor = preferences.edit()
        var mutated = false
        workKeys.forEach { workKey ->
            if (workKey == activeWorkKey) return@forEach
            val touchedAt = readLong(touchedAtKey(workKey), MISSING_TIMESTAMP)
            when {
                touchedAt == MISSING_TIMESTAMP || touchedAt > now -> {
                    editor.putLong(touchedAtKey(workKey), now)
                    mutated = true
                }
                now - touchedAt > STALE_ENTRY_MAX_AGE_MILLIS -> {
                    editor
                        .remove(failureCountKey(workKey))
                        .remove(lockStartedAtKey(workKey))
                        .remove(touchedAtKey(workKey))
                    mutated = true
                }
            }
        }
        if (mutated) editor.apply()
    }

    private fun readInt(
        key: String,
        default: Int,
    ): Int =
        try {
            preferences.getInt(key, default)
        } catch (_: ClassCastException) {
            default
        }

    private fun readLong(
        key: String,
        default: Long,
    ): Long =
        try {
            preferences.getLong(key, default)
        } catch (_: ClassCastException) {
            default
        }

    companion object {
        internal const val MAXIMUM_LOCK_WAIT_MILLIS: Long = 24L * 60L * 60L * 1_000L

        // Two full lock-wait windows: no live entry goes that long untouched.
        internal const val STALE_ENTRY_MAX_AGE_MILLIS: Long = 2L * MAXIMUM_LOCK_WAIT_MILLIS

        private const val PREFERENCES_NAME = "whitenoise.notification_action_retries"
        private const val FAILURE_COUNT_PREFIX = "failure_count_"
        private const val LOCK_STARTED_AT_PREFIX = "lock_started_at_"
        private const val TOUCHED_AT_PREFIX = "touched_at_"
        private const val MISSING_TIMESTAMP = Long.MIN_VALUE
        private val STATE_LOCK = Any()

        fun create(context: Context): NotificationActionRetryStore =
            NotificationActionRetryStore(
                context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE),
            )

        private fun failureCountKey(workKey: String): String = FAILURE_COUNT_PREFIX + workKey

        private fun lockStartedAtKey(workKey: String): String = LOCK_STARTED_AT_PREFIX + workKey

        private fun touchedAtKey(workKey: String): String = TOUCHED_AT_PREFIX + workKey
    }
}
