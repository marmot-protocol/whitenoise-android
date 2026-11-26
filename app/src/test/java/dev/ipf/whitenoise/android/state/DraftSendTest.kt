package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.EncryptedMediaVersionFfi
import dev.ipf.marmotkit.LocalSendStatusFfi
import dev.ipf.marmotkit.Marmot
import dev.ipf.marmotkit.MarmotInterface
import dev.ipf.marmotkit.MarmotKitException
import dev.ipf.marmotkit.MediaAttachmentReferenceFfi
import dev.ipf.marmotkit.MessageDraftAttachmentInputFfi
import dev.ipf.marmotkit.MessageDraftFfi
import dev.ipf.marmotkit.MessageDraftRevisionFfi
import dev.ipf.marmotkit.NoPointer
import dev.ipf.marmotkit.SelectedMessageDraftAttachmentFfi
import dev.ipf.marmotkit.SelectedMessageDraftContentFfi
import dev.ipf.marmotkit.SelectedMessageDraftFfi
import dev.ipf.marmotkit.SendAcceptDispositionFfi
import dev.ipf.marmotkit.SendMaintenanceDispositionFfi
import dev.ipf.marmotkit.SendSummaryFfi
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DraftSendTest {
    /** A text send publishes with one engine call and reads no draft on the way. */
    @Test
    fun textSendPublishesWithASingleEngineCall() =
        runBlocking {
            val engine = ScriptedEngine(draft = content("typed so fa", attachments = emptyList()))
            val summary = engine.marmot.sendComposerText("acct", "group", null, "typed so far")
            assertEquals(listOf("sendText"), engine.calls)
            assertEquals("sendText", summary.messageIds.single())
        }

    /** A reply send takes the same single-call path through the reply entry point. */
    @Test
    fun replySendPublishesWithASingleEngineCall() =
        runBlocking {
            val engine = ScriptedEngine(draft = content("hello", attachments = emptyList(), replyTo = "parent"))
            engine.marmot.sendComposerText("acct", "group", "parent", "hello")
            assertEquals(listOf("replyToMessage"), engine.calls)
        }

    /** A staged-media draft does not divert a text send into the revision route. */
    @Test
    fun textSendIgnoresAStagedMediaDraft() =
        runBlocking {
            val engine = ScriptedEngine(draft = content("caption", attachments = listOf(jpeg("a.jpg"))))
            engine.marmot.sendComposerText("acct", "group", null, "caption")
            assertEquals(listOf("sendText"), engine.calls)
        }

    /** A text send never writes the draft, so a revision conflict cannot arise on this path. */
    @Test
    fun textSendNeverWritesTheDraft() =
        runBlocking {
            val engine = ScriptedEngine(draft = content("x", attachments = emptyList()), conflictOnSave = true)
            engine.marmot.sendComposerText("acct", "group", "parent", "y")
            assertEquals(listOf("replyToMessage"), engine.calls)
            assertEquals(null, engine.savedContent)
        }

    /** Media sends submit the revision only when every uploaded reference matches a descriptor in order. */
    @Test
    fun mediaSendRequiresMatchingDescriptors() =
        runBlocking {
            val matching = ScriptedEngine(draft = content("cap", attachments = listOf(jpeg("a.jpg"))))
            matching.marmot.sendComposerMedia("acct", "group", listOf(reference("a.jpg", "image/jpeg")), "cap")
            assertEquals(listOf("selectedMessageDraft", "sendMessageDraft"), matching.calls)

            val mismatched = ScriptedEngine(draft = content("cap", attachments = listOf(jpeg("a.jpg"))))
            mismatched.marmot.sendComposerMedia("acct", "group", listOf(reference("b.pdf", "application/pdf")), "cap")
            assertEquals(listOf("selectedMessageDraft", "sendMediaAttachments"), mismatched.calls)
        }

    /** A caption change before a draft send keeps every attachment by id, a stored file included, and reads no body. */
    @Test
    fun captionSaveKeepsEveryAttachmentById() =
        runBlocking {
            val stored = descriptor("clip.mp4", "video/mp4").copy(fileBacked = true)
            val engine = ScriptedEngine(draft = content("old", attachments = listOf(jpeg("a.jpg"), stored)))
            val references = listOf(reference("a.jpg", "image/jpeg"), reference("clip.mp4", "video/mp4"))

            engine.marmot.sendComposerMedia("acct", "group", references, "new caption")

            assertEquals(
                listOf("selectedMessageDraft", "saveMessageDraftInputsIfRevision", "sendMessageDraft"),
                engine.calls,
            )
            assertEquals("new caption", engine.savedContent)
            assertEquals(
                listOf(
                    MessageDraftAttachmentInputFfi.Retained("a.jpg"),
                    MessageDraftAttachmentInputFfi.Retained("clip.mp4"),
                ),
                engine.savedInputs,
            )
        }

    /** A draft whose media references MDK refuses, stale or unsettled epoch included, falls back to a direct send. */
    @Test
    fun refusedMediaReferencesFallBackToDirectSend() =
        runBlocking {
            for (refusal in listOf(
                MarmotKitException.InvalidMediaReference("synthetic"),
                MarmotKitException.MediaReferenceStaleEpoch(sourceEpoch = 3uL, currentEpoch = 4uL),
                MarmotKitException.MediaReferenceEpochUnsettled(sourceEpoch = 3uL),
            )) {
                val draft = content("cap", attachments = listOf(jpeg("a.jpg")))
                val engine = ScriptedEngine(draft = draft, draftSendFailure = refusal)
                engine.marmot.sendComposerMedia("acct", "group", listOf(reference("a.jpg", "image/jpeg")), "cap")
                assertEquals(
                    listOf("selectedMessageDraft", "sendMessageDraft", "sendMediaAttachments"),
                    engine.calls,
                )
            }
        }

    /** Any other draft-send failure reaches the caller instead of being retried as a direct send. */
    @Test
    fun otherDraftSendFailuresPropagate() =
        runBlocking {
            val engine =
                ScriptedEngine(
                    draft = content("cap", attachments = listOf(jpeg("a.jpg"))),
                    draftSendFailure = MarmotKitException.MediaUnfetchable("synthetic"),
                )
            val failure =
                runCatching {
                    engine.marmot.sendComposerMedia("acct", "group", listOf(reference("a.jpg", "image/jpeg")), "cap")
                }.exceptionOrNull()

            assertTrue(failure is MarmotKitException.MediaUnfetchable)
            assertFalse("sendMediaAttachments" in engine.calls)
        }

    /** Tokenized retries fail closed when their draft is missing instead of publishing a duplicate. */
    @Test
    fun tokenizedMediaRetryNeverFallsBackToUnkeyedSend() =
        runBlocking {
            for (engine in listOf(
                ScriptedEngine(draft = content("cap", attachments = listOf(jpeg("b.jpg")))),
                ScriptedEngine(
                    draft = content("cap", attachments = listOf(jpeg("a.jpg"))),
                    conflictOnSave = true,
                ),
            )) {
                val failure =
                    runCatching {
                        engine.marmot.sendComposerMedia(
                            "acct",
                            "group",
                            listOf(reference("a.jpg", "image/jpeg")),
                            "changed caption",
                            clientToken = "stable-token",
                        )
                    }.exceptionOrNull()

                assertTrue(failure is IllegalStateException)
                assertFalse("sendMediaAttachments" in engine.calls)
            }
        }

    /** The descriptor match is positional and covers name and media type. */
    @Test
    fun draftDescribesChecksOrderNameAndType() {
        val draft = content("c", attachments = listOf(jpeg("a.jpg"), descriptor("b.pdf", "application/pdf")))
        val photo = reference("a.jpg", "image/jpeg")
        val document = reference("b.pdf", "application/pdf")
        assertTrue(draftDescribes(draft, listOf(photo, document)))
        assertFalse(draftDescribes(draft, listOf(document, photo)))
        assertFalse(draftDescribes(draft, listOf(reference("a.jpg", "image/jpeg"))))
    }
}

/** Engine stub allocated without its native constructor; overrides record the draft and send calls the helpers make. */
private class ScriptedEngine private constructor() : Marmot(NoPointer) {
    // Allocated without a constructor, so every field is assigned in [invoke] rather than by an initialiser.
    private var draft: SelectedMessageDraftContentFfi? = null
    private var conflictOnSave = false
    private var draftSendFailure: MarmotKitException? = null
    lateinit var calls: MutableList<String>
    var savedContent: String? = null
    var savedInputs: List<MessageDraftAttachmentInputFfi>? = null
    private lateinit var revision: MessageDraftRevisionFfi

    val marmot: MarmotInterface get() = this

    /** Reports no prior native ownership so tokenized tests exercise admission routing. */
    override fun localSendStatus(
        accountRef: String,
        groupIdHex: String,
        clientToken: String,
    ): LocalSendStatusFfi? {
        calls += "localSendStatus"
        return null
    }

    /** Records the read so a send's full engine-call sequence, not just its writes, is assertable. */
    override fun selectedMessageDraft(
        accountRef: String,
        groupIdHex: String,
    ): SelectedMessageDraftFfi {
        calls += "selectedMessageDraft"
        return SelectedMessageDraftFfi(revision, draft)
    }

    /** A send never re-reads attachment bodies: MDK refuses that whole read once a draft holds a stored file. */
    override fun messageDraft(
        accountRef: String,
        groupIdHex: String,
    ): MessageDraftFfi? = throw AssertionError("a draft send must not hydrate attachment bodies")

    /** Records the caption save and the inputs it keeps, by id, without copying a body. */
    override fun saveMessageDraftInputsIfRevision(
        accountRef: String,
        revision: MessageDraftRevisionFfi,
        content: String,
        replyToMessageIdHex: String?,
        mediaAttachments: List<MessageDraftAttachmentInputFfi>,
    ): SelectedMessageDraftFfi {
        calls += "saveMessageDraftInputsIfRevision"
        if (conflictOnSave) throw MarmotKitException.MessageDraftRevisionConflict()
        savedInputs = mediaAttachments
        savedContent = content
        val updated = draft?.copy(content = content, replyToMessageIdHex = replyToMessageIdHex)
        return SelectedMessageDraftFfi(this.revision, updated)
    }

    override suspend fun sendMessageDraft(
        accountRef: String,
        revision: MessageDraftRevisionFfi,
        attachments: List<MediaAttachmentReferenceFfi>,
    ): SendSummaryFfi {
        draftSendFailure?.let {
            calls += "sendMessageDraft"
            throw it
        }
        return record("sendMessageDraft")
    }

    override suspend fun sendText(
        accountRef: String,
        groupIdHex: String,
        text: String,
    ): SendSummaryFfi = record("sendText")

    override suspend fun replyToMessage(
        accountRef: String,
        groupIdHex: String,
        targetMessageId: String,
        text: String,
    ): SendSummaryFfi = record("replyToMessage")

    override suspend fun sendMediaAttachments(
        accountRef: String,
        groupIdHex: String,
        attachments: List<MediaAttachmentReferenceFfi>,
        caption: String?,
    ): SendSummaryFfi = record("sendMediaAttachments")

    private fun record(source: String): SendSummaryFfi {
        calls += source
        return SendSummaryFfi(
            published = 1u,
            messageIds = listOf(if (source == "sendMessageDraft") "draft" else source),
            acceptDisposition = SendAcceptDispositionFfi.PUBLISHED,
            maintenanceDisposition = SendMaintenanceDispositionFfi.READY,
        )
    }

    companion object {
        /** Builds a stub without running the UniFFI constructor, then scripts it. */
        operator fun invoke(
            draft: SelectedMessageDraftContentFfi?,
            conflictOnSave: Boolean = false,
            draftSendFailure: MarmotKitException? = null,
        ): ScriptedEngine =
            nativeStub(ScriptedEngine::class.java).apply {
                this.draft = draft
                this.conflictOnSave = conflictOnSave
                this.draftSendFailure = draftSendFailure
                this.calls = mutableListOf()
                this.revision = nativeStub(MessageDraftRevisionFfi::class.java)
            }
    }
}

private fun content(
    text: String,
    attachments: List<SelectedMessageDraftAttachmentFfi>,
    replyTo: String? = null,
) = SelectedMessageDraftContentFfi("group", text, replyTo, attachments, 1L, 1L)

private fun jpeg(fileName: String) = descriptor(fileName, "image/jpeg")

private fun descriptor(
    fileName: String,
    mediaType: String,
) = SelectedMessageDraftAttachmentFfi(fileName, fileName, mediaType, 1uL, null, null, null, emptyList())

private fun reference(
    fileName: String,
    mediaType: String,
) = MediaAttachmentReferenceFfi(
    locators = emptyList(),
    ciphertextSha256 = "",
    plaintextSha256 = "",
    nonceHex = "",
    fileName = fileName,
    mediaType = mediaType,
    version = EncryptedMediaVersionFfi.V1,
    sourceEpoch = 1uL,
    dim = null,
    thumbhash = null,
)

/** Allocates a native handle stub without its constructor, so records embedding one can be built in JVM tests. */
private fun <T> nativeStub(type: Class<T>): T {
    val unsafeClass = Class.forName("sun.misc.Unsafe")
    val field = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }
    val unsafe = field.get(null)
    @Suppress("UNCHECKED_CAST")
    return unsafeClass.getMethod("allocateInstance", Class::class.java).invoke(unsafe, type) as T
}
