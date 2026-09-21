package dev.ipf.whitenoise.android.share

import android.content.ComponentName
import android.content.Intent
import android.provider.OpenableColumns
import android.view.KeyEvent
import androidx.core.content.FileProvider
import androidx.core.content.IntentCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.ipf.marmotkit.EncryptedMediaVersionFfi
import dev.ipf.marmotkit.MediaAttachmentReferenceFfi
import dev.ipf.marmotkit.MediaLocatorFfi
import dev.ipf.whitenoise.android.media.MediaCacheDirs
import dev.ipf.whitenoise.android.state.PendingAttachment
import dev.ipf.whitenoise.android.ui.conversation.media.StagedMessageShareStreams
import dev.ipf.whitenoise.android.ui.conversation.media.messageShareAttachmentSources
import dev.ipf.whitenoise.android.ui.conversation.media.stageMessageShareStreams
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class OutboundShareDeviceContractTest {
    /** An external receiver reads and decodes only the generated stream explicitly granted to it. */
    @Test
    fun generatedQrCardIsReadableButItsCacheNeighborIsNot() =
        runBlocking {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val context = instrumentation.targetContext
            val externalContext = instrumentation.context
            val payload = "marmot://profile/npub1${"q".repeat(58)}?from=qr"
            val directory = File(context.cacheDir, MediaCacheDirs.SHARED).apply { mkdirs() }
            val neighbor = File(directory, "neighbor-account-secret.txt").apply { writeText("not shared") }
            val staged =
                QrShareCardRenderer.stage(
                    context,
                    QrShareCardSpec(
                        headline = "On White Noise? Message me here.",
                        qrPayload = payload,
                        displayName = "Ada",
                    ),
                )
            val neighborUri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", neighbor)
            val results = externalContext.getSharedPreferences(OutboundShareTestTargetActivity.RESULTS, 0)
            results.edit().clear().commit()
            try {
                val share =
                    outboundShareIntent("https://example.test/profile", listOf(staged.stream))
                        .setComponent(ComponentName(externalContext, OutboundShareTestTargetActivity::class.java))
                        .putExtra(OutboundShareTestTargetActivity.EXTRA_NEIGHBOR_URI, neighborUri.toString())
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

                context.startActivity(share)
                instrumentation.waitForIdleSync()

                assertTrue(results.getBoolean(OutboundShareTestTargetActivity.KEY_COMPLETE, false))
                assertEquals(payload, results.getString(OutboundShareTestTargetActivity.KEY_DECODED_QR, null))
                assertFalse(results.getBoolean(OutboundShareTestTargetActivity.KEY_NEIGHBOR_READABLE, true))
            } finally {
                staged.file.delete()
                neighbor.delete()
                results.edit().clear().commit()
            }
        }

    /** A receiving app reads each staged attachment by its own sanitized name, never by the cache file's. */
    @Test
    fun stagedStreamsExposeTheAttachmentsOwnDisplayNames() =
        runBlocking {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val directory = File(context.cacheDir, MediaCacheDirs.SHARED).apply { mkdirs() }
            val neighbor = File(directory, "neighbor-secret.txt").apply { writeBytes("not shared".encodeToByteArray()) }
            val confirmed =
                messageShareAttachmentSources(
                    references =
                        listOf(
                            reference("story.pdf", "application/pdf"),
                            reference("../story.pdf", "application/pdf"),
                        ),
                    retained = emptyList(),
                )
            val retained =
                messageShareAttachmentSources(
                    references = emptyList(),
                    retained = listOf(PendingAttachment("voice".encodeToByteArray(), "audio/mp4", "voice note.m4a")),
                )
            val staged = mutableListOf<StagedMessageShareStreams>()
            try {
                staged +=
                    stageMessageShareStreams(context, confirmed) { source ->
                        "sent ${source.attachmentIndex}".encodeToByteArray()
                    }
                staged += stageMessageShareStreams(context, retained) { "voice".encodeToByteArray() }
                val streams = staged.flatMap { it.streams }

                assertEquals(
                    listOf("story.pdf", "story.pdf", "voice note.m4a"),
                    streams.map { displayName(context, it.uri) },
                )
                assertEquals(listOf("application/pdf", "application/pdf", "audio/mp4"), streams.map { it.mediaType })
                assertEquals(3, streams.map { it.uri }.distinct().size)
                assertEquals(
                    listOf("sent 0", "sent 1", "voice"),
                    streams.map { stream ->
                        context.contentResolver.openInputStream(stream.uri)!!.use { it.readBytes().decodeToString() }
                    },
                )
                streams.forEach { stream ->
                    assertFalse(displayName(context, stream.uri).startsWith("message_"))
                    assertFalse(stream.uri.toString().contains("neighbor"))
                }
            } finally {
                staged.forEach { it.deletePlaintext() }
                neighbor.delete()
            }
        }

    private fun displayName(
        context: android.content.Context,
        uri: android.net.Uri,
    ): String =
        context.contentResolver
            .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)!!
            .use { cursor ->
                assertTrue(cursor.moveToFirst())
                cursor.getString(cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME))
            }

    private fun reference(
        fileName: String,
        mediaType: String,
    ) = MediaAttachmentReferenceFfi(
        locators = listOf(MediaLocatorFfi(kind = "blossom-v1", value = "https://cdn.example.test/blob")),
        ciphertextSha256 = "a".repeat(64),
        plaintextSha256 = "b".repeat(64),
        nonceHex = "c".repeat(48),
        fileName = fileName,
        mediaType = mediaType,
        version = EncryptedMediaVersionFfi.V1,
        sourceEpoch = 1uL,
        dim = null,
        thumbhash = null,
    )

    @Test
    fun realChooserCarriesSentAndReceivedStreamsThenCancelsWithoutStateMutation() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val directory = File(context.cacheDir, MediaCacheDirs.SHARED).apply { mkdirs() }
        val sent = File(directory, "sent-fixture.pdf").apply { writeBytes("sent bytes".encodeToByteArray()) }
        val received =
            File(directory, "received-fixture.bin")
                .apply { writeBytes("received bytes".encodeToByteArray()) }
        val neighbor = File(directory, "neighbor-secret.txt").apply { writeBytes("not shared".encodeToByteArray()) }
        try {
            val sentUri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", sent)
            val receivedUri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", received)
            val neighborUri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", neighbor)
            val send =
                outboundShareIntent(
                    text = "Visible message caption",
                    streams =
                        listOf(
                            OutboundShareStream(sentUri, "application/pdf"),
                            OutboundShareStream(receivedUri, "application/octet-stream"),
                        ),
                )
            val chooser = outboundShareChooser(context, send, "Share")
            val nested = IntentCompat.getParcelableExtra(chooser, Intent.EXTRA_INTENT, Intent::class.java)!!
            val streams =
                IntentCompat.getParcelableArrayListExtra(
                    nested,
                    Intent.EXTRA_STREAM,
                    android.net.Uri::class.java,
                )!!

            assertEquals(Intent.ACTION_SEND_MULTIPLE, nested.action)
            assertEquals("application/*", nested.type)
            assertEquals("Visible message caption", nested.getStringExtra(Intent.EXTRA_TEXT))
            assertEquals(listOf(sentUri, receivedUri), streams)
            assertEquals(
                listOf(sentUri, receivedUri),
                (0 until nested.clipData!!.itemCount).map { nested.clipData!!.getItemAt(it).uri },
            )
            assertTrue(nested.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
            assertFalse(neighborUri in streams)

            instrumentation.runOnMainSync {
                context.startActivity(chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            instrumentation.waitForIdleSync()
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            instrumentation.waitForIdleSync()
        } finally {
            sent.delete()
            received.delete()
            neighbor.delete()
        }
    }
}
