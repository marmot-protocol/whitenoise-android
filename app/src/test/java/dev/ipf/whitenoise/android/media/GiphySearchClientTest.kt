package dev.ipf.whitenoise.android.media

import dev.ipf.whitenoise.android.core.RemoteGiphyMedia
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.net.URI
import java.net.URLDecoder

class GiphySearchClientTest {
    /** Search inputs stay in their own encoded parameters, and blank or oversized inputs skip the request. */
    @Test
    fun buildsBoundedGiphySearchRequest() {
        val url = URI(requireNotNull(giphySearchUrl("  cat & 🐾  ", "test+key", "en")))
        assertEquals("api.giphy.com", url.host)
        assertEquals("/v1/gifs/search", url.path)
        val query =
            url.rawQuery.split('&').associate {
                it.substringBefore('=') to URLDecoder.decode(it.substringAfter('='), "UTF-8")
            }
        assertEquals(
            mapOf("api_key" to "test+key", "q" to "cat & 🐾", "limit" to "24", "rating" to "pg-13", "lang" to "en"),
            query,
        )
        assertNull(giphySearchUrl("   ", "test", "en"))
        assertNull(giphySearchUrl("x".repeat(51), "test", "en"))
        assertNull(giphySearchUrl("cats", "", "en"))
    }

    /** Oversize and wrong-host renditions are skipped, while the selected result round-trips through chat parsing. */
    @Test
    fun selectsBoundedMediaAndKeepsAttribution() {
        val images =
            JSONObject()
                .put("original", rendition("https://media.giphy.com/media/paws/giphy.gif", size = "6000000"))
                .put("downsized_medium", rendition("https://media.giphy.com.evil.test/media/paws/200.gif"))
                .put("downsized", rendition("https://media.giphy.com/media/paws/200.gif"))

        val item = JSONObject().put("title", "  Happy paws ").put("username", " Test\nCreator ").put("images", images)
        val results = requireNotNull(decodeGiphySearchResults(response(item, item)))
        val result = results.single()

        assertEquals("Happy paws", result.title)
        assertEquals("https://media.giphy.com/media/paws/200.gif\nvia GIPHY · Test Creator", result.media.wireText())
        assertEquals(result.media, RemoteGiphyMedia.parse(result.media.wireText()))
    }

    /** A smaller GIF is both previewed and sent when the original exceeds the preferred download budget. */
    @Test
    fun prefersTheLargestRenditionUnderTwoMebibytes() {
        val bestUrl = "https://media.giphy.com/media/paws/200.gif"
        val smaller = rendition("https://media.giphy.com/media/paws/100.gif", size = "200000").put("width", "100")
        val original = rendition("https://media.giphy.com/media/paws/giphy.gif", size = "3000000").put("width", "480")
        val images =
            JSONObject()
                .put("original", original)
                .put("downsized", rendition(bestUrl, size = "1000000"))
                .put("fixed_width_small", smaller)
        val body = response(JSONObject().put("images", images))
        assertEquals(bestUrl, requireNotNull(decodeGiphySearchResults(body)).single().media.url)
    }

    /** Distinguishes a failed response from a valid response with no admissible GIF. */
    @Test
    fun rejectsMalformedResponsesAndOverBudgetAnimations() {
        assertNull(decodeGiphySearchResults("{\"wrong\":[]}"))
        val expensive =
            rendition("https://media.giphy.com/media/paws/giphy.gif")
                .put("width", "1000")
                .put("height", "1000")
                .put("frames", "100")
        val item = JSONObject().put("images", JSONObject().put("original", expensive))
        assertEquals(emptyList<GiphySearchResult>(), decodeGiphySearchResults(response(item)))
    }

    /** Truncating a creator at a word boundary must not make its own envelope unparseable. */
    @Test
    fun trimmedAttributionAlwaysRoundTrips() {
        val url = "https://media.giphy.com/media/cat/giphy.gif"
        val creator = "a".repeat(79)
        val item =
            JSONObject()
                .put("username", "$creator Studio")
                .put("images", JSONObject().put("original", rendition(url)))
        val result = requireNotNull(decodeGiphySearchResults(response(item))).single()
        assertEquals(RemoteGiphyMedia(url, creator), RemoteGiphyMedia.parse(result.media.wireText()))
    }

    private fun rendition(
        url: String,
        size: String = "1500",
    ): JSONObject =
        JSONObject()
            .put("url", url)
            .put("width", "240")
            .put("height", "120")
            .put("size", size)

    private fun response(vararg items: JSONObject): String =
        JSONObject().put("data", JSONArray(items.toList())).toString()
}
