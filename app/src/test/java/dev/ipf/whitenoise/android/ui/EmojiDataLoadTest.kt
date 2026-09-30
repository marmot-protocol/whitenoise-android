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

        assertEquals(listOf("😀", ":marmot:", ":wn:"), second.map { it.emoji })
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

        assertEquals(listOf("😀", ":marmot:", ":wn:"), first.map { it.emoji })
        assertEquals(first, second)
        assertEquals(1, reads)
    }

    @Test
    fun loadCachesSuccessfulEmptyParse() {
        var reads = 0

        val first =
            EmojiData.load {
                reads += 1
                "[]"
            }
        val second =
            EmojiData.load {
                reads += 1
                error("successful empty emoji parse should be served from cache")
            }

        assertEquals(listOf(":marmot:", ":wn:"), first.map { it.emoji })
        assertEquals(first, second)
        assertEquals(1, reads)
    }

    @Test
    fun builtinsAreSearchableByNameShortcodeAndBrandWithoutChangingPayload() {
        val entries = EmojiData.load { sampleEmojiJson }
        for (query in listOf("marmot", ":marmot:")) {
            assertEquals(listOf(":marmot:"), EmojiData.search(entries, query).map { it.emoji })
        }
        for (query in listOf("wn", ":wn:", "white noise", "whitenoise")) {
            assertEquals(listOf(":wn:"), EmojiData.search(entries, query).map { it.emoji })
        }
        assertTrue(EmojiData.search(entries, ":unknown:").isEmpty())
    }

    private fun resetEmojiCache() {
        val cacheField = EmojiData::class.java.getDeclaredField("cache")
        cacheField.isAccessible = true
        cacheField.set(EmojiData, null)
    }

    private companion object {
        const val sampleEmojiJson = """[{"e":"😀","n":"grinning face","g":0,"k":["happy","smile"]}]"""
    }
}
