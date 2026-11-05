package dev.ipf.whitenoise.android.share

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import dev.ipf.whitenoise.android.FileProviderStrategyCacheRule
import dev.ipf.whitenoise.android.state.AUDIT_LOG_ARCHIVE_MIME_TYPE
import dev.ipf.whitenoise.android.state.AUDIT_LOG_ARCHIVE_NAME
import dev.ipf.whitenoise.android.state.auditLogShareChooserIntent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ShareIntentTest {
    @get:Rule
    val fileProviderStrategyCacheRule = FileProviderStrategyCacheRule()

    /** The diagnostic export's chooser target parses as one ZIP stream carrying the exact issued URI. */
    @Test
    fun diagnosticExportChooserTargetParsesAsOneZipStream() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val archive =
            java.io.File(context.cacheDir, "audit_logs/session/$AUDIT_LOG_ARCHIVE_NAME").apply {
                parentFile!!.mkdirs()
                writeText("zip")
            }
        val chooser = auditLogShareChooserIntent(context, archive, "Share")
        val send = chooser.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)!!

        val request = parseShareRequest(send)!!

        assertEquals(listOf(send.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)), request.payload.streamUris)
        assertEquals(AUDIT_LOG_ARCHIVE_MIME_TYPE, request.payload.intentMimeType)
        assertNull(request.payload.text)
        archive.parentFile!!.deleteRecursively()
    }

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

    /**
     * Verifies raw EXTRA_TEXT parsing independently of stream contents; production draft merge is tested separately.
     */
    @Test
    fun plainTextPreservesWhitespaceWithoutReadingFileContents() {
        val intent =
            Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, "  original text\n")
            }
        assertEquals("  original text\n", parseShareIntent(intent)?.text)
    }
}
