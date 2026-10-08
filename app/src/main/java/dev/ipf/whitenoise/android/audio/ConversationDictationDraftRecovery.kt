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
        val draft: ConversationDictationDraftSnapshot,
        val sendEligible: Boolean,
        val base: TextFieldValue,
        val baseTranscript: String = "",
        val appendOnly: Boolean = false,
        val appendPayload: Boolean = false,
        val emptiedRevision: Long? = null,
        val recoveryUnavailable: Boolean = false,
    )

    private var receipt: Receipt? = null

    fun reset() {
        receipt = null
    }

    fun owns(
        session: Long,
        target: ConversationDictationTarget,
    ): Boolean = receipt?.let { it.session == session && it.target == target } == true

    /** Recovery requires a verified save; Retry may inspect historical ownership before checking again. */
    fun hasSavedTranscript(
        session: Long,
        target: ConversationDictationTarget,
        transcript: String?,
        requireVerifiedRecovery: Boolean = true,
    ): Boolean =
        !transcript.isNullOrBlank() &&
            receipt?.let {
                it.session == session &&
                    it.target == target &&
                    it.transcript == transcript.trim() &&
                    it.emptiedRevision == null &&
                    (!requireVerifiedRecovery || !it.recoveryUnavailable)
            } == true

    /** Tracks only the clear or restoration actually performed by this session's dispatch. */
    fun updateDispatch(
        session: Long,
        target: ConversationDictationTarget,
        clearedRevision: Long? = null,
        restoredRevision: Long? = null,
    ) {
        val saved = receipt?.takeIf { it.session == session && it.target == target } ?: return
        if (clearedRevision != null) {
            receipt = saved.copy(emptiedRevision = clearedRevision)
        } else if (restoredRevision != null) {
            receipt =
                saved.copy(
                    draft = saved.draft.copy(revision = restoredRevision),
                    emptiedRevision = null,
                    recoveryUnavailable = false,
                )
        }
    }

    /** Submitted text remains represented even after its composer was cleared or subsequently edited. */
    fun consumeSubmittedTranscript(
        session: Long,
        target: ConversationDictationTarget,
        transcript: String,
    ) {
        val current = runCatching { read(target.accountRef, target.groupIdHex) }.getOrNull()
        val snapshot = current ?: ConversationDictationDraftSnapshot(TextFieldValue(""), Long.MIN_VALUE)
        receipt =
            Receipt(
                session = session,
                target = target,
                transcript = transcript.trim(),
                draft = snapshot,
                sendEligible = false,
                base = snapshot.value,
                baseTranscript = transcript.trim(),
                appendOnly = true,
                recoveryUnavailable = current == null,
            )
    }

    data class Options(
        val restoreCapturedPrefix: Boolean = false,
        val ownedEmptyRevision: Long? = null,
    )

    private data class Insertion(
        val base: TextFieldValue,
        val prefixLength: Int,
        val baseTranscript: String,
        val appendOnly: Boolean,
        val appendPayload: Boolean,
    )

    enum class Result(
        val draftRecovered: Boolean,
    ) {
        Written(true),
        AlreadyPresent(true),
        Superseded(false),
        Unavailable(false),
    }

    /** Distinguishes a new accepted draft write from an unchanged, verified receipt. */
    fun recoverWithResult(
        session: Long,
        target: ConversationDictationTarget,
        transcript: String,
        options: Options = Options(),
    ): Result {
        val text = transcript.trim()
        if (text.isEmpty()) return Result.Unavailable
        var result = RecoveryAttempt.Retry
        repeat(DICTATION_DRAFT_WRITE_ATTEMPTS) {
            if (result == RecoveryAttempt.Retry) result = recoverAttempt(session, target, text, options)
        }
        val outcome =
            when (result) {
                RecoveryAttempt.Written -> Result.Written
                RecoveryAttempt.AlreadyPresent -> Result.AlreadyPresent
                RecoveryAttempt.Superseded -> Result.Superseded
                else -> Result.Unavailable
            }
        // Preserve historical ownership through failed reads, while protecting uncertain recovery.
        receipt?.takeIf { it.session == session && it.target == target && it.transcript == text }?.let {
            receipt = it.copy(recoveryUnavailable = outcome == Result.Unavailable)
        }
        return outcome
    }

    private enum class RecoveryAttempt {
        Written,
        AlreadyPresent,
        Superseded,
        Retry,
        Unavailable,
    }

    private data class RecoveryWrite(
        val previous: Receipt?,
        val current: ConversationDictationDraftSnapshot,
        val insertion: Insertion,
        val value: TextFieldValue,
        val revision: Long,
    )

    private fun recoverAttempt(
        session: Long,
        target: ConversationDictationTarget,
        text: String,
        options: Options,
    ): RecoveryAttempt =
        run {
            val previous = receipt?.takeIf { it.session == session && it.target == target }
            val current =
                runCatching { read(target.accountRef, target.groupIdHex) }.getOrNull()
                    ?: return@run RecoveryAttempt.Unavailable
            if (previous?.transcript == text && previous.emptiedRevision == null && !options.restoreCapturedPrefix) {
                return@run if (sameDictationRecoveryDraft(previous.draft, current)) {
                    RecoveryAttempt.AlreadyPresent
                } else {
                    RecoveryAttempt.Superseded
                }
            }
            val insertion = planInsertion(previous, current, text, options) ?: return@run RecoveryAttempt.Unavailable
            val value = insertionValue(target, text, insertion)
            val revision =
                runCatching { write(target.accountRef, target.groupIdHex, current.revision, value) }.getOrNull()
                    ?: return@run RecoveryAttempt.Retry
            receipt =
                recoveryReceipt(
                    session,
                    target,
                    text,
                    options,
                    RecoveryWrite(previous, current, insertion, value, revision),
                )
            RecoveryAttempt.Written
        }

    private fun recoveryReceipt(
        session: Long,
        target: ConversationDictationTarget,
        text: String,
        options: Options,
        written: RecoveryWrite,
    ): Receipt {
        val previous = written.previous
        val current = written.current
        val originalUnchanged =
            sameDictationRecoveryDraft(
                ConversationDictationDraftSnapshot(target.capturedDraft, target.capturedDraftRevision),
                current,
            )
        val ownsEmpty =
            (previous?.emptiedRevision ?: options.ownedEmptyRevision) == current.revision &&
                current.value.text.isEmpty()
        val sendEligible =
            if (previous == null) {
                originalUnchanged || ownsEmpty
            } else {
                (sameDictationRecoveryDraft(previous.draft, current) || ownsEmpty) && previous.sendEligible
            }
        return Receipt(
            session,
            target,
            text,
            ConversationDictationDraftSnapshot(written.value, written.revision),
            sendEligible,
            written.insertion.base,
            written.insertion.baseTranscript,
            written.insertion.appendOnly,
            written.insertion.appendPayload,
        )
    }

    private fun planInsertion(
        previous: Receipt?,
        current: ConversationDictationDraftSnapshot,
        text: String,
        options: Options,
    ): Insertion? =
        if (previous != null && sameDictationRecoveryDraft(previous.draft, current)) {
            representedPrefixLength(previous.baseTranscript, text)?.let { prefixLength ->
                Insertion(
                    previous.base,
                    prefixLength,
                    previous.baseTranscript,
                    previous.appendOnly,
                    previous.appendPayload,
                )
            }
        } else {
            val restoringPayload = previous?.emptiedRevision != null || options.restoreCapturedPrefix
            val represented = previous?.transcript?.takeIf { !restoringPayload }.orEmpty()
            // A rewrite already mixed with editor changes remains retained, never appended twice.
            representedPrefixLength(represented, text)?.let { prefixLength ->
                Insertion(current.value, prefixLength, represented, previous != null, restoringPayload)
            }
        }

    private fun insertionValue(
        target: ConversationDictationTarget,
        text: String,
        insertion: Insertion,
    ): TextFieldValue {
        val delta = text.drop(insertion.prefixLength).trim()
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
                    ConversationDictationMerge.NeedsAppendFallback ->
                        appendConversationDictationTranscript(insertion.base, delta)
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
            if (saved.sendEligible && current != null && sameDictationRecoveryDraft(saved.draft, current)) {
                target.copy(capturedDraft = current.value, capturedDraftRevision = current.revision)
            } else {
                null
            }
        }
    }

    /** The base's entire represented transcript must survive before any suffix can be appended. */
    private fun representedPrefixLength(
        previous: String,
        current: String,
    ): Int? {
        val before = dictationRecoveryWords(previous)
        val after = dictationRecoveryWords(current)
        val represented =
            before.isNotEmpty() &&
                after.size >= before.size &&
                before.indices.all { before[it].first == after[it].first }
        return when {
            previous.isEmpty() -> 0
            represented -> after.getOrNull(before.size)?.second ?: current.length
            else -> null
        }
    }
}

private fun dictationRecoveryWords(text: String): List<Pair<String, Int>> {
    val boundaries = BreakIterator.getWordInstance(Locale.ROOT)
    boundaries.setText(text)
    val result = mutableListOf<Pair<String, Int>>()
    var start = boundaries.first()
    var end = boundaries.next()
    while (end != BreakIterator.DONE) {
        val word = text.substring(start, end)
        if (word.any(Char::isLetterOrDigit)) result += word.lowercase(Locale.ROOT) to start
        start = end
        end = boundaries.next()
    }
    return result
}

private fun sameDictationRecoveryDraft(
    a: ConversationDictationDraftSnapshot,
    b: ConversationDictationDraftSnapshot,
): Boolean = a.revision == b.revision && a.value.text == b.value.text

/** Boolean callers require current ownership, even when the transcript was saved before an edit. */
internal fun ConversationDictationDraftRecovery.recover(
    session: Long,
    target: ConversationDictationTarget,
    transcript: String,
    options: ConversationDictationDraftRecovery.Options = ConversationDictationDraftRecovery.Options(),
): Boolean = recoverWithResult(session, target, transcript, options).draftRecovered
