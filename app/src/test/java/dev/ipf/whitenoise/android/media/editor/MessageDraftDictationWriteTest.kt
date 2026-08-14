package dev.ipf.whitenoise.android.media.editor

import dev.ipf.marmotkit.MessageDraftAttachmentFfi
import dev.ipf.marmotkit.MessageDraftFfi
import dev.ipf.marmotkit.MessageDraftSummaryFfi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MessageDraftDictationWriteTest {
    @Test
    fun conditionalWriteRejectsAStaleGenerationAndPreservesAttachments() =
        runTest {
            val keptAttachment = attachment("kept", byteArrayOf(1))
            val key = ACCOUNT to GROUP
            val gateway =
                KeyedDraftGateway(
                    mutableMapOf(key to draft(GROUP, "baseline", listOf(keptAttachment))),
                )
            val repository =
                MessageDraftRepository(
                    gateway = gateway,
                    editorSessions = EditorSessionStore(TestStringStore()),
                    ioDispatcher = UnconfinedTestDispatcher(testScheduler),
                )
            val writer = CoalescingMessageDraftWriter(this, repository, debounceMillis = 0)
            val captured = writer.generation(ACCOUNT, GROUP)

            writer.submit(ACCOUNT, GROUP, "newer edit")
            assertEquals(
                null,
                writer.submitIfCurrent(ACCOUNT, GROUP, captured, "stale dictation"),
            )
            writer.flush()

            val latest = writer.generation(ACCOUNT, GROUP)
            assertTrue(
                writer.submitIfCurrent(ACCOUNT, GROUP, latest, "newer edit plus dictation") != null,
            )
            writer.flush()

            assertEquals("newer edit plus dictation", gateway.values.getValue(key).content)
            assertEquals(listOf(keptAttachment), gateway.values.getValue(key).mediaAttachments)
        }

    @Test
    fun conditionalWriteMutatesOnlyTheCapturedAccountAndGroupKey() =
        runTest {
            val attachment = attachment("origin-media", byteArrayOf(1))
            val originKey = ACCOUNT to GROUP
            val otherKey = OTHER_ACCOUNT to OTHER_GROUP
            val gateway =
                KeyedDraftGateway(
                    mutableMapOf(
                        originKey to draft(GROUP, "origin", listOf(attachment)),
                        otherKey to draft(OTHER_GROUP, "visible other"),
                    ),
                )
            val repository =
                MessageDraftRepository(
                    gateway = gateway,
                    editorSessions = EditorSessionStore(TestStringStore()),
                    ioDispatcher = UnconfinedTestDispatcher(testScheduler),
                )
            val writer = CoalescingMessageDraftWriter(this, repository, debounceMillis = 0)
            val capturedGeneration = writer.generation(ACCOUNT, GROUP)

            assertTrue(
                writer.submitIfCurrent(
                    accountRef = ACCOUNT,
                    groupIdHex = GROUP,
                    expected = capturedGeneration,
                    content = "origin dictated",
                ) != null,
            )
            writer.flush()

            assertEquals("origin dictated", gateway.values.getValue(originKey).content)
            assertEquals(listOf(attachment), gateway.values.getValue(originKey).mediaAttachments)
            assertEquals("visible other", gateway.values.getValue(otherKey).content)
        }

    private companion object {
        const val ACCOUNT = "account"
        const val GROUP = "group"
        const val OTHER_ACCOUNT = "other-account"
        const val OTHER_GROUP = "other-group"
    }
}

private class KeyedDraftGateway(
    val values: MutableMap<Pair<String, String>, MessageDraftFfi>,
) : MessageDraftGateway {
    override fun read(
        accountRef: String,
        groupIdHex: String,
    ): MessageDraftFfi? = values[accountRef to groupIdHex]

    override fun save(
        accountRef: String,
        groupIdHex: String,
        content: String,
        replyToMessageIdHex: String?,
        mediaAttachments: List<MessageDraftAttachmentFfi>,
    ): MessageDraftFfi {
        val key = accountRef to groupIdHex
        val current = values[key]
        return MessageDraftFfi(
            groupIdHex = groupIdHex,
            content = content,
            replyToMessageIdHex = replyToMessageIdHex,
            mediaAttachments = mediaAttachments,
            createdAtMs = current?.createdAtMs ?: 1,
            updatedAtMs = (current?.updatedAtMs ?: 0) + 1,
        ).also { values[key] = it }
    }

    override fun delete(
        accountRef: String,
        groupIdHex: String,
    ) {
        values.remove(accountRef to groupIdHex)
    }

    override fun summaries(accountRef: String): List<MessageDraftSummaryFfi> = emptyList()
}

private class TestStringStore : EditorStringStore {
    override fun readAll(): Map<String, String> = emptyMap()

    override fun replaceAll(values: Map<String, String>): Boolean = true

    override fun clear() = Unit
}

private fun draft(
    groupIdHex: String,
    content: String,
    attachments: List<MessageDraftAttachmentFfi> = emptyList(),
) = MessageDraftFfi(
    groupIdHex = groupIdHex,
    content = content,
    replyToMessageIdHex = null,
    mediaAttachments = attachments,
    createdAtMs = 1,
    updatedAtMs = 2,
)

private fun attachment(
    id: String,
    bytes: ByteArray,
) = MessageDraftAttachmentFfi(
    id = id,
    fileName = "$id.jpg",
    mediaType = "image/jpeg",
    plaintext = bytes,
    dim = "1x1",
    thumbhash = "hash",
    durationSeconds = null,
    waveformSamples = emptyList(),
)
