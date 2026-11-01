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
import dev.ipf.whitenoise.android.audio.ConversationDictationTargetValidation
import dev.ipf.whitenoise.android.audio.ConversationDictationTimeoutHandle
import dev.ipf.whitenoise.android.state.ComposerDraftExpansionBridge
import dev.ipf.whitenoise.android.state.ComposerExpansionStateRetention
import dev.ipf.whitenoise.android.state.DraftPersistence
import dev.ipf.whitenoise.android.state.DraftStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MessageDraftDictationWriteTest {
    /** Unchanged empty-field caret and IME updates keep an explicit dictation Send eligible. */
    @Test
    fun emptyPresentationUpdatesKeepDictationSendEligible() =
        runTest {
            val gateway = KeyedDraftGateway(mutableMapOf())
            val repository = repository(gateway, UnconfinedTestDispatcher(testScheduler))
            val writer = CoalescingMessageDraftWriter(this, repository, debounceMillis = 0)
            val store = DraftStore(NoOpDraftPersistence)
            val bridge = draftBridge(writer, store, repository)
            val captured = writer.generation(ACCOUNT, GROUP)
            val sent = mutableListOf<String>()
            val platform = DraftDictationPlatform()
            val controller = sendingDictationController(platform, store, writer, bridge, sent)
            controller.requestStart(ACCOUNT, GROUP, TextFieldValue(""))

            bridge.setDraft(ACCOUNT, GROUP, TextFieldValue("", TextRange(0)))
            bridge.setDraft(ACCOUNT, GROUP, TextFieldValue("", composition = TextRange(0)))
            controller.send()
            platform.listener.onResult("dictated")
            advanceUntilIdle()
            writer.flush()

            assertEquals(listOf("dictated"), sent)
            assertEquals("", store.get(ACCOUNT, GROUP).orEmpty())
            // Only dispatch's conditional clear may advance the captured generation.
            assertEquals(captured.value + 1, writer.generation(ACCOUNT, GROUP).value)
        }

    /** An unchanged empty caption neither mutates an attachment-only draft nor advances its generation. */
    @Test
    fun emptyPresentationUpdatePreservesAttachmentOnlyDraft() =
        runTest {
            val media = attachment("kept", byteArrayOf(1))
            val gateway = KeyedDraftGateway(mutableMapOf((ACCOUNT to GROUP) to draft(GROUP, "", listOf(media))))
            val repository = repository(gateway, UnconfinedTestDispatcher(testScheduler))
            val writer = CoalescingMessageDraftWriter(this, repository, debounceMillis = 0)
            val store = DraftStore(NoOpDraftPersistence)
            val bridge = draftBridge(writer, store, repository)
            val captured = writer.generation(ACCOUNT, GROUP)

            store.replaceSummaries(ACCOUNT, mapOf(GROUP to 123_000L))
            bridge.setDraft(ACCOUNT, GROUP, TextFieldValue(""))
            writer.flush()

            assertEquals(captured, writer.generation(ACCOUNT, GROUP))
            assertEquals(null, store.getDraft(ACCOUNT, GROUP))
            assertEquals(123uL, store.draftedAtSecondsFor(ACCOUNT, GROUP))
            assertEquals(listOf(media), gateway.values.getValue(ACCOUNT to GROUP).mediaAttachments)
        }

    /** Deleting an omitted whitespace caption still saves empty text and fences stale producers. */
    @Test
    fun deletingWhitespaceCaptionPreservesAttachmentsAndInvalidatesGeneration() =
        runTest {
            val media = attachment("kept", byteArrayOf(1))
            val gateway = KeyedDraftGateway(mutableMapOf((ACCOUNT to GROUP) to draft(GROUP, "", listOf(media))))
            val repository = repository(gateway, UnconfinedTestDispatcher(testScheduler))
            val writer = CoalescingMessageDraftWriter(this, repository, debounceMillis = 0)
            val store = DraftStore(NoOpDraftPersistence)
            val bridge = draftBridge(writer, store, repository)
            bridge.setDraft(ACCOUNT, GROUP, TextFieldValue(" "))
            writer.flush()
            assertEquals(null, store.getDraft(ACCOUNT, GROUP))
            assertEquals(" ", gateway.values.getValue(ACCOUNT to GROUP).content)
            val added = attachment("added", byteArrayOf(2))
            repository.addAttachment(ACCOUNT, GROUP, added)
            val captured = writer.generation(ACCOUNT, GROUP)

            bridge.setDraft(ACCOUNT, GROUP, TextFieldValue(""))
            writer.flush()

            assertEquals(captured.value + 1, writer.generation(ACCOUNT, GROUP).value)
            assertEquals("", gateway.values.getValue(ACCOUNT to GROUP).content)
            assertEquals(listOf(media, added), gateway.values.getValue(ACCOUNT to GROUP).mediaAttachments)
            assertFalse(bridge.setDraftIfCurrent(ACCOUNT, GROUP, captured.value, TextFieldValue("stale")))
            val cleared = writer.generation(ACCOUNT, GROUP)
            bridge.setDraft(ACCOUNT, GROUP, TextFieldValue("", composition = TextRange(0)))
            assertEquals(cleared, writer.generation(ACCOUNT, GROUP))
        }

    /** Send presentation and durable cleanup retire omitted whitespace before later empty callbacks. */
    @Test
    fun emptyCallbacksAfterWhitespaceSendKeepPendingAndCleanupGenerations() =
        runTest {
            for (hideBeforeCleanup in listOf(false, true)) {
                val gateway = KeyedDraftGateway(mutableMapOf())
                val repository = repository(gateway, UnconfinedTestDispatcher(testScheduler))
                val writer = CoalescingMessageDraftWriter(this, repository, debounceMillis = 0)
                val store = DraftStore(NoOpDraftPersistence)
                val bridge = draftBridge(writer, store, repository)
                bridge.setDraft(ACCOUNT, GROUP, TextFieldValue(" "))
                val token = requireNotNull(bridge.captureForSend(ACCOUNT, GROUP))
                if (hideBeforeCleanup) {
                    assertTrue(bridge.hideForPendingSend(token))
                    bridge.setDraft(ACCOUNT, GROUP, TextFieldValue(""))
                    assertEquals(token.generation, writer.generation(ACCOUNT, GROUP))
                }
                bridge.clearAfterDurableAcceptance(token)
                advanceUntilIdle()
                val cleaned = writer.generation(ACCOUNT, GROUP)
                assertEquals(token.generation.value + 1, cleaned.value)

                bridge.setDraft(ACCOUNT, GROUP, TextFieldValue(""))

                assertEquals(cleaned, writer.generation(ACCOUNT, GROUP))
            }
        }

    /** Reopening a normalized whitespace draft cannot turn an unchanged empty callback into an edit. */
    @Test
    fun acceptedHydrationRetiresOmittedWhitespacePresentation() =
        runTest {
            val media = attachment("kept", byteArrayOf(1))
            val gateway = KeyedDraftGateway(mutableMapOf((ACCOUNT to GROUP) to draft(GROUP, "", listOf(media))))
            val repository = repository(gateway, UnconfinedTestDispatcher(testScheduler))
            val writer = CoalescingMessageDraftWriter(this, repository, debounceMillis = 0)
            val store = DraftStore(NoOpDraftPersistence)
            val bridge = draftBridge(writer, store, repository)
            bridge.setDraft(ACCOUNT, GROUP, TextFieldValue(" "))
            writer.flush()
            val captured = writer.generation(ACCOUNT, GROUP)
            val loaded = requireNotNull(writer.loadIfCurrent(ACCOUNT, GROUP, captured)).getOrThrow()
            assertTrue(
                writer.runHydrationIfCurrent(ACCOUNT, GROUP, captured) {
                    bridge.replaceHydratedDraft(ACCOUNT, GROUP, loaded?.content, loaded?.updatedAtMs)
                },
            )
            assertEquals(null, store.getDraft(ACCOUNT, GROUP))

            bridge.setDraft(ACCOUNT, GROUP, TextFieldValue("", composition = TextRange(0)))

            assertEquals(captured, writer.generation(ACCOUNT, GROUP))
            assertEquals(" ", gateway.values.getValue(ACCOUNT to GROUP).content)
            assertEquals(listOf(media), gateway.values.getValue(ACCOUNT to GROUP).mediaAttachments)
        }

    /** A real text edit reverted to empty still rejects a producer holding the original generation. */
    @Test
    fun editingTextBackToEmptyStillInvalidatesCapturedGeneration() =
        runTest {
            val gateway = KeyedDraftGateway(mutableMapOf())
            val repository = repository(gateway, UnconfinedTestDispatcher(testScheduler))
            val writer = CoalescingMessageDraftWriter(this, repository, debounceMillis = 0)
            val store = DraftStore(NoOpDraftPersistence)
            val bridge = draftBridge(writer, store, repository)
            val captured = writer.generation(ACCOUNT, GROUP)

            bridge.setDraft(ACCOUNT, GROUP, TextFieldValue("Changed"))
            bridge.setDraft(ACCOUNT, GROUP, TextFieldValue(""))
            writer.flush()

            assertEquals(captured.value + 2, writer.generation(ACCOUNT, GROUP).value)
            assertFalse(bridge.setDraftIfCurrent(ACCOUNT, GROUP, captured.value, TextFieldValue("stale send")))
            assertEquals(null, store.getDraft(ACCOUNT, GROUP))
        }

    /** Empty callbacks on a hidden pending-send draft preserve recovery after a definite failure. */
    @Test
    fun emptyPresentationUpdateKeepsPendingSendFailureRecoverable() =
        runTest {
            val media = attachment("kept", byteArrayOf(1))
            val gateway = KeyedDraftGateway(mutableMapOf((ACCOUNT to GROUP) to draft(GROUP, "caption", listOf(media))))
            val repository = repository(gateway, UnconfinedTestDispatcher(testScheduler))
            val writer = CoalescingMessageDraftWriter(this, repository, debounceMillis = 0)
            val store = DraftStore(NoOpDraftPersistence, nowSeconds = { 10L })
            val changes = mutableListOf<Triple<String, String, Boolean>>()
            val bridge =
                draftBridge(writer, store, repository) { account, group, consumed ->
                    changes += Triple(account, group, consumed)
                }
            val original = TextFieldValue("caption", TextRange(2))
            bridge.setDraft(ACCOUNT, GROUP, original)
            writer.flush()
            val nativeDraft = repository.draft(ACCOUNT, GROUP).getOrThrow()
            val token = requireNotNull(bridge.captureForSend(ACCOUNT, GROUP))
            assertTrue(bridge.hideForPendingSend(token))
            assertEquals(null, store.getDraft(ACCOUNT, GROUP))
            store.applyAuthoritativeTimestamp(ACCOUNT, GROUP, 20_000L)

            bridge.setDraft(ACCOUNT, GROUP, TextFieldValue("", composition = TextRange(0)))
            writer.flush()
            // A summary arriving after the callback must keep the pending send hidden
            // and must not replace the newer authoritative timestamp kept for recovery.
            store.replaceSummaries(ACCOUNT, mapOf(GROUP to 15_000L))
            assertEquals(null, store.draftedAtSecondsFor(ACCOUNT, GROUP))
            bridge.restoreAfterTerminalFailure(token)

            assertEquals(token.generation, writer.generation(ACCOUNT, GROUP))
            assertEquals(original, store.getDraft(ACCOUNT, GROUP)?.textFieldValue)
            assertEquals(20uL, store.draftedAtSecondsFor(ACCOUNT, GROUP))
            assertEquals(nativeDraft, repository.draft(ACCOUNT, GROUP).getOrThrow())
            assertEquals(listOf(Triple(ACCOUNT, GROUP, false)), changes)
        }

    /** A newer edit reverted to empty must not restore the earlier hidden pending-send draft. */
    @Test
    fun editsBackToEmptyPreventPendingSendFailureRestoration() =
        runTest {
            val gateway = KeyedDraftGateway(mutableMapOf((ACCOUNT to GROUP) to draft(GROUP, "caption")))
            val repository = repository(gateway, UnconfinedTestDispatcher(testScheduler))
            val writer = CoalescingMessageDraftWriter(this, repository, debounceMillis = 0)
            val store = DraftStore(NoOpDraftPersistence)
            val changes = mutableListOf<Triple<String, String, Boolean>>()
            val bridge =
                draftBridge(writer, store, repository) { account, group, consumed ->
                    changes += Triple(account, group, consumed)
                }
            bridge.setDraft(ACCOUNT, GROUP, TextFieldValue("caption"))
            writer.flush()
            val token = requireNotNull(bridge.captureForSend(ACCOUNT, GROUP))
            assertTrue(bridge.hideForPendingSend(token))

            bridge.setDraft(ACCOUNT, GROUP, TextFieldValue("newer"))
            bridge.setDraft(ACCOUNT, GROUP, TextFieldValue(""))
            writer.flush()
            bridge.restoreAfterTerminalFailure(token)

            assertEquals(token.generation.value + 2, writer.generation(ACCOUNT, GROUP).value)
            assertEquals(null, store.getDraft(ACCOUNT, GROUP))
            assertEquals(null, repository.draft(ACCOUNT, GROUP).getOrThrow())
            assertTrue(changes.isEmpty())
        }

    /** Caret movement must not turn an explicit Send into the draft-conflict Paste fallback. */
    @Test
    fun cursorOnlyUpdatesKeepDictationSendEligible() =
        runTest {
            val gateway = KeyedDraftGateway(mutableMapOf((ACCOUNT to GROUP) to draft(GROUP, "Origin ")))
            val repository = repository(gateway, UnconfinedTestDispatcher(testScheduler))
            val writer = CoalescingMessageDraftWriter(this, repository, debounceMillis = 0)
            val store = DraftStore(NoOpDraftPersistence)
            val bridge = draftBridge(writer, store, repository)
            bridge.setDraft(ACCOUNT, GROUP, TextFieldValue("Origin ", TextRange(7)))
            writer.flush()
            val captured = writer.generation(ACCOUNT, GROUP)
            val sent = mutableListOf<String>()
            val platform = DraftDictationPlatform()
            val controller = sendingDictationController(platform, store, writer, bridge, sent)
            controller.requestStart(ACCOUNT, GROUP, checkNotNull(store.getDraft(ACCOUNT, GROUP)).textFieldValue)

            bridge.setDraft(ACCOUNT, GROUP, TextFieldValue("Origin ", TextRange(0)))
            bridge.setDraft(ACCOUNT, GROUP, TextFieldValue("Origin ", TextRange(0), composition = TextRange(0, 6)))
            assertEquals(captured, writer.generation(ACCOUNT, GROUP))
            assertEquals(TextRange(0), store.getDraft(ACCOUNT, GROUP)?.textFieldValue?.selection)
            controller.send()
            platform.listener.onResult("dictated")
            advanceUntilIdle()
            writer.flush()

            assertEquals(listOf("Origin dictated"), sent)
            assertEquals("", store.get(ACCOUNT, GROUP).orEmpty())
            // Only dispatch's intentional empty write advances the generation.
            assertEquals(captured.value + 1, writer.generation(ACCOUNT, GROUP).value)
        }

    /** Real edits, including changing back to the original text, still invalidate a captured send. */
    @Test
    fun textEditsStillAdvanceTheGenerationEvenWhenRestoringOriginalContent() =
        runTest {
            val gateway = KeyedDraftGateway(mutableMapOf((ACCOUNT to GROUP) to draft(GROUP, "Origin")))
            val repository = repository(gateway, UnconfinedTestDispatcher(testScheduler))
            val writer = CoalescingMessageDraftWriter(this, repository, debounceMillis = 0)
            val store = DraftStore(NoOpDraftPersistence)
            val bridge = draftBridge(writer, store, repository)
            bridge.setDraft(ACCOUNT, GROUP, TextFieldValue("Origin", TextRange(6)))
            val captured = writer.generation(ACCOUNT, GROUP)

            bridge.setDraft(ACCOUNT, GROUP, TextFieldValue("Changed"))
            bridge.setDraft(ACCOUNT, GROUP, TextFieldValue("Origin"))
            writer.flush()

            assertEquals(captured.value + 2, writer.generation(ACCOUNT, GROUP).value)
            assertFalse(bridge.setDraftIfCurrent(ACCOUNT, GROUP, captured.value, TextFieldValue("stale send")))
            assertEquals("Origin", store.get(ACCOUNT, GROUP))
        }

    /** Cursor updates cannot erase an attachment mutation's stale-send fence or native media. */
    @Test
    fun selectionOnlyUpdatePreservesAttachmentMutationGeneration() =
        runTest {
            val gateway = KeyedDraftGateway(mutableMapOf((ACCOUNT to GROUP) to draft(GROUP, "Origin")))
            val repository = repository(gateway, UnconfinedTestDispatcher(testScheduler))
            val writer = CoalescingMessageDraftWriter(this, repository, debounceMillis = 0)
            val store = DraftStore(NoOpDraftPersistence)
            val bridge = draftBridge(writer, store, repository)
            bridge.setDraft(ACCOUNT, GROUP, TextFieldValue("Origin", TextRange(6)))
            writer.flush()
            val captured = writer.generation(ACCOUNT, GROUP)
            val media = attachment("new-media", byteArrayOf(1))
            repository.addAttachment(ACCOUNT, GROUP, media)
            val withMedia = writer.generation(ACCOUNT, GROUP)

            bridge.setDraft(ACCOUNT, GROUP, TextFieldValue("Origin", TextRange(0, 6)))
            writer.flush()

            assertEquals(withMedia, writer.generation(ACCOUNT, GROUP))
            assertFalse(bridge.setDraftIfCurrent(ACCOUNT, GROUP, captured.value, TextFieldValue("stale send")))
            assertEquals(TextRange(0, 6), store.getDraft(ACCOUNT, GROUP)?.textFieldValue?.selection)
            assertEquals(listOf(media), gateway.values.getValue(ACCOUNT to GROUP).mediaAttachments)
        }

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

    /** Mounted and reopened shelves get an authoritative refresh after successful draft deletion. */
    @Test
    fun durableCleanupPublishesADraftPresentationChange() =
        runTest {
            val gateway = KeyedDraftGateway(mutableMapOf((ACCOUNT to GROUP) to draft(GROUP, "caption")))
            val repository = repository(gateway, UnconfinedTestDispatcher(testScheduler))
            val writer = CoalescingMessageDraftWriter(this, repository, debounceMillis = 0)
            val store = DraftStore(NoOpDraftPersistence)
            val changes = mutableListOf<Triple<String, String, Boolean>>()
            val bridge =
                draftBridge(writer, store, repository) { account, group, consumed ->
                    changes += Triple(account, group, consumed)
                }
            bridge.setDraft(ACCOUNT, GROUP, TextFieldValue("caption"))
            writer.flush()
            val token = requireNotNull(bridge.captureForSend(ACCOUNT, GROUP))

            bridge.clearAfterDurableAcceptance(token)
            advanceUntilIdle()

            assertEquals(listOf(Triple(ACCOUNT, GROUP, true)), changes)
            assertEquals(null, repository.draft(ACCOUNT, GROUP).getOrThrow())
        }

    private fun repository(
        gateway: MessageDraftGateway,
        ioDispatcher: CoroutineDispatcher,
    ) = MessageDraftRepository(
        gateway = gateway,
        editorSessions = EditorSessionStore(TestStringStore()),
        ioDispatcher = ioDispatcher,
    )

    private fun CoroutineScope.draftBridge(
        writer: CoalescingMessageDraftWriter,
        store: DraftStore,
        repository: MessageDraftRepository,
        onDraftPresentationChanged: (String, String, Boolean) -> Unit = { _, _, _ -> },
    ) = ComposerDraftExpansionBridge(
        draftWriter = writer,
        draftStore = store,
        draftRepository = repository,
        expansionRetention = ComposerExpansionStateRetention(),
        scope = this,
        onDraftPresentationChanged = onDraftPresentationChanged,
        onCleanupFailure = { _, cause -> throw cause },
    )

    /** Drives the real draft bridge and records only sends admitted by the controller's dispatch guard. */
    private fun CoroutineScope.sendingDictationController(
        platform: DraftDictationPlatform,
        store: DraftStore,
        writer: CoalescingMessageDraftWriter,
        bridge: ComposerDraftExpansionBridge,
        sent: MutableList<String>,
    ) = ConversationDictationController(
        platform = platform,
        readDraft = { account, group ->
            ConversationDictationDraftSnapshot(
                store.getDraft(account, group)?.textFieldValue ?: TextFieldValue(""),
                writer.generation(account, group).value,
            )
        },
        writeDraft = bridge::writeDraftIfCurrent,
        disclosureAccepted = { true },
        markDisclosureAccepted = {},
        scheduleTimeout = { _, _ -> ConversationDictationTimeoutHandle {} },
        targetValidationScope = this,
        targetValidator = { _, _ -> ConversationDictationTargetValidation.Available },
        sendTranscriptIfOriginUnchanged = { request ->
            if (request.beginDispatch()) {
                sent += request.payload
                true
            } else {
                false
            }
        },
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
                    it.value
                }
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
