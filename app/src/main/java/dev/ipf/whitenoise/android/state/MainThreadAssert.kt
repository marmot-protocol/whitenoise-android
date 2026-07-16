package dev.ipf.whitenoise.android.state

import android.os.Looper
import dev.ipf.whitenoise.android.BuildConfig

/**
 * Tripwire for mutable Compose / LRU state that must only be touched on Main.
 * Release call sites inline [BuildConfig.DEBUG] as false and return before
 * touching the Looper or evaluating the optional [context].
 */
internal inline fun assertMainThread(
    checkingEnabled: Boolean = BuildConfig.DEBUG,
    context: () -> String = { "" },
) {
    if (!checkingEnabled) return
    val currentThread = Thread.currentThread()
    if (Looper.getMainLooper().thread !== currentThread) {
        val detail = context().takeIf { it.isNotEmpty() }?.let { ": $it" } ?: ""
        throw IllegalStateException("Expected main thread but was ${currentThread.name}$detail")
    }
}
