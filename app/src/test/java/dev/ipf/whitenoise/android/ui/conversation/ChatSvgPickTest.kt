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
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
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

    @Before
    fun setUp() {
        fixture = MediaDownloadIntegrationFixture()
        Robolectric.setupContentProvider(ScriptedPickProvider::class.java, "dev.ipf.whitenoise.test.svgpicks")
        IsolatedMediaDecoder.installTestTransport(TestMediaDecodeTransport)
    }

    @After
    fun close() {
        files.forEach { file ->
            ScriptedPickProvider.picks.remove(file.name)
            file.delete()
        }
        fixture.close()
        IsolatedMediaDecoder.installTestTransport(TestMediaDecodeTransport)
    }

    @Test
    fun imageAndDocumentPickPreserveValidatedVectorBytesAtEveryQuality() =
        runBlocking {
            val bytes =
                (
                    """<svg xmlns="http://www.w3.org/2000/svg" width="24" height="24"><t""" +
                        """ext x="1" y="12" font-size="8">Detail</text></svg>"""
                ).toByteArray()
            val uri = pick(bytes)
            val reader = ConversationAttachmentReader(fixture.state, context)
            for (quality in MediaQuality.entries) {
                fixture.state.updateMediaQuality(quality)
                val image = withContext(Dispatchers.IO) { reader.readImageAttachment(uri, 1024).attachment }
                assertArrayEquals(bytes, requireNotNull(image).plaintextBytes)
                assertEquals("image/svg+xml", image.mediaType)
                assertEquals("24x24", image.dim)
                val document = reader.readPickedDocuments(listOf(uri)).attachments.single()
                assertArrayEquals(bytes, document.plaintextBytes)
                assertEquals("image/svg+xml", document.mediaType)
                assertNull(document.sourceFile)
            }
        }

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
                    reader.readImageAttachment(oversized, GroupSvgPolicy.MAX_BYTES.toLong())
                }
            assertTrue(bounded.overflowed)
            assertNull(bounded.attachment)
        }

    private fun pick(bytes: ByteArray): Uri {
        val file = File.createTempFile("synthetic-vector-", ".svg", context.cacheDir).apply { writeBytes(bytes) }
        files += file
        ScriptedPickProvider.picks[file.name] = ScriptedPick(file, "image/svg+xml", 1L)
        return Uri.parse("content://dev.ipf.whitenoise.test.svgpicks/${file.name}")
    }
}
