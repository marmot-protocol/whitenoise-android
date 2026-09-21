package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.EncryptedMediaVersionFfi
import dev.ipf.marmotkit.LocalSendStatusFfi
import dev.ipf.marmotkit.Marmot
import dev.ipf.marmotkit.MarmotInterface
import dev.ipf.marmotkit.MarmotKitException
import dev.ipf.marmotkit.MediaAttachmentReferenceFfi
import dev.ipf.marmotkit.MessageDraftAttachmentFfi
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
    lateinit var calls: MutableList<String>
    var savedContent: String? = null
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

    /** Records the attachment re-read the revision save needs. */
    override fun messageDraft(
        accountRef: String,
        groupIdHex: String,
    ): MessageDraftFfi? {
        calls += "messageDraft"
        return draft?.let {
            MessageDraftFfi(
                it.groupIdHex,
                it.content,
                it.replyToMessageIdHex,
                it.mediaAttachments.map { d -> hydrated(d) },
                1L,
                1L,
            )
        }
    }

    override fun saveMessageDraftIfRevision(
        accountRef: String,
        revision: MessageDraftRevisionFfi,
        content: String,
        replyToMessageIdHex: String?,
        mediaAttachments: List<MessageDraftAttachmentFfi>,
    ): SelectedMessageDraftFfi {
        calls += "saveMessageDraftIfRevision"
        if (conflictOnSave) throw MarmotKitException.MessageDraftRevisionConflict()
        savedContent = content
        val updated = draft?.copy(content = content, replyToMessageIdHex = replyToMessageIdHex)
        return SelectedMessageDraftFfi(this.revision, updated)
    }

    override suspend fun sendMessageDraft(
        accountRef: String,
        revision: MessageDraftRevisionFfi,
        attachments: List<MediaAttachmentReferenceFfi>,
    ): SendSummaryFfi = record("sendMessageDraft")

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
        ): ScriptedEngine =
            nativeStub(ScriptedEngine::class.java).apply {
                this.draft = draft
                this.conflictOnSave = conflictOnSave
                this.calls = mutableListOf()
                this.revision = nativeStub(MessageDraftRevisionFfi::class.java)
            }
    }
}

/** Gives a descriptor one byte of plaintext so the hydrated draft carries attachments. */
private fun hydrated(descriptor: SelectedMessageDraftAttachmentFfi) =
    MessageDraftAttachmentFfi(
        id = descriptor.id,
        fileName = descriptor.fileName,
        mediaType = descriptor.mediaType,
        plaintext = byteArrayOf(1),
        dim = null,
        thumbhash = null,
        durationSeconds = null,
        waveformSamples = emptyList(),
    )

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
