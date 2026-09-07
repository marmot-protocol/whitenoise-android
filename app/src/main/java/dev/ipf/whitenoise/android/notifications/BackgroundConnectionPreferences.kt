package dev.ipf.whitenoise.android.notifications

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import java.util.concurrent.atomic.AtomicReference

private data class BackgroundConnectionWriteIntent(
    val generation: Long,
    val enabled: Boolean,
)

object BackgroundConnectionPreferences {
    private const val PREFERENCES_NAME = "whitenoise"
    private const val KEY_ENABLED = "background_connection_enabled"
    private val writeIntentLock = Any()
    private val latestWrite = AtomicReference(BackgroundConnectionWriteIntent(0L, true))

    fun isEnabled(context: Context): Boolean = preferences(context).getBoolean(KEY_ENABLED, true)

    fun setEnabled(
        context: Context,
        enabled: Boolean,
    ) {
        val target = preferences(context)
        synchronized(writeIntentLock) {
            recordWriteIntent(enabled)
            target.edit().putBoolean(KEY_ENABLED, enabled).apply()
        }
    }

    /**
     * Persists a capability fallback before native delivery is disabled.
     *
     * This deliberately commits even when the in-memory value already equals
     * [enabled]. A previous failed commit can still update SharedPreferences'
     * process cache, so treating that cache hit as durable could lose the only
     * restartable delivery path on process death.
     */
    @SuppressLint("ApplySharedPref")
    fun setEnabledDurably(
        context: Context,
        enabled: Boolean,
    ): Boolean {
        val target = preferences(context)
        synchronized(writeIntentLock) { recordWriteIntent(enabled) }
        return target.edit().putBoolean(KEY_ENABLED, enabled).commit()
    }

    /** Commits only while current, then durably restores any explicit write that superseded it. */
    @SuppressLint("ApplySharedPref")
    internal fun setEnabledDurablyIf(
        context: Context,
        enabled: Boolean,
        isStillDesired: () -> Boolean,
    ): Boolean {
        val target = preferences(context)
        val writeBeforeRead = latestWrite.get()
        val loadedValue = target.getBoolean(KEY_ENABLED, true)
        val (priorValue, priorWrite) =
            synchronized(writeIntentLock) {
                val currentWrite = latestWrite.get()
                val currentValue =
                    if (currentWrite == writeBeforeRead) {
                        loadedValue
                    } else {
                        target.getBoolean(KEY_ENABLED, true)
                    }
                currentValue to currentWrite
            }
        return if (!isStillDesired()) {
            false
        } else {
            val committed = target.edit().putBoolean(KEY_ENABLED, enabled).commit()
            if (isStillDesired()) {
                committed
            } else {
                check(restoreLatestWrite(target, priorValue, priorWrite)) {
                    "Could not restore a superseding background-connection preference"
                }
                false
            }
        }
    }

    /** Records explicit Main-owned preference intent without waiting on disk I/O. */
    private fun recordWriteIntent(enabled: Boolean) {
        latestWrite.updateAndGet { previous ->
            BackgroundConnectionWriteIntent(
                generation = if (previous.generation == Long.MAX_VALUE) 1L else previous.generation + 1L,
                enabled = enabled,
            )
        }
    }

    /** Replays the newest explicit value until no newer apply raced the compensating commit. */
    @SuppressLint("ApplySharedPref")
    private fun restoreLatestWrite(
        target: SharedPreferences,
        priorValue: Boolean,
        priorWrite: BackgroundConnectionWriteIntent,
    ): Boolean {
        var observedWrite = latestWrite.get()
        var value = if (observedWrite.generation == priorWrite.generation) priorValue else observedWrite.enabled
        while (true) {
            if (!target.edit().putBoolean(KEY_ENABLED, value).commit()) return false
            val currentWrite = latestWrite.get()
            if (currentWrite == observedWrite) return true
            observedWrite = currentWrite
            value = currentWrite.enabled
        }
    }

    private fun preferences(context: Context) = context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
}
