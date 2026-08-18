package dev.ipf.whitenoise.android.core

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.ipf.marmotkit.Marmot
import dev.ipf.marmotkit.MarmotAndroid
import dev.ipf.marmotkit.MediaAttachmentReferenceFfi
import dev.ipf.marmotkit.MediaUploadAttachmentRequestFfi
import dev.ipf.marmotkit.MediaUploadRequestFfi
import dev.ipf.marmotkit.TimelineMessageQueryFfi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

/**
 * Real packaged-MDK forwarding tracer bullet. It uploads a source batch,
 * downloads its plaintext, creates fresh references for two destination
 * groups, publishes those references, and decrypts every destination file.
 */
@RunWith(AndroidJUnit4::class)
class ForwardMediaReferenceFfiIntegrationTest {
    @Test
    @Suppress("LongMethod") // Keep the end-to-end native boundary in one auditable sequence.
    fun everyDestinationGetsFreshDecryptablePhotoVideoAudioAndDocumentReferences() =
        runBlocking {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            MarmotAndroid.initialize(context)
            val root = File(context.cacheDir, "forward-media-ffi-${UUID.randomUUID()}").apply { mkdirs() }
            val marmot = Marmot(root.absolutePath, MarmotClient.bootstrapRelays)
            var accountRef: String? = null
            var closed = false

            try {
                withTimeout(TEST_TIMEOUT_MS) {
                    marmot.start()
                    val account =
                        marmot.createIdentity(
                            MarmotClient.bootstrapRelays,
                            MarmotClient.bootstrapRelays,
                        )
                    accountRef = account.label

                    val sourceGroup = marmot.createGroup(account.label, "Forward source", emptyList(), null)
                    val destinationGroups =
                        listOf(
                            marmot.createGroup(account.label, "Forward destination A", emptyList(), null),
                            marmot.createGroup(account.label, "Forward destination B", emptyList(), null),
                        )
                    val fixtures = forwardableFixtures()
                    val sourceUpload =
                        marmot.uploadMedia(
                            account.label,
                            sourceGroup,
                            fixtures.toUploadRequest(caption = FORWARD_CAPTION, send = true),
                        )
                    val sourceReferences = sourceUpload.attachments.map { it.reference }

                    assertEquals(fixtures.size, sourceReferences.size)
                    assertTrue(sourceUpload.sent?.messageIds?.isNotEmpty() == true)
                    val materialized =
                        sourceReferences.map { reference ->
                            marmot.downloadMedia(account.label, sourceGroup, reference)
                        }
                    fixtures.zip(materialized).forEach { (fixture, download) ->
                        assertEquals(fixture.fileName, download.fileName)
                        assertEquals(fixture.mediaType, download.mediaType)
                        assertArrayEquals(fixture.plaintext, download.plaintext)
                    }

                    val destinationReferencesByGroup = mutableMapOf<String, List<MediaAttachmentReferenceFfi>>()
                    destinationGroups.forEach { destinationGroup ->
                        val destinationUpload =
                            marmot.uploadMedia(
                                account.label,
                                destinationGroup,
                                MediaUploadRequestFfi(
                                    attachments =
                                        sourceReferences.zip(materialized).map { (sourceReference, download) ->
                                            MediaUploadAttachmentRequestFfi(
                                                fileName = download.fileName,
                                                mediaType = download.mediaType,
                                                plaintext = download.plaintext,
                                                dim = sourceReference.dim,
                                                thumbhash = sourceReference.thumbhash,
                                            )
                                        },
                                    caption = FORWARD_CAPTION,
                                    send = false,
                                    blossomServer = null,
                                ),
                            )
                        val destinationReferences = destinationUpload.attachments.map { it.reference }
                        destinationReferencesByGroup[destinationGroup] = destinationReferences

                        assertFreshDestinationReferences(sourceReferences, destinationReferences)
                        val send =
                            marmot.sendMediaAttachments(
                                account.label,
                                destinationGroup,
                                destinationReferences,
                                FORWARD_CAPTION,
                            )
                        assertTrue("Destination media send must commit a message", send.messageIds.isNotEmpty())

                        val sentRecord =
                            marmot
                                .timelineMessages(
                                    account.label,
                                    TimelineMessageQueryFfi(
                                        groupIdHex = destinationGroup,
                                        search = null,
                                        before = null,
                                        beforeMessageId = null,
                                        after = null,
                                        afterMessageId = null,
                                        limit = 20u,
                                    ),
                                ).messages
                                .firstOrNull { record -> record.messageIdHex in send.messageIds }
                        assertNotNull("Forwarded media message must be projected locally", sentRecord)
                        requireNotNull(sentRecord)
                        assertEquals(FORWARD_CAPTION, sentRecord.plaintext)
                        assertEquals(fixtures.map(ForwardableFixture::fileName), sentRecord.media.map { it.fileName })
                        assertEquals(fixtures.map(ForwardableFixture::mediaType), sentRecord.media.map { it.mediaType })

                        fixtures.zip(destinationReferences).forEach { (fixture, reference) ->
                            val opened = marmot.downloadMedia(account.label, destinationGroup, reference)
                            assertEquals(fixture.fileName, opened.fileName)
                            assertEquals(fixture.mediaType, opened.mediaType)
                            assertEquals(fixture.plaintext.size.toULong(), opened.sizeBytes)
                            assertArrayEquals(fixture.plaintext, opened.plaintext)
                        }

                        val wrongGroupFailure =
                            runCatching {
                                marmot.downloadMedia(account.label, sourceGroup, destinationReferences.first())
                            }.exceptionOrNull()
                        assertNotNull(
                            "A destination reference must not decrypt with the source group's media secret",
                            wrongGroupFailure,
                        )
                    }

                    val destinationAReferences = destinationReferencesByGroup.getValue(destinationGroups[0])
                    val destinationBReferences = destinationReferencesByGroup.getValue(destinationGroups[1])
                    assertFreshDestinationReferences(destinationAReferences, destinationBReferences)
                    assertReferencesCannotCrossDestinationGroups(
                        marmot = marmot,
                        accountRef = account.label,
                        references = destinationAReferences,
                        wrongGroupIdHex = destinationGroups[1],
                    )
                    assertReferencesCannotCrossDestinationGroups(
                        marmot = marmot,
                        accountRef = account.label,
                        references = destinationBReferences,
                        wrongGroupIdHex = destinationGroups[0],
                    )
                }
            } finally {
                accountRef?.let { ephemeralAccount ->
                    runCatching {
                        withTimeout(CLEANUP_TIMEOUT_MS) { marmot.removeAccount(ephemeralAccount) }
                    }
                }
                try {
                    withTimeout(CLEANUP_TIMEOUT_MS) { marmot.shutdownAndClose() }
                    closed = true
                } finally {
                    if (!closed) marmot.close()
                    root.deleteRecursively()
                }
            }
        }

    private suspend fun assertReferencesCannotCrossDestinationGroups(
        marmot: Marmot,
        accountRef: String,
        references: List<MediaAttachmentReferenceFfi>,
        wrongGroupIdHex: String,
    ) {
        references.forEach { reference ->
            val wrongGroupFailure =
                runCatching {
                    marmot.downloadMedia(accountRef, wrongGroupIdHex, reference)
                }.exceptionOrNull()
            assertNotNull(
                "A forwarded reference must not decrypt with another destination group's media secret",
                wrongGroupFailure,
            )
        }
    }

    private fun assertFreshDestinationReferences(
        source: List<MediaAttachmentReferenceFfi>,
        destination: List<MediaAttachmentReferenceFfi>,
    ) {
        assertEquals(source.size, destination.size)
        source.zip(destination).forEach { (sourceReference, destinationReference) ->
            assertEquals(sourceReference.fileName, destinationReference.fileName)
            assertEquals(sourceReference.mediaType, destinationReference.mediaType)
            assertEquals(sourceReference.plaintextSha256, destinationReference.plaintextSha256)
            assertEquals(sourceReference.dim, destinationReference.dim)
            assertEquals(sourceReference.thumbhash, destinationReference.thumbhash)
            assertNotEquals(sourceReference.ciphertextSha256, destinationReference.ciphertextSha256)
            assertNotEquals(sourceReference.nonceHex, destinationReference.nonceHex)
            assertNotEquals(sourceReference.locators, destinationReference.locators)
        }
    }

    private fun List<ForwardableFixture>.toUploadRequest(
        caption: String,
        send: Boolean,
    ) = MediaUploadRequestFfi(
        attachments =
            map { fixture ->
                MediaUploadAttachmentRequestFfi(
                    fileName = fixture.fileName,
                    mediaType = fixture.mediaType,
                    plaintext = fixture.plaintext,
                    dim = fixture.dim,
                    thumbhash = fixture.thumbhash,
                )
            },
        caption = caption,
        send = send,
        blossomServer = null,
    )

    private fun forwardableFixtures() =
        listOf(
            ForwardableFixture(
                fileName = "photo.png",
                mediaType = "image/png",
                plaintext =
                    java.util.Base64
                        .getDecoder()
                        .decode(PHOTO_PNG_BASE64),
                dim = "1x1",
                thumbhash = "1QcSHQRnh493V4dIh4eXh1h4kJUI",
            ),
            ForwardableFixture(
                fileName = "video.mp4",
                mediaType = "video/mp4",
                plaintext =
                    (
                        "\u0000\u0000\u0000\u0018ftypisom\u0000\u0000\u0000\u0000" +
                            "isommp42\u0000\u0000\u0000\bmdat"
                    ).toByteArray(),
                dim = "320x240",
            ),
            ForwardableFixture(
                fileName = "voice.wav",
                mediaType = "audio/wav",
                plaintext = silentWaveFixture(),
            ),
            ForwardableFixture(
                fileName = "document.pdf",
                mediaType = "application/pdf",
                plaintext = "%PDF-1.4\n1 0 obj<</Type/Catalog>>endobj\ntrailer<</Root 1 0 R>>\n%%EOF\n".toByteArray(),
            ),
        )

    private fun silentWaveFixture(): ByteArray =
        ByteBuffer
            .allocate(45)
            .order(ByteOrder.LITTLE_ENDIAN)
            .apply {
                put("RIFF".toByteArray())
                putInt(37)
                put("WAVEfmt ".toByteArray())
                putInt(16)
                putShort(1)
                putShort(1)
                putInt(8_000)
                putInt(8_000)
                putShort(1)
                putShort(8)
                put("data".toByteArray())
                putInt(1)
                put(0)
            }.array()

    private data class ForwardableFixture(
        val fileName: String,
        val mediaType: String,
        val plaintext: ByteArray,
        val dim: String? = null,
        val thumbhash: String? = null,
    )

    private companion object {
        const val PHOTO_PNG_BASE64 =
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII="
        const val FORWARD_CAPTION = "Forwarded media caption"
        const val TEST_TIMEOUT_MS = 240_000L
        const val CLEANUP_TIMEOUT_MS = 30_000L
    }
}
