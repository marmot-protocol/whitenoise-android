package dev.ipf.whitenoise.android.media.editor

import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.Bitmap
import android.net.Uri
import android.os.CancellationSignal
import android.provider.OpenableColumns
import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.MessageDraftAttachmentFfi
import dev.ipf.marmotkit.MessageDraftFfi
import dev.ipf.marmotkit.MessageDraftSummaryFfi
import dev.ipf.whitenoise.android.media.MediaTrustDomain
import dev.ipf.whitenoise.android.state.MediaQuality
import dev.ipf.whitenoise.android.ui.conversation.media.PendingMediaSlot
import dev.ipf.whitenoise.android.ui.conversation.restoredPhotosMissingFrom
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.CRC32

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
            assertFalse(first.photo.restoredFromNative)
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
            val recoveredPhoto = (recovered as PhotoDraftStageResult.Success).photo
            assertEquals(MediaQuality.Standard, recoveredPhoto.quality)
            assertTrue(recoveredPhoto.restoredFromNative)
            val slot = PendingMediaSlot("saved-picker", Uri.parse("content://picker/saved"))
            assertEquals(
                setOf(slot.id),
                restoredPhotosMissingFrom(listOf(slot), emptyMap(), mapOf(slot.id to recoveredPhoto), emptySet()),
            )
            val freshMissing =
                restoredPhotosMissingFrom(listOf(slot), emptyMap(), mapOf(slot.id to first.photo), emptySet())
            assertTrue(freshMissing.isEmpty())

            fixture.stager.remove(ACCOUNT, GROUP, first.photo)
            assertNull(fixture.gateway.current)
            assertNull(fixture.sources.bytes(first.photo.sourceLeaseId))
            assertNull(fixture.sessions.committed(ACCOUNT, GROUP, "stable-id", first.photo.attachmentDigest))
        }

    @Test
    fun originalStageStripsMetadataWithoutEditorRenderOrPixelChanges() =
        runTest {
            var encodes = 0
            val fixture = fixture(onEncode = { encodes += 1 })
            val bytes = pngBytesWithTextMetadata()
            assertTrue(bytes.toString(Charsets.ISO_8859_1).contains(SENSITIVE_METADATA))

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
            assertFalse(
                result.photo.attachment.plaintext
                    .toString(Charsets.ISO_8859_1)
                    .contains(SENSITIVE_METADATA),
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

    @Test
    fun duplicateAttachmentDuringPreparationRetainsNativeOwnershipAndReleasesNewSource() =
        runTest {
            val native =
                MessageDraftAttachmentFfi(
                    "stable-id",
                    "native.jpg",
                    "image/jpeg",
                    byteArrayOf(1, 2, 3),
                    null,
                    null,
                    null,
                    emptyList(),
                )
            lateinit var staged: Fixture
            staged =
                fixture(onEncode = {
                    staged.gateway.current = MessageDraftFfi(GROUP, "", null, listOf(native), 1L, 1L)
                })

            val result =
                staged.stager.stageBytes(pngBytes(), "picked.png", "stable-id", ACCOUNT, GROUP, MediaQuality.Standard)
            assertTrue(result is PhotoDraftStageResult.PreparedOnly)
            val photo = (result as PhotoDraftStageResult.PreparedOnly).photo
            assertTrue(photo.restoredFromNative)
            assertEquals(native, photo.attachment)
            assertNull(staged.sources.bytes("lease-0"))
            val slot = PendingMediaSlot("saved-picker", Uri.parse("content://picker/saved"))
            assertEquals(
                setOf(slot.id),
                restoredPhotosMissingFrom(listOf(slot), mapOf(slot.id to photo), emptyMap(), emptySet()),
            )
        }

    /**
     * Picks staged through their content URIs, at standard and Original quality, run every inspection, render and
     * Original preparation in the domain the draft policy names for them: one draft's picks share its worker, and a
     * pick of a later draft runs in that draft's own. Staging an attachment the draft already holds, as restoring a
     * draft does, inspects it in the same shared worker (#3373).
     */
    @Test
    fun everyStagedPickDecodesInItsDraftsSharedWorker() =
        runTest {
            EditorDomainRecorder().use { recorder ->
                val draftOf = mapOf("slot-a" to "first-draft", "slot-b" to "first-draft", "slot-c" to "later-draft")
                val domainOf = { pick: String -> MediaTrustDomain.outgoing(ACCOUNT, GROUP, draftOf.getValue(pick)) }
                val fixture = fixture(outgoingDomain = domainOf)
                val bytes = pngBytes()
                ShadowContentResolver.reset()
                val uris =
                    listOf("slot-a", "slot-b", "slot-c").associateWith { Uri.parse("content://photo-editor/$it") }
                uris.values.forEach { uri ->
                    shadowOf(app.contentResolver).registerInputStreamSupplier(uri) { ByteArrayInputStream(bytes) }
                }
                val cases =
                    listOf(
                        "slot-a" to MediaQuality.Standard,
                        "slot-b" to MediaQuality.Original,
                        "slot-c" to MediaQuality.Standard,
                        // Already in the draft: restored through its committed session, not staged again.
                        "slot-a" to MediaQuality.Standard,
                    )
                cases.forEach { (slot, quality) ->
                    recorder.clear()
                    val staged = fixture.stager.stage(uris.getValue(slot), slot, ACCOUNT, GROUP, quality)
                    assertTrue(staged is PhotoDraftStageResult.Success)
                    assertEquals(setOf(domainOf(slot)), recorder.domains())
                }
                // The later draft's pick never shared the first draft's worker.
                val distinct = draftOf.keys.map(domainOf).toSet()
                assertEquals(2, distinct.size)
            }
        }

    /**
     * A pick's staging decodes in the draft its pick belonged to when staging began, even when the composer is emptied
     * and another pick starts a new draft while the source is still being read (#3373).
     */
    @Test
    fun stagingKeepsItsDraftWhenTheComposerChangesMidRead() =
        runTest {
            EditorDomainRecorder().use { recorder ->
                var draft = "first-draft"
                val fixture = fixture(outgoingDomain = { MediaTrustDomain.outgoing(ACCOUNT, GROUP, draft) })
                val bytes = pngBytes()
                val uri = Uri.parse("content://photo-editor/slot-a")
                ShadowContentResolver.reset()
                shadowOf(app.contentResolver).registerInputStreamSupplier(uri) {
                    // The source read is in flight: the user empties the composer and picks into a new draft.
                    draft = "later-draft"
                    ByteArrayInputStream(bytes)
                }
                val staged = fixture.stager.stage(uri, "slot-a", ACCOUNT, GROUP, MediaQuality.Standard)
                assertTrue(staged is PhotoDraftStageResult.Success)
                assertEquals("later-draft", draft)
                assertEquals(setOf(MediaTrustDomain.outgoing(ACCOUNT, GROUP, "first-draft")), recorder.domains())
            }
        }

    /** Issue #3706: a pick abandoned once its source is read must not keep the source's lease. */
    @Test
    fun abandonedPickAfterItsSourceIsReadReleasesTheSource() =
        runTest {
            val fixture = fixture()
            val bytes = pngBytes()
            val uri = Uri.parse("content://photo-editor/abandoned")
            lateinit var pick: Job
            ShadowContentResolver.reset()
            shadowOf(app.contentResolver).registerInputStreamSupplier(uri) {
                // The user dismisses the pick while its source is still being read.
                pick.cancel()
                ByteArrayInputStream(bytes)
            }
            pick = launch { fixture.stager.stage(uri, "slot-a", ACCOUNT, GROUP, MediaQuality.Standard) }
            pick.join()

            assertTrue(pick.isCancelled)
            assertNull(fixture.sources.bytes("lease-0"))
            assertNull(fixture.sources.lease("lease-0"))
            assertNull(fixture.gateway.current)
        }

    /** Issue #3716: the provider's name query never runs on the picking thread and cannot hold the pick. */
    @Test
    fun stalledProviderNameFallsBackWithoutHoldingThePick() =
        runBlocking {
            val io = Executors.newFixedThreadPool(IO_THREADS)
            val provider = DisplayNameProvider(name = "beach.png", gate = CountDownLatch(1))
            try {
                val fixture = fixture(ioDispatcher = io.asCoroutineDispatcher())
                val uri = provider.register(app, "stalled", pngBytes())

                val staged = fixture.stager.stage(uri, "slot-a", ACCOUNT, GROUP, MediaQuality.Standard)

                assertTrue(staged is PhotoDraftStageResult.Success)
                val fileName = (staged as PhotoDraftStageResult.Success).photo.attachment.fileName
                assertTrue(fileName, fileName.startsWith("image"))
                assertTrue(provider.entered.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                assertTrue(provider.cancelled.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                assertNotEquals(Thread.currentThread(), provider.queryThread)
            } finally {
                provider.gate?.countDown()
                io.shutdownNow()
                ShadowContentResolver.reset()
            }
        }

    /** Stalled providers occupy at most two lookups; later picks use the fallback name without querying. */
    @Test
    fun stalledNameLookupsAreBoundedAndLaterPicksDoNotQueue() =
        runBlocking {
            val io = Executors.newFixedThreadPool(IO_THREADS)
            val provider = DisplayNameProvider(name = "beach.png", gate = CountDownLatch(1))
            try {
                val fixture = fixture(ioDispatcher = io.asCoroutineDispatcher())
                val bytes = pngBytes()
                repeat(3) { index ->
                    val uri = provider.register(app, "stalled-$index", bytes, reset = index == 0)
                    val staged = fixture.stager.stage(uri, "slot-$index", ACCOUNT, GROUP, MediaQuality.Standard)
                    assertTrue(staged is PhotoDraftStageResult.Success)
                }
                assertEquals(2, provider.queries.get())
            } finally {
                provider.gate?.countDown()
                io.shutdownNow()
                ShadowContentResolver.reset()
            }
        }

    @Test
    fun answeredProviderNameNamesThePhoto() =
        runTest {
            val fixture = fixture()
            val provider = DisplayNameProvider(name = "beach.png")
            try {
                val uri = provider.register(app, "named", pngBytes())

                val staged = fixture.stager.stage(uri, "slot-a", ACCOUNT, GROUP, MediaQuality.Standard)

                assertTrue(staged is PhotoDraftStageResult.Success)
                val fileName = (staged as PhotoDraftStageResult.Success).photo.attachment.fileName
                assertTrue(fileName, fileName.startsWith("beach"))
            } finally {
                ShadowContentResolver.reset()
            }
        }

    /** A stager over in-memory stores whose picks decode in [outgoingDomain], single-use unless a test names one. */
    private fun fixture(
        onEncode: () -> Unit = {},
        outgoingDomain: (String) -> MediaTrustDomain = { MediaTrustDomain.Unique },
        ioDispatcher: CoroutineDispatcher? = null,
    ): Fixture {
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
                    outgoingDomain = outgoingDomain,
                    ioDispatcher = ioDispatcher ?: dispatcher,
                    nameLookupPermits = Semaphore(ProviderDisplayNames.MAX_NAME_LOOKUPS),
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

    private fun pngBytesWithTextMetadata(): ByteArray {
        val png = pngBytes()
        val iendStart = png.size - PNG_IEND_CHUNK_BYTES
        val metadata = pngChunk("tEXt", "Comment\u0000$SENSITIVE_METADATA".encodeToByteArray())
        return png.copyOfRange(0, iendStart) + metadata + png.copyOfRange(iendStart, png.size)
    }

    private fun pngChunk(
        type: String,
        payload: ByteArray,
    ): ByteArray {
        val output = ByteArrayOutputStream()
        output.write(
            byteArrayOf(
                (payload.size ushr 24).toByte(),
                (payload.size ushr 16).toByte(),
                (payload.size ushr 8).toByte(),
                payload.size.toByte(),
            ),
        )
        val typeBytes = type.encodeToByteArray()
        output.write(typeBytes)
        output.write(payload)
        val crc = CRC32()
        crc.update(typeBytes)
        crc.update(payload)
        val crcValue = crc.value.toInt()
        output.write(
            byteArrayOf(
                (crcValue ushr 24).toByte(),
                (crcValue ushr 16).toByte(),
                (crcValue ushr 8).toByte(),
                crcValue.toByte(),
            ),
        )
        return output.toByteArray()
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
        private const val PNG_IEND_CHUNK_BYTES = 12
        private const val SENSITIVE_METADATA = "private gps metadata"
        private const val IO_THREADS = 4
        private const val TIMEOUT_SECONDS = 5L
    }
}

/** Answers display-name queries, optionally stalling each one until [gate] opens or the caller gives up. */
private class DisplayNameProvider(
    private val name: String?,
    val gate: CountDownLatch? = null,
) : ContentProvider() {
    val queries = AtomicInteger()
    val entered = CountDownLatch(1)
    val cancelled = CountDownLatch(1)

    @Volatile var queryThread: Thread? = null

    fun register(
        app: Application,
        path: String,
        bytes: ByteArray,
        reset: Boolean = true,
    ): Uri {
        if (reset) {
            ShadowContentResolver.reset()
            ShadowContentResolver.registerProviderInternal(AUTHORITY, this)
        }
        val uri = Uri.parse("content://$AUTHORITY/$path")
        // Robolectric serves openInputStream from registered suppliers, never from a provider's openFile.
        shadowOf(app.contentResolver).registerInputStreamSupplier(uri) { ByteArrayInputStream(bytes) }
        return uri
    }

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = query(uri, projection, selection, selectionArgs, sortOrder, null)

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
        cancellationSignal: CancellationSignal?,
    ): Cursor? {
        queries.incrementAndGet()
        queryThread = Thread.currentThread()
        gate?.let { stall ->
            cancellationSignal?.setOnCancelListener { cancelled.countDown() }
            entered.countDown()
            stall.await(STALL_SECONDS, TimeUnit.SECONDS)
        }
        return MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME)).apply { addRow(arrayOf(name)) }
    }

    override fun getType(uri: Uri): String? = null

    override fun insert(
        uri: Uri,
        values: ContentValues?,
    ): Uri? = null

    override fun delete(
        uri: Uri,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    private companion object {
        const val AUTHORITY = "photo-draft-stager-test"
        const val STALL_SECONDS = 10L
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

    override fun summaries(accountRef: String): List<MessageDraftSummaryFfi> = emptyList()
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
