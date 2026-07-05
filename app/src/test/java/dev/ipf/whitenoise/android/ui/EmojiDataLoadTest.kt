package dev.ipf.whitenoise.android.ui

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class EmojiDataLoadTest {
    @Before
    fun setUp() {
        resetEmojiCache()
    }

    @After
    fun tearDown() {
        resetEmojiCache()
    }

    @Test
    fun loadRetriesAfterTransientReadFailure() {
        var reads = 0

        val first =
            EmojiData.load {
                reads += 1
                if (reads == 1) error("transient emoji asset read failure")
                sampleEmojiJson
            }

        assertTrue(first.isEmpty())

        val second =
            EmojiData.load {
                reads += 1
                sampleEmojiJson
            }

        assertEquals(listOf("😀"), second.map { it.emoji })
        assertEquals(2, reads)
    }

    @Test
    fun loadCachesSuccessfulParse() {
        var reads = 0

        val first =
            EmojiData.load {
                reads += 1
                sampleEmojiJson
            }
        val second =
            EmojiData.load {
                reads += 1
                error("successful emoji parse should be served from cache")
            }

        assertEquals(listOf("😀"), first.map { it.emoji })
        assertEquals(first, second)
        assertEquals(1, reads)
    }

    private fun resetEmojiCache() {
        val cacheField = EmojiData::class.java.getDeclaredField("cache")
        cacheField.isAccessible = true
        runCatching { cacheField.set(null, null) }
            .getOrElse { cacheField.set(EmojiData, null) }
    }

    private companion object {
        const val sampleEmojiJson = """[{"e":"😀","n":"grinning face","g":0,"k":["happy","smile"]}]"""
    }
}
