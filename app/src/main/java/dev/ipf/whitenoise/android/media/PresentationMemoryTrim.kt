package dev.ipf.whitenoise.android.media

import android.content.ComponentCallbacks2
import android.os.Build

/** One-shot retention targets; trimming never changes the normal cache capacity or its owners. */
internal enum class PresentationMemoryTrim {
    WARM,
    EMPTY,
    ;

    /** Keeps half the configured budget for a warm return, or releases all expendable cache references. */
    fun targetBytes(capacity: Long): Long = if (this == WARM) capacity / 2 else 0L
}

/**
 * UI-hidden and background callbacks remain supported on modern Android. Foreground pressure levels
 * stopped arriving on API 34; accept them only on older versions. Intermediate levels follow the
 * preceding supported threshold. No trim starts acquisition, requests GC, or resets an account/decoder.
 */
@Suppress("DEPRECATION") // Older Android still delivers the running-pressure levels.
internal fun presentationMemoryTrim(
    level: Int,
    sdk: Int = Build.VERSION.SDK_INT,
): PresentationMemoryTrim? =
    when {
        level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND -> PresentationMemoryTrim.EMPTY
        level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN -> PresentationMemoryTrim.WARM
        sdk >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> null
        level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL -> PresentationMemoryTrim.EMPTY
        level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE -> PresentationMemoryTrim.WARM
        else -> null
    }
