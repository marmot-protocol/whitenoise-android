package dev.ipf.whitenoise.android.audio

import androidx.compose.ui.text.input.TextFieldValue
import java.text.BreakIterator
import java.util.Locale

internal const val DICTATION_DRAFT_WRITE_ATTEMPTS = 2

/** One logical recording owns only the draft write it installed, never later editor changes. */
internal class ConversationDictationDraftRecovery(
    private val read: (String, String) -> ConversationDictationDraftSnapshot,
    private val write: (String, String, Long, TextFieldValue) -> Long?,
) {
    private data class Receipt(
        val session: Long,
        val target: ConversationDictationTarget,
        val transcript: String,
        val acknowledgedPrefix: String,
        val draft: ConversationDictationDraftSnapshot,
        val sendEligible: Boolean,
        val base: TextFieldValue,
        val transcriptPrefix: String = "",
        val appendOnly: Boolean = false,
        val appendPayload: Boolean = false,
        val emptiedRevision: Long? = null,
    )

    private var receipt: Receipt? = null

    fun reset() {
        receipt = null
    }

    fun owns(
        session: Long,
        target: ConversationDictationTarget,
    ): Boolean = receipt?.let { it.session == session && it.target == target } == true

    /** Tracks only the clear or restoration actually performed by this session's dispatch. */
    fun updateDispatch(
        session: Long,
        target: ConversationDictationTarget,
        clearedRevision: Long? = null,
        restoredRevision: Long? = null,
    ) {
        val saved = receipt?.takeIf { owns(session, target) } ?: return
        if (clearedRevision != null) {
            receipt = saved.copy(emptiedRevision = clearedRevision)
        } else if (restoredRevision != null) {
            receipt = saved.copy(draft = saved.draft.copy(revision = restoredRevision), emptiedRevision = null)
        }
    }

    data class Options(
        val restoreCapturedPrefix: Boolean = false,
        val ownedEmptyRevision: Long? = null,
        val acknowledgedPrefix: String? = null,
    )

    private data class Insertion(
        val base: TextFieldValue,
        val prefix: String,
        val appendOnly: Boolean,
        val appendPayload: Boolean,
    )

    /** Repeated failures replace our unchanged insertion; newer edits receive only a new suffix. */
    fun recover(
        session: Long,
        target: ConversationDictationTarget,
        transcript: String,
        options: Options = Options(),
    ): Boolean {
        val text = transcript.trim()
        if (text.isEmpty()) return false
        repeat(DICTATION_DRAFT_WRITE_ATTEMPTS) {
            val previous = receipt?.takeIf { it.session == session && it.target == target }
            val current = runCatching { read(target.accountRef, target.groupIdHex) }.getOrNull() ?: return false
            if (previous?.transcript == text && previous.emptiedRevision == null) return true
            val unchanged = previous?.draft?.let { sameDraft(it, current) } == true
            val insertion = planInsertion(previous, current, text, options)
            val value = insertionValue(target, text, insertion)
            val revision =
                runCatching { write(target.accountRef, target.groupIdHex, current.revision, value) }
                    .getOrNull() ?: return@repeat
            val originalUnchanged = sameDraft(ConversationDictationDraftSnapshot(target.capturedDraft, target.capturedDraftRevision), current)
            val ownsEmpty = (previous?.emptiedRevision ?: options.ownedEmptyRevision) == current.revision && current.value.text.isEmpty()
            receipt =
                Receipt(
                    session,
                    target,
                    text,
                    options.acknowledgedPrefix ?: previous?.acknowledgedPrefix ?: text,
                    ConversationDictationDraftSnapshot(value, revision),
                    if (previous == null) originalUnchanged || ownsEmpty else (unchanged || ownsEmpty) && previous.sendEligible,
                    insertion.base,
                    insertion.prefix,
                    insertion.appendOnly,
                    insertion.appendPayload,
                )
            return true
        }
        return false
    }

    private fun planInsertion(
        previous: Receipt?,
        current: ConversationDictationDraftSnapshot,
        text: String,
        options: Options,
    ): Insertion {
        if (previous != null && sameDraft(previous.draft, current)) {
            return Insertion(previous.base, previous.transcriptPrefix, previous.appendOnly, previous.appendPayload)
        }
        val restoringPayload = previous?.emptiedRevision != null || options.restoreCapturedPrefix
        val prefix = if (previous != null && !restoringPayload) recognizedPrefix(previous, text) else ""
        return Insertion(current.value, prefix, previous != null, restoringPayload)
    }

    private fun recognizedPrefix(
        previous: Receipt,
        text: String,
    ): String {
        if (text.startsWith(previous.transcript)) return previous.transcript
        val acknowledged = previous.acknowledgedPrefix.takeIf { text.startsWith(it) }.orEmpty()
        val length = maxOf(acknowledged.length, commonWordPrefixLength(previous.transcript, text))
        // A completely rewritten result has no shared prefix to discard. Preserve it for review;
        // an edited draft is already ineligible for Retry Send.
        return text.take(length)
    }

    private fun insertionValue(
        target: ConversationDictationTarget,
        text: String,
        insertion: Insertion,
    ): TextFieldValue {
        val delta = text.removePrefix(insertion.prefix).trim()
        return when {
            insertion.appendPayload -> {
                val originalPayload =
                    (
                        mergeConversationDictationTranscript(target.capturedDraft, target.capturedDraft, text)
                            as? ConversationDictationMerge.Applied
                    )?.value
                        ?: appendConversationDictationTranscript(target.capturedDraft, text)
                appendConversationDictationTranscript(insertion.base, originalPayload.text)
            }
            insertion.appendOnly -> appendConversationDictationTranscript(insertion.base, delta)
            else ->
                when (val merged = mergeConversationDictationTranscript(target.capturedDraft, insertion.base, delta)) {
                    is ConversationDictationMerge.Applied -> merged.value
                    ConversationDictationMerge.NeedsAppendFallback -> appendConversationDictationTranscript(insertion.base, delta)
                }
        }
    }

    /** A recovered draft is an admission fence, not a replacement for the immutable outgoing payload. */
    fun sendTarget(
        session: Long,
        target: ConversationDictationTarget,
    ): ConversationDictationTarget? {
        val saved = receipt?.takeIf { it.session == session && it.target == target }
        return if (saved == null) {
            target
        } else {
            val current = runCatching { read(target.accountRef, target.groupIdHex) }.getOrNull()
            if (saved.sendEligible && current != null && sameDraft(saved.draft, current)) {
                target.copy(capturedDraft = current.value, capturedDraftRevision = current.revision)
            } else {
                null
            }
        }
    }

    private fun sameDraft(
        a: ConversationDictationDraftSnapshot,
        b: ConversationDictationDraftSnapshot,
    ): Boolean = a.revision == b.revision && a.value.text == b.value.text

    /** Provider punctuation/case changes cannot repeat a shared spoken prefix after an editor change. */
    private fun commonWordPrefixLength(
        previous: String,
        current: String,
    ): Int {
        fun words(text: String): List<Pair<String, Int>> {
            val boundaries = BreakIterator.getWordInstance(Locale.ROOT)
            boundaries.setText(text)
            val words = mutableListOf<Pair<String, Int>>()
            var start = boundaries.first()
            var end = boundaries.next()
            while (end != BreakIterator.DONE) {
                val word = text.substring(start, end)
                if (word.any(Char::isLetterOrDigit)) words += word.lowercase(Locale.ROOT) to end
                start = end
                end = boundaries.next()
            }
            return words
        }
        val before = words(previous)
        val after = words(current)
        var end = 0
        for ((a, b) in before.zip(after)) {
            if (a.first != b.first) break
            end = b.second
        }
        return end
    }
}
