package dev.ipf.whitenoise.android.media.editor

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import dev.ipf.marmotkit.MessageDraftAttachmentFfi
import dev.ipf.marmotkit.MessageDraftFfi
import dev.ipf.marmotkit.MessageDraftSummaryFfi
import dev.ipf.whitenoise.android.audio.ConversationDictationController
import dev.ipf.whitenoise.android.audio.ConversationDictationDraftSnapshot
import dev.ipf.whitenoise.android.audio.ConversationDictationPlatform
import dev.ipf.whitenoise.android.audio.ConversationDictationRecognitionListener
import dev.ipf.whitenoise.android.audio.ConversationDictationRecognitionSession
import dev.ipf.whitenoise.android.audio.ConversationDictationTimeoutHandle
import dev.ipf.whitenoise.android.state.DraftPersistence
import dev.ipf.whitenoise.android.state.DraftStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
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
            val repository = repository(gateway, UnconfinedTestDispatcher(testScheduler))
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
            val repository = repository(gateway, UnconfinedTestDispatcher(testScheduler))
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

    /** Verifies a terminal result persists to the immutable origin and is authoritative after navigation. */
    @Test
    fun controllerResultSurvivesNavigationAndReopensFromAuthoritativeMdkDraft() =
        runTest {
            val originKey = ACCOUNT to GROUP
            val otherKey = OTHER_ACCOUNT to OTHER_GROUP
            val gateway =
                KeyedDraftGateway(
                    mutableMapOf(
                        originKey to draft(GROUP, "Origin "),
                        otherKey to draft(OTHER_GROUP, "Other"),
                    ),
                )
            val dispatcher = UnconfinedTestDispatcher(testScheduler)
            val repository = repository(gateway, dispatcher)
            val writer = CoalescingMessageDraftWriter(this, repository, debounceMillis = 0)
            val cache =
                mutableMapOf(
                    originKey to TextFieldValue("Origin ", TextRange(7)),
                    otherKey to TextFieldValue("Other", TextRange(5)),
                )
            val platform = DraftDictationPlatform()
            val controller = dictationController(platform, cache, writer)

            controller.requestStart(ACCOUNT, GROUP, cache.getValue(originKey))
            // The visible account/chat can change while the immutable origin
            // remains the delivery target.
            controller.stop()
            platform.listener.onResult("dictated")
            writer.flush()

            assertEquals("Other", gateway.values.getValue(otherKey).content)
            val reopenedRepository = repository(gateway, dispatcher)
            val authoritative = reopenedRepository.draft(ACCOUNT, GROUP).getOrThrow()
            val reopenedStore = DraftStore(NoOpDraftPersistence)
            reopenedStore.replaceFromAuthoritative(
                ACCOUNT,
                GROUP,
                authoritative?.content,
                authoritative?.createdAtMs,
            )

            assertEquals("Origin dictated", authoritative?.content)
            assertEquals(
                TextFieldValue("Origin dictated", TextRange("Origin dictated".length)),
                reopenedStore.getDraft(ACCOUNT, GROUP)?.textFieldValue,
            )
        }

    private fun repository(
        gateway: MessageDraftGateway,
        ioDispatcher: CoroutineDispatcher,
    ) = MessageDraftRepository(
        gateway = gateway,
        editorSessions = EditorSessionStore(TestStringStore()),
        ioDispatcher = ioDispatcher,
    )

    private fun dictationController(
        platform: DraftDictationPlatform,
        cache: MutableMap<Pair<String, String>, TextFieldValue>,
        writer: CoalescingMessageDraftWriter,
    ) = ConversationDictationController(
        platform = platform,
        readDraft = { account, group ->
            ConversationDictationDraftSnapshot(
                cache.getValue(account to group),
                writer.generation(account, group).value,
            )
        },
        writeDraft = { account, group, expectedRevision, value ->
            writer
                .submitIfCurrent(
                    accountRef = account,
                    groupIdHex = group,
                    expected = MessageDraftGeneration(expectedRevision),
                    content = value.text,
                )?.let {
                    cache[account to group] = value
                    true
                } ?: false
        },
        disclosureAccepted = { true },
        markDisclosureAccepted = {},
        scheduleTimeout = { _, _ -> ConversationDictationTimeoutHandle {} },
    )

    private companion object {
        const val ACCOUNT = "account"
        const val GROUP = "group"
        const val OTHER_ACCOUNT = "other-account"
        const val OTHER_GROUP = "other-group"
    }
}

private class DraftDictationPlatform : ConversationDictationPlatform {
    lateinit var listener: ConversationDictationRecognitionListener

    override fun hasRecordAudioPermission() = true

    override fun recognitionAvailable() = true

    @Suppress("MaxLineLength")
    override fun createSession(listener: ConversationDictationRecognitionListener): ConversationDictationRecognitionSession {
        this.listener = listener
        return object : ConversationDictationRecognitionSession {
            override fun start() = Unit

            override fun stop() = Unit

            override fun cancel() = Unit

            override fun destroy() = Unit
        }
    }
}

private data object NoOpDraftPersistence : DraftPersistence {
    override fun read(): Map<String, String> = emptyMap()

    override fun write(
        key: String,
        value: String?,
    ) = Unit
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
