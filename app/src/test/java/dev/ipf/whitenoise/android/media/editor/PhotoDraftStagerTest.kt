package dev.ipf.whitenoise.android.media.editor

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.MessageDraftAttachmentFfi
import dev.ipf.marmotkit.MessageDraftFfi
import dev.ipf.whitenoise.android.media.MediaPipeline
import dev.ipf.whitenoise.android.state.MediaQuality
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowContentResolver
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class PhotoDraftStagerTest {
    @Test
    fun duplicateUriOccurrencesProduceDifferentDraftAttachmentIds() {
        assertNotEquals(
            stagedPhotoAttachmentId("account", "group", "slot-a"),
            stagedPhotoAttachmentId("account", "group", "slot-b"),
        )
    }

    private val app: Application = ApplicationProvider.getApplicationContext()

    @Test
    fun stageCommitsOnceRecoversWithoutReencodingAndRemoveReleasesSource() =
        runTest {
            var encodes = 0
            val fixture = fixture(onEncode = { encodes += 1 })
            val bytes = pngBytes()

            val first =
                fixture.stager.stageBytes(
                    bytes,
                    "picked.png",
                    "stable-id",
                    ACCOUNT,
                    GROUP,
                    MediaQuality.Standard,
                )
            assertTrue(first is PhotoDraftStageResult.Success)
            first as PhotoDraftStageResult.Success
            assertEquals(1, encodes)
            assertEquals(
                "stable-id",
                fixture.gateway.current
                    ?.mediaAttachments
                    ?.single()
                    ?.id,
            )
            assertTrue(
                fixture.sessions.committed(ACCOUNT, GROUP, "stable-id", first.photo.attachmentDigest) != null,
            )
            assertTrue(fixture.sources.bytes(first.photo.sourceLeaseId) != null)

            val recovered =
                fixture.stager.stageBytes(bytes, "ignored.png", "stable-id", ACCOUNT, GROUP, MediaQuality.High)
            assertTrue(recovered is PhotoDraftStageResult.Success)
            assertEquals(1, encodes)
            assertEquals(MediaQuality.Standard, (recovered as PhotoDraftStageResult.Success).photo.quality)

            fixture.stager.remove(ACCOUNT, GROUP, first.photo)
            assertNull(fixture.gateway.current)
            assertNull(fixture.sources.bytes(first.photo.sourceLeaseId))
            assertNull(fixture.sessions.committed(ACCOUNT, GROUP, "stable-id", first.photo.attachmentDigest))
        }

    @Test
    fun originalStagePreservesEncodedPixelsWithoutEditorRender() =
        runTest {
            var encodes = 0
            val fixture = fixture(onEncode = { encodes += 1 })
            val bytes = pngBytes()

            val result =
                fixture.stager.stageBytes(
                    bytes,
                    "picked.png",
                    "stable-id",
                    ACCOUNT,
                    GROUP,
                    MediaQuality.Original,
                ) as PhotoDraftStageResult.Success

            assertEquals(0, encodes)
            assertArrayEquals(
                MediaPipeline.stripOriginalImageMetadata(bytes),
                result.photo.attachment.plaintext,
            )
            assertEquals("image/png", result.photo.attachment.mediaType)
            assertEquals("80x60", result.photo.attachment.dim)
        }

    @Test
    fun legacyRestoreReusesPersistedAttachmentIdentityWithoutDuplicatingItsLease() =
        runTest {
            val fixture = fixture()
            val bytes = pngBytes()
            val initial =
                fixture.stager.stageBytes(
                    bytes,
                    "picked.png",
                    "stable-id",
                    ACCOUNT,
                    GROUP,
                    MediaQuality.Standard,
                ) as PhotoDraftStageResult.Success
            val uri = Uri.parse("content://photo-editor/legacy")
            ShadowContentResolver.reset()
            shadowOf(app.contentResolver).registerInputStreamSupplier(uri) { ByteArrayInputStream(bytes) }

            val restored =
                fixture.stager.stage(
                    uri = uri,
                    attachmentSlotId = "legacy-random-id",
                    accountRef = ACCOUNT,
                    groupIdHex = GROUP,
                    quality = MediaQuality.High,
                    legacyOccurrenceIndex = 0,
                )

            assertTrue(restored is PhotoDraftStageResult.Success)
            restored as PhotoDraftStageResult.Success
            assertEquals("stable-id", restored.photo.attachment.id)
            assertEquals(
                listOf("stable-id"),
                fixture.gateway.current
                    ?.mediaAttachments
                    ?.map { it.id },
            )
            assertEquals(1, fixture.sources.lease(initial.photo.sourceLeaseId)?.references)
        }

    @Test
    fun unsupportedSourceAndDraftFailureNeverPublishOrLeakLease() =
        runTest {
            val unsupported = fixture()
            val rejected =
                unsupported.stager.stageBytes(
                    "not an image".encodeToByteArray(),
                    "bad.bin",
                    "bad",
                    ACCOUNT,
                    GROUP,
                    MediaQuality.Standard,
                )
            assertEquals(PhotoDraftStageResult.NotEditable(PhotoEditorSourceFailure.Unsupported), rejected)
            assertNull(unsupported.sources.bytes("lease-0"))
            assertNull(unsupported.gateway.current)

            val failed = fixture().also { it.gateway.failSave = true }
            val result =
                failed.stager.stageBytes(
                    pngBytes(),
                    "picked.png",
                    "stable-id",
                    ACCOUNT,
                    GROUP,
                    MediaQuality.Standard,
                )
            assertEquals(PhotoDraftStageResult.DraftUnavailable, result)
            assertNull(failed.sources.bytes("lease-0"))
            assertNull(failed.gateway.current)
        }

    @Test
    fun committedMdkPhotoRemainsSendableWhenEditorSourceIsMissing() =
        runTest {
            var encodes = 0
            val fixture = fixture(onEncode = { encodes += 1 })
            val attachment =
                MessageDraftAttachmentFfi(
                    id = "stable-id",
                    fileName = "prepared.jpg",
                    mediaType = "image/jpeg",
                    plaintext = byteArrayOf(1, 2, 3),
                    dim = "20x10",
                    thumbhash = "hash",
                    durationSeconds = null,
                    waveformSamples = emptyList(),
                )
            fixture.gateway.current =
                MessageDraftFfi(
                    groupIdHex = GROUP,
                    content = "",
                    replyToMessageIdHex = null,
                    mediaAttachments = listOf(attachment),
                    createdAtMs = 1,
                    updatedAtMs = 2,
                )

            val result =
                fixture.stager.stageBytes(
                    pngBytes(),
                    "ignored.png",
                    "stable-id",
                    ACCOUNT,
                    GROUP,
                    MediaQuality.High,
                )

            assertTrue(result is PhotoDraftStageResult.PreparedOnly)
            result as PhotoDraftStageResult.PreparedOnly
            assertEquals(
                attachment.plaintext.toList(),
                result.photo
                    .pendingAttachment()
                    .plaintextBytes
                    .toList(),
            )
            assertEquals(0, encodes)

            fixture.stager.removePrepared(ACCOUNT, GROUP, result.photo)
            assertNull(fixture.gateway.current)
        }

    private fun fixture(onEncode: () -> Unit = {}): Fixture {
        val payloads = StagerPayloadStore()
        val sources =
            EditorSourceStore(
                payloads = payloads,
                records = StagerStringStore(),
                newId = { "lease-${payloads.values.size}" },
            )
        val sessions = EditorSessionStore(StagerStringStore())
        val gateway = StagerDraftGateway()
        val dispatcher = UnconfinedTestDispatcher()
        val drafts = MessageDraftRepository(gateway, sessions, dispatcher)
        val renderer =
            PhotoEditorRenderer(
                renderDispatcher = dispatcher,
                memoryBudgetBytes = { Long.MAX_VALUE },
                onEncode = onEncode,
            )
        return Fixture(
            sources = sources,
            sessions = sessions,
            gateway = gateway,
            stager =
                PhotoDraftStager(
                    contentResolver = app.contentResolver,
                    sources = sources,
                    sessions = sessions,
                    renderer = renderer,
                    drafts = drafts,
                    ioDispatcher = dispatcher,
                ),
        )
    }

    private fun pngBytes(): ByteArray {
        val bitmap = Bitmap.createBitmap(80, 60, Bitmap.Config.ARGB_8888)
        return try {
            bitmap.eraseColor(0xFF336699.toInt())
            ByteArrayOutputStream().use { output ->
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
                output.toByteArray()
            }
        } finally {
            bitmap.recycle()
        }
    }

    private data class Fixture(
        val sources: EditorSourceStore,
        val sessions: EditorSessionStore,
        val gateway: StagerDraftGateway,
        val stager: PhotoDraftStager,
    )

    companion object {
        private const val ACCOUNT = "account"
        private const val GROUP = "group"
    }
}

private class StagerDraftGateway : MessageDraftGateway {
    var current: MessageDraftFfi? = null
    var failSave = false

    override fun read(
        accountRef: String,
        groupIdHex: String,
    ): MessageDraftFfi? = current

    override fun save(
        accountRef: String,
        groupIdHex: String,
        content: String,
        replyToMessageIdHex: String?,
        mediaAttachments: List<MessageDraftAttachmentFfi>,
    ): MessageDraftFfi {
        if (failSave) error("save failed")
        return MessageDraftFfi(
            groupIdHex = groupIdHex,
            content = content,
            replyToMessageIdHex = replyToMessageIdHex,
            mediaAttachments = mediaAttachments,
            createdAtMs = current?.createdAtMs ?: 1,
            updatedAtMs = (current?.updatedAtMs ?: 0) + 1,
        ).also { current = it }
    }

    override fun delete(
        accountRef: String,
        groupIdHex: String,
    ) {
        current = null
    }
}

private class StagerPayloadStore : EditorEncryptedPayloadStore {
    val values = linkedMapOf<String, ByteArray>()

    override fun prepare() = Unit

    override fun contains(key: String): Boolean = key in values

    override fun get(key: String): ByteArray? = values[key]?.copyOf()

    override fun put(
        key: String,
        bytes: ByteArray,
    ): Boolean {
        values[key] = bytes.copyOf()
        return true
    }

    override fun remove(key: String) {
        values.remove(key)
    }

    override fun clear() = values.clear()
}

private class StagerStringStore : EditorStringStore {
    private var values = linkedMapOf<String, String>()

    override fun readAll(): Map<String, String> = values.toMap()

    override fun replaceAll(values: Map<String, String>): Boolean {
        this.values = LinkedHashMap(values)
        return true
    }

    override fun clear() = values.clear()
}
