package dev.ipf.whitenoise.android.media.editor

import android.graphics.Bitmap
import android.graphics.Color
import dev.ipf.marmotkit.MessageDraftAttachmentFfi
import dev.ipf.marmotkit.MessageDraftFfi
import dev.ipf.marmotkit.MessageDraftSummaryFfi
import dev.ipf.whitenoise.android.state.MediaQuality
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PhotoEditorCommitterTest {
    @Test
    fun successfulRenderAtomicallyReplacesStableAttachmentAndCommitsSession() =
        runTest {
            val fixture = fixture()
            val recipe = PhotoEditRecipe(quarterTurnsClockwise = 1)

            val result =
                fixture.committer.commit(
                    accountRef = ACCOUNT,
                    groupIdHex = GROUP,
                    currentAttachment = fixture.original,
                    expectedDigest = fixture.original.editorDigest(),
                    sourceLeaseId = fixture.sourceLeaseId,
                    recipe = recipe,
                    quality = MediaQuality.Standard,
                )

            assertTrue(result is PhotoEditorCommitResult.Success)
            result as PhotoEditorCommitResult.Success
            assertEquals(fixture.original.id, result.attachment.id)
            assertEquals("photo-edited.jpg", result.attachment.fileName)
            assertEquals("image/jpeg", result.attachment.mediaType)
            assertEquals(1, fixture.gateway.saveCalls)
            assertEquals(
                fixture.original.id,
                fixture.gateway.current
                    ?.mediaAttachments
                    ?.single()
                    ?.id,
            )
            assertEquals(
                recipe,
                fixture.sessions
                    .committed(ACCOUNT, GROUP, fixture.original.id, result.attachment.editorDigest())
                    ?.recipe,
            )
        }

    @Test
    fun targetChangedDuringEditingFailsClosedAfterRender() =
        runTest {
            var encodeCount = 0
            val fixture = fixture(onEncode = { encodeCount += 1 })
            val changed = attachment(fixture.original.plaintext + 9)
            fixture.gateway.current = draft(changed)

            val result =
                fixture.committer.commit(
                    accountRef = ACCOUNT,
                    groupIdHex = GROUP,
                    currentAttachment = fixture.original,
                    expectedDigest = fixture.original.editorDigest(),
                    sourceLeaseId = fixture.sourceLeaseId,
                    recipe = PhotoEditRecipe(quarterTurnsClockwise = 1),
                    quality = MediaQuality.High,
                )

            assertEquals(PhotoEditorCommitResult.StaleAttachment, result)
            assertEquals(1, encodeCount)
            assertEquals(0, fixture.gateway.saveCalls)
            assertArrayEquals(
                changed.plaintext,
                fixture.gateway.current
                    ?.mediaAttachments
                    ?.single()
                    ?.plaintext,
            )
        }

    @Test
    fun unsupportedSourceNeverMutatesDraft() =
        runTest {
            val fixture = fixture(sourceBytes = byteArrayOf(1, 2, 3))

            val result =
                fixture.committer.commit(
                    accountRef = ACCOUNT,
                    groupIdHex = GROUP,
                    currentAttachment = fixture.original,
                    expectedDigest = fixture.original.editorDigest(),
                    sourceLeaseId = fixture.sourceLeaseId,
                    recipe = PhotoEditRecipe.Original,
                    quality = MediaQuality.Standard,
                )

            assertTrue(result is PhotoEditorCommitResult.RenderFailed)
            assertEquals(0, fixture.gateway.saveCalls)
        }

    private fun fixture(
        sourceBytes: ByteArray = solidPng(),
        onEncode: () -> Unit = {},
    ): CommitFixture {
        val payloads = CommitPayloads()
        val sourceStrings = CommitStrings()
        val sources =
            EditorSourceStore(
                payloads = payloads,
                records = sourceStrings,
                newId = { "source" },
            )
        val lease = (sources.stageBytes(sourceBytes) as EditorSourceStageResult.Success).lease
        val original = attachment(sourceBytes)
        val gateway = CommitGateway(draft(original))
        val sessions = EditorSessionStore(CommitStrings())
        val dispatcher = UnconfinedTestDispatcher()
        val repository = MessageDraftRepository(gateway, sessions, dispatcher)
        val renderer =
            PhotoEditorRenderer(
                renderDispatcher = dispatcher,
                memoryBudgetBytes = { 256L * 1024L * 1024L },
                onEncode = onEncode,
            )
        return CommitFixture(
            original = original,
            sourceLeaseId = lease.id,
            gateway = gateway,
            sessions = sessions,
            committer = PhotoEditorCommitter(sources, renderer, repository, dispatcher),
        )
    }

    private fun attachment(bytes: ByteArray) =
        MessageDraftAttachmentFfi(
            id = "attachment",
            fileName = "photo.png",
            mediaType = "image/png",
            plaintext = bytes,
            dim = "64x48",
            thumbhash = null,
            durationSeconds = null,
            waveformSamples = emptyList(),
        )

    private fun draft(attachment: MessageDraftAttachmentFfi) =
        MessageDraftFfi(
            groupIdHex = GROUP,
            content = "caption",
            replyToMessageIdHex = "reply",
            mediaAttachments = listOf(attachment),
            createdAtMs = 1,
            updatedAtMs = 2,
        )

    private fun solidPng(): ByteArray {
        val bitmap = Bitmap.createBitmap(64, 48, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.CYAN)
        return try {
            ByteArrayOutputStream().use { output ->
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
                output.toByteArray()
            }
        } finally {
            bitmap.recycle()
        }
    }

    private data class CommitFixture(
        val original: MessageDraftAttachmentFfi,
        val sourceLeaseId: String,
        val gateway: CommitGateway,
        val sessions: EditorSessionStore,
        val committer: PhotoEditorCommitter,
    )

    companion object {
        private const val ACCOUNT = "account"
        private const val GROUP = "group"
    }
}

private class CommitGateway(
    var current: MessageDraftFfi?,
) : MessageDraftGateway {
    var saveCalls = 0

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
    ): MessageDraftFfi =
        MessageDraftFfi(
            groupIdHex = groupIdHex,
            content = content,
            replyToMessageIdHex = replyToMessageIdHex,
            mediaAttachments = mediaAttachments,
            createdAtMs = current?.createdAtMs ?: 1,
            updatedAtMs = (current?.updatedAtMs ?: 0) + 1,
        ).also {
            saveCalls += 1
            current = it
        }

    override fun delete(
        accountRef: String,
        groupIdHex: String,
    ) {
        current = null
    }

    override fun summaries(accountRef: String): List<MessageDraftSummaryFfi> = emptyList()
}

private class CommitPayloads : EditorEncryptedPayloadStore {
    private val values = mutableMapOf<String, ByteArray>()

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

private class CommitStrings : EditorStringStore {
    private var values = mapOf<String, String>()

    override fun readAll(): Map<String, String> = values

    override fun replaceAll(values: Map<String, String>): Boolean {
        this.values = LinkedHashMap(values)
        return true
    }

    override fun clear() {
        values = emptyMap()
    }
}
