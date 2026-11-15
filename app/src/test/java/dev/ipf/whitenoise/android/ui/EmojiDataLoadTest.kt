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

    /** A transient asset failure remains retryable and successful parsing adds the canonical built-ins. */
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

        assertEquals(listOf("😀") + EmojiShortcodes.builtins, second.map { it.emoji })
        assertEquals(2, reads)
    }

    /** Successful parsing is reused without opening the asset again. */
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

        assertEquals(listOf("😀") + EmojiShortcodes.builtins, first.map { it.emoji })
        assertEquals(first, second)
        assertEquals(1, reads)
    }

    /** An empty Unicode dataset still supplies the bundled shortcode registry. */
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

        assertEquals(EmojiShortcodes.builtins, first.map { it.emoji })
        assertEquals(first, second)
        assertEquals(1, reads)
    }

    /** Brand, shortcode and localized-name search preserve the literal built-in payload. */
    @Test
    fun builtinsAreSearchableByNameShortcodeAndBrandWithoutChangingPayload() {
        val entries = EmojiData.load { sampleEmojiJson }
        assertEquals(listOf(":marmot:"), EmojiData.search(entries, ":marmot:").map { it.emoji })
        assertEquals(
            EmojiShortcodes.builtins.filter { it != ":wn:" },
            EmojiData.search(entries, "marmot").map { it.emoji },
        )
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
