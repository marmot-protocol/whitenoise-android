package dev.ipf.whitenoise.android.share

import android.content.Intent
import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ShareIntentTest {
    @Test
    fun sendText_plain_returnsPayload() {
        val intent =
            Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, "hello share")
            }
        val payload = parseShareIntent(intent)
        assertEquals("hello share", payload?.text)
        assertTrue(payload?.streamUris?.isEmpty() == true)
    }

    @Test
    fun send_blankText_returnsNull() {
        val intent =
            Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, "   ")
            }
        assertNull(parseShareIntent(intent))
    }

    @Test
    fun send_stream_returnsUri() {
        val uri = Uri.parse("content://example/image.jpg")
        val intent =
            Intent(Intent.ACTION_SEND).apply {
                type = "image/jpeg"
                putExtra(Intent.EXTRA_STREAM, uri)
            }
        val payload = parseShareIntent(intent)
        assertEquals(listOf(uri), payload?.streamUris)
    }

    @Test
    fun sendMultiple_streams_returnsDistinctUris() {
        val first = Uri.parse("content://example/one")
        val second = Uri.parse("content://example/two")
        val intent =
            Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = "image/*"
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, arrayListOf(first, second, first))
            }
        val payload = parseShareIntent(intent)
        assertEquals(listOf(first, second), payload?.streamUris)
    }

    @Test
    fun sendMultiple_textPlain_returnsPayload() {
        val intent =
            Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, "shared lines")
            }
        val payload = parseShareIntent(intent)
        assertEquals("shared lines", payload?.text)
        assertTrue(payload?.streamUris?.isEmpty() == true)
    }

    @Test
    fun unsupportedAction_returnsNull() {
        assertNull(parseShareIntent(Intent(Intent.ACTION_VIEW)))
    }

    @Test
    fun malformedNullIntent_returnsNull() {
        assertNull(parseShareIntent(null))
    }
}
