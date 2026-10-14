package dev.ipf.whitenoise.android.share

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ResultReceiver
import android.provider.OpenableColumns
import android.view.KeyEvent
import androidx.core.content.FileProvider
import androidx.core.content.IntentCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import dev.ipf.marmotkit.EncryptedMediaVersionFfi
import dev.ipf.marmotkit.MediaAttachmentReferenceFfi
import dev.ipf.marmotkit.MediaLocatorFfi
import dev.ipf.whitenoise.android.PullRequestDeviceSmoke
import dev.ipf.whitenoise.android.media.AttachmentPlaintext
import dev.ipf.whitenoise.android.media.MediaCacheDirs
import dev.ipf.whitenoise.android.state.PendingAttachment
import dev.ipf.whitenoise.android.ui.conversation.media.StagedMessageShareStreams
import dev.ipf.whitenoise.android.ui.conversation.media.messageShareAttachmentSources
import dev.ipf.whitenoise.android.ui.conversation.media.stageMessageShareStreams
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest

@RunWith(AndroidJUnit4::class)
@PullRequestDeviceSmoke
class OutboundShareDeviceContractTest {
    /** Real implicit discovery must find an independent receiver before a generated TXT is staged. */
    @Test
    fun implicitTxtChooserTransfersExactBytesNameAndOnlySelectedUri() =
        runBlocking {
            assertIndependentChooser(listOf(PendingAttachment("TXT fixture".encodeToByteArray(), "text/plain", "story.txt")))
        }

    /** SEND_MULTIPLE uses the same discovery contract and preserves captions, order and mixed MIME. */
    @Test
    fun implicitMultiFileChooserPreservesCaptionOrderAndMixedMime() =
        runBlocking {
            assertIndependentChooser(
                listOf(
                    PendingAttachment("TXT fixture".encodeToByteArray(), "text/plain", "story.txt"),
                    PendingAttachment("binary fixture".encodeToByteArray(), "application/octet-stream", "data.bin"),
                ),
            )
        }

    /** Selects through the system chooser without an explicit receiving component or prior receiver interaction. */
    @Suppress("DEPRECATION")
    private suspend fun assertIndependentChooser(attachments: List<PendingAttachment>) {
        assertHiddenWithoutQueries()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val sources = messageShareAttachmentSources(emptyList(), attachments)
        val probe =
            outboundShareIntent(
                FIXTURE_CAPTION,
                sources.map {
                    OutboundShareStream(android.net.Uri.parse("content://${context.packageName}/probe"), it.mediaType)
                },
            )
        val matches = context.packageManager.queryIntentActivities(probe, PackageManager.MATCH_DEFAULT_ONLY)
        assertTrue(
            "Independent receiver must be implicitly visible before staging",
            matches.any { it.activityInfo.packageName == FIXTURE_PACKAGE },
        )
        outboundShareChooser(context, probe, "Share")
        val directory = File(context.cacheDir, MediaCacheDirs.SHARED).apply { mkdirs() }
        val neighbor = File(directory, "unshared-neighbor.txt").apply { writeText("not shared") }
        try {
            val staged =
                stageMessageShareStreams(context, sources) { source ->
                    AttachmentPlaintext.Bytes(checkNotNull(attachments[source.attachmentIndex].inMemoryBytes))
                }
            try {
                val neighborUri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", neighbor)
                selectIndependentReceiver(context, staged, attachments, neighborUri)
            } finally {
                staged.deletePlaintext()
            }
        } finally {
            neighbor.delete()
        }
    }

    /** A different caller UID with the old manifest must fail discovery of this exact independent receiver. */
    private suspend fun assertHiddenWithoutQueries() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val result = CompletableDeferred<Pair<Int, Bundle?>>()
        val probe =
            Intent()
                .setComponent(ComponentName("dev.ipf.sharecontrol", "dev.ipf.sharefixture.VisibilityControlActivity"))
                .putExtra("outbound_share_fixture.result", resultReceiver(result))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        instrumentation.runOnMainSync { instrumentation.targetContext.startActivity(probe) }
        val (code, observed) = withTimeout(10_000) { result.await() }
        assertEquals(Activity.RESULT_OK, code)
        assertFalse("Negative control must prove Android package filtering is active", observed?.getBoolean("visible", true) ?: true)
    }

    /** Clicks the independent target in the real chooser and validates the receiving UID's observations. */
    private suspend fun selectIndependentReceiver(
        context: Context,
        staged: StagedMessageShareStreams,
        attachments: List<PendingAttachment>,
        neighborUri: android.net.Uri,
    ) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val result = CompletableDeferred<Pair<Int, Bundle?>>()
        val send =
            outboundShareIntent(FIXTURE_CAPTION, staged.streams)
                .putExtra("outbound_share_fixture.neighbor", neighborUri.toString())
                .putExtra("outbound_share_fixture.result", resultReceiver(result))
        val chooser = outboundShareChooser(context, send, "Share")
        val device = UiDevice.getInstance(instrumentation)
        try {
            instrumentation.runOnMainSync { context.startActivity(chooser) }
            val target = device.wait(Until.findObject(By.text("Share visibility fixture")), 10_000)
            checkNotNull(target) { "Independent receiver missing from the real Sharesheet" }.click()
            val (code, observed) = withTimeout(10_000) { result.await() }
            assertEquals(Activity.RESULT_OK, code)
            assertEquals(attachments.map { sha256(checkNotNull(it.inMemoryBytes)) }, observed?.getStringArrayList("hashes"))
            assertEquals(attachments.map { it.fileName }, observed?.getStringArrayList("names"))
            assertEquals(if (attachments.size == 1) "text/plain" else "*/*", observed?.getString("mime"))
            assertEquals(FIXTURE_CAPTION, observed?.getString("caption"))
            assertEquals(attachments.size, observed?.getInt("clipCount"))
            assertTrue(observed?.getBoolean("readGrant") == true)
            assertFalse(observed?.getBoolean("neighborReadable", true) ?: true)
        } finally {
            if (!result.isCompleted) device.pressBack()
        }
    }

    /** Receives fixture observations on the app looper while the instrumentation thread awaits them. */
    private fun resultReceiver(result: CompletableDeferred<Pair<Int, Bundle?>>): ResultReceiver =
        object : ResultReceiver(Handler(Looper.getMainLooper())) {
            override fun onReceiveResult(
                resultCode: Int,
                resultData: Bundle?,
            ) {
                result.complete(resultCode to resultData)
            }
        }

    private companion object {
        const val FIXTURE_PACKAGE = "dev.ipf.sharefixture"
        const val FIXTURE_CAPTION = "Visible fixture caption"
    }

    /** An external receiver reads exactly the generated stream explicitly granted to it. */
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
            val expectedStreamSha256 = sha256(staged.file.readBytes())
            val result = CompletableDeferred<Pair<Int, Bundle?>>()
            val receiver =
                object : ResultReceiver(Handler(Looper.getMainLooper())) {
                    override fun onReceiveResult(
                        resultCode: Int,
                        resultData: Bundle?,
                    ) {
                        result.complete(resultCode to resultData)
                    }
                }
            try {
                val share =
                    outboundShareIntent("https://example.test/profile", listOf(staged.stream))
                        .setComponent(ComponentName(externalContext, OutboundShareTestTargetActivity::class.java))
                        .putExtra(OutboundShareTestTargetActivity.EXTRA_NEIGHBOR_URI, neighborUri.toString())
                        .putExtra(OutboundShareTestTargetActivity.EXTRA_RESULT_RECEIVER, receiver)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

                context.startActivity(share)
                val (resultCode, resultData) = withTimeout(10_000) { result.await() }

                assertEquals(Activity.RESULT_OK, resultCode)
                assertEquals(
                    expectedStreamSha256,
                    resultData?.getString(OutboundShareTestTargetActivity.KEY_STREAM_SHA256),
                )
                assertFalse(resultData?.getBoolean(OutboundShareTestTargetActivity.KEY_NEIGHBOR_READABLE, true) ?: true)
            } finally {
                staged.file.delete()
                neighbor.delete()
            }
        }

    /** Produces a stable digest for comparing the staged file with the bytes read by the receiver. */
    private fun sha256(bytes: ByteArray): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { byte -> "%02x".format(byte) }

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
                        AttachmentPlaintext.Bytes("sent ${source.attachmentIndex}".encodeToByteArray())
                    }
                staged +=
                    stageMessageShareStreams(context, retained) {
                        AttachmentPlaintext.Bytes("voice".encodeToByteArray())
                    }
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
