package dev.ipf.whitenoise.android.media.editor

import dev.ipf.marmotkit.MarmotInterface
import dev.ipf.marmotkit.MarmotKitException
import dev.ipf.marmotkit.MediaFileTransferControlFfi
import dev.ipf.marmotkit.MessageDraftAttachmentFileRequestFfi
import dev.ipf.marmotkit.MessageDraftAttachmentInputFfi
import dev.ipf.marmotkit.MessageDraftFfi
import dev.ipf.marmotkit.MessageDraftRevisionFfi
import dev.ipf.marmotkit.SelectedMessageDraftAttachmentFfi
import dev.ipf.marmotkit.SelectedMessageDraftContentFfi
import dev.ipf.marmotkit.SelectedMessageDraftFfi
import dev.ipf.whitenoise.android.state.nativeBoundary

/**
 * An in-memory MarmotKit draft store for one chat that follows 0.18.0's rules for stored files: every write is
 * revision-checked, a whole-draft read is refused, an input left out of a save is deleted, and a stored file is
 * kept only by id. Records each native call so tests can pin the sequence.
 */
internal class FileBackedDraftNative(
    content: String = "",
) {
    private var revision = newRevision()
    private var text: String? = content
    private var descriptors = mutableListOf<SelectedMessageDraftAttachmentFfi>()
    private val bodies = mutableMapOf<String, ByteArray>()
    val calls = mutableListOf<String>()
    val savedInputs = mutableListOf<List<MessageDraftAttachmentInputFfi>>()
    val stagedRequests = mutableListOf<MessageDraftAttachmentFileRequestFfi>()
    var stageFailure: Exception? = null

    /** The ids the draft holds, in order. */
    val attachmentIds: List<String> get() = descriptors.map { it.id }

    /** The draft's current text, or null once it was cleared. */
    val content: String? get() = text

    /** Seeds an inline attachment with [body]. */
    fun holdInline(
        id: String,
        body: ByteArray,
    ) {
        descriptors += descriptor(id, body.size.toULong(), fileBacked = false)
        bodies[id] = body
        if (text == null) text = ""
    }

    /** Seeds a stored file of [size] bytes. */
    fun holdFile(
        id: String,
        size: ULong,
    ) {
        descriptors += descriptor(id, size, fileBacked = true)
        if (text == null) text = ""
    }

    /** Makes the next read miss one inline body, as when another writer moved the draft. */
    fun dropInlineBody(id: String) {
        bodies -= id
    }

    /** The draft calls answer from this store; any other binding call fails the test. */
    val marmot: MarmotInterface = Engine()

    /** Overrides throw MarmotKit's own exception types, which a reflective proxy would wrap. */
    private inner class Engine : MarmotInterface by unexpectedNativeCalls() {
        /** Returns the current descriptors and revision. */
        override fun selectedMessageDraft(
            accountRef: String,
            groupIdHex: String,
        ): SelectedMessageDraftFfi = record("selectedMessageDraft") { selected() }

        /** Refuses a whole-draft read, as MarmotKit does once a draft holds a stored file. */
        override fun messageDraft(
            accountRef: String,
            groupIdHex: String,
        ): MessageDraftFfi? =
            record("messageDraft") {
                throw MarmotKitException.InvalidMessageDraft("draft holds a file-backed attachment")
            }

        /** Returns one inline body while [revision] is current. */
        override fun messageDraftAttachmentIfRevision(
            accountRef: String,
            revision: MessageDraftRevisionFfi,
            attachmentId: String,
        ): ByteArray? =
            record("messageDraftAttachmentIfRevision") {
                requireCurrent(revision)
                bodies[attachmentId]
            }

        /** Applies an input save while [revision] is current. */
        override fun saveMessageDraftInputsIfRevision(
            accountRef: String,
            revision: MessageDraftRevisionFfi,
            content: String,
            replyToMessageIdHex: String?,
            mediaAttachments: List<MessageDraftAttachmentInputFfi>,
        ): SelectedMessageDraftFfi =
            record("saveMessageDraftInputsIfRevision") {
                requireCurrent(revision)
                save(content, mediaAttachments)
            }

        /** Clears the draft while [revision] is current. */
        override fun clearMessageDraftIfRevision(
            accountRef: String,
            revision: MessageDraftRevisionFfi,
        ): SelectedMessageDraftFfi =
            record("clearMessageDraftIfRevision") {
                requireCurrent(revision)
                clear()
            }

        /** Stores one file while [revision] is current. */
        override suspend fun stageMessageDraftAttachmentFile(
            accountRef: String,
            revision: MessageDraftRevisionFfi,
            request: MessageDraftAttachmentFileRequestFfi,
            control: MediaFileTransferControlFfi,
        ): SelectedMessageDraftFfi =
            record("stageMessageDraftAttachmentFile") {
                requireCurrent(revision)
                stage(request)
            }

        /** Records [method] before answering it. */
        private inline fun <T> record(
            method: String,
            block: () -> T,
        ): T {
            calls += method
            return block()
        }
    }

    /** Applies one input save: each input is a held id kept as it is, or a new inline body. */
    private fun save(
        content: String,
        inputs: List<MessageDraftAttachmentInputFfi>,
    ): SelectedMessageDraftFfi {
        savedInputs += inputs
        val held = descriptors.associateBy { it.id }
        val next =
            inputs.map { input ->
                when (input) {
                    is MessageDraftAttachmentInputFfi.Retained ->
                        held[input.id] ?: throw MarmotKitException.InvalidMessageDraft("unknown retained id")
                    is MessageDraftAttachmentInputFfi.Inline -> {
                        bodies[input.attachment.id] = input.attachment.plaintext
                        descriptor(
                            input.attachment.id,
                            input.attachment.plaintext.size
                                .toULong(),
                            fileBacked = false,
                        )
                    }
                }
            }
        (held.keys - next.map { it.id }.toSet()).forEach { bodies -= it }
        descriptors = next.toMutableList()
        text = content
        revision = newRevision()
        return selected()
    }

    /** Appends one stored file after a revision check, or fails as scripted. */
    private fun stage(request: MessageDraftAttachmentFileRequestFfi): SelectedMessageDraftFfi {
        stagedRequests += request
        stageFailure?.let { throw it }
        descriptors += descriptor(request.id, request.expectedSize ?: 0uL, fileBacked = true)
        if (text == null) text = ""
        revision = newRevision()
        return selected()
    }

    /** Empties the draft and moves its revision. */
    private fun clear(): SelectedMessageDraftFfi {
        text = null
        descriptors.clear()
        bodies.clear()
        revision = newRevision()
        return selected()
    }

    /** The draft as a descriptor-only selection. */
    private fun selected(): SelectedMessageDraftFfi =
        SelectedMessageDraftFfi(
            revision,
            text?.let { SelectedMessageDraftContentFfi("group", it, null, descriptors.toList(), 1L, 2L) },
        )

    /** Throws a revision conflict unless [candidate] is the current revision. */
    private fun requireCurrent(candidate: MessageDraftRevisionFfi) {
        if (candidate !== revision) throw MarmotKitException.MessageDraftRevisionConflict()
    }

    /** A descriptor for [id] with [size] bytes. */
    private fun descriptor(
        id: String,
        size: ULong,
        fileBacked: Boolean,
    ) = SelectedMessageDraftAttachmentFfi(
        id,
        "$id.bin",
        "application/octet-stream",
        size,
        null,
        null,
        null,
        emptyList(),
        fileBacked,
    )
}

/** Fails any binding call a draft test did not expect. */
private fun unexpectedNativeCalls(): MarmotInterface =
    nativeBoundary { method, _ ->
        error("unexpected native call $method")
    }

/** Allocates a native handle without its constructor, so records embedding one can be built in JVM tests. */
internal fun <T> nativeHandleStub(type: Class<T>): T {
    val unsafeClass = Class.forName("sun.misc.Unsafe")
    val unsafe = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
    @Suppress("UNCHECKED_CAST")
    return unsafeClass.getMethod("allocateInstance", Class::class.java).invoke(unsafe, type) as T
}

/** A fresh opaque revision handle. */
private fun newRevision(): MessageDraftRevisionFfi = nativeHandleStub(MessageDraftRevisionFfi::class.java)

/** An in-memory editor string store, enough for repositories whose tests do not edit photos. */
internal class InMemoryEditorStrings : EditorStringStore {
    private var values = linkedMapOf<String, String>()

    /** Returns every stored value. */
    override fun readAll(): Map<String, String>? = values.toMap()

    /** Replaces every stored value. */
    override fun replaceAll(values: Map<String, String>): Boolean {
        this.values = LinkedHashMap(values)
        return true
    }

    /** Removes every stored value. */
    override fun clear() = values.clear()
}
