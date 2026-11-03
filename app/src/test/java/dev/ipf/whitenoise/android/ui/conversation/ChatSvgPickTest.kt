package dev.ipf.whitenoise.android.ui.conversation

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import dev.ipf.whitenoise.android.media.GroupSvgPolicy
import dev.ipf.whitenoise.android.media.sandbox.IsolatedMediaDecoder
import dev.ipf.whitenoise.android.media.sandbox.TestMediaDecodeTransport
import dev.ipf.whitenoise.android.state.MediaDownloadIntegrationFixture
import dev.ipf.whitenoise.android.state.MediaQuality
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ChatSvgPickTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var fixture: MediaDownloadIntegrationFixture
    private val files = mutableListOf<File>()

    /** Installs the scripted document provider and isolated media fixture used by each SVG pick scenario. */
    @Before
    fun setUp() {
        fixture = MediaDownloadIntegrationFixture()
        Robolectric.setupContentProvider(ScriptedPickProvider::class.java, "dev.ipf.whitenoise.test.svgpicks")
        IsolatedMediaDecoder.installTestTransport(TestMediaDecodeTransport)
    }

    /** Deletes task-owned picked files, closes the media fixture, and restores the ordinary test decoder transport. */
    @After
    fun close() {
        files.forEach { file ->
            ScriptedPickProvider.picks.remove(file.name)
            file.delete()
        }
        fixture.close()
        IsolatedMediaDecoder.installTestTransport(TestMediaDecodeTransport)
    }

    /** SVG image picks cannot bypass metadata removal; an established safe raster fallback may still succeed. */
    @Test
    fun imageAndDocumentPicksNeverSendOriginalSvgMetadataAtAnyQuality() =
        runBlocking {
            val bytes =
                (
                    """<svg xmlns="http://www.w3.org/2000/svg" width="24" height="24">""" +
                        "<!--synthetic-private-generator--><title>synthetic-private-title</title>" +
                        "<desc>synthetic-private-description</desc>" +
                        """<rect width="24" height="24" fill="red"/></svg>"""
                ).toByteArray()
            val uri = pick(bytes)
            val reader = ConversationAttachmentReader(fixture.state, context)
            for (quality in MediaQuality.entries) {
                fixture.state.updateMediaQuality(quality)
                val image =
                    withContext(Dispatchers.IO) {
                        reader.readImageAttachment(uri, 1024, pick = null).attachment
                    }
                image?.let { assertMetadataSafeFallback(it, bytes) }
                val document = reader.readPickedDocuments(listOf(uri))
                assertTrue(document.attachments.size <= 1)
                document.attachments.forEach { assertMetadataSafeFallback(it, bytes) }
                if (document.attachments.isEmpty()) {
                    assertTrue(DocumentReadFailure.UNPROCESSABLE_IMAGE in document.failures)
                }
            }
        }

    /** Checks that a safe fallback is re-encoded as JPEG and excludes the original SVG metadata bytes. */
    private fun assertMetadataSafeFallback(
        attachment: dev.ipf.whitenoise.android.state.PendingAttachment,
        original: ByteArray,
    ) {
        assertEquals("image/jpeg", attachment.mediaType)
        assertFalse(attachment.plaintextBytes.contentEquals(original))
        val encoded = attachment.plaintextBytes.toString(Charsets.ISO_8859_1)
        assertFalse(encoded.contains("synthetic-private-"))
        assertNull(attachment.sourceFile)
    }

    /**
     * Uses misleading provider metadata to ensure document validation and actual byte limits still control
     * admission.
     */
    @Test
    fun providerMimeAndLyingSizeCannotBypassDocumentOrByteAdmission() =
        runBlocking {
            val reader = ConversationAttachmentReader(fixture.state, context)
            val invalid = pick("""<svg xmlns="http://www.w3.org/2000/svg"><script/></svg>""".toByteArray())
            assertTrue(reader.readPickedDocuments(listOf(invalid)).attachments.isEmpty())
            val oversized = pick(ByteArray(GroupSvgPolicy.MAX_BYTES + 1) { 32 })
            assertTrue(reader.readPickedDocuments(listOf(oversized)).attachments.isEmpty())
            val bounded =
                withContext(Dispatchers.IO) {
                    reader.readImageAttachment(oversized, GroupSvgPolicy.MAX_BYTES.toLong(), pick = null)
                }
            assertTrue(bounded.overflowed)
            assertNull(bounded.attachment)
        }

    /** Registers actual file bytes behind a provider URI with deliberately misleading metadata for admission tests. */
    private fun pick(bytes: ByteArray): Uri {
        val file = File.createTempFile("synthetic-vector-", ".svg", context.cacheDir).apply { writeBytes(bytes) }
        files += file
        ScriptedPickProvider.picks[file.name] = ScriptedPick(file, "image/svg+xml", 1L)
        return Uri.parse("content://dev.ipf.whitenoise.test.svgpicks/${file.name}")
    }
}
