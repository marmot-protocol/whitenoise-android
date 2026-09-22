package dev.ipf.whitenoise.android

import androidx.core.content.FileProvider
import org.junit.rules.ExternalResource

/**
 * Isolates Robolectric tests from AndroidX FileProvider's process-wide path-strategy cache.
 *
 * Robolectric assigns a fresh data directory to each test sandbox, while FileProvider keys its
 * static cache only by authority. Without this reset, a later test can reuse an earlier sandbox's
 * cache root and reject a valid file from the current application context.
 */
class FileProviderStrategyCacheRule : ExternalResource() {
    /** Removes a path strategy inherited from an earlier Robolectric sandbox. */
    override fun before() = clearFileProviderStrategyCache()

    /** Prevents this test's sandbox-specific roots from leaking into the next test. */
    override fun after() = clearFileProviderStrategyCache()

    /** Clears the same synchronized map AndroidX consults when resolving provider roots. */
    private fun clearFileProviderStrategyCache() {
        val cacheField = FileProvider::class.java.getDeclaredField("sCache").apply { isAccessible = true }

        @Suppress("UNCHECKED_CAST")
        val cache = cacheField.get(null) as MutableMap<String, *>
        synchronized(cache) { cache.clear() }
    }
}
