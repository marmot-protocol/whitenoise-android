package dev.ipf.whitenoise.android.state

import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaMemoryCacheEvictionTest {
    @OptIn(DelicateCoroutinesApi::class, ExperimentalCoroutinesApi::class)
    @Test
    fun removeMediaMemoryCacheKeys_usesSuppliedDispatcherForBothLrus() {
        val removals = mutableListOf<String>()
        val threads = mutableListOf<String>()

        newSingleThreadContext("media-memory-evict-main").use { dispatcher ->
            runBlocking {
                removeMediaMemoryCacheKeys(
                    cacheKeys = listOf("one", "two"),
                    dispatcher = dispatcher,
                    removePlaintext = { key ->
                        removals += "plain:$key"
                        threads += Thread.currentThread().name
                    },
                    removeThumbnail = { key ->
                        removals += "thumb:$key"
                        threads += Thread.currentThread().name
                    },
                )
            }
        }

        assertEquals(
            listOf("plain:one", "thumb:one", "plain:two", "thumb:two"),
            removals,
        )
        assertTrue(
            "all removals should run on the supplied dispatcher thread, got $threads",
            threads.all { it.startsWith("media-memory-evict-main") },
        )
    }
}
